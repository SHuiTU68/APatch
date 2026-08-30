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
use std::sync::OnceLock;

use anyhow::{Context, Result, bail};
use log::{info, warn};

use crate::{defs, insmod, late_load};

/// Cached "kernel driver confirmed responsive" state. The NoMount driver (a
/// built-in or an LKM) cannot spontaneously unload once loaded, so after the
/// first successful probe we skip the add_key syscall on every later call.
/// Only the positive result is cached: a negative is never cached, so a user
/// dropping a matching `.ko` later and re-running enable/inject is retried.
static KERNEL_READY: OnceLock<()> = OnceLock::new();

fn mark_kernel_ready() {
    let _ = KERNEL_READY.set(());
}

/// Cheap cached check: `true` once the driver has been confirmed once.
pub fn kernel_ready() -> bool {
    KERNEL_READY.get().is_some()
}

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
// UNSPEC=0, GET_VERSION=1, ADD_RULE=2, DEL_RULE=3, ADD_UID=4, DEL_UID=5,
// CLEAR_ALL=6, CLEAR_RULES=7, CLEAR_UIDS=8, GET_LIST=9, GET_UIDS=10.
const NM_CMD_GET_VERSION: u32 = 1;
const NM_CMD_ADD_RULE: u32 = 2;
const NM_CMD_DEL_RULE: u32 = 3;
const NM_CMD_ADD_UID: u32 = 4;
const NM_CMD_DEL_UID: u32 = 5;
const NM_CMD_CLEAR_ALL: u32 = 6;
const NM_CMD_CLEAR_RULES: u32 = 7;
const NM_CMD_CLEAR_UIDS: u32 = 8;
const NM_CMD_GET_LIST: u32 = 9;
const NM_CMD_GET_UIDS: u32 = 10;

/// Rule flags (struct nm_rule_hdr.flags): whiteout & virtual-dir.
pub const NM_FLAG_WHITEOUT: u32 = 4;
pub const NM_FLAG_VIRTUAL_DIR: u32 = 2;

