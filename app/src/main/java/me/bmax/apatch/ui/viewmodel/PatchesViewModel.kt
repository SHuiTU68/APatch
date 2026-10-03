package me.bmax.apatch.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.util.Version
import me.bmax.apatch.util.copyAndClose
import me.bmax.apatch.util.copyAndCloseOut
import me.bmax.apatch.util.createRootShell
import me.bmax.apatch.util.inputStream
import me.bmax.apatch.util.jailbreakAssetName
import me.bmax.apatch.util.shellForResult
import me.bmax.apatch.util.writeTo
import org.ini4j.Ini
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader

private const val TAG = "PatchViewModel"

/**
 * Payload file names used inside the patch dir. `kpinit` and the KernelPatch LKM
 * are extracted from the apk assets when a ramdisk mode is chosen; kpramdisk
 * injects them under their fixed entry names (`init`, `kernelpatch.ko`).
 */
private const val RAMDISK_KPINIT = "kpinit"
private const val RAMDISK_KO = "kernelpatch.ko"

/** Copy of the stock ramdisk, kept in the patch dir and in /data/adb/ap. */
private const val RAMDISK_ORI = "ori_init_boot.img"

class PatchesViewModel : ViewModel() {

    /**
     * [ramdisk] selects the delivery path: kernel image patching through kptools
     * (`boot.img`), or first stage ramdisk patching through kpramdisk
     * (`init_boot`, the KernelSU-style LKM path).
     */
    enum class PatchMode(val sId: Int, val ramdisk: Boolean = false) {
        PATCH_ONLY(R.string.patch_mode_bootimg_patch),
        PATCH_AND_INSTALL(R.string.patch_mode_patch_and_install),
        INSTALL_TO_NEXT_SLOT(R.string.patch_mode_install_to_next_slot),
        UNPATCH(R.string.patch_mode_uninstall_patch),

        RAMDISK_PATCH_ONLY(R.string.patch_mode_ramdisk_patch, ramdisk = true),
        RAMDISK_PATCH_AND_INSTALL(R.string.patch_mode_ramdisk_patch_and_install, ramdisk = true),
        RAMDISK_UNPATCH(R.string.patch_mode_ramdisk_uninstall, ramdisk = true);

        /** True for the modes that remove a previously applied patch. */
        val isUnpatch: Boolean
            get() = this == UNPATCH || this == RAMDISK_UNPATCH
    }

    var bootSlot by mutableStateOf("")
    var bootDev by mutableStateOf("")
    var kimgInfo by mutableStateOf(KPModel.KImgInfo("", false))
    var kpimgInfo by mutableStateOf(KPModel.KPImgInfo("", "", "", "", ""))
    var superkey by mutableStateOf("")

    /** `kpramdisk info`/`list` output of the ramdisk selected in a ramdisk mode. */
    var ramdiskInfo by mutableStateOf("")

    /** `/dev/block/...` of the first stage ramdisk partition (init_boot usually). */
    var ramdiskDev by mutableStateOf("")
    var ramdiskReady by mutableStateOf(false)
    var ramdiskPatched by mutableStateOf(false)

    var existedExtras = mutableStateListOf<KPModel.IExtraInfo>()
    var newExtras = mutableStateListOf<KPModel.IExtraInfo>()
    var newExtrasFileName = mutableListOf<String>()

    var running by mutableStateOf(false)
    var patching by mutableStateOf(false)
    var patchdone by mutableStateOf(false)
    var needReboot by mutableStateOf(false)

    var error by mutableStateOf("")
    var patchLog by mutableStateOf("")

    private val patchDir: ExtendedFile = FileSystemManager.getLocal().getFile(apApp.filesDir.parent, "patch")
    private var srcBoot: ExtendedFile = patchDir.getChildFile("boot.img")
    private var shell: Shell = createRootShell()
    private var prepared: Boolean = false

    // Serializes work that mutates patchDir: prepare() wipes it, so a concurrent
    // copyAndParseBootimg/embedKPM must wait instead of racing or being dropped.
    private val workMutex = Mutex()

