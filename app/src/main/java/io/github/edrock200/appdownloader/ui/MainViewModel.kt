package io.github.edrock200.appdownloader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.edrock200.appdownloader.App
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.OpState
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.Backup
import io.github.edrock200.appdownloader.data.BackupFile
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.Schedule
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.SourceParser
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.InstalledInfo
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.Errors
import io.github.edrock200.appdownloader.net.Http
import io.github.edrock200.appdownloader.work.AutoUpdateScheduler
import io.github.edrock200.appdownloader.work.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

data class EntryUi(
    val entry: AppEntry,
    val installed: InstalledInfo?,
    val op: OpState?,
    val hasUpdate: Boolean,
    /** The installed app has a launcher screen ("Open" button). */
    val canLaunch: Boolean = false,
) {
    /** A scheduled run downloaded an update that still needs to be installed. */
    val waiting get() = entry.readyFile != null
    /** The link is unencrypted (http). */
    val insecure get() = entry.type == SourceType.URL && entry.source.startsWith("http://", ignoreCase = true)
}

/** Values collected from the add/edit dialog. */
data class EntryForm(
    val input: String,
    val name: String,
    val includePrereleases: Boolean,
    val assetFilter: String,
    val packageName: String,
)

/** Where a backup being restored came from (others' backups are trusted less). */
enum class BackupOrigin { OWN_FILE, LINK, QR }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val installedTick = MutableStateFlow(0)

    private data class Resolved(val entry: AppEntry, val installed: InstalledInfo?, val canLaunch: Boolean)

    /** Installed versions are looked up only when the list or the device changes, not on every progress tick. */
    private val resolved = combine(app.repository.entries, installedTick) { entries, _ ->
        entries.map { e ->
            val installed = InstalledApps.get(app, e.packageName)
            Resolved(e, installed, installed != null && e.packageName != null && SystemActions.canLaunch(app, e.packageName))
        }
    }.flowOn(Dispatchers.Default)

    /** Null while loading (so "No apps yet" doesn't flash before the saved list appears). */
    val items: StateFlow<List<EntryUi>?> = combine(resolved, app.controller.ops) { list, ops ->
        list.map { r ->
            EntryUi(
                r.entry, r.installed, ops[r.entry.id],
                VersionUtils.updateAvailable(r.entry, r.installed?.versionName, r.installed?.versionCode),
                r.canLaunch,
            )
        }.sortedWith(compareByDescending<EntryUi> { it.hasUpdate || it.waiting }.thenBy { it.entry.name.lowercase() })
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val checking = app.controller.checking
    val ops = app.controller.ops
    val selfUpdate = app.controller.selfUpdate
    val selfCheckState = app.controller.selfCheckState
    val updatingAll = app.controller.updatingAll
    val tokenExpired = app.controller.tokenExpired
    val confirmRequest = app.controller.confirmRequest
    val repositoryProblem = app.repository.problem
    val settings = app.settings.state
    val messages = app.controller.messages

    /** Waiting updates already offered in the "ready to install" prompt (id to version). */
    private var offeredWaiting = emptySet<String>()

    /** Returns true when the waiting set contains something not offered yet (and remembers it). */
    fun shouldOfferWaiting(waiting: List<EntryUi>): Boolean {
        val keys = waiting.map { "${it.entry.id}:${it.entry.readyVersionCode ?: it.entry.readyFile}" }.toSet()
        val fresh = (keys - offeredWaiting).isNotEmpty()
        offeredWaiting = offeredWaiting + keys
        return fresh
    }

    /** Whether Android currently lets App Downloader install apps; refreshed when the app returns to the screen. */
    private val _canInstall = MutableStateFlow(SystemActions.canInstallPackages(app))
    val canInstall: StateFlow<Boolean> = _canInstall.asStateFlow()

    /** A backup that was read and is waiting for the user to confirm the restore. */
    val pendingRestore = MutableStateFlow<Pair<BackupFile, BackupOrigin>?>(null)

    /** Backups found in Downloads, for the picker (kept here so rotation doesn't lose it). */
    val downloadsBackups = MutableStateFlow<List<StoredBackup>?>(null)

    /** Text shared into the app from another app, waiting to be added. */
    val sharedText = MutableStateFlow<String?>(null)

    fun refreshInstalled() {
        installedTick.value++
        _canInstall.value = SystemActions.canInstallPackages(app)
    }

    sealed interface AddResult {
        data class Added(val id: String, val type: SourceType) : AddResult
        data class Invalid(val message: String) : AddResult
    }

    private fun isDuplicate(type: SourceType, source: String, exceptId: String? = null) =
        app.repository.entries.value.any { it.id != exceptId && it.type == type && SourceParser.sameSource(type, it.source, source) }

    /** Adds a new entry and optionally starts installing it right away. */
    fun add(form: EntryForm, installNow: Boolean): AddResult {
        val parsed = SourceParser.parse(form.input)
            ?: return AddResult.Invalid("Enter a URL, an aftv.news code or a GitHub repository")
        if (isDuplicate(parsed.type, parsed.source)) return AddResult.Invalid("That source is already in your list")
        val entry = AppEntry(
            name = form.name.trim().ifBlank { parsed.suggestedName },
            autoName = form.name.isBlank(),
            type = parsed.type,
            source = parsed.source,
            packageName = form.packageName.trim().ifBlank { null },
            includePrereleases = form.includePrereleases,
            assetFilter = form.assetFilter.trim().ifBlank { null },
        )
        viewModelScope.launch {
            app.repository.add(entry)
            when {
                installNow -> app.controller.downloadAndInstall(entry.id)
                entry.type == SourceType.GITHUB -> app.controller.checkOne(entry.id)
            }
        }
        return AddResult.Added(entry.id, entry.type)
    }

    fun edit(original: AppEntry, form: EntryForm): String? {
        val parsed = SourceParser.parse(form.input) ?: return "Enter a URL, an aftv.news code or a GitHub repository"
        val sourceChanged = parsed.type != original.type || parsed.source != original.source
        if (sourceChanged && isDuplicate(parsed.type, parsed.source, exceptId = original.id)) {
            return "That source is already in your list"
        }
        val filterChanged = form.assetFilter.trim().ifBlank { null } != original.assetFilter ||
            form.includePrereleases != original.includePrereleases
        viewModelScope.launch {
            // A download waiting from the old source (or filter) no longer applies.
            if (sourceChanged || (filterChanged && original.type == SourceType.GITHUB)) {
                original.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
            }
            app.repository.update(original.id) { e ->
                var updated = e.copy(
                    name = form.name.trim().ifBlank { parsed.suggestedName },
                    autoName = form.name.isBlank(),
                    type = parsed.type,
                    source = parsed.source,
                    packageName = form.packageName.trim().ifBlank { null },
                    includePrereleases = form.includePrereleases,
                    assetFilter = form.assetFilter.trim().ifBlank { null },
                )
                if (sourceChanged || filterChanged) {
                    updated = updated.copy(
                        latestTag = null, latestReleaseName = null, latestReleaseUrl = null,
                        latestAssetName = null, latestAssetUrl = null, latestAssetApiUrl = null,
                        latestAssetSize = null, latestAssetDigest = null, latestPublishedAt = null,
                        lastCheckError = null, lastCheckedAt = null, skippedTag = null, failedTag = null,
                        readyFile = null, readyVersionName = null, readyVersionCode = null, readyTag = null, readyNeedsUser = false,
                    )
                }
                if (sourceChanged) {
                    updated = updated.copy(
                        installedTag = null, installedVersionCode = null, notifiedTag = null,
                        fingerprint = null, pendingFingerprint = null, remoteFingerprint = null,
                        remoteChange = null, remoteReason = null, remoteCheckedAt = null, notifiedChangeKey = null,
                        failedVersionCode = null, lastUpdateError = null, lastUpdateErrorAt = null,
                    )
                }
                updated
            }
            val installed = InstalledApps.get(app, form.packageName.trim().ifBlank { original.packageName }) != null
            if (sourceChanged || filterChanged) {
                if (parsed.type == SourceType.GITHUB || installed) app.controller.checkOne(original.id)
            }
        }
        return null
    }

    fun delete(id: String) {
        app.controller.clearOp(id)
        viewModelScope.launch {
            app.repository.get(id)?.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
            app.repository.delete(id)
            app.controller.cleanCache()
        }
    }

    fun install(id: String) = app.controller.downloadAndInstall(id)
    fun installAnyway(id: String) = app.controller.downloadAndInstall(id, allowPackageChange = true)
    fun clearOp(id: String) = app.controller.clearOp(id)
    fun checkOne(id: String) = app.controller.checkOne(id)
    fun checkAll() = app.controller.checkAllNow()
    fun checkAllOnLaunch() = app.controller.checkAllOnLaunch()
    fun reconcileSessions() = app.controller.reconcileSessions()
    fun showPromptAgain(id: String) = app.controller.showInstallPromptAgain(id)
    fun canShowPromptAgain(id: String) = app.controller.canShowPromptAgain(id)
    fun consumeConfirmRequest() = app.controller.consumeConfirmRequest()
    fun clearRepositoryProblem() = app.repository.clearProblem()
    fun stopUpdateAll() = app.controller.stopUpdateAll()

    /** Apps marked with a star: a newer version was found, or one is downloaded and waiting. */
    fun appsWithUpdates(list: List<EntryUi> = items.value.orEmpty()) =
        list.filter { (it.hasUpdate || it.waiting) && (it.op == null || it.op is OpState.Failed) }

    /** Apps restored from a backup that aren't installed on this device yet. */
    fun restoredNotInstalled(list: List<EntryUi> = items.value.orEmpty()) =
        list.filter { it.entry.pendingInstall && it.installed == null && (it.op == null || it.op is OpState.Failed) }

    /** "Update all" at the top of the list. */
    fun updateAllAvailable() = app.controller.updateEntries(appsWithUpdates().map { it.entry.id })

    /** "Install all" for apps restored from a backup. */
    fun installRestored() = app.controller.updateEntries(restoredNotInstalled().map { it.entry.id })

    fun dismissRestored() = viewModelScope.launch {
        restoredNotInstalled().forEach { item -> app.repository.update(item.entry.id) { it.copy(pendingInstall = false) } }
    }

    fun message(text: String) = app.controller.message(text)
    fun checkSelfUpdateOnLaunch() = app.controller.checkSelfUpdateOnLaunch()
    fun checkSelfUpdateNow() = app.controller.checkSelfUpdateNow()
    fun installSelfUpdate() = app.controller.installSelfUpdate()
    fun updateAllNow() = app.controller.updateAllNow()
    fun installWaitingUpdates() = app.controller.installWaitingUpdates()

    fun setSchedule(at: Long, repeat: Repeat) {
        val (minute, weekday) = if (at > 0) Schedule.localTimeOf(at) else (-1 to 0)
        saveSettings(app.settings.current.copy(scheduleAt = at, scheduleRepeat = repeat, scheduleMinuteOfDay = minute, scheduleWeekday = weekday))
        if (at > 0) message("Updates scheduled for ${formatDateTime(app, at)}")
    }

    // ------------------------------------------------------------ backup & restore

    private fun makeBackup(includeToken: Boolean) = Backup.create(
        entries = app.repository.entries.value,
        settings = app.settings.current,
        includeToken = includeToken,
        appVersion = BuildConfig.VERSION_NAME,
        now = System.currentTimeMillis(),
    )

    /** Text for the settings QR code (never contains the GitHub token), or null if the list is too long. */
    fun qrText(): String? = Backup.toQrText(makeBackup(includeToken = false))

    fun appCount() = app.repository.entries.value.size

    fun saveBackupTo(uri: Uri, includeToken: Boolean) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { BackupStorage.write(app, uri, Backup.toJson(makeBackup(includeToken))) } }
            .onSuccess { message("Backup saved (${appCount()} apps${if (includeToken) ", including your GitHub token" else ""})") }
            .onFailure { message("Couldn't save the backup: ${Errors.friendly(it)}") }
    }

    /** Saves to the shared Downloads folder, which other apps can read, so the token is never included there. */
    fun saveBackupToDownloads(includeToken: Boolean) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                BackupStorage.saveToDownloads(app, BackupStorage.fileName(), Backup.toJson(makeBackup(includeToken = false)))
            }
        }
            .onSuccess {
                message("Backup saved to $it" + if (includeToken) " (without your GitHub token: the Downloads folder is shared with other apps)" else "")
            }
            .onFailure { message("Couldn't save the backup: ${Errors.friendly(it)}") }
    }

    fun findDownloadsBackups() = viewModelScope.launch {
        val found = withContext(Dispatchers.IO) { runCatching { BackupStorage.listDownloads(app) }.getOrDefault(emptyList()) }
        if (found.isEmpty()) {
            message(BackupStorage.noBackupsMessage())
        } else {
            downloadsBackups.value = found
        }
    }

    fun deleteDownloadsBackup(backup: StoredBackup) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { BackupStorage.delete(app, backup) }
        downloadsBackups.value = downloadsBackups.value?.filterNot { it.uri == backup.uri }?.ifEmpty { null }
        message(if (ok) "Deleted ${backup.name}" else "Couldn't delete ${backup.name}")
    }

    fun loadBackup(uri: Uri, origin: BackupOrigin = BackupOrigin.OWN_FILE) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { BackupStorage.read(app, uri) } }
            .onSuccess { loadBackupText(it, origin) }
            .onFailure { message("Couldn't read the file: ${Errors.friendly(it)}") }
    }

    /** From a file's contents, a downloaded link or a scanned QR code (parsed off the main thread). */
    fun loadBackupText(text: String, origin: BackupOrigin) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.Default) { Backup.parse(text) } }
            .onSuccess { pendingRestore.value = it to origin }
            .onFailure { message(it.message ?: "This isn't an App Downloader backup") }
    }

    /** Downloads a backup file from a secure link or an aftv.news code (handy on TVs). */
    fun loadBackupFromLink(input: String) = viewModelScope.launch {
        runCatching {
            val parsed = SourceParser.parse(input) ?: throw IllegalArgumentException("Enter a link or an aftv.news code")
            val url = when (parsed.type) {
                SourceType.AFTV_CODE -> AftvResolver.resolve(parsed.source)
                SourceType.URL -> parsed.source
                SourceType.GITHUB -> throw IllegalArgumentException("Use the direct (\"raw\") link to the backup file")
            }
            if (!url.startsWith("https://", ignoreCase = true)) {
                throw IllegalArgumentException("For safety, backups can only be loaded from secure (https) links")
            }
            withContext(Dispatchers.IO) {
                Http.execute(Request.Builder().url(url).build()).use { response ->
                    if (!response.isSuccessful) throw IOException("The link returned HTTP ${response.code}")
                    val body = response.body ?: throw IOException("The link returned nothing")
                    Backup.readBounded(body.byteStream()).decodeToString()
                }
            }
        }
            .onSuccess { loadBackupText(it, BackupOrigin.LINK) }
            .onFailure { message(if (it is IllegalArgumentException) it.message!! else "Couldn't download the backup: ${Errors.friendly(it)}") }
    }

    fun restore(backup: BackupFile, origin: BackupOrigin, replace: Boolean, withSettings: Boolean, withToken: Boolean) = viewModelScope.launch {
        val existing = app.repository.entries.value
        val result = Backup.restoreApps(existing, backup, replace, trusted = origin == BackupOrigin.OWN_FILE)
        if (replace) {
            // Clean up downloads waiting for apps that are no longer in the list.
            val keptIds = result.entries.map { it.id }.toSet()
            existing.filter { it.id !in keptIds }.forEach { e ->
                app.controller.clearOp(e.id)
                e.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
            }
        }
        // Restored apps already installed here don't need "Install all".
        val restored = result.entries.map { e ->
            if (e.pendingInstall && InstalledApps.get(app, e.packageName) != null) e.copy(pendingInstall = false) else e
        }
        app.repository.replaceAll(restored)
        if (withSettings) {
            saveSettings(Backup.restoreSettings(app.settings.current, backup.settings, System.currentTimeMillis(), includeToken = withToken))
        }
        pendingRestore.value = null
        message(
            "Restored ${result.added} app${if (result.added == 1) "" else "s"}" +
                (if (result.skipped > 0) " (${result.skipped} already in your list)" else "") +
                (if (result.invalid > 0) ", ${result.invalid} with an invalid link left out" else "") +
                (if (withSettings) " and your settings" else "")
        )
        app.controller.cleanCache()
        app.controller.checkAllNow(quiet = true) // Fill in release and change information for the restored apps.
    }

    fun setUpdateOnlyIfChanged(enabled: Boolean) {
        saveSettings(app.settings.current.copy(updateOnlyIfChanged = enabled))
    }

    fun setAutoUpdate(id: String, enabled: Boolean) {
        viewModelScope.launch { app.repository.update(id) { it.copy(autoUpdate = enabled) } }
    }

    fun dismissSelfUpdate() = app.controller.dismissSelfUpdate()

    fun saveSettings(state: SettingsState) {
        val old = app.settings.current
        app.settings.update(state)
        if (old.checkIntervalHours != state.checkIntervalHours || old.wifiOnly != state.wifiOnly) {
            UpdateScheduler.schedule(app, state.checkIntervalHours, state.wifiOnly)
        }
        if (old.scheduleAt != state.scheduleAt || old.scheduleRepeat != state.scheduleRepeat || old.wifiOnly != state.wifiOnly) {
            AutoUpdateScheduler.apply(app, state)
        }
        if (old.githubToken != state.githubToken.trim()) app.controller.clearTokenExpired()
    }
}
