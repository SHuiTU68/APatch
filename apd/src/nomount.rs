//! Built-in NoMount (VFS path redirection) feature
//!
//! NoMount is bundled inside APatch and replaces the OverlayFS/MagicMount
//! mount strategy with in-RAM VFS path redirection: instead of modifying the
//! mount table, module files are registered with the kernel and spliced into
//! path resolution / directory iteration on the fly.
//!
//! It is fully built in: there is no module dir under `/data/adb/modules` and
//! no metamodule symlink — the feature never shows up in the module page.
//! Everything lives under the working dir (`/data/adb/ap/nomount`): the
//! matching LKM, the boot log and the state markers. Interactive operations
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
pub fn is_provisioned() -> bool {
    Path::new(defs::NOMOUNT_DATA_DIR).join("version").exists()
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
    for name in [".exclusion_list.json", "nomount.log"] {
        let from = Path::new(LEGACY_DATA_DIR).join(name);
        let to = new_dir.join(name);
        if from.is_file() && !to.exists() {
            fs::copy(&from, &to)
                .with_context(|| format!("Failed to migrate NoMount data file {name}"))?;
        }
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

/// Write `data` to `path` only when it differs from what's already there, so an
/// unchanged boot doesn't re-write the same LKM / version stamp to disk.
fn write_if_changed(path: &Path, data: &[u8]) -> Result<()> {
    match fs::read(path) {
        Ok(existing) if existing == data => return Ok(()),
        _ => {}
    }
    fs::write(path, data).with_context(|| format!("Failed to write {}", path.display()))?;
    Ok(())
}

/// Provision the built-in NoMount feature under its data dir.
///
/// Writes a version stamp (what `module.prop` used to carry) and the matching
/// LKM. Also cleans up the legacy metamodule layout from older builds.
/// Every write is skipped when the target is already present and unchanged, so
/// boot-time provisioning is mostly a couple of stat()s, not disk I/O.
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

    // Version stamp (replaces the old module.prop version= line).
    write_if_changed(&data_dir.join("version"), b"version=v2.0.0\n")?;

    // LKM support: kernels without CONFIG_NOMOUNT=y can still use NoMount by
    // loading a matching nomount-<androidX-Y.Z>.ko. We bundle the official
    // NoMount GKI prebuilt LKMs (GPL-3.0, github.com/maxsteeel/nomount) but
    // deploy only the one matching this device's KMI into the persistent data
    // dir, so GKI devices work out of the box without every prebuilt showing
    // up: the native injection loads it via `apd insmod` at boot. Non-GKI
    // users can still drop a custom nomount-<androidX-Y.Z>.ko in the same dir
    // and it takes precedence.
    let data_lkm = data_dir.join("lkm");
    fs::create_dir_all(&data_lkm)?;
    let bundled_lkms: &[(&str, &[u8])] = &[
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

    // Deploy only the ko matching this device's KMI (e.g. nomount-android14-5.15.ko),
    // and drop stale bundled prebuilts left over from earlier builds so the data
    // dir stays slim. Custom ko files dropped by the user are never touched.
    let matched = late_load::detect_kmi()
        .map(|kmi| format!("nomount-{kmi}.ko"))
        .filter(|name| bundled_lkms.iter().any(|(n, _)| n == name));
    if let Some(name) = matched.as_deref() {
        if let Some((_, data)) = bundled_lkms.iter().find(|(n, _)| *n == name) {
            write_if_changed(&data_lkm.join(name), data)?;
        }
    }
    for (name, _) in bundled_lkms {
        if data_lkm.join(name).is_file() && Some(*name) != matched.as_deref() {
            let _ = fs::remove_file(data_lkm.join(name));
        }
    }
    write_if_changed(
        &data_lkm.join("README.txt"),
        b"The built-in LKM matching this device's KMI (detected automatically) is\n\
          provisioned here and loaded at boot via `apd insmod`. For non-GKI or\n\
          custom kernels, drop a matching nomount-<androidX-Y.Z>.ko here (e.g.\n\
          nomount-android14-6.1.ko) from the NoMount release\n\
          (github.com/maxsteeel/nomount); it takes precedence over the bundled one.\n",
    )?;

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
            "[INFO] Kernel must have CONFIG_NOMOUNT=y (or a nomount LKM loaded).",
        );
        nomount_inject::log_append(&format!(
            "[INFO] Place nomount-<androidX-Y.Z>.ko in {}/lkm",
            defs::NOMOUNT_DATA_DIR
        ));
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