    private var entryMode: PatchMode = PatchMode.PATCH_ONLY

    /**
     * Whether the image the current mode patches has been read successfully.
     * A ramdisk has no kernel banner to look at, so the two paths report
     * readiness differently.
     */
    val imageReady: Boolean
        get() = if (entryMode.ramdisk) ramdiskReady else kimgInfo.banner.isNotEmpty()

    private fun prepare() {
        patchDir.deleteRecursively()
        patchDir.mkdirs()
        val execs = listOf(
            "libkptools.so", "libbusybox.so", "libkpatch.so", "libbootctl.so",
            // ramdisk (init_boot) patcher: `./kpramdisk inject` puts kpinit and
            // the kernelpatch.ko into a ramdisk image.
            "libkpramdisk.so"
        )
        error = ""

        val info = apApp.applicationInfo
        val libs = File(info.nativeLibraryDir).listFiles { _, name ->
            execs.contains(name)
        } ?: emptyArray()

        for (lib in libs) {
            val name = lib.name.substring(3, lib.name.length - 3)
            Os.symlink(lib.path, "$patchDir/$name")
        }

        // Extract scripts
        for (script in listOf(
            "boot_patch.sh", "boot_unpatch.sh", "boot_extract.sh", "util_functions.sh", "kpimg"
        )) {
            val dest = File(patchDir, script)
            apApp.assets.open(script).writeTo(dest)
        }

        // The ramdisk path injects kpinit and the LKM matching the running
        // kernel, both of which ship inside the apk.
        if (entryMode.ramdisk) {
            try {
                apApp.assets.open(RAMDISK_KPINIT).writeTo(File(patchDir, RAMDISK_KPINIT))
                val koName = jailbreakAssetName()
                    ?: throw IOException("cannot derive the KMI from ${Os.uname().release}")
                apApp.assets.open(koName).writeTo(File(patchDir, RAMDISK_KO))
            } catch (e: Exception) {
                error += "ramdisk payload missing: ${e.message}\n"
            }
        }

    }

    private fun parseKpimg() {
        val result = shellForResult(
            shell, "cd $patchDir", "./kptools -l -k kpimg"
        )

        if (result.isSuccess) {
            val ini = Ini(StringReader(result.out.joinToString("\n")))
            val kpimg = ini["kpimg"]
            if (kpimg != null) {
                kpimgInfo = KPModel.KPImgInfo(
                    kpimg["version"].toString(),
                    kpimg["compile_time"].toString(),
                    kpimg["config"].toString(),
                    "",     // manager no longer keeps a separate superkey
                    kpimg["root_superkey"].toString(),   // empty
                )
            } else {
                error += "parse kpimg error\n"
            }
        } else {
            error = result.err.joinToString("\n")
        }
    }

