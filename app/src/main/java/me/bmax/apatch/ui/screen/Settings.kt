package me.bmax.apatch.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.automirrored.filled.FeaturedPlayList
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Web
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AboutScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NoMountControlScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.Natives
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.ui.component.ArrowItem
import me.bmax.apatch.ui.component.DropdownItem
import me.bmax.apatch.ui.component.SwitchItem
import me.bmax.apatch.ui.component.rememberLoadingDialog
import me.bmax.apatch.ui.theme.LocalPageScale
import me.bmax.apatch.ui.theme.blurEnabled
import me.bmax.apatch.ui.theme.pageScale
import me.bmax.apatch.util.calculateCacheSize
import me.bmax.apatch.util.clearAppCache
import me.bmax.apatch.util.formatSize
import me.bmax.apatch.util.getBugreportFile
import me.bmax.apatch.util.getKernelVersionCode
import me.bmax.apatch.util.isGkiKernel
import me.bmax.apatch.util.isGlobalNamespaceEnabled
import me.bmax.apatch.util.isNoMountEnabled
import me.bmax.apatch.util.outputStream
import me.bmax.apatch.util.rootShellForResult
import me.bmax.apatch.util.setGlobalNamespaceEnabled
import me.bmax.apatch.util.setNoMountEnabled
import me.bmax.apatch.util.ui.LocalSnackbarHost
import me.bmax.apatch.util.ui.NavigationBarsSpacer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

