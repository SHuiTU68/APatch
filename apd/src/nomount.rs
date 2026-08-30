//! Built-in NoMount (VFS path redirection) feature
//!
//! NoMount is bundled inside APatch and replaces the OverlayFS/MagicMount
//! mount strategy with in-RAM VFS path redirection: instead of modifying the
//! mount table, module files are registered with the kernel and spliced into
//! path resolution / directory iteration on the fly.
//!
//! It is fully built in: there is no module dir under `/data/adb/modules` and
//! no metamodule symlink — the feature never shows up in the module page.
//! Everything is embedded in the APatch working dir (`/data/adb/ap`): the
//! matching LKM is compiled into this binary and loaded from memory, and the
//! only file left in the data dir (`/data/adb/ap/nomount`) is the boot log
//! (`nomount.log`). The exclusion list and the boot semaphore live in the
//! working dir root alongside the other manager markers. Interactive operations
//! (rule/uid management) are native `apd nomount` subcommands; there is no
//! separate `nm` binary anymore. The toggle state is persisted in the
//! `NOMOUNT_ENABLE_FILE` marker so the daemon can re-provision and re-inject
//! at boot independently of any module machinery.

use std::{
    fs,
    path::Path,
};

use anyhow::{Context, Result, bail};
use log::{info, warn};

use crate::{defs, late_load, metamodule, nomount_inject, utils};

/// Whether the built-in NoMount feature is currently enabled (toggle state).
pub fn is_enabled() -> bool {
    Path::new(defs::NOMOUNT_ENABLE_FILE).exists()
}

/// Whether the built-in NoMount feature is provisioned on disk (data dir).
///
/// There is no `version` stamp file anymore (it is a compile-time constant),
/// so provisioning is just the presence of the data dir holding the log.
pub fn is_provisioned() -> bool {
    Path::new(defs::NOMOUNT_DATA_DIR).is_dir()
}

/// Bundled NoMount GKI prebuilt LKMs (GPL-3.0, github.com/maxsteeel/nomount),
/// embedded in this binary via `include_bytes!`. Only the one matching this
/// device's KMI is loaded, and it is loaded straight from memory — nothing is
/// ever written to disk, so the data dir keeps just `nomount.log`.
static BUNDLED_LKMS: &[(&str, &[u8])] = &[
    (
        "nomount-android12-5.10.ko",
        include_bytes!("../assets/nomount/lkm/nomount-android12-5.10.ko"),
    ),
    (
        "nomount-android13-5.10.ko",
        include_bytes!("../assets/nomount/lkm/nomount-android13-5.10.ko"),
    ),
    (
        "nomount-android13-5.15.ko",
        include_bytes!("../assets/nomount/lkm/nomount-android13-5.15.ko"),
    ),
    (
        "nomount-android14-5.15.ko",
        include_bytes!("../assets/nomount/lkm/nomount-android14-5.15.ko"),
    ),
    (
        "nomount-android14-6.1.ko",
        include_bytes!("../assets/nomount/lkm/nomount-android14-6.1.ko"),
    ),
    (
        "nomount-android15-6.6.ko",
        include_bytes!("../assets/nomount/lkm/nomount-android15-6.6.ko"),
    ),
    (
        "nomount-android16-6.12.ko",
        include_bytes!("../assets/nomount/lkm/nomount-android16-6.12.ko"),
    ),
];

/// Accessor for the embedded LKM table (shared with `nomount_inject`).
pub fn bundled_lkms() -> &'static [(&'static str, &'static [u8])] {
    BUNDLED_LKMS
}

