package io.github.edrock200.appdownloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.edrock200.appdownloader.AppController
import kotlinx.coroutines.launch
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sharedText by vm.sharedText.collectAsStateWithLifecycle()
    val selfUpdate by vm.selfUpdate.collectAsStateWithLifecycle()
    val ops by vm.ops.collectAsStateWithLifecycle()
    val isTv = rememberIsTv()
    val updatingAll by vm.updatingAll.collectAsStateWithLifecycle()
    var showWaitingPrompt by rememberSaveable { mutableStateOf(false) }
    val waitingItems = items.filter { it.waiting && it.op == null }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ---- Backup & restore state ----
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    var qrText by remember { mutableStateOf<String?>(null) }
    var showRestoreLink by rememberSaveable { mutableStateOf(false) }
    var downloadsBackups by remember { mutableStateOf<List<StoredBackup>?>(null) }
    var backupIncludeToken by remember { mutableStateOf(false) }
    var afterStoragePermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val createBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.saveBackupTo(it, backupIncludeToken) }
    }
    val openBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.loadBackup(it) }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterStoragePermission?.invoke()
        else vm.message("App Downloader needs storage access to save or read backups on this device")
        afterStoragePermission = null
    }
    fun withStorage(action: () -> Unit) {
        if (BackupStorage.needsStoragePermission(context)) {
            afterStoragePermission = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }
    fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let(vm::loadBackupText) }
            .addOnFailureListener { vm.message("Couldn't scan: ${it.message}") }
    }

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var deleting by remember { mutableStateOf<AppEntry?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingInstallId by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) showAdd = true
    }
    // Once per app start, offer to install updates that a scheduled run downloaded.
    LaunchedEffect(waitingItems.isNotEmpty(), selfUpdate == null) {
        if (waitingItems.isNotEmpty() && selfUpdate == null && !vm.waitingPromptShown) {
            vm.waitingPromptShown = true
            showWaitingPrompt = true
        }
    }

    fun startInstall(id: String) {
        when (id) {
            AppController.SELF_UPDATE_ID -> vm.installSelfUpdate()
            UPDATE_ALL_ID -> vm.updateAllNow()
            INSTALL_WAITING_ID -> vm.installWaitingUpdates()
            UPDATE_AVAILABLE_ID -> vm.updateAllAvailable()
            else -> vm.install(id)
        }
    }

    fun install(id: String) {
        if (SystemActions.canInstallPackages(context)) startInstall(id) else pendingInstallId = id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Downloader") },
                actions = {
                    if (items.any { it.entry.type == SourceType.GITHUB || it.installed != null }) {
                        if (checking) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = vm::checkAll, modifier = Modifier.focusHighlight(FocusShapes.Icon)) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Check for updates")
                            }
                        }
                    }
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.focusHighlight(FocusShapes.Icon),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (items.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.focusHighlight(FocusShapes.Fab),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(Modifier.padding(padding), onAdd = { showAdd = true })
        } else {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    // TVs need extra margin: edges of the picture can be cut off (overscan).
                    contentPadding = if (isTv) {
                        PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 120.dp)
                    } else {
                        PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val withUpdates = vm.appsWithUpdates(items)
                    if (withUpdates.isNotEmpty()) {
                        item(key = "update-all") {
                            UpdateAllBar(
                                count = withUpdates.size,
                                updating = updatingAll,
                                onUpdateAll = { install(UPDATE_AVAILABLE_ID) },
                            )
                        }
                    }
                    items(items, key = { it.entry.id }) { item ->
                        EntryCard(
                            item = item,
                            onInstall = { install(item.entry.id) },
                            onCancel = { vm.clearOp(item.entry.id) },
                            onCheck = { vm.checkOne(item.entry.id) },
                            onEdit = { editing = item.entry },
                            onDelete = { deleting = item.entry },
                            onAutoUpdateChange = { vm.setAutoUpdate(item.entry.id, it) },
                            onMessage = vm::message,
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        EntryDialog(
            original = null,
            prefill = sharedText,
            onDismiss = {
                showAdd = false
                vm.sharedText.value = null
            },
            onSubmit = { form, installNow ->
                val canInstall = SystemActions.canInstallPackages(context)
                when (val result = vm.add(form, installNow && canInstall)) {
                    is MainViewModel.AddResult.Invalid -> result.message
                    is MainViewModel.AddResult.Added -> {
                        showAdd = false
                        vm.sharedText.value = null
                        if (installNow && !canInstall) pendingInstallId = result.id
                        if (result.type == SourceType.GITHUB && settings.checkIntervalHours > 0) askForNotifications()
                        null
                    }
                }
            },
        )
    }

    editing?.let { entry ->
        EntryDialog(
            original = entry,
            prefill = null,
            onDismiss = { editing = null },
            onSubmit = { form, _ ->
                vm.edit(entry, form).also { if (it == null) editing = null }
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${entry.name}?") },
            text = { Text("This removes it from your list. The installed app (if any) is not uninstalled.") },
            confirmButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    vm.delete(entry.id)
                    deleting = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(modifier = Modifier.focusHighlight(), onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    pendingInstallId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingInstallId = null },
            title = { Text("Allow app installs") },
            text = {
                Text(
                    "To install APKs, allow App Downloader to install unknown apps.\n\n" +
                        Device.installPermissionHelp(context)
                )
            },
            confirmButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    if (SystemActions.canInstallPackages(context)) {
                        pendingInstallId = null
                        startInstall(id)
                    } else if (!SystemActions.openInstallPermissionSettings(context)) {
                        vm.message("Could not open settings")
                    }
                }) { Text(if (SystemActions.canInstallPackages(context)) "Continue" else "Open settings") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    pendingInstallId = null
                    startInstall(id) // The system may still prompt for permission itself.
                }) { Text("Try anyway") }
            },
        )
    }

    selfUpdate?.let { update ->
        SelfUpdateDialog(
            update = update,
            op = ops[AppController.SELF_UPDATE_ID],
            onUpdate = { install(AppController.SELF_UPDATE_ID) },
            onLater = vm::dismissSelfUpdate,
        )
    }

    if (showWaitingPrompt && waitingItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showWaitingPrompt = false },
            title = { Text(if (waitingItems.size == 1) "Update ready to install" else "${waitingItems.size} updates ready to install") },
            text = {
                Text(
                    "These were downloaded by your scheduled update and need your OK to install:\n\n" +
                        waitingItems.joinToString("\n") { item ->
                            "• " + item.entry.name + (item.entry.readyVersionName?.let { " $it" } ?: "")
                        }
                )
            },
            confirmButton = {
                Button(modifier = Modifier.focusHighlight(), onClick = {
                    showWaitingPrompt = false
                    install(INSTALL_WAITING_ID)
                }) { Text("Install now") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = { showWaitingPrompt = false }) { Text("Later") }
            },
        )
    }

    BackupDialogs(
        vm = vm,
        qrText = qrText,
        onQrDismiss = { qrText = null },
        pendingRestore = pendingRestore,
        showRestoreLink = showRestoreLink,
        onRestoreLinkDismiss = { showRestoreLink = false },
        downloadsBackups = downloadsBackups,
        onDownloadsDismiss = { downloadsBackups = null },
    )

    if (showSettings) {
        SettingsDialog(
            current = settings,
            updatingAll = updatingAll,
            onCheckSelfUpdate = vm::checkSelfUpdateNow,
            onScheduleChange = { at, repeat ->
                vm.setSchedule(at, repeat)
                if (at > 0) askForNotifications()
            },
            onOnlyIfChangedChange = vm::setUpdateOnlyIfChanged,
            onThemeModeChange = { vm.saveSettings(settings.copy(themeMode = it)) },
            onUpdateAllNow = {
                showSettings = false
                install(UPDATE_ALL_ID)
            },
            onSaveBackup = { includeToken ->
                showSettings = false
                backupIncludeToken = includeToken
                if (BackupStorage.hasFilePicker(context)) {
                    createBackupFile.launch(BackupStorage.fileName())
                } else {
                    withStorage { vm.saveBackupToDownloads(includeToken) }
                }
            },
            onRestoreBackup = {
                showSettings = false
                if (BackupStorage.hasFilePicker(context)) {
                    openBackupFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                } else {
                    withStorage {
                        scope.launch {
                            val found = vm.downloadsBackups()
                            if (found.isEmpty()) {
                                vm.message("No backups found in Downloads. Save one first, or restore from a link.")
                            } else {
                                downloadsBackups = found
                            }
                        }
                    }
                }
            },
            onShowQr = {
                val text = vm.qrText()
                if (text == null) {
                    vm.message("Too many apps to fit in one QR code. Use a backup file instead.")
                } else {
                    showSettings = false
                    qrText = text
                }
            },
            onScanQr = if (remember { BackupStorage.canScanQr(context) }) {
                { showSettings = false; scanQr() }
            } else {
                null
            },
            onRestoreFromLink = {
                showSettings = false
                showRestoreLink = true
            },
            onDismiss = { showSettings = false },
            onSave = {
                vm.saveSettings(it)
                showSettings = false
                if (it.checkIntervalHours > 0 && items.any { i -> i.entry.type == SourceType.GITHUB }) {
                    askForNotifications()
                }
            },
        )
    }
}