    private fun parseBootimg(bootimg: String) {
        if (entryMode.ramdisk) {
            // No kernel to unpack in init_boot; kpramdisk reads the ramdisk.
            error = ""
            parseRamdiskImg(bootimg)
            return
        }
        val result = shellForResult(
            shell,
            "cd $patchDir",
            "./kptools unpacknolog $bootimg",
            "./kptools -l -i kernel",
        )
        if (result.isSuccess) {
            val ini = Ini(StringReader(result.out.joinToString("\n")))
            Log.d(TAG, "kernel image info: $ini")

            val kernel = ini["kernel"]
            if (kernel == null) {
                error += "empty kernel section"
                Log.d(TAG, error)
                return
            }
            kimgInfo = KPModel.KImgInfo(kernel["banner"].toString(), kernel["patched"].toBoolean())
            if (kimgInfo.patched) {
                val superkey = ini["kpimg"]?.getOrDefault("superkey", "") ?: ""
                kpimgInfo.superKey = superkey
                if (checkSuperKeyValidation(superkey)) {
                    this.superkey = superkey
                }
                var kpmNum = kernel["extra_num"]?.toInt()
                if (kpmNum == null) {
                    val extras = ini["extras"]
                    kpmNum = extras?.get("num")?.toInt()
                }
                if (kpmNum != null && kpmNum > 0) {
                    for (i in 0..<kpmNum) {
                        val extra = ini["extra $i"]
                        if (extra == null) {
                            error += "empty extra section"
                            break
                        }
                        val type = KPModel.ExtraType.valueOf(extra["type"]!!.uppercase())
                        val name = extra["name"].toString()
                        val args = extra["args"].toString()
                        var event = extra["event"].toString()
                        if (event.isEmpty()) {
                            event = KPModel.TriggerEvent.PRE_KERNEL_INIT.event
                        }
                        if (type == KPModel.ExtraType.KPM) {
                            val kpmInfo = KPModel.KPMInfo(
                                type, name, event, args,
                                extra["version"].toString(),
                                extra["license"].toString(),
                                extra["author"].toString(),
                                extra["description"].toString(),
                                moduleId = safeKpmModuleId(name),
                                loadSource = "embedded",
                            )
                            existedExtras.add(kpmInfo)
                        }
                    }

                }
            }
        } else {
            error += result.err.joinToString("\n")
        }
    }

    val checkSuperKeyValidation: (superKey: String) -> Boolean = { superKey ->
        superKey.length in 8..63 && superKey.any { it.isDigit() } && superKey.any { it.isLetter() }
    }

