package me.bmax.apatch.ui.screen

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.ui.component.ArrowItem
import me.bmax.apatch.ui.component.DropdownItem
import me.bmax.apatch.ui.component.SwitchItem
import me.bmax.apatch.ui.component.pinnedScrollBehavior
import me.bmax.apatch.util.rootShellForResult
import me.bmax.apatch.util.ui.LocalSnackbarHost
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

private const val TAG = "NoMountControl"

// --- Paths (mirror /apd/assets/nomount/webroot/index.js) ---
private const val NM_MOD_DIR = "/data/adb/modules"
private const val NM_DATA = "/data/adb/nomount"
private const val NM_BIN = "$NM_MOD_DIR/nomount/bin/nm"
private const val NM_EXCLUSIONS = "$NM_DATA/.exclusion_list.json"
private const val NM_DISABLE = "$NM_DATA/disable"
private const val NM_TARGET_PARTITIONS =
    "system system_ext vendor odm product apex oem optics prism mi_ext my_bigball my_carrier my_company my_engineering my_heytap my_manifest my_preload my_product my_region my_reserve my_stock"

// --- Data models ---
data class NoMountHomeInfo(
    val kernelVer: String = "",
    val deviceModel: String = "",
    val androidInfo: String = "",
    val versionFull: String = "",
    val active: Boolean = false,
    val nmMode: String = "",
    val injectedModules: Int = 0,
)

data class NoMountModuleInfo(
    val id: String,
    val name: String,
    val disabled: Boolean,
    val skipMount: Boolean,
    val injectedFiles: Int = 0,
)

data class NoMountExclusion(
    val uid: String,
    val label: String,
    val pkg: String,
)

private data class PickableApp(
    val packageInfo: android.content.pm.PackageInfo,
    val label: String,
    val uid: String,
)

@Destination<RootGraph>
@Composable
fun NoMountControlScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val snackBarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var homeInfo by remember { mutableStateOf(NoMountHomeInfo()) }
    var modules by remember { mutableStateOf<List<NoMountModuleInfo>>(emptyList()) }
    var exclusions by remember { mutableStateOf<List<NoMountExclusion>>(emptyList()) }
    var safeMode by remember { mutableStateOf(false) }
    var optionsLoaded by remember { mutableStateOf(false) }
    var homeLoading by remember { mutableStateOf(true) }
    var modulesLoading by remember { mutableStateOf(true) }
    var exclusionsLoading by remember { mutableStateOf(true) }
    var showAppPicker by remember { mutableStateOf(false) }

    val tabs = listOf(
        stringResource(R.string.nomount_tab_home),
        stringResource(R.string.nomount_tab_modules),
        stringResource(R.string.nomount_tab_exclusions),
        stringResource(R.string.nomount_tab_options),
    )

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    suspend fun refreshHome() {
        homeLoading = true
        homeInfo = withContext(Dispatchers.IO) { NoMountApi.loadHomeInfo() }
        homeLoading = false
    }

    suspend fun refreshModules() {
        modulesLoading = true
        modules = withContext(Dispatchers.IO) { NoMountApi.listModules() }
        modulesLoading = false
    }

    suspend fun refreshExclusions() {
        exclusionsLoading = true
        exclusions = withContext(Dispatchers.IO) { NoMountApi.listExclusions() }
        exclusionsLoading = false
    }

    suspend fun refreshOptions() {
        safeMode = withContext(Dispatchers.IO) { NoMountApi.isSafeMode() }
        optionsLoaded = true
    }

    suspend fun refreshAll() {
        refreshHome()
        refreshModules()
        refreshExclusions()
        refreshOptions()
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.nomount_control),
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                scrollBehavior = pinnedScrollBehavior()
            )
        },
        snackbarHost = { SnackbarHost(snackBarHost) },
        floatingActionButton = {
            if (selectedTab == 2) {
                FloatingActionButton(
                    onClick = { showAppPicker = true }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.nomount_add_exclusion)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                tabs = tabs,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            when (selectedTab) {
                0 -> HomeTab(homeInfo, homeLoading, onRefresh = {
                    scope.launch { refreshHome() }
                })
                1 -> ModulesTab(
                    modules = modules,
                    loading = modulesLoading,
                    onRefresh = { scope.launch { refreshModules() } },
                    onToggle = { module, enabled ->
                        scope.launch {
                            if (enabled) {
                                withContext(Dispatchers.IO) {
                                    NoMountApi.enableModule(module.id)
                                }
                            } else {
                                withContext(Dispatchers.IO) {
                                    NoMountApi.disableModule(module.id)
                                }
                            }
                            modules = withContext(Dispatchers.IO) { NoMountApi.listModules() }
                            refreshHome()
                        }
                    },
                    onHotAction = { module ->
                        scope.launch {
                            val isLoaded = module.injectedFiles > 0
                            val ok = withContext(Dispatchers.IO) {
                                if (isLoaded) NoMountApi.unloadModule(module.id)
                                else NoMountApi.loadModule(module.id)
                            }
                            if (ok) {
                                modules = withContext(Dispatchers.IO) { NoMountApi.listModules() }
                            } else {
                                toast(context.getString(R.string.failure))
                            }
                            refreshHome()
                        }
                    }
                )
                2 -> ExclusionsTab(
                    exclusions = exclusions,
                    loading = exclusionsLoading,
                    onRefresh = { scope.launch { refreshExclusions() } },
                    onRemove = { entry ->
                        scope.launch {
                            withContext(Dispatchers.IO) { NoMountApi.removeExclusion(entry) }
                            exclusions = withContext(Dispatchers.IO) { NoMountApi.listExclusions() }
                        }
                    }
                )
                3 -> OptionsTab(
                    safeMode = safeMode,
                    loaded = optionsLoaded,
                    onSafeModeChange = { enabled ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { NoMountApi.setSafeMode(enabled) }
                            if (ok) safeMode = enabled else toast(context.getString(R.string.failure))
                        }
                    },
                    onClearRules = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { NoMountApi.clearRules() }
                            if (ok) {
                                toast(context.getString(R.string.success))
                                refreshAll()
                            } else {
                                toast(context.getString(R.string.failure))
                            }
                        }
                    }
                )
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            existingUids = exclusions.map { it.uid }.toSet(),
            onDismiss = { showAppPicker = false },
            onAdd = { entries ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        NoMountApi.addExclusions(entries)
                    }
                    exclusions = withContext(Dispatchers.IO) { NoMountApi.listExclusions() }
                }
                showAppPicker = false
            }
        )
    }
}

