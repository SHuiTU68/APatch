//! Native NoMount injection (deep APatch integration)
//!
//! The built-in NoMount metamodule is driven by the `apd` daemon directly,
//! instead of shelling out to `metamount.sh`. That script forked
//! `find`/`grep`/`getfattr`/`xargs`/`nm` once per rule — thousands of process
//! spawns at every boot. Here the module tree is walked in-process with
//! `std::fs` and rules are sent to the NoMount kernel driver over the Linux
//! keyring `add_key` syscall, mirroring the `nm` userspace tool's wire format
//! exactly (see `.nmref/nm.h` / `.nmref/nm.c`).

use std::ffi::{CStr, c_char, c_long, c_void};
use std::fs;
use std::io::Write;
use std::os::unix::fs::FileTypeExt;
use std::path::{Path, PathBuf};
use std::ptr;

use anyhow::{Context, Result, bail};
use log::{info, warn};

use crate::{defs, insmod, late_load};

/// NOMOUNT_MAGIC_SIG from .nmref/nm.h
const NOMOUNT_MAGIC: u64 = 0x4E4F4D4F554E54;
const PAYLOAD_BUF_SIZE: usize = 4068;

// add_key syscall numbers (asm-generic: aarch64/arm, x86_64: arch-specific)
#[cfg(target_arch = "aarch64")]
const SYS_ADD_KEY: c_long = 217;
#[cfg(target_arch = "arm")]
const SYS_ADD_KEY: c_long = 309;
#[cfg(target_arch = "x86_64")]
const SYS_ADD_KEY: c_long = 248;

// Command numbers must match the enum in .nmref/nm.h:
// UNSPEC=0, GET_VERSION=1, ADD_RULE=2, DEL_RULE=3, ADD_UID=4, DEL_UID=5, ...
const NM_CMD_GET_VERSION: u32 = 1;
const NM_CMD_ADD_RULE: u32 = 2;
const NM_CMD_ADD_UID: u32 = 4;
const NM_FLAG_WHITEOUT: u32 = 4;

const RULE_HDR_SIZE: usize = 12; // struct nm_rule_hdr { u32 flags; u32 uid; u16 v_len; u16 r_len; }

/// Wire payload, layout must match `struct nm_payload` (4096 bytes total).
#[repr(C, packed)]
struct NmPayload {
    magic: u64,
    cmd: u32,
    target_uid: u32,
    status: i32,
    arg1: u32,
    data_size: u32,
    buffer: [u8; PAYLOAD_BUF_SIZE],
}

/// The kernel requires the whole payload to live in a single page
/// (`pg_off + sizeof(payload) <= PAGE_SIZE`), so it must be page-aligned.
struct PayloadPage {
    ptr: *mut NmPayload,
}

