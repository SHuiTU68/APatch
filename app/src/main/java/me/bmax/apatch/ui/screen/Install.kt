package me.bmax.apatch.ui.screen

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.KeyEventBlocker
import me.bmax.apatch.ui.component.rememberCustomDialog
import me.bmax.apatch.util.hasMetaModule
import me.bmax.apatch.util.installModule
import me.bmax.apatch.util.reboot
import me.bmax.apatch.util.ui.LocalSnackbarHost
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

enum class MODULE_TYPE {
    KPM, APM
}

@Composable
@Destination<RootGraph>
fun InstallScreen(navigator: DestinationsNavigator, uri: Uri, type: MODULE_TYPE) {
    var text by rememberSaveable { mutableStateOf("") }
    val logContent = remember { StringBuilder() }
    var showFloatAction by rememberSaveable { mutableStateOf(false) }

    fun appendLog(line: String) {
        logContent.append(line).append("\n")
        val newText = text + line + "\n"
        text = if (newText.length > 100_000) newText.takeLast(100_000) else newText
    }
    val metaModuleAlertDialog = rememberCustomDialog { dismiss: () -> Unit ->
        val uriHandler = LocalUriHandler.current
        WindowDialog(
            show = true,
            title = stringResource(R.string.warning_of_meta_module_title),
            onDismissRequest = { dismiss() },
            content = {
                Text(
                    text = stringResource(R.string.warning_of_meta_module_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            uriHandler.openUri("https://apatch.dev/meta-module.html")
                        }
                    ) {
                        Text(text = stringResource(id = R.string.learn_more))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { dismiss() },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(text = stringResource(id = android.R.string.ok))
                    }
                }
            }
        )
    }

    val context = LocalContext.current
    val snackBarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        if (text.isNotEmpty()) {
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            installModule(uri, type, onFinish = { success ->
                if (!success) return@installModule

                scope.launch {
                    showFloatAction = true

                    // check metamodule
                    if (hasMetaModule()) return@launch
                    val mountOldDirectory =
                        SuFile.open("/data/adb/modules/${getModuleIdFromUri(context, uri)}/system")
                    val mountNewDirectory =
                        SuFile.open("/data/adb/modules_update/${getModuleIdFromUri(context, uri)}/system")
                    if (!mountNewDirectory.isDirectory && !mountOldDirectory.isDirectory) return@launch

                    metaModuleAlertDialog.show()
                }

            }, onStdout = {
                if (it.startsWith("[H[J")) { // clear command
                    text = it.substring(5)
                } else {
                    appendLog(it)
                }
            }, onStderr = {
                if (it.startsWith("[H[J")) { // clear command
                    text = it.substring(5)
                } else {
                    appendLog(it)
                }
            })
        }
    }

    Scaffold(topBar = {
        TopBar(onBack = dropUnlessResumed {
            navigator.popBackStack()
        }, onSave = {
            scope.launch {
                val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                val date = format.format(Date())
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "APatch_install_${type}_log_${date}.log"
                )
                file.writeText(logContent.toString())
                snackBarHost.showSnackbar("Log saved to ${file.absolutePath}")
            }
        })
    }, floatingActionButton = {
        if (showFloatAction) {
            val reboot = stringResource(id = R.string.reboot)
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            reboot()
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MiuixTheme.colorScheme.primary
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        reboot,
                        tint = MiuixTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = reboot,
                        color = MiuixTheme.colorScheme.onPrimary
                    )
                }
            }
        }

    }, snackbarHost = { SnackbarHost(snackBarHost) }) { innerPadding ->
        KeyEventBlocker {
            it.key == Key.VolumeDown || it.key == Key.VolumeUp
        }
        Column(
            modifier = Modifier
                .fillMaxSize(1f)
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            LaunchedEffect(text) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            Text(
                modifier = Modifier.padding(8.dp),
                text = text,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontFamily = FontFamily.Monospace,
                lineHeight = MiuixTheme.textStyles.footnote1.lineHeight,
            )
        }
    }
}

fun isUriAccessible(context: Context, uri: Uri): Boolean {
    if (uri == Uri.EMPTY) return false

    return try {
        context.contentResolver.openInputStream(uri)?.use {} != null
    } catch (e: Exception) {
        Log.e("ModuleInstall", "URI is inaccessible: $uri", e)
        false
    }
}


fun extractModuleId(context: Context, uri: Uri): String? {
    if (uri == Uri.EMPTY) return null

    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry?

            while (zip.nextEntry.also { entry = it } != null) {
                if (entry?.name == "module.prop") {
                    val prop = Properties()
                    prop.load(zip)
                    return prop.getProperty("id")
                }
            }
        }
    }

    return null
}

suspend fun getModuleIdFromUri(context: Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            if (uri == Uri.EMPTY) {
                return@withContext null
            }
            if (!isUriAccessible(context, uri)) {
                return@withContext null
            }
            extractModuleId(context, uri)
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit = {}, onSave: () -> Unit = {}) {
    TopAppBar(title = stringResource(R.string.apm_install), navigationIcon = {
        IconButton(
            onClick = onBack
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
    }, actions = {
        IconButton(onClick = onSave) {
            Icon(
                imageVector = Icons.Filled.Save, contentDescription = "Localized description"
            )
        }
    })
}

@Preview
@Composable
fun InstallPreview() {
//    InstallScreen(DestinationsNavigator(), uri = Uri.EMPTY)
}