/// One-time migration from the legacy `/data/adb/nomount` working dir to
/// `/data/adb/ap/nomount`. Preserves user data (exclusion list, log) and then
/// removes the legacy directory (which also contained every bundled KMI LKM).
fn migrate_legacy_data_dir() -> Result<()> {
    const LEGACY_DATA_DIR: &str = "/data/adb/nomount";

    let new_dir = Path::new(defs::NOMOUNT_DATA_DIR);
    if new_dir.exists() || !Path::new(LEGACY_DATA_DIR).exists() {
        return Ok(());
    }

    fs::create_dir_all(new_dir)?;
    // The log stays in the data dir; the exclusion list moved to the working
    // dir root (`/data/adb/ap/.nomount_exclusions`) as part of the embedded
    // layout, so legacy files are migrated to their new homes.
    let from_log = Path::new(LEGACY_DATA_DIR).join("nomount.log");
    let to_log = Path::new(defs::NOMOUNT_LOG_FILE);
    if from_log.is_file() && !to_log.exists() {
        fs::copy(&from_log, &to_log)
            .with_context(|| format!("Failed to migrate NoMount data file nomount.log"))?;
    }
    let from_exclusions = Path::new(LEGACY_DATA_DIR).join(".exclusion_list.json");
    let to_exclusions = Path::new(defs::NOMOUNT_EXCLUSION_FILE);
    if from_exclusions.is_file() && !to_exclusions.exists() {
        fs::copy(&from_exclusions, &to_exclusions).with_context(|| {
            format!("Failed to migrate NoMount data file .exclusion_list.json")
        })?;
    }
    let _ = fs::remove_dir_all(LEGACY_DATA_DIR);
    info!(
        "NoMount working dir migrated from {LEGACY_DATA_DIR} to {}",
        new_dir.display()
    );
    Ok(())
}

/// Remove leftovers from builds that shipped NoMount as a metamodule
/// (`/data/adb/modules/nomount` + the `/data/adb/metamodule` symlink), so the
/// feature is fully built in and never appears in the module page again.
fn cleanup_legacy_module() {
    let _ = fs::remove_dir_all(Path::new(defs::NOMOUNT_MODULE_DIR));
    if let Some(path) = metamodule::get_metamodule_path() {
        if path == Path::new(defs::NOMOUNT_MODULE_DIR) {
            let _ = metamodule::remove_symlink();
        }
    }
}

/// Provision the built-in NoMount feature under its data dir.
///
/// The feature is fully embedded: the matching LKM lives in this binary and is
/// loaded from memory, the version is a compile-time constant, and the
/// exclusion list / boot semaphore live in the APatch working dir root. So
/// provisioning only guarantees the data dir exists (for the log) and sweeps
/// leftover files from earlier builds that wrote LKMs / version stamps into it,
/// converging the data dir on a single file: `nomount.log`.
fn provision() -> Result<()> {
    // One-time migration: the working dir moved from /data/adb/nomount to
    // /data/adb/ap/nomount. Run it first so user data (exclusion list, log)
    // survives and the legacy dir (with all KMI LKMs) is cleaned up.
    migrate_legacy_data_dir()?;
    cleanup_legacy_module();

    let data_dir = Path::new(defs::NOMOUNT_DATA_DIR);
    fs::create_dir_all(data_dir)?;

    // Deep APatch integration: interactive ops are native `apd nomount`
    // subcommands, so no separate `nm` binary is deployed. Drop the old bin
    // helper left over from earlier builds.
    let _ = fs::remove_dir_all(data_dir.join("bin"));

    // One-time move of the exclusion list from the old in-data-dir location
    // (`/data/adb/ap/nomount/.exclusion_list.json`) to the working-dir root,
    // so lists configured on earlier builds survive the embedded layout.
    let old_exclusions = data_dir.join(".exclusion_list.json");
    let new_exclusions = Path::new(defs::NOMOUNT_EXCLUSION_FILE);
    if old_exclusions.is_file() && !new_exclusions.exists() {
        let _ = fs::copy(&old_exclusions, &new_exclusions);
    }
    let _ = fs::remove_file(&old_exclusions);

    // Sweep leftovers from builds that stored the LKM set / version stamp in
    // the data dir. Bundled prebuilts (identified by their exact names) are
    // removed; anything the user dropped under lkm/ on purpose (a custom
    // nomount-<androidX-Y.Z>.ko for a non-GKI kernel) is kept and still
    // honoured by `ensure_kernel_support`. The lkm dir is removed if it ends
    // up empty.
    let lkm_dir = data_dir.join("lkm");
    let _ = fs::remove_file(lkm_dir.join("README.txt"));
    for (name, _) in bundled_lkms() {
        let _ = fs::remove_file(lkm_dir.join(name));
    }
    if fs::read_dir(&lkm_dir).map(|mut it| it.next().is_none()).unwrap_or(false) {
        let _ = fs::remove_dir(&lkm_dir);
    }
    let _ = fs::remove_file(data_dir.join("version"));

    info!("NoMount built-in feature provisioned at {}", data_dir.display());
    Ok(())
}