impl PayloadPage {
    fn new() -> Result<Self> {
        let p = unsafe {
            libc::mmap(
                ptr::null_mut(),
                4096,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        if p == libc::MAP_FAILED {
            bail!(
                "nomount: mmap payload page failed: {}",
                std::io::Error::last_os_error()
            );
        }
        Ok(Self { ptr: p.cast() })
    }
}

impl Drop for PayloadPage {
    fn drop(&mut self) {
        unsafe { libc::munmap(self.ptr.cast(), 4096) };
    }
}

/// Send one payload and return the kernel-written status (<0 on error).
fn send(page: &PayloadPage, cmd: u32, target_uid: u32, data: &[u8]) -> i32 {
    unsafe {
        let p = &mut *page.ptr;
        p.magic = NOMOUNT_MAGIC;
        p.cmd = cmd;
        p.target_uid = target_uid;
        p.status = -1;
        p.arg1 = 0;
        p.data_size = data.len() as u32;
        if !data.is_empty() {
            ptr::copy_nonoverlapping(data.as_ptr(), p.buffer.as_mut_ptr(), data.len());
        }
        let addr = page.ptr as usize;
        let type_ = c"nomount";
        let desc = c"trigger";
        // add_key("nomount", "trigger", &addr, sizeof(addr), KEY_SPEC_PROCESS_KEYRING)
        // ringid is passed as a full-width long so varargs widening is well-defined.
        libc::syscall(
            SYS_ADD_KEY,
            type_.as_ptr(),
            desc.as_ptr(),
            &addr as *const usize as *const c_void,
            std::mem::size_of::<usize>(),
            -1 as c_long,
        );
        p.status
    }
}

/// Query the kernel driver version; `None` means the API is missing (no
/// CONFIG_NOMOUNT and no matching LKM loaded).
pub fn kernel_version() -> Option<String> {
    let page = match PayloadPage::new() {
        Ok(p) => p,
        Err(_) => return None,
    };
    let status = send(&page, NM_CMD_GET_VERSION, 0, &[]);
    if status < 0 {
        return None;
    }
    unsafe {
        let p = &*page.ptr;
        let n = (p.data_size as usize).min(255);
        if n == 0 {
            return Some(String::new());
        }
        let buf = CStr::from_bytes_with_nul(&p.buffer[..n + 1])
            .ok()
            .map(|c| c.to_string_lossy().into_owned())
            .unwrap_or_default();
        Some(buf)
    }
}

/// Flush a batch of queued rules to the kernel.
fn flush_rules(page: &PayloadPage, batch: &mut Vec<u8>) -> Result<()> {
    if batch.is_empty() {
        return Ok(());
    }
    let status = send(page, NM_CMD_ADD_RULE, 0, batch);
    if status < 0 {
        warn!("nomount: rule batch failed (status {status})");
    }
    batch.clear();
    Ok(())
}

/// Queue one rule (vpath → rpath), flushing when the 4068-byte buffer fills.
fn queue_rule(page: &PayloadPage, batch: &mut Vec<u8>, vpath: &str, rpath: &str) -> Result<()> {
    let need = RULE_HDR_SIZE + vpath.len() + rpath.len();
    if !batch.is_empty() && batch.len() + need > PAYLOAD_BUF_SIZE {
        flush_rules(page, batch)?;
    }
    let flags = if rpath.is_empty() { NM_FLAG_WHITEOUT } else { 0 };
    batch.extend_from_slice(&flags.to_ne_bytes());
    batch.extend_from_slice(&0u32.to_ne_bytes()); // per-rule uid, 0 like metamount.sh
    batch.extend_from_slice(&(vpath.len() as u16).to_ne_bytes());
    batch.extend_from_slice(&(rpath.len() as u16).to_ne_bytes());
    batch.extend_from_slice(vpath.as_bytes());
    batch.extend_from_slice(rpath.as_bytes());
    Ok(())
}

/// Result summary of an injection pass.
#[derive(Default, Debug)]
pub struct InjectReport {
    pub files: usize,
    pub whiteouts: usize,
    pub modules: usize,
}

const TARGET_PARTITIONS: &[&str] = &[
    "system",
    "system_ext",
    "vendor",
    "odm",
    "product",
    "apex",
    "oem",
    "optics",
    "prism",
    "mi_ext",
    "my_bigball",
    "my_carrier",
    "my_company",
    "my_engineering",
    "my_heytap",
    "my_manifest",
    "my_preload",
    "my_product",
    "my_region",
    "my_reserve",
    "my_stock",
];

/// Virtual path for a file under a module dir, with the /system/odm → /odm
/// rewrite performed by metamount.sh.
fn vpath_of(path: &Path, mod_path: &Path) -> String {
    let rel = path.strip_prefix(mod_path).unwrap_or(path);
    let v = format!("/{}", rel.display());
    if let Some(rest) = v.strip_prefix("/system/odm/") {
        format!("/odm/{rest}")
    } else {
        v
    }
}

fn dir_is_opaque(path: &Path) -> bool {
    match extattr::getxattr(path, "trusted.overlay.opaque") {
        Ok(v) => v == b"y",
        Err(_) => false,
    }
}

/// Recursively collect file rules and whiteout rules under `dir`, mirroring
/// the `find -L` semantics of metamount.sh (symlinks are followed).
fn walk_dir(
    dir: &Path,
    mod_path: &Path,
    files: &mut Vec<(String, String)>,
    whiteouts: &mut Vec<String>,
) {
    let entries = match fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        // Dereference like `find -L`; broken symlinks are skipped.
        let meta = match fs::metadata(&path) {
            Ok(m) => m,
            Err(_) => continue,
        };
        let ftype = meta.file_type();
        if ftype.is_dir() {
            if dir_is_opaque(&path) {
                whiteouts.push(vpath_of(&path, mod_path));
            }
            walk_dir(&path, mod_path, files, whiteouts);
        } else if ftype.is_file() {
            if name == ".replace" {
                // Whiteout the containing directory (Magisk .replace semantics).
                if let Some(parent) = path.parent() {
                    whiteouts.push(vpath_of(parent, mod_path));
                }
            } else {
                files.push((vpath_of(&path, mod_path), path.to_string_lossy().into_owned()));
            }
        } else if ftype.is_char_device() {
            whiteouts.push(vpath_of(&path, mod_path));
        }
    }
}

/// Walk every active module's partition dirs and register VFS path
/// redirections with the kernel driver, in-process.
pub fn inject_all() -> Result<InjectReport> {
    let page = PayloadPage::new()?;
    let mut report = InjectReport::default();
    let mut batch: Vec<u8> = Vec::with_capacity(PAYLOAD_BUF_SIZE);

    let modules_dir = Path::new(defs::MODULE_DIR);
    let entries = fs::read_dir(modules_dir)
        .with_context(|| format!("nomount: cannot read {}", modules_dir.display()))?;

    for entry in entries.flatten() {
        let mod_path = entry.path();
        if !mod_path.is_dir() {
            continue;
        }
        let mod_name = mod_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_string();
        if mod_name == defs::NOMOUNT_MODULE_ID {
            continue;
        }
        if ["disable", "remove", "skip_mount"]
            .iter()
            .any(|m| mod_path.join(m).exists())
        {
            continue;
        }

        let mut files: Vec<(String, String)> = Vec::new();
        let mut whiteouts: Vec<String> = Vec::new();
        for partition in TARGET_PARTITIONS {
            let part_dir = mod_path.join(partition);
            if !part_dir.is_dir() {
                continue;
            }
            if !Path::new(&format!("/{partition}")).exists()
                && !Path::new(&format!("/system/{partition}")).exists()
            {
                continue;
            }
            walk_dir(&part_dir, &mod_path, &mut files, &mut whiteouts);
        }
        if files.is_empty() && whiteouts.is_empty() {
            continue;
        }
        report.modules += 1;

        // Whiteout rules first, then plain file rules (same order as metamount.sh).
        for w in &whiteouts {
            queue_rule(&page, &mut batch, w, "")?;
            report.whiteouts += 1;
        }
        flush_rules(&page, &mut batch)?;
        for (v, r) in &files {
            queue_rule(&page, &mut batch, v, r)?;
            report.files += 1;
        }
        flush_rules(&page, &mut batch)?;
    }
    flush_rules(&page, &mut batch)?;
    Ok(report)
}

/// Register the exclusion-list UIDs with the kernel (replaces `service.sh`).
pub fn sync_exclusion_uids() -> Result<usize> {
    let json_path = Path::new(defs::NOMOUNT_DATA_DIR).join(".exclusion_list.json");
    if !json_path.exists() {
        return Ok(0);
    }
    let text = fs::read_to_string(&json_path).context("nomount: read exclusion list")?;
    let value: serde_json::Value =
        serde_json::from_str(&text).unwrap_or(serde_json::Value::Null);
    let mut uids: Vec<u32> = Vec::new();
    if let Some(arr) = value.as_array() {
        for item in arr {
            if let Some(u) = item
                .get("uid")
                .and_then(|x| x.as_str())
                .and_then(|s| s.parse::<u32>().ok())
            {
                uids.push(u);
            }
        }
    }
    uids.sort_unstable();
    uids.dedup();

    let page = PayloadPage::new()?;
    for uid in &uids {
        let status = send(&page, NM_CMD_ADD_UID, *uid, &[]);
        // -EEXIST means already registered (idempotent); anything else < 0 is real trouble.
        if status < 0 && status != -17 {
            warn!("nomount: uid add {uid} failed (status {status})");
        }
    }
    Ok(uids.len())
}

/// Make sure the NoMount kernel API responds, loading a matching LKM if needed
/// (native equivalent of metamount.sh's `try_load_lkm`).
pub fn ensure_kernel_support() -> Result<bool> {
    if kernel_version().is_some() {
        return Ok(true);
    }

    let lkm_dir = Path::new(defs::NOMOUNT_DATA_DIR).join("lkm");
    let mut candidates: Vec<PathBuf> = Vec::new();

    // 1. Exact KMI match: nomount-<androidX-Y.Z>.ko
    if let Some(kmi) = late_load::detect_kmi() {
        let p = lkm_dir.join(format!("nomount-{kmi}.ko"));
        if p.exists() {
            candidates.push(p);
        }
    }
    // 2. Kernel-version glob fallback: nomount-*-5.15.ko
    if let Some(kver) = kernel_version_only() {
        if let Ok(rd) = fs::read_dir(&lkm_dir) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().into_owned();
                if name.starts_with("nomount-")
                    && name.ends_with(".ko")
                    && name.contains(&kver)
                {
                    candidates.push(e.path());
                }
            }
        }
    }
    // 3. Plain nomount.ko
    let p = lkm_dir.join("nomount.ko");
    if p.exists() {
        candidates.push(p);
    }

    for ko in candidates {
        if insmod::insmod(&ko, &[]).is_ok() && kernel_version().is_some() {
            info!("nomount: LKM loaded: {}", ko.display());
            return Ok(true);
        }
    }
    Ok(false)
}