const RULE_HDR_SIZE: usize = 12; // struct nm_rule_hdr { u32 flags; u32 uid; u16 v_len; u16 r_len; }
const DEL_HDR_SIZE: usize = 6; // struct nm_del_hdr { u32 uid; u16 v_len; }

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
///
/// `arg1` is the pagination cursor passed straight through to the kernel:
/// for `GET_LIST`/`GET_UIDS` it must carry over the value the kernel wrote on
/// the previous call (the reference `nm.c` never resets it between requests,
/// see `nm_send_payload` in `.nmref/nm.h`), otherwise the kernel keeps
/// returning the same first batch and the caller loops forever.
fn send_arg1(page: &PayloadPage, cmd: u32, target_uid: u32, data: &[u8], arg1: u32) -> i32 {
    unsafe {
        let p = &mut *page.ptr;
        p.magic = NOMOUNT_MAGIC;
        p.cmd = cmd;
        p.target_uid = target_uid;
        p.status = -1;
        p.arg1 = arg1;
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

/// Send one payload, resetting the pagination cursor (all non-query commands).
fn send(page: &PayloadPage, cmd: u32, target_uid: u32, data: &[u8]) -> i32 {
    send_arg1(page, cmd, target_uid, data, 0)
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

/// One VFS redirection rule as returned by the kernel (`GET_LIST`).
#[derive(Debug, Clone)]
pub struct RuleEntry {
    pub flags: u32,
    pub uid: u32,
    pub virtual_path: String,
    pub real_path: String,
}

/// Add VFS redirection rule(s), native `nm rule add`.
///
/// Each entry is `(vpath, rpath)`; when `whiteout` is true only the vpath is
/// used and every entry is registered as a whiteout (matching `nm.c`).
/// Rules are queued and flushed in 4068-byte payloads.
pub fn add_rules(rules: &[(String, Option<String>)], uid: u32, whiteout: bool) -> Result<()> {
    let page = PayloadPage::new()?;
    let mut batch: Vec<u8> = Vec::with_capacity(PAYLOAD_BUF_SIZE);
    for (v, r) in rules {
        let rpath = if whiteout { "" } else { r.as_deref().unwrap_or("") };
        let need = RULE_HDR_SIZE + v.len() + rpath.len();
        if !batch.is_empty() && batch.len() + need > PAYLOAD_BUF_SIZE {
            flush_rules(&page, &mut batch)?;
        }
        let flags = if whiteout { NM_FLAG_WHITEOUT } else { 0 };
        batch.extend_from_slice(&flags.to_ne_bytes());
        batch.extend_from_slice(&uid.to_ne_bytes());
        batch.extend_from_slice(&(v.len() as u16).to_ne_bytes());
        batch.extend_from_slice(&(rpath.len() as u16).to_ne_bytes());
        batch.extend_from_slice(v.as_bytes());
        batch.extend_from_slice(rpath.as_bytes());
    }
    flush_rules(&page, &mut batch)?;
    Ok(())
}

/// Remove VFS redirection rule(s) by virtual path, native `nm rule del`.
pub fn del_rules(paths: &[String], uid: u32) -> Result<()> {
    let page = PayloadPage::new()?;
    let mut batch: Vec<u8> = Vec::with_capacity(PAYLOAD_BUF_SIZE);
    let flush = |batch: &mut Vec<u8>| {
        if !batch.is_empty() {
            let status = send(&page, NM_CMD_DEL_RULE, 0, batch);
            if status < 0 {
                warn!("nomount: rule del batch failed (status {status})");
            }
            batch.clear();
        }
    };
    for p in paths {
        let need = DEL_HDR_SIZE + p.len();
        if !batch.is_empty() && batch.len() + need > PAYLOAD_BUF_SIZE {
            flush(&mut batch);
        }
        batch.extend_from_slice(&uid.to_ne_bytes());
        batch.extend_from_slice(&(p.len() as u16).to_ne_bytes());
        batch.extend_from_slice(p.as_bytes());
    }
    flush(&mut batch);
    Ok(())
}

/// Add an app uid to the exclusion list, native `nm uid add` (-EEXIST is fine).
pub fn add_uid(uid: u32) -> Result<()> {
    let page = PayloadPage::new()?;
    let status = send(&page, NM_CMD_ADD_UID, uid, &[]);
    if status < 0 && status != -17 {
        bail!("nomount: uid add {uid} failed (status {status})");
    }
    Ok(())
}

/// Remove an app uid from the exclusion list, native `nm uid del`.
pub fn del_uid(uid: u32) -> Result<()> {
    let page = PayloadPage::new()?;
    let status = send(&page, NM_CMD_DEL_UID, uid, &[]);
    if status < 0 && status != -17 {
        bail!("nomount: uid del {uid} failed (status {status})");
    }
    Ok(())
}

fn clear(cmd: u32) -> Result<()> {
    let page = PayloadPage::new()?;
    let status = send(&page, cmd, 0, &[]);
    if status < 0 {
        bail!("nomount: clear command {cmd} failed (status {status})");
    }
    Ok(())
}

/// Clear every VFS rule and blocked uid (native `nm clear all`).
pub fn clear_all() -> Result<()> {
    clear(NM_CMD_CLEAR_ALL)
}

/// Clear only the VFS redirection rules (native `nm clear rules`).
pub fn clear_rules() -> Result<()> {
    clear(NM_CMD_CLEAR_RULES)
}

/// Clear only the blocked uids (native `nm clear uid`).
pub fn clear_uids() -> Result<()> {
    clear(NM_CMD_CLEAR_UIDS)
}

/// Query all active rules, native `nm rule list` (mirrors the `nm.c` loop:
/// keep sending until the kernel reports an empty payload). The kernel tracks
/// the scan position in `payload->arg1` (see `NM_CMD_GET_LIST` in nomount.c),
/// so each request must pass back the cursor from the previous reply, or the
/// kernel would return the same first batch forever.
pub fn query_rules() -> Result<Vec<RuleEntry>> {
    let page = PayloadPage::new()?;
    let mut out = Vec::new();
    let mut cursor: u32 = 0;
    loop {
        let status = send_arg1(&page, NM_CMD_GET_LIST, 0, &[], cursor);
        if status < 0 {
            bail!("nomount: rule list failed (status {status})");
        }
        let p = unsafe { &*page.ptr };
        if p.data_size == 0 {
            break;
        }
        let data = &p.buffer[..p.data_size as usize];
        let mut pos = 0;
        while pos + RULE_HDR_SIZE <= data.len() {
            let flags = u32::from_ne_bytes(data[pos..pos + 4].try_into().unwrap());
            let uid = u32::from_ne_bytes(data[pos + 4..pos + 8].try_into().unwrap());
            let vlen = u16::from_ne_bytes(data[pos + 8..pos + 10].try_into().unwrap()) as usize;
            let rlen = u16::from_ne_bytes(data[pos + 10..pos + 12].try_into().unwrap()) as usize;
            pos += RULE_HDR_SIZE;
            if pos + vlen + rlen > data.len() {
                break;
            }
            let virtual_path = String::from_utf8_lossy(&data[pos..pos + vlen]).into_owned();
            pos += vlen;
            let real_path = String::from_utf8_lossy(&data[pos..pos + rlen]).into_owned();
            pos += rlen;
            out.push(RuleEntry {
                flags,
                uid,
                virtual_path,
                real_path,
            });
        }
        cursor = p.arg1;
    }
    Ok(out)
}

/// Query all blocked uids, native `nm uid list` (same pagination contract as
/// `query_rules`: the kernel advances `payload->arg1` past the returned ids).
pub fn query_uids() -> Result<Vec<u32>> {
    let page = PayloadPage::new()?;
    let mut out = Vec::new();
    let mut cursor: u32 = 0;
    loop {
        let status = send_arg1(&page, NM_CMD_GET_UIDS, 0, &[], cursor);
        if status < 0 {
            bail!("nomount: uid list failed (status {status})");
        }
        let p = unsafe { &*page.ptr };
        if p.data_size == 0 {
            break;
        }
        let data = &p.buffer[..p.data_size as usize];
        let mut pos = 0;
        while pos + 4 <= data.len() {
            out.push(u32::from_ne_bytes(data[pos..pos + 4].try_into().unwrap()));
            pos += 4;
        }
        cursor = p.arg1;
    }
    Ok(out)
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
///
/// Most entries are classified from the readdir `d_type` (`DirEntry::file_type`)
/// with no extra syscall; only symlinks fall back to `metadata()` (which
/// dereferences), keeping the `find -L` behavior while avoiding a stat() per
/// file — thousands of syscalls saved on large module trees.
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
        let ftype = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
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
        } else if ftype.is_symlink() {
            // Dereference like `find -L`; broken symlinks are skipped.
            let meta = match fs::metadata(&path) {
                Ok(m) => m,
                Err(_) => continue,
            };
            let t = meta.file_type();
            if t.is_dir() {
                if dir_is_opaque(&path) {
                    whiteouts.push(vpath_of(&path, mod_path));
                }
                walk_dir(&path, mod_path, files, whiteouts);
            } else if t.is_file() {
                if name == ".replace" {
                    if let Some(parent) = path.parent() {
                        whiteouts.push(vpath_of(parent, mod_path));
                    }
                } else {
                    files.push((
                        vpath_of(&path, mod_path),
                        path.to_string_lossy().into_owned(),
                    ));
                }
            } else if t.is_char_device() {
                whiteouts.push(vpath_of(&path, mod_path));
            }
        }
    }
}

/// Collect the injectable files (`(vpath, rpath)`) and whiteouts of one module
/// dir, mirroring the manager's `find` walk (opaque dirs, `.replace`, char
/// devices and regular files/symlinks, with the `/system/odm` → `/odm` rewrite).
///
/// `partitions` must come from `device_partitions()` (computed once per boot)
/// so we don't re-stat every partition for every module.
fn collect_module_paths(
    mod_path: &Path,
    partitions: &[&str],
) -> (Vec<(String, String)>, Vec<String>) {
    let mut files: Vec<(String, String)> = Vec::new();
    let mut whiteouts: Vec<String> = Vec::new();
    for partition in partitions {
        let part_dir = mod_path.join(partition);
        if !part_dir.is_dir() {
            continue;
        }
        walk_dir(&part_dir, mod_path, &mut files, &mut whiteouts);
    }
    (files, whiteouts)
}

/// Partitions that exist on this device (either at `/X` or `/system/X`),
/// cached for the life of this apd process: the mount table does not change
/// during a boot, so the ~22 stats once per boot replace ~22 stats per module.
fn device_partitions() -> &'static [&'static str] {
    static CACHE: OnceLock<Vec<&'static str>> = OnceLock::new();
    CACHE.get_or_init(|| {
        TARGET_PARTITIONS
            .iter()
            .copied()
            .filter(|p| {
                Path::new(&format!("/{p}")).exists()
                    || Path::new(&format!("/system/{p}")).exists()
            })
            .collect()
    })
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
    let partitions = device_partitions();

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

        let (files, whiteouts) = collect_module_paths(&mod_path, partitions);
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

/// Hot-inject a single module's files into the VFS rules (native replacement
/// for the manager's `find | xargs nm rule add` pipeline).
pub fn inject_module(mod_id: &str) -> Result<InjectReport> {
    let mod_path = Path::new(defs::MODULE_DIR).join(mod_id);
    if !mod_path.is_dir() {
        bail!("nomount: module not found: {mod_id}");
    }
    if ["disable", "remove", "skip_mount"]
        .iter()
        .any(|m| mod_path.join(m).exists())
    {
        return Ok(InjectReport::default());
    }

    let (files, whiteouts) = collect_module_paths(&mod_path, device_partitions());
    if files.is_empty() && whiteouts.is_empty() {
        return Ok(InjectReport::default());
    }

    let page = PayloadPage::new()?;
    let mut report = InjectReport::default();
    let mut batch: Vec<u8> = Vec::with_capacity(PAYLOAD_BUF_SIZE);
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
    report.modules = 1;
    Ok(report)
}

/// Hot-unload a single module (remove all its rules, native replacement for
/// the manager's `find | xargs nm rule del` pipeline).
pub fn unload_module(mod_id: &str) -> Result<()> {
    let mod_path = Path::new(defs::MODULE_DIR).join(mod_id);
    if !mod_path.is_dir() {
        bail!("nomount: module not found: {mod_id}");
    }
    let (files, whiteouts) = collect_module_paths(&mod_path, device_partitions());

    let page = PayloadPage::new()?;
    let mut batch: Vec<u8> = Vec::with_capacity(PAYLOAD_BUF_SIZE);
    let flush = |batch: &mut Vec<u8>| {
        if !batch.is_empty() {
            let status = send(&page, NM_CMD_DEL_RULE, 0, batch);
            if status < 0 {
                warn!("nomount: rule del batch failed (status {status})");
            }
            batch.clear();
        }
    };
    for w in &whiteouts {
        let need = DEL_HDR_SIZE + w.len();
        if !batch.is_empty() && batch.len() + need > PAYLOAD_BUF_SIZE {
            flush(&mut batch);
        }
        batch.extend_from_slice(&0u32.to_ne_bytes());
        batch.extend_from_slice(&(w.len() as u16).to_ne_bytes());
        batch.extend_from_slice(w.as_bytes());
    }
    for (v, _) in &files {
        let need = DEL_HDR_SIZE + v.len();
        if !batch.is_empty() && batch.len() + need > PAYLOAD_BUF_SIZE {
            flush(&mut batch);
        }
        batch.extend_from_slice(&0u32.to_ne_bytes());
        batch.extend_from_slice(&(v.len() as u16).to_ne_bytes());
        batch.extend_from_slice(v.as_bytes());
    }
    flush(&mut batch);
    Ok(())
}

/// Register the exclusion-list UIDs with the kernel (replaces `service.sh`).
///
/// The exclusion list lives in the APatch working dir root
/// (`/data/adb/ap/.nomount_exclusions`), not inside the nomount data dir, so
/// the data dir keeps just `nomount.log`.
pub fn sync_exclusion_uids() -> Result<usize> {
    let json_path = Path::new(defs::NOMOUNT_EXCLUSION_FILE);
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
///
/// Once the driver answers, the result is cached for the life of this apd
/// process: boot calls it twice (`inject_at_boot` then `inject`) and every
/// interactive op re-checks, so the cached flag turns those into a free
/// return instead of a fresh add_key probe (plus an LKM scan on failure).
pub fn ensure_kernel_support() -> Result<bool> {
    if kernel_ready() {
        return Ok(true);
    }
    if kernel_version().is_some() {
        mark_kernel_ready();
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
            mark_kernel_ready();
            return Ok(true);
        }
    }

    // 2. Embedded LKM matching this device's KMI, loaded straight from memory.
    //    The prebuilt is compiled into this binary (include_bytes!), so nothing
    //    is ever written under the data dir — the dir keeps just nomount.log.
    if let Some(kmi) = late_load::detect_kmi() {
        let name = format!("nomount-{kmi}.ko");
        if let Some((_, data)) = crate::nomount::bundled_lkms().iter().find(|(n, _)| *n == name) {
            match insmod::load_module(data, c"") {
                Ok(()) if kernel_version().is_some() => {
                    info!("nomount: LKM loaded from embedded data: {name}");
                    mark_kernel_ready();
                    return Ok(true);
                }
                Ok(()) => warn!(
                    "nomount: embedded LKM {name} loaded but driver still unresponsive"
                ),
                Err(e) => warn!("nomount: embedded LKM {name} failed to load: {e:#}"),
            }
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