/// Ensure the built-in NoMount feature is provisioned.
///
/// Idempotent; safe to call on every boot.
fn ensure_active() -> Result<()> {
    provision()?;
    Ok(())
}

/// Enable the built-in NoMount feature.
pub fn enable() -> Result<()> {
    ensure_active()?;
    utils::ensure_file_exists(defs::NOMOUNT_ENABLE_FILE)?;
    // Hot-apply without a reboot: load a matching LKM if needed and inject
    // module files natively, all in-process (no metamount.sh subprocess storm).
    //
    // A hot-apply is not a boot, so clear the bootloop semaphore around it.
    // The boot path re-touches .booting; leaving it behind would make the next
    // real boot think a crash happened, self-disable NoMount and skip LKM
    // loading (status "未运行" after reboot).
    let _ = fs::remove_file(defs::NOMOUNT_BOOT_SEMAPHORE);
    if let Err(e) = inject() {
        warn!("nomount: hot injection failed (will retry at next boot): {e:#}");
    }
    let _ = fs::remove_file(defs::NOMOUNT_BOOT_SEMAPHORE);
    info!("NoMount enabled");
    Ok(())
}

/// Native injection: load the kernel driver if needed, then register every
/// active module's files (and the exclusion UIDs) with it. Used both for the
/// hot-apply path and by the `apd nomount inject` command.
pub fn inject() -> Result<()> {
    if !nomount_inject::ensure_kernel_support()? {
        bail!("NoMount Internal API is missing/unresponsive");
    }
    let report = nomount_inject::inject_all()?;
    let uids = nomount_inject::sync_exclusion_uids()?;
    info!(
        "NoMount injected {} file(s), {} whiteout(s) from {} module(s), {} uid(s)",
        report.files, report.whiteouts, report.modules, uids
    );
    Ok(())
}

/// Boot-time injection called from post-fs-data. Runs the whole injection
/// in-process. The `.booting` semaphore is left in place until boot completes,
/// when `boot_completed()` clears it natively (it used to be a module's
/// boot-completed.sh job).
pub fn inject_at_boot() -> Result<()> {
    let data_dir = Path::new(defs::NOMOUNT_DATA_DIR);
    if !data_dir.exists() {
        fs::create_dir_all(data_dir)?;
    }
    let semaphore = Path::new(defs::NOMOUNT_BOOT_SEMAPHORE);

    if semaphore.exists() {
        nomount_inject::log_append("[FATAL] Bootloop detected! NoMount caused a crash on the last boot.");
        nomount_inject::log_append("[INFO] Disabling NoMount for safety...");
        // Disabling the feature is just clearing the enable marker now that
        // there is no module dir / disable marker to maintain.
        let _ = fs::remove_file(defs::NOMOUNT_ENABLE_FILE);
        let _ = fs::remove_file(semaphore);
        return Ok(());
    }
    let _ = fs::write(semaphore, "");

    nomount_inject::log_append(&format!(
        "=== NoMount Injection | Started: {} ===",
        nomount_inject::now_str()
    ));
    nomount_inject::log_append(&format!(
        "Kernel Version: {}",
        nomount_inject::kernel_release_str()
    ));

    if !nomount_inject::ensure_kernel_support()? {
        nomount_inject::log_append("[FATAL] NoMount Internal API is missing/unresponsive.");
        nomount_inject::log_append(
            "[INFO] Kernel must have CONFIG_NOMOUNT=y (or a bundled nomount LKM must load).",
        );
        nomount_inject::log_append(
            "[INFO] For non-GKI kernels, drop a custom nomount-<androidX-Y.Z>.ko in /data/adb/ap/nomount/lkm",
        );
        nomount_inject::log_append(
            "[INFO] Skipping injection this boot; re-run 'apd nomount enable' after fixing.",
        );
        let _ = fs::remove_file(semaphore);
        return Ok(());
    }

    match inject() {
        Ok(()) => {
            nomount_inject::log_append(&format!(
                "=== Injection Complete: {} ===",
                nomount_inject::now_str()
            ));
        }
        Err(e) => {
            warn!("nomount: boot injection failed: {e:#}");
            nomount_inject::log_append(&format!("[FATAL] Injection failed: {e:#}"));
            let _ = fs::remove_file(semaphore);
        }
    }
    Ok(())
}

