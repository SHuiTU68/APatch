package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.InstallScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PatchesDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.Natives
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.ui.component.ConfirmResult
import me.bmax.apatch.ui.component.KPModuleRemoveButton
import me.bmax.apatch.ui.component.LoadingDialogHandle
import me.bmax.apatch.ui.component.MiuixDropdownItem
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.component.pinnedScrollBehavior
import me.bmax.apatch.ui.component.rememberConfirmDialog
import me.bmax.apatch.ui.component.rememberLoadingDialog
import me.bmax.apatch.ui.viewmodel.KPModel
import me.bmax.apatch.ui.viewmodel.KPModuleViewModel
import me.bmax.apatch.ui.viewmodel.safeKpmModuleId
import me.bmax.apatch.ui.viewmodel.PatchesViewModel
import me.bmax.apatch.util.inputStream
import me.bmax.apatch.util.writeTo
import me.bmax.apatch.util.rootShellForResult
import java.io.IOException
import java.io.StringReader
import org.ini4j.Ini

private const val TAG = "KernelPatchModule"
private val kpmInstallMutex = Mutex()
private data class UninstallResult(
    val unloaded: Boolean,
    val removed: Boolean,
)
private lateinit var targetKPMToControl: KPModel.KPMInfo

@Destination<RootGraph>
@Composable
fun KPModuleScreen(navigator: DestinationsNavigator) {
    val state by APApplication.apStateLiveData.observeAsState(APApplication.State.UNKNOWN_STATE)
    if (state == APApplication.State.UNKNOWN_STATE) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row {
                Text(
                    text = stringResource(id = R.string.kpm_kp_not_installed),
                    style = MiuixTheme.textStyles.title2
                )
            }
        }
        return
    }

    val viewModel = viewModel<KPModuleViewModel>()
    val scrollBehavior = pinnedScrollBehavior()
    val kpModuleListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (viewModel.moduleList.isEmpty() || viewModel.isNeedRefresh) {
            viewModel.fetchModuleList()
        }
    }

    Scaffold(topBar = {
        SearchAppBar(
            searchText = viewModel.search,
            onSearchTextChange = { viewModel.search = it },
            searchBarPlaceHolderText = stringResource(R.string.search_modules)
        )
    }, floatingActionButton = run {
        {
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            val moduleLoad = stringResource(id = R.string.kpm_load)
            val moduleInstall = stringResource(id = R.string.kpm_install)
            val moduleEmbed = stringResource(id = R.string.kpm_embed)
            val successToastText = stringResource(id = R.string.kpm_load_toast_succ)
            val installSuccessToastText = stringResource(id = R.string.kpm_install_toast_succ)
            val failToastText = stringResource(id = R.string.kpm_load_toast_failed)
            val loadingDialog = rememberLoadingDialog()

            val selectZipLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                if (it.resultCode != RESULT_OK) {
                    return@rememberLauncherForActivityResult
                }
                val data = it.data ?: return@rememberLauncherForActivityResult
                val uri = data.data ?: return@rememberLauncherForActivityResult

                Log.i(TAG, "select zip result: $uri")

                navigator.navigate(InstallScreenDestination(uri, MODULE_TYPE.KPM))
            }

            val selectKpmLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                if (it.resultCode != RESULT_OK) {
                    return@rememberLauncherForActivityResult
                }
                val data = it.data ?: return@rememberLauncherForActivityResult
                val uri = data.data ?: return@rememberLauncherForActivityResult

                // todo: args
                scope.launch {
                    val rc = loadModule(loadingDialog, uri, "")
                    val toastText = if (rc == 0) successToastText else "$failToastText: $rc"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context, toastText, Toast.LENGTH_SHORT
                        ).show()
                    }
                    viewModel.markNeedRefresh()
                    viewModel.fetchModuleList()
                }
            }

            val selectInstallKpmLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                if (it.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
                val uri = it.data?.data ?: return@rememberLauncherForActivityResult
                scope.launch {
                    val rc = kpmInstallMutex.withLock { installKpm(uri) }
                    Toast.makeText(context, if (rc == 0) installSuccessToastText else "$failToastText: $rc", Toast.LENGTH_SHORT).show()
                    viewModel.markNeedRefresh()
                }
            }

            var expanded by remember { mutableStateOf(false) }
            val options = listOf(moduleEmbed, moduleInstall, moduleLoad)

            Column {
                FloatingActionButton(
                    onClick = {
                        expanded = !expanded
                    },
                    containerColor = MiuixTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Add,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary
                    )
                }

                WindowListPopup(
                    show = expanded,
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { expanded = false }
                ) {
                    ListPopupColumn {
                        options.forEachIndexed { index, label ->
                            MiuixDropdownItem(
                                text = label,
                                optionSize = options.size,
                                index = index,
                                onSelectedIndexChange = {
                                    expanded = false
                                    when (label) {
                                        moduleEmbed -> {
                                            navigator.navigate(PatchesDestination(PatchesViewModel.PatchMode.PATCH_AND_INSTALL))
                                        }

                                        moduleInstall -> {
                                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                                            selectInstallKpmLauncher.launch(intent)
                                        }

                                        moduleLoad -> {
                                            val intent = Intent(Intent.ACTION_GET_CONTENT)
                                            intent.type = "*/*"
                                            selectKpmLauncher.launch(intent)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }) { innerPadding ->
        KPModuleList(
            viewModel = viewModel,
            modules = viewModel.moduleList,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            state = kpModuleListState,
            scrollBehavior = scrollBehavior
        )
    }
}

suspend fun loadModule(loadingDialog: LoadingDialogHandle, uri: Uri, args: String): Int {
    val rc = loadingDialog.withLoading {
        withContext(Dispatchers.IO) {
            run {
                val kpmDir: ExtendedFile = FileSystemManager.getLocal().getFile(apApp.cacheDir.path, "kpm")
                kpmDir.deleteRecursively()
                kpmDir.mkdirs()
                val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
                val kpm = kpmDir.getChildFile("${rand}.kpm")
                Log.d(TAG, "save tmp kpm: ${kpm.path}")
                var rc = -1
                try {
                    uri.inputStream().buffered().writeTo(kpm)
                    rc = Natives.loadKernelPatchModule(kpm.path, args).toInt()
                } catch (e: IOException) {
                    Log.e(TAG, "Copy kpm error: $e")
                }
                Log.d(TAG, "load ${kpm.path} rc: $rc")
                rc
            }
        }
    }
    return rc
}

/** Install a KPM from an app-local temporary file; it takes effect after reboot. */
suspend fun installKpm(uri: Uri): Int = withContext(Dispatchers.IO) {
    val tempDir: ExtendedFile =
        FileSystemManager.getLocal().getFile(apApp.cacheDir.path, "kpm-install")
    tempDir.deleteRecursively()
    tempDir.mkdirs()
    val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
    val temp = tempDir.getChildFile("$rand.kpm")
    try {
        Log.d(TAG, "save temporary KPM: ${temp.path}")
        uri.inputStream().buffered().writeTo(temp)
        val infoResult = rootShellForResult(
            "${APApplication.APATCH_FOLDER}bin/kptools -l -M '${temp.path}'"
        )
        if (!infoResult.isSuccess) return@withContext -2
        val section = Ini(StringReader(infoResult.out.joinToString("\n")))["kpm"] ?: return@withContext -3
        val name = section["name"]?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return@withContext -4
        val id = safeKpmModuleId(name)
        val dir = "${APApplication.KPMS_DIR}$id"
        val destination = "$dir/$id.kpm"
        val result = rootShellForResult(
            "mkdir -p '$dir' && cp -f '${temp.path}' '$destination'"
        )
        if (!result.isSuccess) return@withContext -5

        // Installed KPMs are loaded by the boot-time loader. Do not load them in
        // the current session; installation takes effect after reboot.
        Log.i(TAG, "install KPM $name to $destination; reboot required")
        0
    } catch (e: Exception) {
        Log.e(TAG, "install KPM failed", e)
        -1
    } finally {
        tempDir.deleteRecursively()
    }
}

@Composable
fun KPMControlDialog(showDialog: MutableState<Boolean>, onConfirm: (String) -> Unit) {
    val controlState = remember { TextFieldState() }
    var enable by remember { mutableStateOf(false) }

    LaunchedEffect(controlState.text) {
        enable = controlState.text.isNotEmpty()
    }

    WindowDialog(
        show = showDialog.value,
        title = stringResource(R.string.kpm_control_dialog_title),
        summary = stringResource(R.string.kpm_control_dialog_content),
        onDismissRequest = { showDialog.value = false }
    ) {
        TextField(
            state = controlState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            label = stringResource(id = R.string.kpm_control_paramters)
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = stringResource(id = android.R.string.cancel),
                onClick = { showDialog.value = false },
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(20.dp))

            TextButton(
                text = stringResource(id = android.R.string.ok),
                onClick = {
                    showDialog.value = false
                    // Run the control on the caller's scope: this dialog leaves
                    // composition here, cancelling any scope it owns.
                    onConfirm(controlState.text.toString())
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = enable
            )
        }
    }
}

@Composable
private fun KPModuleList(
    viewModel: KPModuleViewModel,
    modules: List<KPModel.KPMInfo>,
    modifier: Modifier = Modifier,
    state: LazyListState,
    scrollBehavior: ScrollBehavior
) {
    val moduleStr = stringResource(id = R.string.kpm)
    val moduleUninstallConfirm = stringResource(id = R.string.kpm_unload_confirm)
    val embeddedUnloadInvalid = stringResource(id = R.string.kpm_embedded_unload_invalid)
    val uninstall = stringResource(id = R.string.kpm_unload)
    val cancel = stringResource(id = android.R.string.cancel)
    val context = LocalContext.current
    val outMsgStringRes = stringResource(id = R.string.kpm_control_outMsg)
    val okStringRes = stringResource(id = R.string.kpm_control_ok)
    val failedStringRes = stringResource(id = R.string.kpm_control_failed)

    val confirmDialog = rememberConfirmDialog()
    val loadingDialog = rememberLoadingDialog()

    suspend fun onModuleControl(module: KPModel.KPMInfo, param: String) {
        lateinit var controlResult: Natives.KPMCtlRes
        loadingDialog.withLoading {
            withContext(Dispatchers.IO) {
                controlResult = Natives.kernelPatchModuleControl(module.name, param)
            }
        }

        if (controlResult.rc >= 0) {
            Toast.makeText(
                context,
                "$okStringRes\n${outMsgStringRes}: ${controlResult.outMsg}",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context,
                "$failedStringRes\n${outMsgStringRes}: ${controlResult.outMsg}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val showKPMControlDialog = remember { mutableStateOf(false) }
    if (showKPMControlDialog.value) {
        KPMControlDialog(showDialog = showKPMControlDialog, onConfirm = { param ->
            viewModel.viewModelScope.launch { onModuleControl(targetKPMToControl, param) }
        })
    }

    suspend fun onModuleUninstall(module: KPModel.KPMInfo) {
        val confirmResult = confirmDialog.awaitConfirm(
            moduleStr,
            content = if (module.loadSource == "embedded") {
                embeddedUnloadInvalid
            } else {
                moduleUninstallConfirm.format(module.name)
            },
            confirm = uninstall,
            dismiss = cancel
        )
        if (confirmResult != ConfirmResult.Confirmed) {
            return
        }

        val result = loadingDialog.withLoading {
            withContext(Dispatchers.IO) {
                val unloaded = module.loadSource.isBlank() || Natives.unloadKernelPatchModule(module.name) == 0L
                val removed = if (module.installed && module.loadSource != "embedded") {
                    val id = safeKpmModuleId(module.moduleId.ifBlank { module.name })
                    val dir = "${APApplication.KPMS_DIR}$id"
                    rootShellForResult("rm -rf '$dir' && test ! -e '$dir'").isSuccess
                } else true
                UninstallResult(unloaded, removed)
            }
        }

        // Refresh even when the live kernel instance could not be unloaded:
        // the persistent file may still have been removed and must not remain
        // represented as installed in the UI.
        if (result.removed) {
            viewModel.fetchModuleList()
        }
    }

    PullToRefresh(
        modifier = modifier,
        onRefresh = { viewModel.fetchModuleList() },
        isRefreshing = viewModel.isRefreshing
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = state,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = remember {
                PaddingValues(
                    start = 16.dp,
                    top = 11.dp, // spacedBy - TopBar padding
                    end = 16.dp,
                    bottom = 16.dp + 16.dp + 56.dp /*  Scaffold Fab Spacing + Fab container height */
                )
            },
        ) {
            when {
                modules.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.kpm_apm_empty), textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    items(modules) { module ->
                        val scope = rememberCoroutineScope()
                        KPModuleItem(
                            module,
                            onUninstall = {
                                scope.launch { onModuleUninstall(module) }
                            },
                            onControl = {
                                targetKPMToControl = module
                                showKPMControlDialog.value = true
                            },
                            onToggle = { enabled ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val id = safeKpmModuleId(module.moduleId.ifBlank { module.name })
                                        if (enabled) {
                                            rootShellForResult("rm -f '${APApplication.KPMS_DIR}$id/disable'")
                                        } else {
                                            rootShellForResult("touch '${APApplication.KPMS_DIR}$id/disable'")
                                        }
                                    }
                                    viewModel.updateModuleDisabled(module.moduleId, !enabled)
                                    viewModel.markNeedRefresh()
                                    viewModel.fetchModuleList()
                                }
                            },
                        )

                        // fix last item shadow incomplete in LazyColumn
                        Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KPModuleItem(
    module: KPModel.KPMInfo,
    onUninstall: (KPModel.KPMInfo) -> Unit,
    onControl: (KPModel.KPMInfo) -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val moduleAuthor = stringResource(id = R.string.kpm_author)
    val moduleArgs = stringResource(id = R.string.kpm_args)
    val decoration = TextDecoration.None

    Card(
        modifier = modifier,
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
    ) {

        Box(
            modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(all = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .alpha(alpha = alpha)
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = module.name,
                            style = MiuixTheme.textStyles.subtitle,
                            maxLines = 2,
                            textDecoration = decoration,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (module.loadSource == "embedded") {
                            Text(stringResource(R.string.kpm_embedded), style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.primary)
                        } else if (module.installed) {
                            Text(if (module.disabled) stringResource(R.string.kpm_disabled) else stringResource(R.string.kpm_installed),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.primary)
                        }

                        Text(
                            text = "${module.version}, $moduleAuthor ${module.author}",
                            style = MiuixTheme.textStyles.footnote1,
                            textDecoration = decoration,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )

                        Text(
                            text = "$moduleArgs: ${module.args}",
                            style = MiuixTheme.textStyles.footnote1,
                            textDecoration = decoration,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }

                    if (module.installed && module.loadSource != "embedded") {
                        Switch(checked = !module.disabled, onCheckedChange = onToggle)
                    }

                }

                Text(
                    modifier = Modifier
                        .alpha(alpha = alpha)
                        .padding(horizontal = 16.dp),
                    text = module.description,
                    style = MiuixTheme.textStyles.footnote1,
                    textDecoration = decoration,
                    color = MiuixTheme.colorScheme.outline
                )

                HorizontalDivider(
                    thickness = 1.5.dp,
                    color = MiuixTheme.colorScheme.dividerLine,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = { onControl(module) },
                        enabled = true,
                        minHeight = 35.dp,
                        minWidth = 35.dp,
                        backgroundColor = MiuixTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            painter = painterResource(id = R.drawable.settings),
                            contentDescription = stringResource(id = R.string.kpm_control)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    KPModuleRemoveButton(enabled = true, onClick = { onUninstall(module) })
                }
            }

        }
    }
}
