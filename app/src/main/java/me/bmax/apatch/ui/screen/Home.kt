package me.bmax.apatch.ui.screen

import android.os.Build
import android.system.Os
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.AboutScreenDestination
import com.ramcosta.composedestinations.generated.destinations.APModuleScreenDestination
import com.ramcosta.composedestinations.generated.destinations.InstallModeSelectScreenDestination
import com.ramcosta.composedestinations.generated.destinations.KPModuleScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PatchesDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.Natives
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.ui.component.WarningCard
import me.bmax.apatch.ui.component.rememberConfirmDialog
import me.bmax.apatch.ui.theme.blurEffect
import me.bmax.apatch.ui.theme.getAppBarColor
import me.bmax.apatch.ui.theme.isInDarkTheme
import me.bmax.apatch.ui.theme.rememberBlurBackdrop
import me.bmax.apatch.ui.theme.withBackdrop
import me.bmax.apatch.ui.viewmodel.PatchesViewModel
import me.bmax.apatch.util.LatestVersionInfo
import me.bmax.apatch.util.Version
import me.bmax.apatch.util.Version.getManagerVersion
import me.bmax.apatch.util.checkNewVersion
import me.bmax.apatch.util.getSELinuxStatus
import me.bmax.apatch.util.installJailbreak
import me.bmax.apatch.util.isJailbreakMode
import me.bmax.apatch.util.isSELinuxPermissive
import me.bmax.apatch.util.listModules
import me.bmax.apatch.util.migrateStockBootBackup
import me.bmax.apatch.util.reboot
import me.bmax.apatch.util.softReboot
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog

private val managerVersion = getManagerVersion()

@Destination<RootGraph>(start = true)
@Composable
fun HomeScreen(navigator: DestinationsNavigator) {
    val kpState by APApplication.kpStateLiveData.observeAsState(APApplication.State.UNKNOWN_STATE)
    val apState by APApplication.apStateLiveData.observeAsState(APApplication.State.UNKNOWN_STATE)
    val backdrop = rememberBlurBackdrop()

    // Pick up a stock boot backup left behind by a manually flashed PATCH_ONLY
    // install; see migrateStockBootBackup.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { migrateStockBootBackup() }
    }

    // Module counts for the KStatusCard quick cards (same data source as the module lists).
    val apmCount by produceState(initialValue = 0) {
        value = withContext(Dispatchers.IO) {
            runCatching { JSONArray(listModules()).length() }.getOrDefault(0)
        }
    }
    val kpmCount by produceState(initialValue = 0) {
        value = withContext(Dispatchers.IO) {
            Natives.kernelPatchModuleNum().toInt().coerceAtLeast(0)
        }
    }

    Scaffold(topBar = {
        TopBar(
            onInstallClick = dropUnlessResumed {
                navigator.navigate(InstallModeSelectScreenDestination)
            },
            navigator,
            kpState,
            backdrop
        )
    }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .withBackdrop(backdrop),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(0.dp))
            WarningCard()
            KStatusCard(
                kpState = kpState,
                apState = apState,
                apmCount = apmCount,
                kpmCount = kpmCount,
                onApmClick = {
                    navigator.navigate(APModuleScreenDestination) {
                        popUpTo(NavGraphs.root) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onKpmClick = {
                    navigator.navigate(KPModuleScreenDestination) {
                        popUpTo(NavGraphs.root) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                navigator = navigator
            )
            if (kpState != APApplication.State.UNKNOWN_STATE && apState != APApplication.State.ANDROIDPATCH_INSTALLED) {
                AStatusCard(apState)
            }
            val prefs = APApplication.sharedPreferences
            val checkUpdate by produceState(initialValue = prefs.getBoolean("check_update", true)) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == "check_update") {
                        value = p.getBoolean(key, true)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                awaitDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            if (checkUpdate) {
                UpdateCard()
            }
            InfoCard(kpState, apState)
            LearnMoreCard()
            Spacer(Modifier)
        }
    }
}

@Composable
fun UninstallDialog(showDialog: MutableState<Boolean>, navigator: DestinationsNavigator) {
    WindowDialog(
        show = showDialog.value,
        title = stringResource(id = R.string.home_dialog_uninstall_title),
        onDismissRequest = { showDialog.value = false },
        content = {
            Text(
                text = stringResource(id = R.string.home_dialog_uninstall_message),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = stringResource(id = android.R.string.cancel),
                    onClick = { showDialog.value = false },
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    text = stringResource(id = R.string.home_dialog_uninstall_ap_only),
                    onClick = {
                        showDialog.value = false
                        APApplication.uninstallApatch()
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        showDialog.value = false
                        APApplication.uninstallApatch()
                        navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.UNPATCH))
                    },
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError
                    )
                ) {
                    Text(text = stringResource(id = R.string.home_dialog_uninstall_all))
                }
            }
        }
    )
}