/// Clear the boot semaphore once boot has completed (native replacement for
/// the module's old boot-completed.sh). Idempotent.
pub fn boot_completed() {
    let semaphore = Path::new(defs::NOMOUNT_BOOT_SEMAPHORE);
    if semaphore.exists() {
        let _ = fs::remove_file(semaphore);
        nomount_inject::log_append("[OK] Boot completed safely.");
    }
}

/// Disable the built-in NoMount feature.
pub fn disable() -> Result<()> {
    // Fully built in: disabling is just clearing the enable marker (plus the
    // boot semaphore, if a boot is still in progress). No module dir or
    // metamodule symlink to touch.
    let _ = fs::remove_file(defs::NOMOUNT_BOOT_SEMAPHORE);
    let _ = fs::remove_file(defs::NOMOUNT_ENABLE_FILE);
    info!("NoMount disabled");
    Ok(())
}

/// Best-effort re-provisioning hook called at post-fs-data.
///
/// The manager writes the enable marker when the toggle is turned on; on the
/// next boot we re-materialize the data dir (e.g. after an APatch upgrade) so
/// the feature stays active without user intervention.
pub fn provision_if_enabled() {
    if !is_enabled() {
        return;
    }
    if let Err(e) = ensure_active() {
        warn!("nomount: failed to provision built-in feature: {e:#}");
    }
}

/// Print the current NoMount state for `apd nomount status`.
pub fn status() -> Result<()> {
    println!("enabled: {}", is_enabled());
    println!("provisioned: {}", is_provisioned());
    println!(
        "kernel-kmi: {}",
        late_load::detect_kmi().unwrap_or_else(|| "<unknown>".to_string())
    );
    println!(
        "kernel-support: {}",
        if nomount_inject::kernel_version().is_some() {
            "yes"
        } else {
            "no (need CONFIG_NOMOUNT or a matching LKM)"
        }
    );
    Ok(())
}

/// Shared guard for interactive ops: the kernel API must respond first.
fn ensure_kernel() -> Result<()> {
    if !nomount_inject::ensure_kernel_support()? {
        bail!("NoMount Internal API is missing/unresponsive");
    }
    Ok(())
}

/// `apd nomount version` — print the NoMount kernel driver version.
pub fn version() -> Result<()> {
    match nomount_inject::kernel_version() {
        Some(v) => {
            println!("{v}");
            Ok(())
        }
        None => bail!("NoMount Internal API is missing/unresponsive"),
    }
}

/// `apd nomount rule add` — add VFS redirection rule(s).
pub fn rule_add(rules: &[(String, Option<String>)], uid: u32, whiteout: bool) -> Result<()> {
    ensure_kernel()?;
    nomount_inject::add_rules(rules, uid, whiteout)
}

/// `apd nomount rule del` — remove VFS redirection rule(s).
pub fn rule_del(paths: &[String], uid: u32) -> Result<()> {
    ensure_kernel()?;
    nomount_inject::del_rules(paths, uid)
}

