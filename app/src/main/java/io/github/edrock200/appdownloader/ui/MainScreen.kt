package io.github.edrock200.appdownloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.work.Notifications
import kotlinx.coroutines.flow.collectLatest

/** Something to do once the storage permission (Android 6-9) has been granted. Saveable across rotation. */
private enum class StorageAction { NONE, SAVE_BACKUP, LIST_BACKUPS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val loadedItems by vm.items.collectAsStateWithLifecycle()
    val items = loadedItems.orEmpty()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sharedText by vm.sharedText.collectAsStateWithLifecycle()
    val selfUpdate by vm.selfUpdate.collectAsStateWithLifecycle()
    val selfCheckState by vm.selfCheckState.collectAsStateWithLifecycle()
    val ops by vm.ops.collectAsStateWithLifecycle()
    val canInstall by vm.canInstall.collectAsStateWithLifecycle()
    val confirmRequest by vm.confirmRequest.collectAsStateWithLifecycle()
    val repositoryProblem by vm.repositoryProblem.collectAsStateWithLifecycle()
    val tokenExpired by vm.tokenExpired.collectAsStateWithLifecycle()
    val isTv = rememberIsTv()
    val updatingAll by vm.updatingAll.collectAsStateWithLifecycle()
    var showWaitingPrompt by rememberSaveable { mutableStateOf(false) }
    val waitingItems = items.filter { it.waiting && it.op == null }
    val snackbar = remember { SnackbarHostState() }

