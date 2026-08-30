//! Built-in NoMount (VFS path redirection) metamodule
//!
//! NoMount is bundled inside APatch and replaces the OverlayFS/MagicMount
//! mount strategy with in-RAM VFS path redirection: instead of modifying the
//! mount table, module files are registered with the kernel and spliced into
//! path resolution / directory iteration on the fly.
//!
//! It is exposed as a *metamodule* (id = `nomount`). When the manager toggle is
//! on, `/data/adb/metamodule` points to `/data/adb/modules/nomount` whose
//! `metamount.sh` registers module files with the kernel. The toggle state is
//! persisted in the `NOMOUNT_ENABLE_FILE` marker under the working dir, so the
//! daemon can re-provision the module at boot independently of the module dir.

use std::{
    fs,
    os::unix::fs::PermissionsExt,
    path::Path,
};

use anyhow::{Context, Result, bail};
use log::{info, warn};

use crate::{defs, late_load, metamodule, utils};

/// Whether the built-in NoMount feature is currently enabled (toggle state).
pub fn is_enabled() -> bool {
    Path::new(defs::NOMOUNT_ENABLE_FILE).exists()
}

/// Whether the built-in NoMount metamodule is provisioned on disk.
pub fn is_provisioned() -> bool {
    Path::new(defs::NOMOUNT_MODULE_DIR).join("module.prop").exists()
}

/// Copy the embedded NoMount assets into the module directory.
fn provision() -> Result<()> {
    let module_dir = Path::new(defs::NOMOUNT_MODULE_DIR);
    if !module_dir.exists() {
        fs::create_dir_all(module_dir)
            .with_context(|| "Failed to create nomount module dir")?;
        fs::set_permissions(module_dir, fs::Permissions::from_mode(0o755))?;
    }

    for (name, content) in [
        ("module.prop", include_str!("../assets/nomount/module.prop")),
        ("metamount.sh", include_str!("../assets/nomount/metamount.sh")),
        (
            "metainstall.sh",
            include_str!("../assets/nomount/metainstall.sh"),
        ),
        ("service.sh", include_str!("../assets/nomount/service.sh")),
        (
            "boot-completed.sh",
            include_str!("../assets/nomount/boot-completed.sh"),
        ),
        ("uninstall.sh", include_str!("../assets/nomount/uninstall.sh")),
        ("customize.sh", include_str!("../assets/nomount/customize.sh")),
    ] {
        fs::write(module_dir.join(name), content)
            .with_context(|| format!("Failed to write {name}"))?;
    }

    // WebUI + locale assets (dev branch)
    let webroot_dir = module_dir.join("webroot");
    fs::create_dir_all(webroot_dir.join("locales"))?;
    for (name, content) in [
        (
            "index.html",
            include_str!("../assets/nomount/webroot/index.html"),
        ),
        ("index.js", include_str!("../assets/nomount/webroot/index.js")),
        (
            "styles.css",
            include_str!("../assets/nomount/webroot/styles.css"),
        ),
        ("theme.css", include_str!("../assets/nomount/webroot/theme.css")),
    ] {
        fs::write(webroot_dir.join(name), content)
            .with_context(|| format!("Failed to write webroot/{name}"))?;
    }
    for (name, content) in [
        ("bn.json", include_str!("../assets/nomount/webroot/locales/bn.json")),
        ("en.json", include_str!("../assets/nomount/webroot/locales/en.json")),
        ("es.json", include_str!("../assets/nomount/webroot/locales/es.json")),
        ("id.json", include_str!("../assets/nomount/webroot/locales/id.json")),
        ("ja.json", include_str!("../assets/nomount/webroot/locales/ja.json")),
        ("ru.json", include_str!("../assets/nomount/webroot/locales/ru.json")),
        ("tr.json", include_str!("../assets/nomount/webroot/locales/tr.json")),
        ("vi.json", include_str!("../assets/nomount/webroot/locales/vi.json")),
        ("zh.json", include_str!("../assets/nomount/webroot/locales/zh.json")),
    ] {
        fs::write(webroot_dir.join("locales").join(name), content)
            .with_context(|| format!("Failed to write webroot/locales/{name}"))?;
    }

    let bin_dir = module_dir.join("bin");
    fs::create_dir_all(&bin_dir)?;
    fs::write(bin_dir.join("nm"), include_bytes!("../assets/nomount/bin/nm"))
        .with_context(|| "Failed to write nm binary")?;
    utils::ensure_binary(bin_dir.join("nm"))?;

    // LKM support: kernels without CONFIG_NOMOUNT=y can still use NoMount by
    // loading a matching nomount-<androidX-Y.Z>.ko. We bundle the official
    // NoMount GKI prebuilt LKMs (GPL-3.0, github.com/maxsteeel/nomount) and
    // deploy them to the persistent data dir, so GKI devices work out of the
    // box without the user fetching anything: metamount.sh loads the best match
    // via `apd insmod` at boot. Non-GKI users can still drop a custom
    // nomount-<androidX-Y.Z>.ko in the same dir and it takes precedence.
    fs::create_dir_all(module_dir.join("lkm"))?;
    let data_lkm = Path::new(defs::NOMOUNT_DATA_DIR).join("lkm");
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
    for (name, data) in bundled_lkms {
        fs::write(data_lkm.join(name), data)
            .with_context(|| format!("Failed to write LKM {name}"))?;
    }
    fs::write(
        data_lkm.join("README.txt"),
        "Built-in GKI prebuilt LKMs are provisioned automatically. For non-GKI\n\
         kernels, drop a matching nomount-<androidX-Y.Z>.ko here (e.g.\n\
         nomount-android14-6.1.ko) from the NoMount release\n\
         (github.com/maxsteeel/nomount); it is loaded at boot via `apd insmod`.\n",
    )?;

    if !Path::new(defs::NOMOUNT_DATA_DIR).exists() {
        fs::create_dir_all(defs::NOMOUNT_DATA_DIR)?;
    }

    info!(
        "NoMount built-in metamodule provisioned at {}",
        defs::NOMOUNT_MODULE_DIR
    );
    Ok(())
}