@Destination<RootGraph>
@Composable
fun SettingScreen(navigator: DestinationsNavigator) {
    val state by APApplication.apStateLiveData.observeAsState(APApplication.State.UNKNOWN_STATE)
    val kPatchReady = state != APApplication.State.UNKNOWN_STATE
    val aPatchReady =
        (state == APApplication.State.ANDROIDPATCH_INSTALLING || state == APApplication.State.ANDROIDPATCH_INSTALLED || state == APApplication.State.ANDROIDPATCH_NEED_UPDATE)
    var isGlobalNamespaceEnabled by rememberSaveable {
        mutableStateOf(false)
    }
    var namespaceLoaded by remember { mutableStateOf(false) }
    // The check shells out as root; run it once off the main thread instead of
    // synchronously in composition on every recomposition. The switch stays
    // disabled until the real value lands so a fast tap can't act on the
    // placeholder and get overwritten by the late result.
    LaunchedEffect(kPatchReady && aPatchReady) {
        if (kPatchReady && aPatchReady) {
            isGlobalNamespaceEnabled = withContext(Dispatchers.IO) { isGlobalNamespaceEnabled() }
            namespaceLoaded = true
        }
    }

    var isNoMountEnabled by rememberSaveable {
        mutableStateOf(false)
    }
    var noMountLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(kPatchReady && aPatchReady) {
        if (kPatchReady && aPatchReady) {
            isNoMountEnabled = withContext(Dispatchers.IO) { isNoMountEnabled() }
            noMountLoaded = true
        }
    }

    val snackBarHost = LocalSnackbarHost.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings),
            )
        },
        snackbarHost = { SnackbarHost(snackBarHost) }
    ) { paddingValues ->

        val loadingDialog = rememberLoadingDialog()

        val showResetSuPathDialog = remember { mutableStateOf(false) }
        if (showResetSuPathDialog.value) {
            ResetSUPathDialog(showResetSuPathDialog)
        }

        var showLogBottomSheet by remember { mutableStateOf(false) }
        val saveLog = stringResource(R.string.save_log)
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var cacheSize by remember { mutableStateOf(0L) }
        val showClearCacheDialog = remember { mutableStateOf(false) }
        if (showClearCacheDialog.value) {
            ClearCacheDialog(
                showDialog = showClearCacheDialog,
                cacheSize = cacheSize,
                onCleared = {
                    scope.launch {
                        cacheSize = withContext(Dispatchers.IO) { calculateCacheSize(context) }
                    }
                }
            )
        }
        val logSavedMessage = stringResource(R.string.log_saved)
        val exportBugreportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/gzip")
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch(Dispatchers.IO) {
                    loadingDialog.show()
                    uri.outputStream().use { output ->
                        getBugreportFile(context).inputStream().use {
                            it.copyTo(output)
                        }
                    }
                    loadingDialog.hide()
                    snackBarHost.showSnackbar(message = logSavedMessage)
                }
            }
        }

        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {

            val prefs = APApplication.sharedPreferences

            // All settings live inside a single card, matching upstream APatch's Miuix UI.
            Card {
            // Global mount
            if (kPatchReady && aPatchReady) {
                SwitchItem(
                    icon = Icons.Filled.Engineering,
                    title = stringResource(id = R.string.settings_global_namespace_mode),
                    summary = stringResource(id = R.string.settings_global_namespace_mode_summary),
                    checked = isGlobalNamespaceEnabled,
                    enabled = namespaceLoaded,
                    onCheckedChange = {
                        setGlobalNamespaceEnabled(
                            if (isGlobalNamespaceEnabled) {
                                "0"
                            } else {
                                "1"
                            }
                        )
                        isGlobalNamespaceEnabled = it
                    })
            }

            // Built-in NoMount (VFS path redirection) metamodule
            if (kPatchReady && aPatchReady) {
                SwitchItem(
                    icon = Icons.Filled.AltRoute,
                    title = stringResource(id = R.string.settings_nomount),
                    summary = stringResource(id = R.string.settings_nomount_summary),
                    checked = isNoMountEnabled,
                    enabled = noMountLoaded,
                    onCheckedChange = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            val result = setNoMountEnabled(enabled)
                            if (result) {
                                isNoMountEnabled = enabled
                            } else {
                                withContext(Dispatchers.Main) {
                                    snackBarHost.showSnackbar(
                                        message = context.getString(R.string.failure)
                                    )
                                }
                            }
                        }
                    })
                // NoMount control panel: native Compose page (replaces the WebUI)
                ArrowItem(
                    icon = Icons.Filled.Web,
                    title = stringResource(id = R.string.nomount_control_entry),
                    summary = stringResource(id = R.string.nomount_control_entry_summary),
                    onClick = { navigator.navigate(NoMountControlScreenDestination) })
            }

            // Legacy sucompat (path_probe) support
            if (kPatchReady && aPatchReady) {
                var sucompatEnabled by rememberSaveable {
                    mutableStateOf(
                        prefs.getBoolean("sucompat_enabled", false)
                    )
                }
                SwitchItem(
                    icon = Icons.AutoMirrored.Filled.FeaturedPlayList,
                    title = stringResource(id = R.string.settings_sucompat),
                    summary = stringResource(id = R.string.settings_sucompat_summary),
                    checked = sucompatEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            val result = if (enabled) {
                                // Enable: create marker file and register hooks via supercall
                                rootShellForResult("touch ${APApplication.SUCOMPAT_FILE}")
                                Natives.controlFeature("sucompat_extra", true)
                            } else {
                                // Disable: remove marker file and unregister hooks via supercall
                                rootShellForResult("rm -f ${APApplication.SUCOMPAT_FILE}")
                                Natives.controlFeature("sucompat_extra", false)
                            }
                            Log.d("SucompatToggle", "sucompat_extra ${if (enabled) "enable" else "disable"} result: $result")
                            if (result == 0L) {
                                prefs.edit { putBoolean("sucompat_enabled", enabled) }
                                sucompatEnabled = enabled
                            }
                        }
                    })
            }

            // Hide SELinux modification (test)
            if (kPatchReady && aPatchReady) {
                val kernelVersion = remember { getKernelVersionCode() }
                val kernelSupported = (kernelVersion ?: 0) >= 419
                val isGki = remember { isGkiKernel() }
                var selinuxHideEnabled by rememberSaveable {
                    mutableStateOf(prefs.getBoolean("selinux_hide_enabled", false))
                }
                val showSelinuxHideWarning = remember { mutableStateOf(false) }

                fun applySelinuxHide(enabled: Boolean) {
                    scope.launch(Dispatchers.IO) {
                        val command = if (enabled) {
                            "touch ${APApplication.SELINUX_HIDE_FILE}"
                        } else {
                            "rm -f ${APApplication.SELINUX_HIDE_FILE}"
                        }
                        val result = rootShellForResult(command)
                        Log.d("SelinuxHideToggle", "$command result: ${result.code}")
                        if (result.isSuccess) {
                            prefs.edit { putBoolean("selinux_hide_enabled", enabled) }
                            selinuxHideEnabled = enabled
                        }
                    }
                }

                SwitchItem(
                    icon = Icons.Filled.Security,
                    title = stringResource(id = R.string.settings_selinux_hide),
                    summary = stringResource(id = R.string.settings_selinux_hide_summary),
                    checked = selinuxHideEnabled,
                    enabled = kernelSupported,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            // Only tested on 5.10+, and non-GKI carries a bigger risk, so warn first.
                            val below510 = (kernelVersion ?: 0) < 510
                            if (below510 || !isGki) {
                                showSelinuxHideWarning.value = true
                            } else {
                                applySelinuxHide(true)
                            }
                        } else {
                            applySelinuxHide(false)
                        }
                    }
                )

                if (showSelinuxHideWarning.value) {
                    SelinuxHideWarningDialog(
                        showDialog = showSelinuxHideWarning,
                        kernelVersion = kernelVersion,
                        isGki = isGki,
                        onConfirm = { applySelinuxHide(true) },
                    )
                }
            }

            // WebView Debug
            if (aPatchReady) {
                var enableWebDebugging by rememberSaveable {
                    mutableStateOf(
                        prefs.getBoolean("enable_web_debugging", false)
                    )
                }
                SwitchItem(
                    icon = Icons.Filled.DeveloperMode,
                    title = stringResource(id = R.string.enable_web_debugging),
                    summary = stringResource(id = R.string.enable_web_debugging_summary),
                    checked = enableWebDebugging
                ) {
                    APApplication.sharedPreferences.edit {
                        putBoolean("enable_web_debugging", it)
                    }
                    enableWebDebugging = it
                }
            }

            // Check Update
            var checkUpdate by rememberSaveable {
                mutableStateOf(
                    prefs.getBoolean("check_update", true)
                )
            }

            SwitchItem(
                icon = Icons.Filled.Update,
                title = stringResource(id = R.string.settings_check_update),
                summary = stringResource(id = R.string.settings_check_update_summary),
                checked = checkUpdate
            ) {
                prefs.edit { putBoolean("check_update", it) }
                checkUpdate = it
            }

            // Blur Effects (API 33+ only)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var blurEnabledPref by rememberSaveable {
                    mutableStateOf(prefs.getBoolean("blur_enabled", true))
                }
                SwitchItem(
                    icon = Icons.Filled.BlurOn,
                    title = stringResource(R.string.settings_blur_enabled),
                    summary = stringResource(R.string.settings_blur_enabled_summary),
                    checked = blurEnabledPref,
                    onCheckedChange = { enabled ->
                        prefs.edit { putBoolean("blur_enabled", enabled) }
                        blurEnabledPref = enabled
                        blurEnabled = enabled
                    }
                )
            }

            // Page Scale
            var showScaleSlider by remember { mutableStateOf(false) }
            val currentPageScale = LocalPageScale.current
            var sliderValue by remember(currentPageScale) { mutableFloatStateOf(currentPageScale) }
            ArrowPreference(
                title = stringResource(R.string.settings_page_scale),
                summary = stringResource(R.string.settings_page_scale_summary),
                startAction = {
                    Icon(
                        imageVector = Icons.Filled.ZoomIn,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.settings_page_scale),
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                },
                endActions = {
                    Text(
                        text = "${(currentPageScale * 100).toInt()}%",
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                },
                onClick = { showScaleSlider = !showScaleSlider },
                holdDownState = showScaleSlider,
                bottomAction = {
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            prefs.edit { putFloat("page_scale", sliderValue) }
                            pageScale = sliderValue
                        },
                        valueRange = 0.8f..1.1f,
                        showKeyPoints = true,
                        keyPoints = listOf(0.8f, 0.9f, 1.0f, 1.1f),
                        magnetThreshold = 0.05f
                    )
                }
            )

            // Theme (Miuix style, matches upstream APatch settings UI)
            val colorValues = listOf(
                0,
                Color(0xFFEA4335).toArgb(),  // red
                Color(0xFF34A853).toArgb(),  // green
                Color(0xFF1A73E8).toArgb(),  // blue
                Color(0xFF9333EA).toArgb(),  // purple
                Color(0xFFFB8C00).toArgb(),  // orange
                Color(0xFF009688).toArgb(),  // teal
                Color(0xFFE91E63).toArgb(),  // pink
                Color(0xFF795548).toArgb(),  // brown
            )
            var themeMode by rememberSaveable {
                mutableStateOf(prefs.getInt("color_mode", 0))
            }
            var keyColor by rememberSaveable {
                mutableStateOf(prefs.getInt("key_color", 0))
            }
            val keyColorIndex = colorValues.indexOf(keyColor).coerceAtLeast(0)

            DropdownItem(
                title = stringResource(R.string.settings_theme),
                summary = stringResource(R.string.settings_theme_summary),
                items = listOf(
                    stringResource(R.string.settings_theme_mode_system),
                    stringResource(R.string.settings_theme_mode_light),
                    stringResource(R.string.settings_theme_mode_dark),
                    stringResource(R.string.settings_theme_mode_monet_system),
                    stringResource(R.string.settings_theme_mode_monet_light),
                    stringResource(R.string.settings_theme_mode_monet_dark),
                ),
                selectedIndex = themeMode,
                icon = Icons.Filled.Palette,
                onSelectedIndexChange = { index ->
                    themeMode = index
                    prefs.edit { putInt("color_mode", index) }
                }
            )

            // Key color (only used by Monet modes)
            AnimatedVisibility(visible = themeMode in 3..5) {
                DropdownItem(
                    title = stringResource(R.string.settings_key_color),
                    summary = stringResource(R.string.settings_key_color_summary),
                    items = listOf(
                        stringResource(R.string.settings_key_color_default),
                        stringResource(R.string.color_red),
                        stringResource(R.string.color_green),
                        stringResource(R.string.color_blue),
                        stringResource(R.string.color_purple),
                        stringResource(R.string.color_orange),
                        stringResource(R.string.color_teal),
                        stringResource(R.string.color_pink),
                        stringResource(R.string.color_brown),
                    ),
                    selectedIndex = keyColorIndex,
                    icon = Icons.Filled.FormatColorFill,
                    onSelectedIndexChange = { index ->
                        keyColor = colorValues[index]
                        prefs.edit { putInt("key_color", keyColor) }
                    }
                )
            }

            // su path
            if (kPatchReady) {
                ArrowItem(
                    title = stringResource(id = R.string.setting_reset_su_path),
                    summary = "",
                    icon = Icons.Filled.Commit,
                    onClick = { showResetSuPathDialog.value = true }
                )
            }

            // language
            val languagesValues = stringArrayResource(R.array.languages_values)
            val currentTag = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()
            val currentLanguageIndex = remember(currentTag) {
                languagesValues.indexOf(currentTag).coerceAtLeast(0)
            }
            DropdownItem(
                title = stringResource(R.string.settings_app_language),
                summary = stringResource(R.string.settings_app_language_summary),
                items = stringArrayResource(R.array.languages).toList(),
                selectedIndex = currentLanguageIndex,
                icon = Icons.Filled.Translate,
                onSelectedIndexChange = { index ->
                    val tag = if (index == 0) "" else languagesValues[index]
                    AppCompatDelegate.setApplicationLocales(
                        if (tag.isEmpty()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(tag)
                        }
                    )
                }
            )

            // log
            ArrowItem(
                title = stringResource(id = R.string.send_log),
                summary = stringResource(id = R.string.send_log_summary),
                icon = Icons.Filled.BugReport,
                onClick = { showLogBottomSheet = true }
            )
            if (showLogBottomSheet) {
                WindowBottomSheet(
                    show = showLogBottomSheet,
                    title = stringResource(id = R.string.send_log),
                    onDismissRequest = { showLogBottomSheet = false },
                    content = {
                        Row(
                            modifier = Modifier
                                .padding(10.dp)
                                .align(Alignment.CenterHorizontally)

                        ) {
                            Box {
                                Column(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .clickable {
                                            scope.launch {
                                                val formatter =
                                                    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
                                                val current = LocalDateTime.now().format(formatter)
                                                exportBugreportLauncher.launch("APatch_bugreport_${current}.tar.gz")
                                                showLogBottomSheet = false
                                            }
                                        }
                                ) {
                                    Icon(
                                        Icons.Filled.Save,
                                        contentDescription = null,
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    )
                                    Text(
                                        text = stringResource(id = R.string.save_log),
                                        modifier = Modifier.padding(top = 16.dp),
                                        textAlign = TextAlign.Center

                                    )
                                }

                            }
                            Box {
                                Column(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .clickable {
                                            scope.launch {
                                                val bugreport = loadingDialog.withLoading {
                                                    withContext(Dispatchers.IO) {
                                                        getBugreportFile(context)
                                                    }
                                                }

                                                val uri: Uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                                                    bugreport
                                                )

                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    setDataAndType(uri, "application/gzip")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }

                                                context.startActivity(
                                                    Intent.createChooser(
                                                        shareIntent,
                                                        saveLog
                                                    )
                                                )
                                                showLogBottomSheet = false
                                            }
                                        }) {
                                    Icon(
                                        Icons.Filled.Share,
                                        contentDescription = null,
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    )
                                    Text(
                                        text = stringResource(id = R.string.send_log),
                                        modifier = Modifier.padding(top = 16.dp),
                                        textAlign = TextAlign.Center

                                    )
                                }

                            }
                        }
                        NavigationBarsSpacer()
                    })
            }

            // clean cache
            LaunchedEffect(Unit) {
                cacheSize = withContext(Dispatchers.IO) { calculateCacheSize(context) }
            }
            ArrowItem(
                title = stringResource(id = R.string.settings_clean_cache),
                summary = formatSize(cacheSize),
                icon = Icons.Filled.CleaningServices,
                onClick = {
                    if (cacheSize > 0L) {
                        showClearCacheDialog.value = true
                    } else {
                        Toast.makeText(context, R.string.no_cache_to_clear, Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // about
            ArrowItem(
                title = stringResource(id = R.string.home_more_menu_about),
                summary = stringResource(id = R.string.about_summary),
                icon = Icons.Filled.Info,
                onClick = { navigator.navigate(AboutScreenDestination) }
            )
        }

    }
}
}