// ---------------------------------------------------------------- Home ---

@Composable
private fun HomeTab(info: NoMountHomeInfo, loading: Boolean, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.nomount_unknown)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            // Status card
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (info.active) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (info.active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                loading -> stringResource(R.string.nomount_checking)
                                info.active -> stringResource(R.string.nomount_status_active)
                                else -> stringResource(R.string.nomount_status_inactive)
                            },
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = info.versionFull.ifBlank { unknown },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    if (info.nmMode.isNotEmpty()) {
                        Text(
                            text = if (info.nmMode == "lkm") {
                                stringResource(R.string.nomount_mode_lkm)
                            } else {
                                stringResource(R.string.nomount_mode_builtin)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Extension,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.nomount_status_label),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (info.injectedModules > 0) {
                            context.getString(
                                R.string.nomount_modules_injected_count,
                                info.injectedModules
                            )
                        } else {
                            stringResource(R.string.nomount_status_inactive)
                        },
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.nomount_device_label),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
            Card {
                InfoRow(Icons.Filled.Smartphone, stringResource(R.string.nomount_model_label), info.deviceModel.ifBlank { unknown })
                HorizontalDivider()
                InfoRow(Icons.Filled.SystemUpdateAlt, stringResource(R.string.nomount_system_label), info.androidInfo.ifBlank { unknown })
                HorizontalDivider()
                InfoRow(Icons.Filled.Memory, stringResource(R.string.nomount_kernel_label), info.kernelVer.ifBlank { unknown })
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// -------------------------------------------------------------- Modules ---

@Composable
private fun ModulesTab(
    modules: List<NoMountModuleInfo>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onToggle: (NoMountModuleInfo, Boolean) -> Unit,
    onHotAction: (NoMountModuleInfo) -> Unit,
) {
    val context = LocalContext.current

    if (loading && modules.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = stringResource(R.string.nomount_loading))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (modules.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.nomount_no_modules_found),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            items(modules, key = { it.id }) { module ->
                ModuleCard(
                    module = module,
                    onToggle = { onToggle(module, it) },
                    onHotAction = { onHotAction(module) }
                )
            }
        }
    }
}

