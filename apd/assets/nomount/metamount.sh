#!/system/bin/sh

# Built-in NoMount metamount.sh (APatch, dev-branch v2.0.0, LKM-capable)
# Runs at post-fs-data through the metamodule machinery. Instead of mounting
# module files into /system with OverlayFS/bind mounts, it walks every active
# module's partition directory and registers VFS path redirections in RAM.
#
# Kernel driver support, in order of preference:
#   1. NoMount compiled into the kernel (CONFIG_NOMOUNT=y)
#   2. A matching nomount LKM (nomount-<androidX-Y.Z>.ko or nomount.ko) loaded
#      through APatch's own `apd insmod` (or a bundled ko-loader at
#      $MODDIR/loader as fallback). LKMs are looked up in $MODDIR/lkm and
#      /data/adb/nomount/lkm.

MODDIR=${0%/*}
LOADER="$MODDIR/bin/nm"
APD_BIN="apd"
MODULES_DIR="/data/adb/modules"
NOMOUNT_DATA="/data/adb/nomount"
LKM_DIR1="$MODDIR/lkm"
LKM_DIR2="$NOMOUNT_DATA/lkm"
LOG_FILE="$NOMOUNT_DATA/nomount.log"
BOOT_SEMAPHORE="$NOMOUNT_DATA/.booting"
TARGET_PARTITIONS="system system_ext vendor odm product apex oem optics prism
                    mi_ext my_bigball my_carrier my_company my_engineering my_heytap
                    my_manifest my_preload my_product my_region my_reserve my_stock"
PROP_FILE="$MODDIR/module.prop"
BASE_DESC="A built-in metamodule that replaces OverlayFS/MagicMount with VFS path redirection."

command -v "$APD_BIN" >/dev/null 2>&1 || APD_BIN="/data/adb/ap/bin/apd"

# Emit the kernel module interface name, e.g. android14-5.15, or empty.
detect_kmi() {
    KVER=$(uname -r | cut -d'.' -f1,2)
    AKVER=$(uname -r | grep -oE 'android[0-9]+')
    [ -n "$AKVER" ] && [ -n "$KVER" ] && printf '%s-%s\n' "$AKVER" "$KVER"
}

# Load a kernel module and confirm the NoMount API responds afterwards.
load_ko() {
    if [ -x "$APD_BIN" ]; then
        if "$APD_BIN" insmod "$1" && "$LOADER" version >/dev/null 2>&1; then return 0; fi
    fi
    if [ -x "$MODDIR/loader" ]; then
        if "$MODDIR/loader" "$1" >/dev/null 2>&1 && "$LOADER" version >/dev/null 2>&1; then return 0; fi
    fi
    return 1
}

# Try to load a matching LKM so NoMount works on stock kernels.
try_load_lkm() {
    KMI=$(detect_kmi)
    if [ -n "$KMI" ]; then
        for dir in "$LKM_DIR1" "$LKM_DIR2"; do
            [ -f "$dir/nomount-$KMI.ko" ] || continue
            if load_ko "$dir/nomount-$KMI.ko"; then
                echo "[OK] LKM loaded: nomount-$KMI.ko" >> "$LOG_FILE"
                return 0
            fi
        done
    fi

    # Fallback: match by kernel version only, e.g. nomount-*-5.15.ko
    KVER=$(uname -r | cut -d'.' -f1,2)
    if [ -n "$KVER" ]; then
        for dir in "$LKM_DIR1" "$LKM_DIR2"; do
            for ko in "$dir"/nomount-*"$KVER".ko; do
                [ -f "$ko" ] || continue
                if load_ko "$ko"; then
                    echo "[OK] LKM loaded: $(basename "$ko")" >> "$LOG_FILE"
                    return 0
                fi
            done
        done
    fi

    # Final fallback: dev-branch naming (customize.sh renames the matched LKM
    # to nomount.ko); also covers a plain nomount.ko placed by the user.
    for dir in "$LKM_DIR1" "$LKM_DIR2"; do
        [ -f "$dir/nomount.ko" ] || continue
        if load_ko "$dir/nomount.ko"; then
            echo "[OK] LKM loaded: nomount.ko" >> "$LOG_FILE"
            return 0
        fi
    done
    return 1
}

if [ ! -d "$NOMOUNT_DATA" ]; then
    mkdir -p "$NOMOUNT_DATA"
fi

echo "=== NoMount Boot Log | Started: $(date) ===" > "$LOG_FILE"
echo "Kernel Version: $(uname -r)" >> "$LOG_FILE"

if [ -f "$BOOT_SEMAPHORE" ]; then
    echo "[FATAL] Bootloop detected! NoMount caused a crash on the last boot." >> "$LOG_FILE"
    echo "[INFO] Disabling NoMount for safety..." >> "$LOG_FILE"
    touch "$MODDIR/disable"
    sed -i "s|^description=.*|description=[🚨 DISABLED: Bootloop Prevented] \\\\n$BASE_DESC|" "$PROP_FILE"
    rm -f "$BOOT_SEMAPHORE"
    exit 1
fi

touch "$BOOT_SEMAPHORE"

echo "[INFO] Checking NoMount kernel support..." >> "$LOG_FILE"
if "$LOADER" version > /dev/null 2>&1; then
    echo "[OK] Built-in Kernel support detected." >> "$LOG_FILE"
else
    echo "[INFO] Built-in not found. Attempting to load LKM..." >> "$LOG_FILE"
    if ! try_load_lkm; then
        echo "[FATAL] NoMount Internal API is missing/unresponsive." >> "$LOG_FILE"
        echo "[INFO] Kernel must have CONFIG_NOMOUNT=y (or a nomount LKM loaded)." >> "$LOG_FILE"
        echo "[INFO] Place nomount-<androidX-Y.Z>.ko in $LKM_DIR2 to enable LKM support." >> "$LOG_FILE"
        echo "[INFO] Skipping injection this boot; re-run 'apd nomount enable' after fixing." >> "$LOG_FILE"
        rm -f "$BOOT_SEMAPHORE"
        exit 0
    fi
fi

for mod_path in "$MODULES_DIR"/*; do
    [ -d "$mod_path" ] || continue
    mod_name="${mod_path##*/}"
    [ "$mod_name" = "nomount" ] && continue

    if [ -f "$mod_path/disable" ] || [ -f "$mod_path/remove" ] || [ -f "$mod_path/skip_mount" ]; then
        echo "[SKIP] Module $mod_name is disabled/removed/skipped" >> "$LOG_FILE"; continue
    fi

    for partition in $TARGET_PARTITIONS; do
        if [ -d "$mod_path/$partition" ]; then
            [ -d "/$partition" ] || [ -d "/system/$partition" ] || continue
            echo "[INFO] Mounting module: $mod_name (/$partition)" >> "$LOG_FILE"
            find -L "$mod_path/$partition" \( -type d -o -type c -o -name ".replace" \) -exec sh -c '
                for f do
                    v="${f#'"$mod_path"'}"; [ "${v#/system/odm/}" != "$v" ] && v="/odm/${v#/system/odm/}"
                    if [ -d "$f" ]; then getfattr -n trusted.overlay.opaque "$f" 2>/dev/null | grep -q "=\"y\"" && printf "%s\0" "$v"
                    elif [ "${f##*/}" = ".replace" ]; then printf "%s\0" "${v%/.replace}"
                    else printf "%s\0" "$v"; fi
                done
            ' _ {} + 2>/dev/null | xargs -0 -r "$LOADER" rule add --whiteout >> "$LOG_FILE" 2>&1

            find -L "$mod_path/$partition" \( -type f -o -type l \) ! -name ".replace" -exec sh -c '
                for f do
                    v="${f#'"$mod_path"'}"; [ "${v#/system/odm/}" != "$v" ] && v="/odm/${v#/system/odm/}"
                    printf "%s\0%s\0" "$v" "$f"
                done
            ' _ {} + 2>/dev/null | xargs -0 -r "$LOADER" rule add >> "$LOG_FILE" 2>&1
        fi
    done
done

echo "=== Injection Complete: $(date) ===" >> "$LOG_FILE"

# NOTE: moved to boot-completed.sh
# rm -f "$BOOT_SEMAPHORE"
# echo "[OK] Boot phase completed safely." >> "$LOG_FILE"
sed -i "s|^description=.*|description=$BASE_DESC|" "$PROP_FILE"

echo -e "\nCurrent files injected:" >> "$LOG_FILE"
"$LOADER" rule list >> "$LOG_FILE"

exit 0