/// `apd nomount rule list` — print active VFS rules (plain or JSON).
pub fn rule_list(json: bool) -> Result<()> {
    ensure_kernel()?;
    let rules = nomount_inject::query_rules()?;
    if json {
        let arr: Vec<serde_json::Value> = rules
            .iter()
            .map(|r| {
                let mut m = serde_json::Map::new();
                m.insert(
                    "virtual".into(),
                    serde_json::Value::String(r.virtual_path.clone()),
                );
                if r.flags & nomount_inject::NM_FLAG_WHITEOUT != 0 {
                    m.insert("whiteout".into(), serde_json::Value::Bool(true));
                } else if r.flags & nomount_inject::NM_FLAG_VIRTUAL_DIR != 0 {
                    m.insert("virtual_dir".into(), serde_json::Value::Bool(true));
                } else {
                    m.insert(
                        "real".into(),
                        serde_json::Value::String(r.real_path.clone()),
                    );
                }
                if r.uid != 0 {
                    m.insert("uid".into(), serde_json::Value::Number(r.uid.into()));
                }
                serde_json::Value::Object(m)
            })
            .collect();
        println!(
            "{}",
            serde_json::to_string_pretty(&serde_json::Value::Array(arr))?
        );
    } else {
        for r in &rules {
            if r.flags & nomount_inject::NM_FLAG_WHITEOUT != 0 {
                println!("{} (whiteout)", r.virtual_path);
            } else if r.flags & nomount_inject::NM_FLAG_VIRTUAL_DIR != 0 {
                println!("{} (virtual dir)", r.virtual_path);
            } else if r.uid != 0 {
                println!("{} -> {} [UID: {}]", r.virtual_path, r.real_path, r.uid);
            } else {
                println!("{} -> {}", r.virtual_path, r.real_path);
            }
        }
    }
    Ok(())
}

/// `apd nomount rule clear` — remove all VFS redirection rules.
pub fn rule_clear() -> Result<()> {
    ensure_kernel()?;
    nomount_inject::clear_rules()
}

/// `apd nomount uid add` — block an app uid.
pub fn uid_add(uid: u32) -> Result<()> {
    ensure_kernel()?;
    nomount_inject::add_uid(uid)
}

/// `apd nomount uid del` — unblock an app uid.
pub fn uid_del(uid: u32) -> Result<()> {
    ensure_kernel()?;
    nomount_inject::del_uid(uid)
}

/// `apd nomount uid list` — print blocked uids as a JSON array.
pub fn uid_list() -> Result<()> {
    ensure_kernel()?;
    let uids = nomount_inject::query_uids()?;
    println!("{}", serde_json::to_string(&uids)?);
    Ok(())
}

/// `apd nomount uid clear` — remove all blocked uids.
pub fn uid_clear() -> Result<()> {
    ensure_kernel()?;
    nomount_inject::clear_uids()
}

/// `apd nomount hide add` — add Kasumi-style hide rule(s).
///
/// `flags` is one or more of the `NM_HIDE_*` kinds; `uid` restricts the rule
/// to a reader uid (0 = apply to everyone); `arg` is the forged statfs
/// `f_type` when `flags` includes `NM_HIDE_STATFS`. `paths` are the path
/// prefixes matched against proc file lines.
pub fn hide_add(flags: u32, uid: u32, arg: u32, paths: &[String]) -> Result<()> {
    ensure_kernel()?;
    let rules: Vec<(u32, u32, u32, String)> = paths
        .iter()
        .map(|p| (flags, uid, arg, p.clone()))
        .collect();
    nomount_inject::add_hide_rules(&rules)
}

/// `apd nomount hide del` — remove hide rule(s) by path.
pub fn hide_del(uid: u32, paths: &[String]) -> Result<()> {
    ensure_kernel()?;
    nomount_inject::del_hide_rules(uid, paths)
}