@Composable
private fun ModuleCard(
    module: NoMountModuleInfo,
    onToggle: (Boolean) -> Unit,
    onHotAction: () -> Unit,
) {
    val context = LocalContext.current
    val statusText = when {
        module.injectedFiles > 0 && module.disabled -> stringResource(R.string.nomount_module_status_loaded)
        module.injectedFiles > 0 -> stringResource(R.string.nomount_module_status_active)
        module.disabled -> stringResource(R.string.nomount_module_status_disabled)
        module.skipMount -> stringResource(R.string.nomount_module_status_skipped)
        else -> stringResource(R.string.nomount_module_status_inactive)
    }
    val isLoaded = module.injectedFiles > 0

    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.name,
                        style = MiuixTheme.textStyles.title3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${stringResource(R.string.nomount_status_label)}: $statusText",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = context.getString(
                            R.string.nomount_modules_injected_files,
                            module.injectedFiles
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.width(8.dp))
                top.yukonga.miuix.kmp.basic.Switch(
                    checked = !module.disabled,
                    onCheckedChange = onToggle
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onHotAction,
                    colors = if (isLoaded) {
                        ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                            contentColor = MiuixTheme.colorScheme.onSurface
                        )
                    } else {
                        ButtonDefaults.buttonColorsPrimary()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    content = {
                        Text(
                            text = if (isLoaded) {
                                stringResource(R.string.nomount_hot_unload)
                            } else {
                                stringResource(R.string.nomount_hot_load)
                            }
                        )
                    }
                )
            }
        }
    }
}

// ----------------------------------------------------------- Exclusions ---