    // ---- Backup & restore state (saveable, so rotation doesn't lose it) ----
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val downloadsBackups by vm.downloadsBackups.collectAsStateWithLifecycle()
    var qrText by rememberSaveable { mutableStateOf<String?>(null) }
    var showRestoreLink by rememberSaveable { mutableStateOf(false) }
    var backupIncludeToken by rememberSaveable { mutableStateOf(false) }
    var afterStoragePermission by rememberSaveable { mutableStateOf(StorageAction.NONE) }
    val createBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.saveBackupTo(it, backupIncludeToken) }
    }
    val openBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.loadBackup(it) }
    }
    val getBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.loadBackup(it) }
    }
    fun runStorageAction(action: StorageAction) {
        when (action) {
            StorageAction.SAVE_BACKUP -> vm.saveBackupToDownloads(backupIncludeToken)
            StorageAction.LIST_BACKUPS -> vm.findDownloadsBackups()
            StorageAction.NONE -> Unit
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runStorageAction(afterStoragePermission)
        else vm.message("App Downloader needs storage access to save or read backups on this device")
        afterStoragePermission = StorageAction.NONE
    }
    fun withStorage(action: StorageAction) {
        if (BackupStorage.needsStoragePermission(context)) {
            afterStoragePermission = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            runStorageAction(action)
        }
    }
    fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { vm.loadBackupText(it, BackupOrigin.QR) } }
            .addOnFailureListener { vm.message("Couldn't scan: ${it.message}") }
    }

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyFor by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingInstallId by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Newest message replaces any still showing, so a burst doesn't queue up for half a minute.
    LaunchedEffect(Unit) {
        vm.messages.collectLatest {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }
    LaunchedEffect(repositoryProblem) {
        repositoryProblem?.let {
            vm.message(it)
            vm.clearRepositoryProblem()
        }
    }
    // Install confirmation screens are started here: Android blocks starting them from the background.
    LaunchedEffect(confirmRequest) {
        confirmRequest?.let { intent ->
            vm.consumeConfirmRequest()
            runCatching { context.startActivity(intent) }.onFailure { vm.message("Couldn't show the install prompt") }
        }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) showAdd = true
    }
    // Ask for notifications whenever something would send them (background checks or a schedule).
    LaunchedEffect(loadedItems != null, settings.checkIntervalHours, settings.hasSchedule) {
        if (!askedNotifications && loadedItems?.isNotEmpty() == true && (settings.checkIntervalHours > 0 || settings.hasSchedule)) {
            askedNotifications = true
            askForNotifications()
        }
    }
    // Offer to install updates that a scheduled run downloaded, whenever something new is waiting.
    LaunchedEffect(waitingItems.map { it.entry.id to it.entry.readyVersionCode }, selfUpdate == null) {
        if (waitingItems.isNotEmpty() && selfUpdate == null && vm.shouldOfferWaiting(waitingItems)) showWaitingPrompt = true
    }
    // Back from system settings with the permission granted: carry on with the install.
    LaunchedEffect(canInstall) {
        val id = pendingInstallId
        if (canInstall && id != null) {
            pendingInstallId = null
            startInstallFor(vm, id)
        }
    }
    // Dialogs for entries that no longer exist close, but only once the list has loaded.
    LaunchedEffect(loadedItems) {
        val ids = loadedItems?.map { it.entry.id }?.toSet() ?: return@LaunchedEffect
        if (historyFor != null && historyFor !in ids) historyFor = null
        if (editingId != null && editingId !in ids) editingId = null
        if (deletingId != null && deletingId !in ids) deletingId = null
    }

    fun install(id: String) {
        if (canInstall) startInstallFor(vm, id) else pendingInstallId = id
    }

    val sidePadding = if (isTv) 32.dp else 0.dp

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(horizontal = sidePadding),
                title = { Text("App Downloader") },
                actions = {
                    if (items.any { it.entry.type == SourceType.GITHUB || it.installed != null }) {
                        // One stable button (showing a spinner while checking) so remote focus isn't lost.
                        IconButton(
                            onClick = { if (!checking) vm.checkAll() },
                            modifier = Modifier.focusHighlight(FocusShapes.Icon),
                        ) {
                            if (checking) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
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
                    modifier = Modifier.padding(end = sidePadding, bottom = if (isTv) 16.dp else 0.dp).focusHighlight(FocusShapes.Fab),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            loadedItems == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> EmptyState(Modifier.padding(padding), onAdd = { showAdd = true })
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
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
                    if (tokenExpired) {
                        item(key = "token-expired") {
                            NoticeBar(
                                text = "Your GitHub token has expired. Checks continue without it (with a lower limit).",
                                action = "Settings",
                                onAction = { showSettings = true },
                            )
                        }
                    }
                    val withUpdates = vm.appsWithUpdates(items)
                    if (withUpdates.isNotEmpty() || updatingAll) {
                        item(key = "update-all") {
                            UpdateAllBar(
                                count = withUpdates.size,
                                updating = updatingAll,
                                onUpdateAll = { install(UPDATE_AVAILABLE_ID) },
                                onStop = vm::stopUpdateAll,
                            )
                        }
                    }
                    val restored = vm.restoredNotInstalled(items)
                    if (restored.isNotEmpty() && !updatingAll) {
                        item(key = "install-restored") {
                            NoticeBar(
                                text = "${restored.size} app${if (restored.size == 1) "" else "s"} from your backup " +
                                    "${if (restored.size == 1) "isn't" else "aren't"} installed yet",
                                action = "Install all",
                                onAction = { install(INSTALL_RESTORED_ID) },
                                secondary = "Dismiss",
                                onSecondary = { vm.dismissRestored() },
                            )
                        }
                    }
                    items(items, key = { it.entry.id }) { item ->
                        EntryCard(
                            item = item,
                            onInstall = { install(item.entry.id) },
                            onInstallAnyway = { vm.installAnyway(item.entry.id) },
                            onCancel = { vm.clearOp(item.entry.id) },
                            canShowPrompt = vm.canShowPromptAgain(item.entry.id),
                            onShowPrompt = { vm.showPromptAgain(item.entry.id) },
                            onCheck = { vm.checkOne(item.entry.id) },
                            onEdit = { editingId = item.entry.id },
                            onDelete = { deletingId = item.entry.id },
                            onAutoUpdateChange = { vm.setAutoUpdate(item.entry.id, it) },
                            onHistory = { historyFor = item.entry.id },
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
                when (val result = vm.add(form, installNow && canInstall)) {
                    is MainViewModel.AddResult.Invalid -> result.message
                    is MainViewModel.AddResult.Added -> {
                        showAdd = false
                        vm.sharedText.value = null
                        if (installNow && !canInstall) pendingInstallId = result.id
                        null
                    }
                }
            },
        )
    }

    editingId?.let { id ->
        items.firstOrNull { it.entry.id == id }?.entry?.let { entry ->
            EntryDialog(
                original = entry,
                prefill = null,
                onDismiss = { editingId = null },
                onSubmit = { form, _ -> vm.edit(entry, form).also { if (it == null) editingId = null } },
            )
        }
    }

    historyFor?.let { id ->
        items.firstOrNull { it.entry.id == id }?.entry?.let { HistoryDialog(it, onDismiss = { historyFor = null }) }
    }

    deletingId?.let { id ->
        items.firstOrNull { it.entry.id == id }?.entry?.let { entry ->
            AlertDialog(
                onDismissRequest = { deletingId = null },
                title = { Text("Remove ${entry.name}?") },
                text = { Text("This removes it from your list. The installed app (if any) is not uninstalled.") },
                confirmButton = {
                    TextButton(modifier = Modifier.focusHighlight(), onClick = {
                        vm.delete(entry.id)
                        deletingId = null
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(modifier = Modifier.focusHighlight(), onClick = { deletingId = null }) { Text("Cancel") } },
            )
        }
    }

    pendingInstallId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingInstallId = null },
            title = { Text("Allow app installs") },
            text = {
                Text("To install APKs, allow App Downloader to install unknown apps.\n\n" + Device.installPermissionHelp(context))
            },
            confirmButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    if (canInstall) {
                        pendingInstallId = null
                        startInstallFor(vm, id)
                    } else if (!SystemActions.openInstallPermissionSettings(context)) {
                        vm.message("Could not open settings")
                    }
                }) { Text(if (canInstall) "Continue" else "Open settings") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    pendingInstallId = null
                    startInstallFor(vm, id) // The system may still prompt for permission itself.
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
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("These were downloaded earlier and need your OK to install:")
                    Spacer(Modifier.height(8.dp))
                    waitingItems.forEach { item ->
                        // Focusable rows let a TV remote scroll a long list.
                        Text(
                            "• " + item.entry.name + (item.entry.readyVersionName?.let { " $it" } ?: ""),
                            modifier = Modifier.fillMaxWidth().focusHighlight(FocusShapes.Row, scaleUp = false).focusable().padding(4.dp),
                        )
                    }
                }
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

    qrText?.let { ShowQrDialog(it, vm.appCount(), onDismiss = { qrText = null }) }
    if (showRestoreLink) {
        RestoreLinkDialog(onDismiss = { showRestoreLink = false }) { input ->
            showRestoreLink = false
            vm.loadBackupFromLink(input)
        }
    }
    downloadsBackups?.let { list ->
        DownloadsBackupDialog(
            backups = list,
            onPick = { vm.downloadsBackups.value = null; vm.loadBackup(it.uri) },
            onDelete = vm::deleteDownloadsBackup,
            onDismiss = { vm.downloadsBackups.value = null },
        )
    }
    pendingRestore?.let { (backup, origin) ->
        RestoreDialog(
            backup = backup,
            origin = origin,
            currentCount = vm.appCount(),
            onDismiss = { vm.pendingRestore.value = null },
            onRestore = { replace, withSettings, withToken -> vm.restore(backup, origin, replace, withSettings, withToken) },
        )
    }

    if (showSettings) {
        SettingsDialog(
            current = settings,
            updatingAll = updatingAll,
            selfCheckState = selfCheckState,
            tokenExpired = tokenExpired,
            canPostNotifications = Notifications.canPost(context),
            onChange = vm::saveSettings,
            onAskNotifications = ::askForNotifications,
            onCheckSelfUpdate = vm::checkSelfUpdateNow,
            onScheduleChange = { at, repeat -> vm.setSchedule(at, repeat) },
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
                    withStorage(StorageAction.SAVE_BACKUP)
                }
            },
            onRestoreBackup = {
                showSettings = false
                when {
                    BackupStorage.hasFilePicker(context) ->
                        openBackupFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                    BackupStorage.hasGetContent(context) -> getBackupFile.launch("*/*")
                    else -> withStorage(StorageAction.LIST_BACKUPS)
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
        )
    }
}