fun RebootDropdownItem(text: String, reason: String = "", onClick: (() -> Unit)? = null): DropdownItem {
    return DropdownItem(
        text = text,
        onClick = onClick ?: { reboot(reason) }
    )
}

@Composable
private fun TopBar(
    onInstallClick: () -> Unit,
    navigator: DestinationsNavigator,
    kpState: APApplication.State,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?
) {
    val uriHandler = LocalUriHandler.current
    val rebootConfirmDialog = rememberConfirmDialog()

    val rebootText = stringResource(R.string.reboot)
    val rebootSoftText = stringResource(R.string.reboot_soft)
    val rebootRecoveryText = stringResource(R.string.reboot_recovery)
    val rebootBootloaderText = stringResource(R.string.reboot_bootloader)
    val feedbackOrSuggestionText = stringResource(R.string.home_more_menu_feedback_or_suggestion)
    val aboutText = stringResource(R.string.home_more_menu_about)

    val rebootEntry = remember(kpState) {
        if (kpState != APApplication.State.UNKNOWN_STATE) {
            DropdownEntry(
                items = listOf(
                    RebootDropdownItem(text = rebootText),
                    RebootDropdownItem(text = rebootSoftText, reason = "soft_reboot"),
                    RebootDropdownItem(text = rebootRecoveryText, reason = "recovery"),
                    RebootDropdownItem(text = rebootBootloaderText, reason = "bootloader"),
                )
            )
        } else {
            null
        }
    }

    val moreEntry = remember {
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = feedbackOrSuggestionText,
                    onClick = {
                        uriHandler.openUri("https://github.com/bmax121/APatch/issues/new/choose")
                    }
                ),
                DropdownItem(
                    text = aboutText,
                    onClick = {
                        navigator.navigate(AboutScreenDestination)
                    }
                ),
            )
        )
    }

    TopAppBar(
        modifier = Modifier.blurEffect(backdrop),
        color = backdrop.getAppBarColor(),
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = onInstallClick) {
                Icon(
                    imageVector = Icons.Filled.InstallMobile,
                    contentDescription = stringResource(id = R.string.mode_select_page_title)
                )
            }

            if (kpState != APApplication.State.UNKNOWN_STATE && rebootEntry != null) {
                OverlayIconDropdownMenu(entry = rebootEntry) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(id = R.string.reboot)
                    )
                }
            }

            OverlayIconDropdownMenu(entry = moreEntry) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(id = R.string.settings)
                )
            }
        }
    )
}

