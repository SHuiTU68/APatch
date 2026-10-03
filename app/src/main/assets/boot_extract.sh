#!/system/bin/sh

ARCH=$(getprop ro.product.cpu.abi)
MODE=$1

# Load utility functions
. ./util_functions.sh

case "$MODE" in
  ramdisk)
    # First stage ramdisk: init_boot on GKI devices, boot on devices that keep
    # the ramdisk inside the kernel image.
    get_current_slot
    find_ramdisk_image
    [ -e "$RAMDISKIMAGE" ] || { >&2 echo "- can't find init_boot.img!"; exit 1; }
    ;;
  true)
    get_next_slot
    find_boot_image
    [ -e "$BOOTIMAGE" ] || { >&2 echo "- can't find boot.img!"; exit 1; }
    ;;
  *)
    get_current_slot
    find_boot_image
    [ -e "$BOOTIMAGE" ] || { >&2 echo "- can't find boot.img!"; exit 1; }
    ;;
esac

true