@Composable
private fun BackupDialogs(
    vm: MainViewModel,
    qrText: String?,
    onQrDismiss: () -> Unit,
    pendingRestore: io.github.edrock200.appdownloader.data.BackupFile?,
    showRestoreLink: Boolean,
    onRestoreLinkDismiss: () -> Unit,
    downloadsBackups: List<StoredBackup>?,
    onDownloadsDismiss: () -> Unit,
) {
    qrText?.let { ShowQrDialog(it, vm.appCount(), onQrDismiss) }
    if (showRestoreLink) {
        RestoreLinkDialog(onDismiss = onRestoreLinkDismiss) { input ->
            onRestoreLinkDismiss()
            vm.loadBackupFromLink(input)
        }
    }
    downloadsBackups?.let { list ->
        DownloadsBackupDialog(list, onPick = { onDownloadsDismiss(); vm.loadBackup(it.uri) }, onDismiss = onDownloadsDismiss)
    }
    pendingRestore?.let { backup ->
        RestoreDialog(
            backup = backup,
            currentCount = vm.appCount(),
            onDismiss = { vm.pendingRestore.value = null },
            onRestore = { replace, withSettings -> vm.restore(backup, replace, withSettings) },
        )
    }
}

/** Pseudo ids for the install-permission dialog, alongside real entry ids. */
private const val UPDATE_ALL_ID = "update-all"
private const val INSTALL_WAITING_ID = "install-waiting"
private const val UPDATE_AVAILABLE_ID = "update-available"

/** Shown at the top of the list when any app has an update. */
@Composable
private fun UpdateAllBar(count: Int, updating: Boolean, onUpdateAll: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UpdateStar()
            Spacer(Modifier.size(8.dp))
            Text(
                if (count == 1) "1 update available" else "$count updates available",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onUpdateAll, enabled = !updating, modifier = Modifier.focusHighlight()) {
                Text(if (updating) "Updating…" else "Update all")
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No apps yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Add a direct APK link, an aftv.news code (like 12345), or a GitHub repository " +
                "(like github.com/owner/repo) to download, install and keep apps up to date.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.focusHighlight()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Add an app")
        }
    }
}