@Composable
private fun KStatusCard(
    kpState: APApplication.State,
    apState: APApplication.State,
    apmCount: Int,
    kpmCount: Int,
    onApmClick: () -> Unit,
    onKpmClick: () -> Unit,
    navigator: DestinationsNavigator
) {
    val showUninstallDialog = remember { mutableStateOf(false) }
    if (showUninstallDialog.value) {
        UninstallDialog(showDialog = showUninstallDialog, navigator)
    }

    // Jailbreak button appears when the kernel is not installed and SELinux is permissive.
    val isPermissive by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { isSELinuxPermissive() }
    }
    // Jailbreak mode is active when the KernelPatch module has been loaded on a
    // stock kernel (a marker is written by apd late-load).
    val isJailbreak by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { isJailbreakMode() }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val jailbreakFailedMsg = stringResource(R.string.settings_jailbreak_failed)
    val jailbreakTriggeredMsg = stringResource(R.string.jailbreak_triggered)

    val isKpInstalled = kpState == APApplication.State.KERNELPATCH_INSTALLED
    val isKpNeedUpdate = kpState == APApplication.State.KERNELPATCH_NEED_UPDATE
    val isKpNeedReboot = kpState == APApplication.State.KERNELPATCH_NEED_REBOOT
    val isKpUninstalling = kpState == APApplication.State.KERNELPATCH_UNINSTALLING
    val isKpUnknown = kpState == APApplication.State.UNKNOWN_STATE

    val mainCardOnClick = {
        when {
            isJailbreak -> softReboot()
            isKpUnknown -> navigator.navigate(InstallModeSelectScreenDestination)
            isKpNeedUpdate -> {
                // todo: remove legacy compact for kp < 0.9.0
                if (Version.installedKPVUInt() < 0x900u) {
                    navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.PATCH_ONLY))
                } else {
                    navigator.navigate(InstallModeSelectScreenDestination)
                }
            }

            isKpNeedReboot -> reboot()
            isKpUninstalling -> { /* Do nothing */ }
            else -> {
                if (apState == APApplication.State.ANDROIDPATCH_INSTALLED ||
                    apState == APApplication.State.ANDROIDPATCH_NEED_UPDATE
                ) {
                    showUninstallDialog.value = true
                } else {
                    navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.UNPATCH))
                }
            }
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = CardDefaults.defaultColors(
                    color = when {
                        isJailbreak -> MiuixTheme.colorScheme.tertiaryContainer
                        isKpNeedUpdate || isKpNeedReboot -> MiuixTheme.colorScheme.errorContainer
                        isKpUnknown -> MiuixTheme.colorScheme.surfaceVariant
                        isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                        isInDarkTheme(0) -> Color(0xFF1A3825)
                        else -> Color(0xFFDFFAE4)
                    }
                ),
                onClick = mainCardOnClick,
                pressFeedbackType = PressFeedbackType.Tilt
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(38.dp, 45.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            modifier = Modifier.size(170.dp),
                            imageVector = when {
                                isJailbreak -> Icons.Filled.LockOpen
                                isKpNeedUpdate || isKpNeedReboot -> Icons.Rounded.ErrorOutline
                                isKpUnknown -> Icons.AutoMirrored.Outlined.HelpOutline
                                else -> Icons.Rounded.CheckCircleOutline
                            },
                            tint = when {
                                isJailbreak -> MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                                isKpNeedUpdate || isKpNeedReboot -> MiuixTheme.colorScheme.error.copy(alpha = 0.6f)
                                isKpUnknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                                isDynamicColor -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                                else -> Color(0xFF36D167)
                            },
                            contentDescription = null
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(all = 16.dp)
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = when {
                                isJailbreak -> stringResource(R.string.settings_jailbreak_mode)
                                isKpInstalled || isKpUninstalling -> stringResource(R.string.home_working)
                                isKpNeedUpdate || isKpNeedReboot -> stringResource(R.string.home_need_update)
                                else -> stringResource(R.string.home_install_unknown)
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface
                        )

                        Spacer(Modifier.height(2.dp))
                        when {
                            isJailbreak -> Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(R.string.settings_jailbreak_mode_summary),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )

                            isKpInstalled -> Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = "${Version.installedKPVString()} (${managerVersion.second}) - " +
                                    if (apState != APApplication.State.ANDROIDPATCH_NOT_INSTALLED) "Full" else "KernelPatch",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface
                            )

                            isKpNeedUpdate || isKpNeedReboot -> Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(
                                    R.string.kpatch_version_update,
                                    Version.installedKPVString(),
                                    Version.buildKPVString()
                                ),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface
                            )

                            isKpUninstalling -> { /* busy, no extra line */ }
                            else -> Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(R.string.home_install_unknown_summary),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    insideMargin = PaddingValues(16.dp),
                    onClick = onApmClick,
                    showIndication = true,
                    pressFeedbackType = PressFeedbackType.Tilt
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.apm),
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = apmCount.toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    insideMargin = PaddingValues(16.dp),
                    onClick = onKpmClick,
                    showIndication = true,
                    pressFeedbackType = PressFeedbackType.Tilt
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.kpm),
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = kpmCount.toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (isKpUnknown && isPermissive) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                scope.launch {
                    val success = installJailbreak()
                    if (success) {
                        Toast.makeText(context, jailbreakTriggeredMsg, Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        Toast.makeText(context, jailbreakFailedMsg, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }, content = {
                Text(stringResource(R.string.jailbreak))
            })
        }
    }
}

