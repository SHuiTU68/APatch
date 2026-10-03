package me.bmax.apatch.util

import android.content.Context
import android.net.Uri
import android.util.Log

private const val TAG = "BootImage"

/** Magic every Android boot image (`boot.img` / `init_boot.img`) starts with. */
private const val BOOT_MAGIC = "ANDROID!"

/**
 * Offset of `kernel_size`. It is at the same place in every header version
 * (v0..v4): magic[8], kernel_size[4], ramdisk_size[4].
 */
private const val KERNEL_SIZE_OFF = 8
private const val RAMDISK_SIZE_OFF = 12

/** Reads that much of the header; enough for the two sizes above. */
private const val HEADER_PROBE = 16

/**
 * Tells a first stage ramdisk (`init_boot.img`) from a kernel image
 * (`boot.img`) by looking at the header of [uri], so the user does not have to
 * know which of the two files a device ships.
 *
 * `init_boot.img` deliberately carries no kernel at all - only the generic
 * ramdisk the first stage loads - so `kernel_size == 0 && ramdisk_size > 0` is
 * exactly the shape of an image that has to go through the ramdisk patcher
 * (`kpramdisk`), while anything with a kernel goes through `kptools`.
 *
 * @return true for a ramdisk-only image, false for a kernel image, or null when
 * the header could not be read (unreadable uri, not a boot image, ...) - the
 * caller should then keep its default.
 */
fun isInitBootImage(context: Context, uri: Uri): Boolean? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val header = ByteArray(HEADER_PROBE)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < HEADER_PROBE || String(header, 0, 8, Charsets.US_ASCII) != BOOT_MAGIC) {
            null
        } else {
            le32(header, KERNEL_SIZE_OFF) == 0 && le32(header, RAMDISK_SIZE_OFF) > 0
        }
    }
} catch (e: Exception) {
    // A content uri that we cannot read must not take the install flow down.
    Log.w(TAG, "cannot read the boot image header of $uri", e)
    null
}

private fun le32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