/// Ensure the built-in NoMount metamodule is provisioned and active.
///
/// Idempotent; safe to call on every boot. Never fights a metamodule that the
/// user installed as a ZIP: if a different metamodule is active, we bail so the
/// boot path can log a warning and fall back to the default overlay mounting.
fn ensure_active() -> Result<()> {
    if let Some(path) = metamodule::get_metamodule_path() {
        if path != Path::new(defs::NOMOUNT_MODULE_DIR) {
            bail!(
                "another metamodule is active at {}; disable it before using built-in NoMount",
                path.display()
            );
        }
    }

    provision()?;

    let disable = Path::new(defs::NOMOUNT_MODULE_DIR).join(defs::DISABLE_FILE_NAME);
    let _ = fs::remove_file(&disable);

    metamodule::ensure_symlink(defs::NOMOUNT_MODULE_DIR)?;
    Ok(())
}

/// Enable the built-in NoMount feature.
pub fn enable() -> Result<()> {
    ensure_active()?;
    utils::ensure_file_exists(defs::NOMOUNT_ENABLE_FILE)?;
    // Hot-apply without a reboot: run the mount script right away so a matching
    // LKM is loaded and module files are injected immediately.
    //
    // A hot-apply is not a boot, so clear the bootloop semaphore around it.
    // metamount.sh re-touches .booting during the run; leaving it behind would
    // make the next real boot think a crash happened, self-disable NoMount and
    // skip LKM loading (status "未运行" after reboot).
    let _ = fs::remove_file(defs::NOMOUNT_BOOT_SEMAPHORE);
    if let Err(e) = metamodule::exec_mount_script(defs::NOMOUNT_MODULE_DIR) {
        warn!("nomount: hot mount failed (will retry at next boot): {e:#}");
    }
    let _ = fs::remove_file(defs::NOMOUNT_BOOT_SEMAPHORE);
    info!("NoMount enabled");
    Ok(())
}

/// Disable the built-in NoMount feature.
pub fn disable() -> Result<()> {
    let disable = Path::new(defs::NOMOUNT_MODULE_DIR).join(defs::DISABLE_FILE_NAME);
    utils::ensure_file_exists(disable)?;
    let _ = metamodule::remove_symlink();
    let _ = fs::remove_file(defs::NOMOUNT_ENABLE_FILE);
    info!("NoMount disabled");
    Ok(())
}

/// Best-effort re-provisioning hook called at post-fs-data.
///
/// The manager writes the enable marker when the toggle is turned on; on the
/// next boot we re-materialize the module dir (e.g. after an APatch upgrade or
/// a wiped module dir) so the feature stays active without user intervention.
pub fn provision_if_enabled() {
    if !is_enabled() {
        return;
    }
    if let Err(e) = ensure_active() {
        warn!("nomount: failed to provision built-in metamodule: {e:#}");
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
    Ok(())
}
