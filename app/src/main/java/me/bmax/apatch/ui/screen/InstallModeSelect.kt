package me.bmax.apatch.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.PatchesDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.WarningCard
import me.bmax.apatch.ui.component.rememberConfirmDialog
import me.bmax.apatch.ui.viewmodel.PatchesViewModel
import me.bmax.apatch.util.isABDevice
import me.bmax.apatch.util.isJailbreakMode
import me.bmax.apatch.util.rootAvailable

// Hand-off channel from this screen to the Patches screen; a plain var would not
// notify the LaunchedEffect consuming it there.
var selectedBootImage by mutableStateOf<Uri?>(null)

@Destination<RootGraph>
@Composable
fun InstallModeSelectScreen(navigator: DestinationsNavigator) {
    var installMethod by remember {
        mutableStateOf<InstallMethod?>(null)
    }

    Scaffold(topBar = {
        TopBar(
            onBack = dropUnlessResumed { navigator.popBackStack() },
        )
    }) {
        Column(modifier = Modifier.padding(it)) {
            SelectInstallMethod(
                onSelected = { method ->
                    installMethod = method
                },
                navigator = navigator
            )

        }
    }
}

sealed class InstallMethod {
    data class SelectFile(
        val uri: Uri? = null,
        @param:StringRes override val label: Int = R.string.mode_select_page_select_file,
    ) : InstallMethod()

    // The first stage ramdisk (init_boot) is patched by kpramdisk rather than by
    // kptools, so it is picked as a separate kind of image.
    data class SelectInitBootFile(
        val uri: Uri? = null,
        @param:StringRes override val label: Int = R.string.mode_select_page_select_init_boot,
    ) : InstallMethod()

    data object DirectInstall : InstallMethod() {
        override val label: Int
            get() = R.string.mode_select_page_patch_and_install
    }

    // KernelPost-style delivery: kpinit + kernelpatch.ko go into the first stage
    // ramdisk, which is what brings root up on devices whose kernel cannot be
    // patched directly (and puts su into the kernel domain on boot).
    data object RamdiskDirectInstall : InstallMethod() {
        override val label: Int
            get() = R.string.mode_select_page_ramdisk_install

        override val summary: Int
            get() = R.string.mode_select_page_ramdisk_install_summary
    }

    data object DirectInstallToInactiveSlot : InstallMethod() {
        override val label: Int
            get() = R.string.mode_select_page_install_inactive_slot
    }

    data object RamdiskRestore : InstallMethod() {
        override val label: Int
            get() = R.string.mode_select_page_restore_init_boot
    }

    abstract val label: Int

    // Resource id of the optional second line; 0 means "no summary".
    open val summary: Int = 0
}

@Composable
private fun SelectInstallMethod(
    onSelected: (InstallMethod) -> Unit = {},
    navigator: DestinationsNavigator
) {
    val rootAvailable = rootAvailable()
    val isAbDevice = isABDevice()
    var jailbreakBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        jailbreakBlocked = withContext(Dispatchers.IO) { isJailbreakMode() }
    }

    val radioOptions =
        mutableListOf<InstallMethod>(InstallMethod.SelectFile(), InstallMethod.SelectInitBootFile())
    if (rootAvailable) {
        radioOptions.add(InstallMethod.DirectInstall)
        radioOptions.add(InstallMethod.RamdiskDirectInstall)
        radioOptions.add(InstallMethod.RamdiskRestore)
        if (isAbDevice) {
            radioOptions.add(InstallMethod.DirectInstallToInactiveSlot)
        }
    }

    var selectedOption by remember { mutableStateOf<InstallMethod?>(null) }
    // Which entry the file picker was opened for; the result tells the two
    // "pick an image yourself" flows apart, as they land on different screens.
    var pickerRamdisk by remember { mutableStateOf(false) }
    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                if (pickerRamdisk) {
                    val option = InstallMethod.SelectInitBootFile(uri)
                    selectedOption = option
                    onSelected(option)
                    selectedBootImage = option.uri
                    navigator.navigate(
                        PatchesDestination(PatchesViewModel.PatchMode.RAMDISK_PATCH_ONLY)
                    )
                } else {
                    val option = InstallMethod.SelectFile(uri)
                    selectedOption = option
                    onSelected(option)
                    selectedBootImage = option.uri
                    navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.PATCH_ONLY))
                }
            }
        }
    }

    val confirmDialog = rememberConfirmDialog(onConfirm = {
        selectedOption = InstallMethod.DirectInstallToInactiveSlot
        onSelected(InstallMethod.DirectInstallToInactiveSlot)
        navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.INSTALL_TO_NEXT_SLOT))
    }, onDismiss = null)
    val dialogTitle = stringResource(id = android.R.string.dialog_alert_title)
    val dialogContent = stringResource(id = R.string.mode_select_page_install_inactive_slot_warning)

    val onClick = { option: InstallMethod ->
        when (option) {
            is InstallMethod.SelectFile -> {
                // Reset before selecting
                selectedBootImage = null
                pickerRamdisk = false
                selectImageLauncher.launch(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/octet-stream"
                    }
                )
            }

            is InstallMethod.SelectInitBootFile -> {
                selectedBootImage = null
                pickerRamdisk = true
                selectImageLauncher.launch(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/octet-stream"
                    }
                )
            }

            is InstallMethod.DirectInstall -> {
                selectedOption = option
                onSelected(option)
                navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.PATCH_AND_INSTALL))
            }

            is InstallMethod.RamdiskDirectInstall -> {
                selectedOption = option
                onSelected(option)
                navigator.navigate(
                    PatchesDestination(PatchesViewModel.PatchMode.RAMDISK_PATCH_AND_INSTALL)
                )
            }

            is InstallMethod.DirectInstallToInactiveSlot -> {
                confirmDialog.showConfirm(dialogTitle, dialogContent)
            }

            is InstallMethod.RamdiskRestore -> {
                selectedOption = option
                onSelected(option)
                navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.RAMDISK_UNPATCH))
            }
        }
    }

    Column {
        if (jailbreakBlocked) {
            Box(Modifier.padding(12.dp)) {
                WarningCard(
                    message = stringResource(R.string.jailbreak_no_patch),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        if (!rootAvailable) {
            Box(Modifier.padding(12.dp)) {
                WarningCard(
                    message = stringResource(R.string.home_install_unknown_summary),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        if (!jailbreakBlocked) {
            radioOptions.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onClick(option)
                    }) {
                RadioButton(selected = option.javaClass == selectedOption?.javaClass, onClick = {
                    onClick(option)
                })
                Column {
                    Text(
                        text = stringResource(id = option.label),
                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                        fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                        fontStyle = MaterialTheme.typography.titleMedium.fontStyle
                    )
                    option.summary.takeIf { it != 0 }?.let {
                        Text(
                            text = stringResource(id = it),
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontFamily = MaterialTheme.typography.bodySmall.fontFamily,
                            fontStyle = MaterialTheme.typography.bodySmall.fontStyle
                        )
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(onBack: () -> Unit = {}) {
    TopAppBar(
        title = { Text(stringResource(R.string.mode_select_page_title)) },
        navigationIcon = {
            IconButton(
                onClick = onBack
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        },
    )
}