fn kernel_version_only() -> Option<String> {
    let release = late_load::kernel_release()?;
    let mut it = release.split('.');
    let major = it.next()?;
    let minor = it.next()?;
    Some(format!("{major}.{minor}"))
}

// --- boot log helpers (used by nomount.rs orchestration) ---

/// Append one line to the NoMount boot log (creates the file if needed).
pub fn log_append(msg: &str) {
    let path = Path::new(defs::NOMOUNT_LOG_FILE);
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "{msg}");
    }
}

/// Current wall-clock time as `YYYY-MM-DD HH:MM:SS` (or empty on failure).
pub fn now_str() -> String {
    unsafe {
        let mut tm: libc::tm = std::mem::zeroed();
        let now = libc::time(ptr::null_mut());
        if libc::localtime_r(&now, &mut tm).is_null() {
            return String::new();
        }
        let mut buf = [0 as c_char; 64];
        let fmt = c"%Y-%m-%d %H:%M:%S".as_ptr();
        let n = libc::strftime(buf.as_mut_ptr(), buf.len(), fmt, &tm);
        if n == 0 {
            return String::new();
        }
        CStr::from_ptr(buf.as_ptr()).to_string_lossy().into_owned()
    }
}

/// Current kernel release string (for the boot log header).
pub fn kernel_release_str() -> String {
    late_load::kernel_release().unwrap_or_else(|| "<unknown>".to_string())
}