@Composable
private fun ClearCacheDialog(showDialog: MutableState<Boolean>, cacheSize: Long, onCleared: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    WindowDialog(
        show = showDialog.value,
        title = stringResource(id = R.string.clear_cache_title),
        summary = stringResource(id = R.string.clear_cache_message, formatSize(cacheSize)),
        onDismissRequest = { showDialog.value = false }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = stringResource(id = android.R.string.cancel),
                onClick = { showDialog.value = false }
            )
            TextButton(
                text = stringResource(id = android.R.string.ok),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showDialog.value = false
                    scope.launch {
                        loadingDialog.withLoading {
                            clearAppCache(context)
                        }
                        onCleared()
                    }
                }
            )
        }
    }
}

val suPathChecked: (path: String) -> Boolean = {
    it.startsWith("/") && it.trim().length > 1
}

@Composable
fun ResetSUPathDialog(showDialog: MutableState<Boolean>) {
    val context = LocalContext.current
    var suPath by remember { mutableStateOf(Natives.suPath()) }
    WindowDialog(
        show = showDialog.value,
        title = stringResource(id = R.string.setting_reset_su_path),
        onDismissRequest = { showDialog.value = false }
    ) {
        TextField(
            value = suPath,
            onValueChange = {
                suPath = it
            },
            label = stringResource(id = R.string.setting_reset_su_new_path),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = stringResource(id = android.R.string.cancel),
                onClick = { showDialog.value = false }
            )

            TextButton(
                text = stringResource(id = android.R.string.ok),
                enabled = suPathChecked(suPath),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showDialog.value = false
                    val success = Natives.resetSuPath(suPath)
                    Toast.makeText(
                        context,
                        if (success) R.string.success else R.string.failure,
                        Toast.LENGTH_SHORT
                    ).show()
                    rootShellForResult("echo $suPath > ${APApplication.SU_PATH_FILE}")
                }
            )
        }
    }
}

@Composable
fun SelinuxHideWarningDialog(
    showDialog: MutableState<Boolean>,
    kernelVersion: Int?,
    isGki: Boolean,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = showDialog.value,
        title = stringResource(id = R.string.settings_selinux_hide_warning_title),
        onDismissRequest = { showDialog.value = false }
    ) {
        if ((kernelVersion ?: 0) < 510) {
            Text(
                text = stringResource(id = R.string.settings_selinux_hide_warning_below_5_10),
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (!isGki) {
            Text(
                text = stringResource(id = R.string.settings_selinux_hide_warning_non_gki),
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = stringResource(id = android.R.string.cancel),
                onClick = { showDialog.value = false }
            )

            TextButton(
                text = stringResource(id = android.R.string.ok),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showDialog.value = false
                    onConfirm()
                }
            )
        }
    }
}