/** Starts the action behind an install button (real entry ids and the pseudo ids below). */
private fun startInstallFor(vm: MainViewModel, id: String) {
    when (id) {
        AppController.SELF_UPDATE_ID -> vm.installSelfUpdate()
        UPDATE_ALL_ID -> vm.updateAllNow()
        INSTALL_WAITING_ID -> vm.installWaitingUpdates()
        UPDATE_AVAILABLE_ID -> vm.updateAllAvailable()
        INSTALL_RESTORED_ID -> vm.installRestored()
        else -> vm.install(id)
    }
}

/** Pseudo ids for the install-permission dialog, alongside real entry ids. */
private const val UPDATE_ALL_ID = "update-all"
private const val INSTALL_WAITING_ID = "install-waiting"
private const val UPDATE_AVAILABLE_ID = "update-available"
private const val INSTALL_RESTORED_ID = "install-restored"

/** Shown at the top of the list when any app has an update; turns into "Stop" while updating. */
@Composable
private fun UpdateAllBar(count: Int, updating: Boolean, onUpdateAll: () -> Unit, onStop: () -> Unit) {
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
                when {
                    updating -> "Updating…"
                    count == 1 -> "1 update available"
                    else -> "$count updates available"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            // The same button stays focused and switches between "Update all" and "Stop".
            Button(onClick = { if (updating) onStop() else onUpdateAll() }, modifier = Modifier.focusHighlight()) {
                Text(if (updating) "Stop" else "Update all")
            }
        }
    }
}

@Composable
private fun NoticeBar(text: String, action: String, onAction: () -> Unit, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            if (secondary != null && onSecondary != null) {
                TextButton(onClick = onSecondary, modifier = Modifier.focusHighlight()) { Text(secondary) }
            }
            Button(onClick = onAction, modifier = Modifier.focusHighlight()) { Text(action) }
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