@Composable
private fun AStatusCard(apState: APApplication.State) {
    Card(
        colors = CardDefaults.defaultColors(color = run {
            MiuixTheme.colorScheme.secondaryContainer
        })
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row {
                Text(
                    text = stringResource(R.string.android_patch),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (apState) {
                    APApplication.State.ANDROIDPATCH_NOT_INSTALLED -> {
                        Icon(Icons.Outlined.Block, stringResource(R.string.home_not_installed))
                    }

                    APApplication.State.ANDROIDPATCH_INSTALLING -> {
                        Icon(Icons.Outlined.InstallMobile, stringResource(R.string.home_installing))
                    }

                    APApplication.State.ANDROIDPATCH_INSTALLED -> {
                        Icon(Icons.Outlined.CheckCircle, stringResource(R.string.home_working))
                    }

                    APApplication.State.ANDROIDPATCH_NEED_UPDATE -> {
                        Icon(Icons.Outlined.SystemUpdate, stringResource(R.string.home_need_update))
                    }

                    else -> {
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            stringResource(R.string.home_install_unknown)
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(2f)
                        .padding(start = 16.dp)
                ) {

                    when (apState) {
                        APApplication.State.ANDROIDPATCH_NOT_INSTALLED -> {
                            Text(
                                text = stringResource(R.string.home_not_installed),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        APApplication.State.ANDROIDPATCH_INSTALLING -> {
                            Text(
                                text = stringResource(R.string.home_installing),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        APApplication.State.ANDROIDPATCH_INSTALLED -> {
                            Text(
                                text = stringResource(R.string.home_working),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        APApplication.State.ANDROIDPATCH_NEED_UPDATE -> {
                            Text(
                                text = stringResource(R.string.home_need_update),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.apatch_version_update,
                                    Version.installedApdVString,
                                    managerVersion.second
                                ), fontSize = 14.sp
                            )
                        }

                        else -> {
                            Text(
                                text = stringResource(R.string.home_install_unknown),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                if (apState != APApplication.State.UNKNOWN_STATE) {
                    Column(
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Button(onClick = {
                            when (apState) {
                                APApplication.State.ANDROIDPATCH_NOT_INSTALLED, APApplication.State.ANDROIDPATCH_NEED_UPDATE -> {
                                    APApplication.installApatch()
                                }

                                APApplication.State.ANDROIDPATCH_UNINSTALLING -> {
                                    // Do nothing
                                }

                                else -> {
                                    APApplication.uninstallApatch()
                                }
                            }
                        }, content = {
                            when (apState) {
                                APApplication.State.ANDROIDPATCH_NOT_INSTALLED -> {
                                    Text(text = stringResource(id = R.string.home_ap_cando_install))
                                }

                                APApplication.State.ANDROIDPATCH_NEED_UPDATE -> {
                                    Text(text = stringResource(id = R.string.home_ap_cando_update))
                                }

                                APApplication.State.ANDROIDPATCH_UNINSTALLING -> {
                                    Icon(Icons.Outlined.Cached, contentDescription = "busy")
                                }

                                else -> {
                                    Text(text = stringResource(id = R.string.home_ap_cando_uninstall))
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}


@Composable
fun WarningCard() {
    var show by rememberSaveable { mutableStateOf(apApp.getBackupWarningState()) }
    if (show) {
        me.bmax.apatch.ui.component.WarningCard(
            message = stringResource(id = R.string.patch_warnning),
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp)
                )
            },
            onClose = {
                show = false
                apApp.updateBackupWarningState(false)
            }
        )
    }
}

private fun getSystemVersion(): String {
    return "${Build.VERSION.RELEASE} ${if (Build.VERSION.PREVIEW_SDK_INT != 0) "Preview" else ""} (API ${Build.VERSION.SDK_INT})"
}

private fun getDeviceInfo(): String {
    var manufacturer =
        Build.MANUFACTURER[0].uppercaseChar().toString() + Build.MANUFACTURER.substring(1)
    if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) {
        manufacturer += " " + Build.BRAND[0].uppercaseChar() + Build.BRAND.substring(1)
    }
    manufacturer += " " + Build.MODEL + " "
    return manufacturer
}

@Composable
private fun InfoCard(kpState: APApplication.State, apState: APApplication.State) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            val uname = Os.uname()

            @Composable
            fun InfoCardItem(label: String, content: String) {
                Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(text = content, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }

            if (kpState != APApplication.State.UNKNOWN_STATE) {
                InfoCardItem(
                    stringResource(R.string.home_kpatch_version), Version.installedKPVString()
                )

                Spacer(Modifier.height(16.dp))
                InfoCardItem(stringResource(R.string.home_su_path), Natives.suPath())

                Spacer(Modifier.height(16.dp))
            }

            if (apState != APApplication.State.UNKNOWN_STATE && apState != APApplication.State.ANDROIDPATCH_NOT_INSTALLED) {
                InfoCardItem(
                    stringResource(R.string.home_apatch_version), managerVersion.second.toString()
                )
                Spacer(Modifier.height(16.dp))
            }

            InfoCardItem(stringResource(R.string.home_device_info), getDeviceInfo())

            Spacer(Modifier.height(16.dp))
            InfoCardItem(stringResource(R.string.home_kernel), uname.release)

            Spacer(Modifier.height(16.dp))
            InfoCardItem(stringResource(R.string.home_system_version), getSystemVersion())

            Spacer(Modifier.height(16.dp))
            InfoCardItem(stringResource(R.string.home_fingerprint), Build.FINGERPRINT)

            Spacer(Modifier.height(16.dp))
            InfoCardItem(stringResource(R.string.home_selinux_status), getSELinuxStatus())

        }
    }
}

@Composable
fun UpdateCard() {
    val latestVersionInfo = LatestVersionInfo()
    val newVersion by produceState(initialValue = latestVersionInfo) {
        value = withContext(Dispatchers.IO) {
            checkNewVersion()
        }
    }
    val currentVersionCode = managerVersion.second
    val newVersionCode = newVersion.versionCode
    val newVersionUrl = newVersion.downloadUrl
    val changelog = newVersion.changelog

    val uriHandler = LocalUriHandler.current
    val title = stringResource(id = R.string.apm_changelog)
    val updateText = stringResource(id = R.string.apm_update)

    AnimatedVisibility(
        visible = newVersionCode > currentVersionCode,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val updateDialog = rememberConfirmDialog(onConfirm = { uriHandler.openUri(newVersionUrl) })
        WarningCard(
            message = stringResource(id = R.string.home_new_apatch_found).format(newVersionCode),
            containerColor = MiuixTheme.colorScheme.dividerLine,
            contentColor = MiuixTheme.colorScheme.onSurface,
            onClick = {
                if (changelog.isEmpty()) {
                    uriHandler.openUri(newVersionUrl)
                } else {
                    updateDialog.showConfirm(
                        title = title, content = changelog, markdown = true, confirm = updateText
                    )
                }
            }
        )
    }
}

@Composable
fun LearnMoreCard() {
    val uriHandler = LocalUriHandler.current

    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri("https://apatch.dev")
                }
                .padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = stringResource(R.string.home_learn_apatch),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_click_to_learn_apatch),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}