    fun copyAndParseBootimg(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            workMutex.withLock {
                if (!ensurePrepared()) return@withLock
                if (running) return@withLock
                running = true
                try {
                    uri.inputStream().buffered().use { src ->
                        srcBoot.also {
                            src.copyAndCloseOut(it.newOutputStream())
                        }
                    }
                    parseBootimg(srcBoot.path)
                } catch (e: IOException) {
                    Log.e(TAG, "copy boot image error: $e")
                } finally {
                    running = false
                }
            }
        }
    }

    private fun extractAndParseBootimg(mode: PatchMode) {
        var cmdBuilder = "./boot_extract.sh"

        if (mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
            cmdBuilder += " true"
        } else if (mode.ramdisk) {
            cmdBuilder += " ramdisk"
        }

        val result = shellForResult(
            shell,
            "export ASH_STANDALONE=1",
            "cd $patchDir",
            "./busybox sh $cmdBuilder",
        )

        if (result.isSuccess) {
            bootSlot = if (!result.out.toString().contains("SLOT=")) {
                ""
            } else {
                result.out.filter { it.startsWith("SLOT=") }[0].removePrefix("SLOT=")
            }
            if (mode.ramdisk) {
                val dev = result.out.filter { it.startsWith("RAMDISKIMAGE=") }
                if (dev.isEmpty()) {
                    error = "- can't find init_boot.img!\n"
                    running = false
                    return
                }
                ramdiskDev = dev[0].removePrefix("RAMDISKIMAGE=")
                bootDev = ramdiskDev
                Log.i(TAG, "current ramdisk: $ramdiskDev")
                srcBoot = FileSystemManager.getLocal().getFile(ramdiskDev)
                parseBootimg(ramdiskDev)
                running = false
                return
            }
            bootDev =
                result.out.filter { it.startsWith("BOOTIMAGE=") }[0].removePrefix("BOOTIMAGE=")
            Log.i(TAG, "current slot: $bootSlot")
            Log.i(TAG, "current bootimg: $bootDev")
            srcBoot = FileSystemManager.getLocal().getFile(bootDev)
            parseBootimg(bootDev)
        } else {
            error = result.err.joinToString("\n")
        }
        running = false
    }

    /**
     * Read a first stage ramdisk with kpramdisk instead of kptools: there is no
     * kernel to unpack, and the entries decide whether the image is usable and
     * whether it already carries a KernelPatch LKM.
     */
    private fun parseRamdiskImg(ramdisk: String) {
        val info = shellForResult(shell, "cd $patchDir", "./kpramdisk info $ramdisk")
        val list = shellForResult(shell, "cd $patchDir", "./kpramdisk list $ramdisk")
        if (!list.isSuccess) {
            error = (info.err + list.err).joinToString("\n")
            ramdiskReady = false
            return
        }
        val entries = list.out.mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            // `<type><mode> <size> <name>[ -> <target>]`
            if (fields.size >= 4) fields[3] else null
        }
        if (!entries.contains("init")) {
            error = "- no first stage `init` in $ramdisk, this is not an init_boot ramdisk\n"
            ramdiskReady = false
            return
        }
        ramdiskPatched = entries.contains(RAMDISK_KO)
        ramdiskInfo = (info.out + list.out).joinToString("\n")
        ramdiskReady = true
    }

    // Runs the one-time initialization with the caller already holding
    // workMutex. Every patchDir consumer funnels through this so ordering does
    // not depend on which fire-and-forget coroutine grabs the lock first.
    // Returns false when initialization failed; callers must not touch
    // patchDir in that case.
    private suspend fun ensurePrepared(): Boolean {
        if (prepared) return true
        running = true
        try {
            prepare()
            if (!entryMode.isUnpatch) {
                parseKpimg()
            }
            if (entryMode == PatchMode.PATCH_AND_INSTALL || entryMode == PatchMode.UNPATCH || entryMode == PatchMode.INSTALL_TO_NEXT_SLOT ||
                entryMode == PatchMode.RAMDISK_PATCH_AND_INSTALL || entryMode == PatchMode.RAMDISK_UNPATCH
            ) {
                // Ramdisk modes read the ramdisk partition directly;
                // RAMDISK_PATCH_ONLY waits for the file the user picks instead.
                extractAndParseBootimg(entryMode)
            }
            prepared = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            error = "prepare failed: ${e.message}\n"
            return false
        } finally {
            running = false
        }
    }

    fun prepare(mode: PatchMode) {
        entryMode = mode
        viewModelScope.launch(Dispatchers.IO) {
            workMutex.withLock { ensurePrepared() }
        }
    }

    fun embedKPM(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            workMutex.withLock {
                if (!ensurePrepared()) return@withLock
                if (running) return@withLock
                running = true
                error = ""
                try {

                val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
                val kpmFileName = "${rand}.kpm"
                val kpmFile: ExtendedFile = patchDir.getChildFile(kpmFileName)

                Log.i(TAG, "copy kpm to: " + kpmFile.path)
                try {
                    uri.inputStream().buffered().use { src ->
                        kpmFile.also {
                            src.copyAndCloseOut(it.newOutputStream())
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Copy kpm error: $e")
                }

                val result = shellForResult(
                    shell, "cd $patchDir", "./kptools -l -M ${kpmFile.path}"
                )

                if (result.isSuccess) {
                    val ini = Ini(StringReader(result.out.joinToString("\n")))
                    val kpm = ini["kpm"]
                    if (kpm != null) {
                        val kpmInfo = KPModel.KPMInfo(
                            KPModel.ExtraType.KPM,
                            kpm["name"].toString(),
                            KPModel.TriggerEvent.PRE_KERNEL_INIT.event,
                            "",
                            kpm["version"].toString(),
                            kpm["license"].toString(),
                            kpm["author"].toString(),
                            kpm["description"].toString(),
                            moduleId = safeKpmModuleId(kpm["name"].toString()),
                            loadSource = "embedded",
                        )
                        newExtras.add(kpmInfo)
                        newExtrasFileName.add(kpmFileName)
                    }
                } else {
                    error = "Invalid KPM\n"
                }
                } finally {
                    running = false
                }
            }
        }
    }

    fun doUnpatch() {
        viewModelScope.launch(Dispatchers.IO) {
            workMutex.withLock {
                if (!ensurePrepared()) return@withLock
                if (entryMode.ramdisk) {
                    doUnpatchRamdisk()
                    return@withLock
                }
                patching = true
                try {
                    patchLog = ""
                    Log.i(TAG, "starting unpatching...")

                    val logs = object : CallbackList<String>() {
                        override fun onAddElement(e: String?) {
                            patchLog += e
                            Log.i(TAG, "" + e)
                            patchLog += "\n"
                        }
                    }

                    val result = shell.newJob().add(
                        "export ASH_STANDALONE=1",
                        "cd $patchDir",
                        "cp /data/adb/ap/ori.img new-boot.img",
                        "./busybox sh ./boot_unpatch.sh $bootDev",
                        "rm -f ${APApplication.APD_PATH}",
                        "rm -rf ${APApplication.APATCH_FOLDER}",
                    ).to(logs, logs).exec()

                    if (result.isSuccess) {
                        logs.add(" Unpatch successful")
                        needReboot = true
                        APApplication.markNeedReboot()
                    } else {
                        logs.add(" Unpatched failed")
                        error = result.err.joinToString("\n")
                    }
                    logs.add("****************************")

                    patchdone = true
                    patching = false
                } finally {
                    patching = false
                }
            }
        }
    }
    fun isSuExecutable(): Boolean {
        val suFile = File("/system/bin/su")
        return suFile.exists() && suFile.canExecute()
    }
    fun doPatch(mode: PatchMode, useKey: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            workMutex.withLock {
                if (!ensurePrepared()) return@withLock
                if (mode.ramdisk) {
                    doPatchRamdisk(mode)
                    return@withLock
                }
                patching = true
                try {
                    Log.d(TAG, "starting patching...")

                    val apVer = Version.getManagerVersion().second
                    val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
                    val outFilename = "apatch_patched_${apVer}_${BuildConfig.buildKPV}_${rand}.img"

                    val logs = object : CallbackList<String>() {
                        override fun onAddElement(e: String?) {
                            patchLog += e
                            Log.d(TAG, "" + e)
                            patchLog += "\n"
                        }
                    }
                    logs.add("****************************")

                    var patchCommand = mutableListOf("./busybox sh boot_patch.sh \"$0\" \"$@\"")

                    // adapt for 0.10.7 and lower KP
                    var isKpOld = false

                    val superkey = if (useKey && this@PatchesViewModel.superkey.isNotEmpty()) this@PatchesViewModel.superkey else "su"

                    if (mode == PatchMode.PATCH_AND_INSTALL || mode == PatchMode.INSTALL_TO_NEXT_SLOT) {

                        val KPCheck = shell.newJob().add("truncate ${APApplication.superKey} -Z ${APApplication.allAllowScontext} -c whoami").exec()
                        if (KPCheck.isSuccess && !isSuExecutable()) {
                            patchCommand.addAll(0, listOf("truncate", APApplication.superKey, "-Z", APApplication.allAllowScontext, "-c"))
                            patchCommand.addAll(listOf(superkey, srcBoot.path, "true"))
                        } else {
                            patchCommand = mutableListOf("./busybox", "sh", "boot_patch.sh")
                            patchCommand.addAll(listOf(superkey, srcBoot.path, "true"))
                            isKpOld = true
                        }

                    } else {
                        patchCommand.addAll(0, listOf("sh", "-c"))
                        patchCommand.addAll(listOf(superkey, srcBoot.path))
                    }

                    for (i in 0..<newExtrasFileName.size) {
                        patchCommand.addAll(listOf("-M", newExtrasFileName[i]))
                        val extra = newExtras[i]
                        if (extra.args.isNotEmpty()) {
                            patchCommand.addAll(listOf("-A", extra.args))
                        }
                        if (extra.event.isNotEmpty()) {
                            patchCommand.addAll(listOf("-V", extra.event))
                        }
                        patchCommand.addAll(listOf("-T", extra.type.desc))
                    }
                    for (i in 0..<existedExtras.size) {
                        val extra = existedExtras[i]
                        patchCommand.addAll(listOf("-E", extra.name))
                        if (extra.args.isNotEmpty()) {
                            patchCommand.addAll(listOf("-A", extra.args))
                        }
                        if (extra.event.isNotEmpty()) {
                            patchCommand.addAll(listOf("-V", extra.event))
                        }
                        patchCommand.addAll(listOf("-T", extra.type.desc))
                    }

                    val builder = ProcessBuilder(patchCommand)

                    Log.i(TAG, "patchCommand: $patchCommand")

                    var succ = false

                    if (isKpOld) {
                        val resultString = "\"" + patchCommand.joinToString(separator = "\" \"") + "\""
                        val result = shell.newJob().add(
                            "export ASH_STANDALONE=1",
                            "cd $patchDir",
                            resultString,
                        ).to(logs, logs).exec()
                        succ = result.isSuccess
                    } else {
                        builder.environment().put("ASH_STANDALONE", "1")
                        builder.directory(patchDir)
                        builder.redirectErrorStream(true)

                        val process = builder.start()

                        Thread {
                            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    patchLog += line
                                    Log.i(TAG, "" + line)
                                    patchLog += "\n"
                                }
                            }
                        }.start()
                        succ = process.waitFor() == 0
                    }

                    if (!succ) {
                        val msg = " Patch failed."
                        error = msg
        //                error += result.err.joinToString("\n")
                        logs.add(error)
                        logs.add("****************************")
                        patching = false
                        return@launch
                    }

                    // Move the stock boot backup to where the unpatch flow expects it;
                    // boot_patch.sh leaves ori.img in the app-private work dir, and the
                    // OTA post_ota.sh cleanup deletes that dir on reboot. Only created
                    // when the boot image was not already patched.
                    shell.newJob().add(
                        "[ -f $patchDir/ori.img ] && mkdir -p /data/adb/ap && cp $patchDir/ori.img /data/adb/ap/ && rm -f $patchDir/ori.img || true"
                    ).to(logs, logs).exec()

                    if (mode == PatchMode.PATCH_AND_INSTALL) {
                        logs.add("- Reboot to finish the installation...")
                        needReboot = true
                        APApplication.markNeedReboot()
                    } else if (mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
                        logs.add("- Connecting boot hal...")
                        val bootctlStatus = shell.newJob().add(
                            "cd $patchDir", "chmod 0777 $patchDir/bootctl", "./bootctl hal-info"
                        ).to(logs, logs).exec()
                        if (!bootctlStatus.isSuccess) {
                            logs.add("[X] Failed to connect to boot hal, you may need switch slot manually")
                        } else {
                            val currSlot = shellForResult(
                                shell, "cd $patchDir", "./bootctl get-current-slot"
                            ).out.toString()
                            val targetSlot = if (currSlot.contains("0")) {
                                1
                            } else {
                                0
                            }
                            logs.add("- Switching to next slot: $targetSlot...")
                            val setNextActiveSlot = shell.newJob().add(
                                "cd $patchDir", "./bootctl set-active-boot-slot $targetSlot"
                            ).exec()
                            if (setNextActiveSlot.isSuccess) {
                                logs.add("- Switch done")
                                logs.add("- Writing boot marker script...")
                                val markBootableScript = shell.newJob().add(
                                    "mkdir -p /data/adb/post-fs-data.d && rm -rf /data/adb/post-fs-data.d/post_ota.sh && touch /data/adb/post-fs-data.d/post_ota.sh",
                                    "echo \"chmod 0777 $patchDir/bootctl\" > /data/adb/post-fs-data.d/post_ota.sh",
                                    "echo \"chown root:root 0777 $patchDir/bootctl\" > /data/adb/post-fs-data.d/post_ota.sh",
                                    "echo \"$patchDir/bootctl mark-boot-successful\" > /data/adb/post-fs-data.d/post_ota.sh",
                                    "echo >> /data/adb/post-fs-data.d/post_ota.sh",
                                    "echo \"rm -rf $patchDir\" >> /data/adb/post-fs-data.d/post_ota.sh",
                                    "echo >> /data/adb/post-fs-data.d/post_ota.sh",
                                    "echo \"rm -f /data/adb/post-fs-data.d/post_ota.sh\" >> /data/adb/post-fs-data.d/post_ota.sh",
                                    "chmod 0777 /data/adb/post-fs-data.d/post_ota.sh",
                                    "chown root:root /data/adb/post-fs-data.d/post_ota.sh",
                                ).to(logs, logs).exec()
                                if (markBootableScript.isSuccess) {
                                    logs.add("- Boot marker script write done")
                                } else {
                                    logs.add("[X] Boot marker scripts write failed")
                                }
                            }
                        }
                        logs.add("- Reboot to finish the installation...")
                        needReboot = true
                        APApplication.markNeedReboot()
                    } else if (mode == PatchMode.PATCH_ONLY) {
                        succ = writePatchedImageToDownloads(outFilename, logs)
                    }
                    logs.add("****************************")
                    patchdone = true
                    patching = false
                } finally {
                    patching = false
                }
            }
        }
    }

    /**
     * Write the image produced in the patch dir to the public Downloads folder,
     * through MediaStore on API 29+ so no storage permission is needed.
     */
    private fun writePatchedImageToDownloads(
        outFilename: String,
        logs: CallbackList<String>,
    ): Boolean {
        val newBootFile = patchDir.getChildFile("new-boot.img")
        val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!outDir.exists()) outDir.mkdirs()
        val outPath = File(outDir, outFilename)
        val inputUri = newBootFile.getUri(apApp)

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val outUri = createDownloadUri(apApp, outFilename)
            insertDownload(apApp, outUri, inputUri)
        } else {
            newBootFile.inputStream().copyAndClose(outPath.outputStream())
            true
        }
        if (ok) {
            logs.add(" Output file is written to ")
            logs.add(" ${outPath.path}")
        } else {
            logs.add(" Write patched image failed")
        }
        return ok
    }

    /**
     * Ramdisk delivery path: kpramdisk injects kpinit and the KMI-matched
     * kernelpatch.ko into the first stage ramdisk, which is where a GKI device
     * loads the KernelPatch LKM from (init_boot).
     *
     * The source image is copied to a regular file first, because kpramdisk
     * needs a seekable file while a ramdisk partition is a block device.
     */
    private fun doPatchRamdisk(mode: PatchMode) {
        patching = true
        try {
            patchLog = ""
            error = ""

            val logs = object : CallbackList<String>() {
                override fun onAddElement(e: String?) {
                    patchLog += e
                    Log.d(TAG, "" + e)
                    patchLog += "\n"
                }
            }
            logs.add("****************************")
            logs.add(" KernelPatch ramdisk (init_boot) patcher")
            logs.add("****************************")

            val kpinit = File(patchDir, RAMDISK_KPINIT)
            val ko = File(patchDir, RAMDISK_KO)
            if (!kpinit.exists() || !ko.exists()) {
                error = "- kpinit / kernelpatch.ko is missing, nothing to inject\n"
                logs.add(error)
                logs.add("****************************")
                return
            }
            if (!srcBoot.exists()) {
                error = "- no ramdisk image selected\n"
                logs.add(error)
                logs.add("****************************")
                return
            }

            val cmd = "./kpramdisk inject $RAMDISK_ORI new-boot.img" +
                    " --init $RAMDISK_KPINIT --ko $RAMDISK_KO --force"
            logs.add("- Injecting KernelPatch into the first stage ramdisk")
            val result = shell.newJob().add(
                "export ASH_STANDALONE=1",
                "cd $patchDir",
                "cp -f ${srcBoot.path} $RAMDISK_ORI",
                cmd,
            ).to(logs, logs).exec()

            if (!result.isSuccess) {
                error = "- kpramdisk inject failed\n"
                logs.add(error)
                logs.add("****************************")
                return
            }

            // Keep the stock ramdisk for the restore path, and for installs that
            // were flashed by hand.
            shell.newJob().add(
                "mkdir -p ${APApplication.APATCH_FOLDER}",
                "cp -f $patchDir/$RAMDISK_ORI ${APApplication.APATCH_FOLDER}$RAMDISK_ORI",
            ).to(logs, logs).exec()

            if (mode == PatchMode.RAMDISK_PATCH_AND_INSTALL) {
                if (ramdiskDev.isEmpty()) {
                    error = "- init_boot partition unknown, cannot flash\n"
                    logs.add(error)
                    logs.add("****************************")
                    return
                }
                logs.add("- Flashing the new ramdisk to $ramdiskDev")
                val flash = shell.newJob().add(
                    "export ASH_STANDALONE=1",
                    "cd $patchDir",
                    "./busybox sh -c '. ./util_functions.sh; flash_image new-boot.img $ramdiskDev'",
                ).to(logs, logs).exec()
                if (!flash.isSuccess) {
                    error = "- flash failed (partition too small or read only?)\n"
                    logs.add(error)
                } else {
                    logs.add("- Reboot to finish, then install APatch from the home screen")
                    needReboot = true
                    APApplication.markNeedReboot()
                }
            } else {
                val apVer = Version.getManagerVersion().second
                val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
                val outFilename = "apatch_patched_init_boot_${apVer}_${BuildConfig.buildKPV}_${rand}.img"
                writePatchedImageToDownloads(outFilename, logs)
                logs.add("- Flash the image to init_boot, then install APatch from the home screen")
            }

            logs.add("****************************")
            patchdone = true
        } finally {
            patching = false
        }
    }

    /**
     * Restore the stock first stage ramdisk. There is no `kptools` equivalent of
     * an unpatch for a ramdisk, so the backup taken before injecting is flashed
     * back verbatim.
     */
    private fun doUnpatchRamdisk() {
        patching = true
        try {
            patchLog = ""
            error = ""
            Log.i(TAG, "starting ramdisk unpatching...")

            val logs = object : CallbackList<String>() {
                override fun onAddElement(e: String?) {
                    patchLog += e
                    Log.i(TAG, "" + e)
                    patchLog += "\n"
                }
            }
            logs.add("****************************")
            logs.add(" KernelPatch ramdisk (init_boot) restore")
            logs.add("****************************")

            if (ramdiskDev.isEmpty()) {
                error = "- init_boot partition unknown\n"
                logs.add(error)
                logs.add("****************************")
                return
            }
            val backup = "${APApplication.APATCH_FOLDER}$RAMDISK_ORI"
            val present = shellForResult(shell, "[ -f $backup ] && echo yes")
            if (!present.out.toString().contains("yes")) {
                error = "- no stock ramdisk backup at $backup\n"
                logs.add(error)
                logs.add("****************************")
                return
            }

            val result = shell.newJob().add(
                "export ASH_STANDALONE=1",
                "cd $patchDir",
                "cp -f $backup $RAMDISK_ORI",
                "./busybox sh -c '. ./util_functions.sh; flash_image $RAMDISK_ORI $ramdiskDev'",
            ).to(logs, logs).exec()

            if (result.isSuccess) {
                logs.add(" Restore successful")
                needReboot = true
                APApplication.markNeedReboot()
            } else {
                logs.add(" Restore failed")
                error = result.err.joinToString("\n")
            }
            logs.add("****************************")
            patchdone = true
        } finally {
            patching = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun createDownloadUri(context: Context, outFilename: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outFilename)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun insertDownload(context: Context, outUri: Uri?, inputUri: Uri): Boolean {
        if (outUri == null) return false

        try {
            val resolver = context.contentResolver
            resolver.openInputStream(inputUri)?.use { inputStream ->
                resolver.openOutputStream(outUri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(outUri, contentValues, null, null)

            return true
        } catch (_: FileNotFoundException) {
            return false
        }
    }

    fun File.getUri(context: Context): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, this)
    }

}