/// `apd nomount hide list` — print active hide rules (plain or JSON).
pub fn hide_list(json: bool) -> Result<()> {
    ensure_kernel()?;
    let rules = nomount_inject::query_hide_rules()?;
    if json {
        let arr: Vec<serde_json::Value> = rules
            .iter()
            .map(|r| {
                let mut m = serde_json::Map::new();
                let mut kinds = Vec::new();
                if r.flags & nomount_inject::NM_HIDE_MOUNTINFO != 0 {
                    kinds.push("mountinfo");
                }
                if r.flags & nomount_inject::NM_HIDE_MOUNTS != 0 {
                    kinds.push("mounts");
                }
                if r.flags & nomount_inject::NM_HIDE_MAPS != 0 {
                    kinds.push("maps");
                }
                if r.flags & nomount_inject::NM_HIDE_SMAPS != 0 {
                    kinds.push("smaps");
                }
                if r.flags & nomount_inject::NM_HIDE_STATFS != 0 {
                    kinds.push("statfs");
                }
                m.insert(
                    "hide".into(),
                    serde_json::Value::Array(
                        kinds
                            .into_iter()
                            .map(|k| serde_json::Value::String(k.into()))
                            .collect(),
                    ),
                );
                m.insert("path".into(), serde_json::Value::String(r.path.clone()));
                if r.uid != 0 {
                    m.insert("uid".into(), serde_json::Value::Number(r.uid.into()));
                }
                if r.flags & nomount_inject::NM_HIDE_STATFS != 0 && r.arg != 0 {
                    m.insert("f_type".into(), serde_json::Value::Number(r.arg.into()));
                }
                serde_json::Value::Object(m)
            })
            .collect();
        println!(
            "{}",
            serde_json::to_string_pretty(&serde_json::Value::Array(arr))?
        );
    } else {
        for r in &rules {
            let mut kinds = Vec::new();
            if r.flags & nomount_inject::NM_HIDE_MOUNTINFO != 0 {
                kinds.push("mountinfo");
            }
            if r.flags & nomount_inject::NM_HIDE_MOUNTS != 0 {
                kinds.push("mounts");
            }
            if r.flags & nomount_inject::NM_HIDE_MAPS != 0 {
                kinds.push("maps");
            }
            if r.flags & nomount_inject::NM_HIDE_SMAPS != 0 {
                kinds.push("smaps");
            }
            if r.flags & nomount_inject::NM_HIDE_STATFS != 0 {
                kinds.push("statfs");
            }
            let mut line = format!("{} (hide: {})", r.path, kinds.join(","));
            if r.uid != 0 {
                line.push_str(&format!(" [UID: {}]", r.uid));
            }
            if r.flags & nomount_inject::NM_HIDE_STATFS != 0 && r.arg != 0 {
                line.push_str(&format!(" [f_type: {:#x}]", r.arg));
            }
            println!("{line}");
        }
    }
    Ok(())
}

/// `apd nomount hide clear` — remove all hide rules.
pub fn hide_clear() -> Result<()> {
    ensure_kernel()?;
    nomount_inject::clear_hide_rules()
}

/// `apd nomount clear <all|rules|uid>` — clear everything / rules / uids.
pub fn clear(what: &str) -> Result<()> {
    ensure_kernel()?;
    match what {
        "all" => nomount_inject::clear_all(),
        "rules" => nomount_inject::clear_rules(),
        "uid" | "uids" => nomount_inject::clear_uids(),
        _ => bail!("nomount: clear expects 'all', 'rules' or 'uid'"),
    }
}

/// `apd nomount module inject <id>` — hot-inject one module's files.
pub fn module_inject(id: &str) -> Result<()> {
    ensure_kernel()?;
    let report = nomount_inject::inject_module(id)?;
    info!(
        "nomount: hot-injected {id}: {} file(s), {} whiteout(s)",
        report.files, report.whiteouts
    );
    Ok(())
}

/// `apd nomount module unload <id>` — hot-unload one module's files.
pub fn module_unload(id: &str) -> Result<()> {
    ensure_kernel()?;
    nomount_inject::unload_module(id)
}
