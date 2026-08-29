#!/system/bin/sh

# Built-in NoMount cleanup. The manager recreates the built-in module on the
# next boot if the feature is still enabled, so this only clears runtime state.
rm -rf /data/adb/nomount/ || true

# Remove symlinks
rm -f /data/adb/ksu/bin/nm || true
rm -f /data/adb/ap/bin/nm || true
