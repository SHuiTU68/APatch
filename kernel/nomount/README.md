# NoMount - Kernel Integration

This directory contains the NoMount kernel subsystem used by the built-in
NoMount metamodule in APatch.

NoMount is a **VFS path redirection** framework: instead of modifying the mount
table with OverlayFS/bind mounts, it hijacks inode/dentry operations
(`lookup`, `iterate_shared`, `readdir`, `stat`...) at runtime and splices in
"virtual" files/directories that resolve to real module files. Everything lives
in RAM; there is no `mount(2)`, no overlay, no `lowerdir`, and no `/proc/mounts`
noise. Module files are registered with the kernel by the userspace `nm` tool
(via the packed-offset "NoMount VFS Offset Protocol" - no device node or new
syscall is needed).

## Files

- `nomount.c` - the VFS hijacking subsystem (LKM/builtin source)
- `nomount.h` - data structures, payload protocol, kernel-version compat macros
- `Kconfig` - `CONFIG_NOMOUNT` (tristate, default `y`)
- `Makefile` - `obj-$(CONFIG_NOMOUNT) += nomount.o`

## How the APatch toggle uses it

The manager toggle runs `apd nomount enable|disable`. When enabled, the daemon
provisions a built-in metamodule at `/data/adb/modules/nomount` and the boot
`metamount.sh` walks every active module and registers its files with the
kernel via `bin/nm rule add ...`. The script first probes `nm version`:

- If the kernel reports the NoMount API -> rules are injected, modules appear
  without any mount.
- If the API is missing -> the script tries to load a matching NoMount LKM
  (`nomount-<androidX-Y.Z>.ko`) from `/data/adb/nomount/lkm` (or the module's
  `lkm/` dir) via `apd insmod`, then re-probes.
- If no driver is available at all -> injection is skipped and modules keep
  using the default OverlayFS/MagicMount path.

So NoMount needs either a kernel built with `CONFIG_NOMOUNT=y` **or** a
matching LKM. Two integration methods are supported.

## Method 1: Built-in (`CONFIG_NOMOUNT=y`) - Recommended

Copy these files into your kernel tree and enable the option:

```bash
mkdir -p <kernel>/fs/nomount
cp nomount.c nomount.h Kconfig Makefile <kernel>/fs/nomount/
```

Then wire it up in `fs/Makefile` and `fs/Kconfig`:

```make
obj-$(CONFIG_NOMOUNT) += nomount/
```

```kconfig
source "fs/nomount/Kconfig"
```

Enable in your `defconfig` (or via `menuconfig`):

```kconfig
CONFIG_NOMOUNT=y
```

Compile the kernel as usual. The APatch built-in toggle now works out of the
box. This is the reliable path for APatch users building a custom kernel: the
VFS hooks are present before `post-fs-data`, so the metamodule's `metamount.sh`
can register rules at boot.

## Method 2: Out-of-tree LKM (`nomount.ko`)

Build against your kernel headers:

```bash
make <compiler args> O=out modules_prepare
CONFIG_NOMOUNT=m make <compiler args> -C $(pwd)/out M=$(pwd)/fs/nomount/ modules
```

The module is produced at `fs/nomount/nomount.ko`.

> **APatch usage:** no extra work is needed. The built-in NoMount metamodule
> automatically loads a matching LKM at `post-fs-data` (before registering
> module rules), so a prebuilt `nomount-<androidX-Y.Z>.ko` placed in
> `/data/adb/nomount/lkm/` is enough for stock GKI kernels. Prebuilt LKMs for
> the common KMIs are published by the NoMount project
> (`github.com/maxsteeel/nomount`); build them out-of-tree here if your KMI is
> not covered.

## Notes

- Upstream: https://github.com/maxsteeel/nomount (the sources here mirror the `dev` branch)
- License: GPL-2.0 (same as the Linux kernel), see the upstream repository.
- The kernel subsystem deliberately keeps a tiny footprint and uses
  SRCU/RCU + seqcount so hooking stays lock-light on hot VFS paths.