@Composable
private fun ExclusionsTab(
    exclusions: List<NoMountExclusion>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onRemove: (NoMountExclusion) -> Unit,
) {
    val context = LocalContext.current

    if (loading && exclusions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = stringResource(R.string.nomount_loading))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (exclusions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.nomount_no_exclusions_yet),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            items(exclusions, key = { it.uid }) { entry ->
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(entry.pkg)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.label,
                                style = MiuixTheme.textStyles.title3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entry.pkg,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onRemove(entry) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.nomount_remove_exclusion),
                                tint = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- Options ---

@Composable
private fun OptionsTab(
    safeMode: Boolean,
    loaded: Boolean,
    onSafeModeChange: (Boolean) -> Unit,
    onClearRules: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Card {
                SwitchItem(
                    icon = Icons.Filled.Shield,
                    title = stringResource(R.string.nomount_safe_mode),
                    summary = stringResource(R.string.nomount_safe_mode_desc),
                    checked = safeMode,
                    enabled = loaded,
                    onCheckedChange = onSafeModeChange
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.nomount_clear_rules),
                            style = MiuixTheme.textStyles.title3
                        )
                        Text(
                            text = stringResource(R.string.settings_nomount_summary),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Button(
                        onClick = onClearRules,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        content = {
                            Text(text = stringResource(R.string.nomount_clear_rules))
                        }
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------- App Picker ---

@Composable
private fun AppPickerDialog(
    existingUids: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (List<NoMountExclusion>) -> Unit,
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<PickableApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showManualUid by remember { mutableStateOf(false) }
    var manualUid by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val installed = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                installed
                    .filter { it.uid != 2000 && it.packageName != apApp.packageName }
                    .mapNotNull { appInfo ->
                        runCatching {
                            val info = pm.getPackageInfo(appInfo.packageName, 0)
                            val label = appInfo.loadLabel(pm).toString()
                            PickableApp(info, label, info.applicationInfo?.uid?.toString() ?: "")
                        }.getOrNull()
                    }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
        apps = result
        loading = false
    }

    val filtered = remember(search, showSystem, apps) {
        val query = search.lowercase().trim()
        apps.filter { app ->
            val isSystem = (app.packageInfo.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM)) != 0
            (!isSystem || showSystem) &&
                (query.isEmpty() ||
                    app.label.lowercase().contains(query) ||
                    app.packageInfo.packageName.lowercase().contains(query))
        }
    }

    BackHandler {
        if (showManualUid) showManualUid = false else onDismiss()
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.nomount_select_application),
        onDismissRequest = { if (showManualUid) showManualUid = false else onDismiss() }
    ) {
        if (showManualUid) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.nomount_enter_manual_uid),
                    style = MiuixTheme.textStyles.body2
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = manualUid,
                    onValueChange = { manualUid = it.filter { c -> c.isDigit() } },
                    label = stringResource(R.string.nomount_manual_uid_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            val uid = manualUid.trim()
                            if (uid.isNotEmpty()) {
                                onAdd(listOf(NoMountExclusion(uid, "UID: $uid", "System/Manual")))
                            }
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        content = {
                            Text(text = stringResource(android.R.string.ok))
                        }
                    )
                }
            }
            return@WindowDialog
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body1
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { showSystem = !showSystem }) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.nomount_show_system_apps)
                )
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.nomount_loading_apps))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = rememberLazyListState()
            ) {
                items(filtered, key = { it.uid + it.packageInfo.packageName }) { app ->
                    val isExisting = existingUids.contains(app.uid)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(app.packageInfo).build(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.label, style = MiuixTheme.textStyles.title3)
                            Text(
                                text = app.packageInfo.packageName,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        Text(
                            text = "UID: ${app.uid}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        if (!isExisting) {
                            Button(
                                onClick = {
                                    onAdd(
                                        listOf(
                                            NoMountExclusion(
                                                app.uid,
                                                app.label,
                                                app.packageInfo.packageName
                                            )
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                content = {
                                    Text(text = stringResource(R.string.nomount_add_exclusion))
                                }
                            )
                        }
                    }
                    if (app != filtered.lastOrNull()) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ API ---

/**
 * Shell helpers that replicate the NoMount WebUI's `exec()` calls
 * (see /apd/assets/nomount/webroot/index.js).
 */
object NoMountApi {

    private fun shellOut(cmd: String): Shell.Result = rootShellForResult(cmd)

    private fun fastCmd(cmd: String): String {
        return shellOut(cmd).out.joinToString("\n")
    }

    /** Split multi-part output on the same delimiter the WebUI uses. */
    private fun partsOf(output: String): List<String> =
        output.split("|||").map { it.trim() }

    fun loadHomeInfo(): NoMountHomeInfo {
        val script = """
            uname -r; echo "|||"
            getprop ro.product.vendor.model; [ -z "${'$'}(getprop ro.product.vendor.model)" ] && getprop ro.product.model; echo "|||"
            getprop ro.build.version.release; echo "|||"
            getprop ro.build.version.sdk; echo "|||"
            grep "version=" $NM_MOD_DIR/nomount/module.prop | cut -d= -f2; echo "|||"
            $NM_BIN version; echo "|||"
            $NM_BIN rule list --json; echo "|||"
            if $NM_BIN version > /dev/null 2>&1; then lsmod | grep -q nomount && echo lkm || echo built-in; fi
        """.trimIndent()

        val raw = partsOf(fastCmd(script))
        val unknown = ""
        val kVer = raw.getOrElse(0) { "" }.ifBlank { unknown }
        val model = raw.getOrElse(1) { "" }.ifBlank { unknown }
        val aRel = raw.getOrElse(2) { "" }.ifBlank { unknown }
        val aSdk = raw.getOrElse(3) { "" }.ifBlank { unknown }
        val mVer = raw.getOrElse(4) { "" }.ifBlank { unknown }
        val dVer = raw.getOrElse(5) { "" }.ifBlank { unknown }
        val nmMode = raw.getOrElse(7) { "" }.lowercase()

        var injectedModules = 0
        val rulesJson = raw.getOrElse(6) { "[]" }
        runCatching {
            val rules = JSONArray(rulesJson)
            val modCounts = HashSet<String>()
            for (i in 0 until rules.length()) {
                val obj = rules.optJSONObject(i)
                val real = obj?.optString("real") ?: continue
                if (real.startsWith(NM_MOD_DIR)) {
                    val seg = real.split("/")
                    if (seg.size > 4 && seg[4] != "nomount") modCounts.add(seg[4])
                }
            }
            injectedModules = modCounts.size
        }

        return NoMountHomeInfo(
            kernelVer = kVer,
            deviceModel = model,
            androidInfo = if (aRel.isNotEmpty()) "Android $aRel (API $aSdk)" else unknown,
            versionFull = if (mVer.isNotEmpty()) "$mVer ($dVer)" else unknown,
            active = dVer.isNotEmpty() && dVer != unknown,
            nmMode = nmMode,
            injectedModules = injectedModules,
        )
    }

    fun listModules(): List<NoMountModuleInfo> {
        val script = """
            $NM_BIN rule list --json; echo "|||"
            cd $NM_MOD_DIR
            for mod in *; do
                [ ! -d "${'$'}mod" ] || [ "${'$'}mod" = "nomount" ] || [ ! -f "${'$'}mod/module.prop" ] && continue
                has_injectable=0
                for p in $NM_TARGET_PARTITIONS; do [ -d "${'$'}mod/${'$'}p" ] && { [ -d "/${'$'}p" ] || [ -d "/system/${'$'}p" ]; } && has_injectable=1 && break; done
                [ ${'$'}has_injectable -eq 0 ] && continue
                echo "${'$'}mod|${'$'}(grep "^name=" "${'$'}mod/module.prop" | head -n1 | cut -d= -f2-)|${'$'}([ -f "${'$'}mod/disable" ] && echo true || echo false)|${'$'}([ -f "${'$'}mod/skip_mount" ] && echo true || echo false)"
            done
        """.trimIndent()

        val stdout = fastCmd(script)
        val sep = stdout.indexOf("|||")
        val jsonPart = if (sep >= 0) stdout.substring(0, sep).trim() else "[]"
        val modulesPart = if (sep >= 0) stdout.substring(sep + 3) else ""

        val ruleCountByMod = HashMap<String, Int>()
        runCatching {
            val rules = JSONArray(jsonPart)
            for (i in 0 until rules.length()) {
                val obj = rules.optJSONObject(i)
                val real = obj?.optString("real") ?: continue
                if (real.startsWith(NM_MOD_DIR)) {
                    val seg = real.split("/")
                    if (seg.size > 4 && seg[4] != "nomount") {
                        ruleCountByMod[seg[4]] = (ruleCountByMod[seg[4]] ?: 0) + 1
                    }
                }
            }
        }

        return modulesPart.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val cols = line.split("|")
                if (cols.size < 4) return@mapNotNull null
                NoMountModuleInfo(
                    id = cols[0],
                    name = cols[1].ifBlank { cols[0] },
                    disabled = cols[2] == "true",
                    skipMount = cols[3] == "true",
                    injectedFiles = ruleCountByMod[cols[0]] ?: 0,
                )
            }
    }

    /** Hot-load a module by injecting its partition files into the VFS rules. */
    fun loadModule(modId: String): Boolean {
        val modPath = "$NM_MOD_DIR/$modId"
        val script = """
            cd "$modPath" || exit 0
            valid_dirs=""
            for p in $NM_TARGET_PARTITIONS; do [ -d "${'$'}p" ] && { [ -d "/${'$'}p" ] || [ -d "/system/${'$'}p" ]; } && valid_dirs="${'$'}valid_dirs ${'$'}p"; done
            [ -z "${'$'}valid_dirs" ] && exit 0
            find -L ${'$'}valid_dirs \( -type d -o -type c -o -name ".replace" \) -exec sh -c '
                for f do
                    v="${'$'}f"; [ "${'$'}{v#system/odm/}" != "${'$'}v" ] && v="odm/${'$'}{v#system/odm/}"
                    if [ -d "${'$'}f" ]; then
                        getfattr -n trusted.overlay.opaque "${'$'}f" 2>/dev/null | grep -q "=\"y\"" && printf "/%s\0" "${'$'}v"
                    elif [ "${'$'}{f##*/}" = ".replace" ]; then
                        printf "/%s\0" "${'$'}{v%/.replace}"
                    else
                        printf "/%s\0" "${'$'}v"
                    fi
                done
            ' _ {} + 2>/dev/null | xargs -0 -r $NM_BIN rule add --whiteout

            find -L ${'$'}valid_dirs \( -type f -o -type l \) ! -name ".replace" -exec sh -c '
                mod="${'$'}1"; shift
                for f do
                    v="${'$'}f"; [ "${'$'}{v#system/odm/}" != "${'$'}v" ] && v="odm/${'$'}{v#system/odm/}"
                    printf "/%s\0%s/%s\0" "${'$'}v" "${'$'}mod" "${'$'}f"
                done
            ' _ "$modPath" {} + 2>/dev/null | xargs -0 -r $NM_BIN rule add
        """.trimIndent()
        return shellOut(script).isSuccess
    }

    /** Hot-unload a module by removing its rules. */
    fun unloadModule(modId: String): Boolean {
        val modPath = "$NM_MOD_DIR/$modId"
        val script = """
            cd "$modPath" || exit 0
            valid_dirs=""
            for p in $NM_TARGET_PARTITIONS; do [ -d "${'$'}p" ] && { [ -d "/${'$'}p" ] || [ -d "/system/${'$'}p" ]; } && valid_dirs="${'$'}valid_dirs ${'$'}p"; done
            [ -z "${'$'}valid_dirs" ] && exit 0
            find -L ${'$'}valid_dirs \( -type f -o -type l -o -type c -o -type d \) -exec sh -c '
                for f do
                    v="${'$'}f"; [ "${'$'}{v#system/odm/}" != "${'$'}v" ] && v="odm/${'$'}{v#system/odm/}"
                    if [ -d "${'$'}f" ]; then
                        getfattr -n trusted.overlay.opaque "${'$'}f" 2>/dev/null | grep -q "=\"y\"" && printf "/%s\0" "${'$'}v"
                    elif [ "${'$'}{f##*/}" = ".replace" ]; then
                        printf "/%s\0" "${'$'}{v%/.replace}"
                    else
                        printf "/%s\0" "${'$'}v"
                    fi
                done
            ' _ {} + 2>/dev/null | xargs -0 -r $NM_BIN rule del
        """.trimIndent()
        return shellOut(script).isSuccess
    }

    /** Enable (mount) a module: clear the disable marker and hot-load it. */
    fun enableModule(modId: String): Boolean {
        shellOut("rm -f $NM_MOD_DIR/$modId/disable")
        loadModule(modId)
        return true
    }

    /** Disable a module: hot-unload it and set the disable marker. */
    fun disableModule(modId: String): Boolean {
        unloadModule(modId)
        shellOut("touch $NM_MOD_DIR/$modId/disable")
        return true
    }

    // ------------------------------------------------------ exclusions ---

    private fun readExclusionsJson(): List<NoMountExclusion> {
        val stdout = fastCmd("cat $NM_EXCLUSIONS 2>/dev/null || echo \"[]\"")
        return runCatching {
            val arr = JSONArray(stdout.trim())
            List(arr.length()) { i ->
                val obj = arr.optJSONObject(i)
                NoMountExclusion(
                    uid = obj?.optString("uid") ?: "",
                    label = obj?.optString("label") ?: "",
                    pkg = obj?.optString("pkg") ?: "",
                )
            }.filter { it.uid.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun writeExclusionsJson(list: List<NoMountExclusion>): Boolean {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                org.json.JSONObject().put("uid", entry.uid).put("label", entry.label).put("pkg", entry.pkg)
            )
        }
        val jsonStr = arr.toString()
        val b64 = Base64.encodeToString(
            jsonStr.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        val cmd = "mkdir -p $NM_DATA && echo \"$b64\" | base64 -d > $NM_EXCLUSIONS.tmp && mv -f $NM_EXCLUSIONS.tmp $NM_EXCLUSIONS"
        return shellOut(cmd).isSuccess
    }

    fun listExclusions(): List<NoMountExclusion> {
        val stdout = fastCmd("$NM_BIN uid list 2>/dev/null")
        val blockedUids = runCatching {
            val arr = JSONArray(stdout.trim())
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())

        val saved = readExclusionsJson()
        val appsMap = saved.associateBy { it.uid }

        return blockedUids.map { uid ->
            appsMap[uid] ?: NoMountExclusion(uid, "UID: $uid", "System/Unknown")
        }
    }

    fun removeExclusion(entry: NoMountExclusion) {
        shellOut("$NM_BIN uid del ${entry.uid}")
        val current = readExclusionsJson()
        writeExclusionsJson(current.filter { it.uid != entry.uid })
    }

    fun addExclusions(entries: List<NoMountExclusion>) {
        val current = readExclusionsJson()
        val existing = current.map { it.uid }.toSet()
        val toAdd = entries.filter { it.uid !in existing }
        val updated = current + toAdd
        writeExclusionsJson(updated)
        if (toAdd.isNotEmpty()) {
            val uids = toAdd.joinToString(" ") { it.uid }
            shellOut("for u in $uids; do $NM_BIN uid add ${'$'}u; done")
        }
    }

    // -------------------------------------------------------- options ---

    fun isSafeMode(): Boolean =
        fastCmd("[ -f $NM_DISABLE ] && echo yes").contains("yes")

    fun setSafeMode(enabled: Boolean): Boolean =
        shellOut(if (enabled) "touch $NM_DISABLE" else "rm $NM_DISABLE").isSuccess

    fun clearRules(): Boolean {
        val okJson = writeExclusionsJson(emptyList())
        val okClear = shellOut("$NM_BIN clear all").isSuccess
        return okJson && okClear
    }
}
