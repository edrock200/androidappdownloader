package io.github.edrock200.appdownloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.PowerManager
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.History
import io.github.edrock200.appdownloader.data.InstallRecord
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ApkInfo
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.ExpectedFile
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.NotAnApkException
import io.github.edrock200.appdownloader.net.AftvBlockedException
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.ChangeReason
import io.github.edrock200.appdownloader.net.Errors
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.net.RateLimitException
import io.github.edrock200.appdownloader.net.RemoteChange
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import io.github.edrock200.appdownloader.work.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

/** Progress of a download/install for one entry. */
sealed interface OpState {
    data object Resolving : OpState
    data class Downloading(val progress: Float?) : OpState
    data object Installing : OpState
    data class Failed(
        val message: String,
        val openUrl: String? = null,
        /** The link now provides a different app; the user may choose to install it anyway. */
        val differentApp: String? = null,
    ) : OpState
}

/** Outcome of an "update all" run. */
data class UpdateRunSummary(
    val updated: List<String> = emptyList(),
    val waiting: List<String> = emptyList(),
    /** Silent installs handed to Android; their results arrive later. */
    val installing: List<String> = emptyList(),
    val upToDate: Int = 0,
    val failed: List<String> = emptyList(),
    /** Unencrypted (http) links skipped because automatic updates from them aren't allowed. */
    val insecureSkipped: List<String> = emptyList(),
    /** Every failure was a connection problem (the run can be retried later). */
    val onlyConnectivityFailures: Boolean = false,
) {
    fun describe(): String = buildList {
        if (updated.isNotEmpty()) add("Updated ${updated.size}")
        if (installing.isNotEmpty()) add("installing ${installing.size}")
        if (waiting.isNotEmpty()) add("${waiting.size} waiting to install")
        if (upToDate > 0) add("$upToDate up to date")
        if (failed.isNotEmpty()) add("${failed.size} failed (${failed.joinToString(", ")})")
        if (insecureSkipped.isNotEmpty()) add("${insecureSkipped.size} skipped (unencrypted link)")
    }.joinToString(", ").ifEmpty { "No apps are selected for auto-update" }
}

/** When opening the app should re-check every app for updates (pure, for tests). */
object UpdateCheckPolicy {
    const val MIN_INTERVAL_MS = 5 * 60_000L
    const val ATTEMPTS = 3
    const val RETRY_DELAY_MS = 10_000L

    fun shouldCheckOnLaunch(checkRunning: Boolean, lastCheckAt: Long, now: Long): Boolean =
        !checkRunning && now - lastCheckAt >= MIN_INTERVAL_MS
}

/** When the automatic "is there a newer App Downloader?" check should run (pure, for tests). */
object SelfUpdatePolicy {
    const val ATTEMPTS = 3
    const val RETRY_DELAY_MS = 10_000L
    /** Returning from a file picker or the QR scanner shouldn't trigger another check straight away. */
    const val MIN_GAP_MS = 2 * 60_000L
    /** A version the user chose "Later" for isn't offered again for this long. */
    const val SNOOZE_MS = 6 * 60 * 60_000L

    /** Every launch, unless turned off, busy, or just checked. */
    fun shouldAutoCheck(
        enabled: Boolean,
        updateOffered: Boolean,
        checkRunning: Boolean,
        busy: Boolean = false,
        lastCheckAt: Long = 0,
        now: Long = Long.MAX_VALUE,
    ): Boolean = enabled && !updateOffered && !checkRunning && !busy && now - lastCheckAt >= MIN_GAP_MS

    /** Whether to offer [version] after the user chose "Later" for [snoozedVersion] at [snoozedAt]. */
    fun shouldOffer(version: String, snoozedVersion: String?, snoozedAt: Long, now: Long): Boolean =
        version != snoozedVersion || now - snoozedAt >= SNOOZE_MS
}

/** A newer release of App Downloader itself. */
data class SelfUpdate(
    val version: String,
    val notes: String?,
    val assetUrl: String,
    val releaseUrl: String?,
    val size: Long? = null,
    val digest: String? = null,
)

/** State of the "Check for app update now" button in Settings. */
enum class SelfCheckState { IDLE, CHECKING, UP_TO_DATE, FAILED }

/** The asset filter for App Downloader's own release file. */
const val SELF_ASSET_FILTER = "^AppDownloader\\.apk$"

/**
 * Application-wide coordinator for downloads, installs and update checks. Lives as long as the
 * process so work continues across configuration changes.
 *
 * All state here is confined to the main thread: public entry points are called from the UI, and
 * workers switch to Dispatchers.Main before calling in. Long work suspends onto IO internally.
 */
class AppController(
    private val context: Context,
    private val repository: EntryRepository,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val isForeground: () -> Boolean = { true },
    private val isTv: Boolean = false,
) {
    private val _ops = MutableStateFlow<Map<String, OpState>>(emptyMap())
    val ops: StateFlow<Map<String, OpState>> = _ops.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    /** Set when GitHub rejected the saved token (expired); cleared when a new token is saved. */
    private val _tokenExpired = MutableStateFlow(false)
    val tokenExpired: StateFlow<Boolean> = _tokenExpired.asStateFlow()

    /** An install confirmation screen for the activity to show (it can't be started from the background). */
    private val _confirmRequest = MutableStateFlow<Intent?>(null)
    val confirmRequest: StateFlow<Intent?> = _confirmRequest.asStateFlow()
    fun consumeConfirmRequest() {
        _confirmRequest.value = null
    }

    /** Running jobs by op id (entry id or [SELF_UPDATE_ID]). */
    private val jobs = mutableMapOf<String, Job>()
    /** The current install session of each op, so results of older attempts are ignored. */
    private val sessions = mutableMapOf<String, Int>()
    /** Sessions App Downloader abandoned itself; their "aborted" results are ignored. */
    private val abandonedSessions = mutableSetOf<Int>()
    /** Last confirmation screen per op, to show it again if it was dismissed without an answer. */
    private val confirmIntents = mutableMapOf<String, Intent>()

    companion object {
        /** Operation id used for updating this app itself. */
        const val SELF_UPDATE_ID = "self-update"
    }

    private val apkDir get() = File(context.cacheDir, "apks")

    private fun setOp(id: String, state: OpState?) = _ops.update { if (state == null) it - id else it + (id to state) }

    fun clearOp(id: String) {
        jobs.remove(id)?.cancel()
        sessions.remove(id)
        confirmIntents.remove(id)
        setOp(id, null)
    }

    fun isBusy(id: String) = _ops.value[id].let { it != null && it !is OpState.Failed }

    private fun anyBusy() = _ops.value.values.any { it !is OpState.Failed }

    /** True when [job] is still the registered job of [id] (a cancelled job must not clean up after its replacement). */
    private fun owns(id: String, job: Job?) = job != null && jobs[id] === job

    // ---------------------------------------------------------------- install

    fun downloadAndInstall(entryId: String, allowPackageChange: Boolean = false) {
        if (jobs[entryId]?.isActive == true || isBusy(entryId)) return
        setOp(entryId, OpState.Resolving) // Claim the entry before anything suspends.
        val job = scope.launch { runInstall(entryId, allowPackageChange) }
        if (job.isActive) jobs[entryId] = job
    }

    /** Shows the install confirmation again after it was dismissed without an answer. */
    fun showInstallPromptAgain(id: String) {
        confirmIntents[id]?.let { _confirmRequest.value = it }
    }
    fun canShowPromptAgain(id: String) = confirmIntents.containsKey(id)

    private class PackageChangedException(val newPackage: String, message: String) : IOException(message)

    private suspend fun runInstall(entryId: String, allowPackageChange: Boolean = false) {
        val myJob = currentCoroutineContext()[Job]
        var entry = repository.get(entryId) ?: run { setOp(entryId, null); return }
        try {
            setOp(entryId, OpState.Resolving)
            // An update downloaded earlier is waiting: install that file (if it's still the right one).
            usableReady(entry)?.let { apk ->
                install(entryId, apk, entry.readyTag)
                return
            }
            entry = repository.get(entryId) ?: return
            val (url, headers, expected) = resolveDownload(entry).also { entry = it.entry }.let { Triple(it.url, it.headers, it.expected) }

            val apk = downloadAndInspect(entryId, url, headers, expected)
            val previousPackage = entry.packageName
            if (previousPackage != null && previousPackage != apk.packageName && !allowPackageChange) {
                withContext(Dispatchers.IO) { apk.file.delete() }
                throw PackageChangedException(
                    apk.packageName,
                    "This link now provides a different app (${apk.label ?: apk.packageName}) instead of $previousPackage",
                )
            }
            // Never "update" to an older version: Android refuses it with a confusing error.
            val installed = InstalledApps.get(context, apk.packageName)
            if (installed != null && apk.info.versionCode < installed.versionCode) {
                withContext(Dispatchers.IO) { apk.file.delete() }
                markNotNewer(entry, apk)
                throw IOException(
                    "The file offered (version ${apk.info.versionName ?: apk.info.versionCode}) is older than the installed " +
                        "version ${installed.versionName ?: installed.versionCode}, so it wasn't installed"
                )
            }
            repository.update(entryId) { it.copy(pendingFingerprint = apk.fingerprint) }
            val tag = if (entry.type == SourceType.GITHUB) entry.latestTag else null
            install(entryId, apk, tag)
        } catch (e: CancellationException) {
            if (owns(entryId, myJob) || jobs[entryId] == null) setOp(entryId, null)
            throw e
        } catch (e: PackageChangedException) {
            setOp(entryId, OpState.Failed(e.message!!, differentApp = e.newPackage))
        } catch (e: NotAnApkException) {
            setOp(entryId, OpState.Failed(e.message ?: "Not an APK", e.pageUrl))
        } catch (e: Exception) {
            setOp(entryId, failed(e))
        } finally {
            if (owns(entryId, myJob)) jobs.remove(entryId)
        }
    }

    private data class Download(
        val entry: AppEntry,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val expected: ExpectedFile? = null,
    )

    /** Works out what to download for [entry] (checking GitHub first for repositories). */
    private suspend fun resolveDownload(entry: AppEntry): Download = when (entry.type) {
        SourceType.URL -> Download(entry, entry.source)
        SourceType.AFTV_CODE -> Download(entry, AftvResolver.resolve(entry.source))
        SourceType.GITHUB -> {
            val checked = checkGitHub(entry, quiet = false)
            checked.lastCheckError?.let { throw IOException(it) }
            val browserUrl = checked.latestAssetUrl ?: throw IOException("No APK found in the latest release")
            val token = settings.current.githubToken.takeIf { it.isNotBlank() && !_tokenExpired.value }
            val expected = ExpectedFile(checked.latestAssetSize, checked.latestAssetDigest)
            if (token != null && checked.latestAssetApiUrl != null) {
                // Private repositories: download through the API with the token. GitHub redirects to its
                // file storage, and OkHttp drops the Authorization header on that cross-host redirect.
                Download(checked, checked.latestAssetApiUrl, mapOf("Accept" to "application/octet-stream", "Authorization" to "Bearer $token"), expected)
            } else {
                Download(checked, browserUrl, expected = expected)
            }
        }
    }

    /** Downloads [url] for the operation [opId] and reads the package details. */
    private suspend fun downloadAndInspect(
        opId: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        expected: ExpectedFile? = null,
    ): DownloadedApk {
        setOp(opId, OpState.Downloading(null))
        val result = ApkDownloader.download(
            url, apkDir, opId, headers, expected, Build.SUPPORTED_ABIS.toList(),
        ) { p -> setOp(opId, OpState.Downloading(p)) }
        val info = try {
            withContext(Dispatchers.IO) { ApkInspector.inspect(context, result.file) }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { result.file.delete() }
            throw e
        }
        return DownloadedApk(result.file, info, result.fingerprint)
    }

    private suspend fun install(opId: String, apk: DownloadedApk, releaseTag: String?, background: Boolean = false) {
        setOp(opId, OpState.Installing)
        val previous = InstalledApps.get(context, apk.packageName)
        // A new attempt replaces any earlier one; until it is registered, results without a known
        // current session are accepted (a result can arrive before install() returns).
        sessions.remove(opId)?.let { abandonedSessions += it }
        confirmIntents.remove(opId)
        abandonedSessions += withContext(Dispatchers.IO) { ApkInstaller.abandonOldSessions(context, apk.packageName) }
        val sessionId = withContext(Dispatchers.IO) {
            ApkInstaller.install(
                context, apk.file, apk.info, opId, releaseTag, background,
                previousVersionName = previous?.versionName,
                previousVersionCode = previous?.versionCode,
                label = apk.label,
            )
        }
        sessions[opId] = sessionId
    }

    /** Records that the website's current file was examined and isn't newer than what is installed. */
    private suspend fun markNotNewer(entry: AppEntry, apk: DownloadedApk) {
        repository.update(entry.id) {
            when {
                it.type == SourceType.GITHUB -> it.copy(skippedTag = entry.latestTag)
                apk.fingerprint != null -> it.copy(
                    fingerprint = apk.fingerprint.copy(notNewer = true),
                    remoteFingerprint = apk.fingerprint,
                    remoteChange = RemoteChange.UNCHANGED,
                    remoteReason = ChangeReason.NOT_NEWER,
                )
                else -> it
            }
        }
    }

    /**
     * The waiting downloaded update for [entry], if its file is still there, newer than what is
     * installed now and (for GitHub) still the latest known release. A stale one is deleted.
     */
    private suspend fun usableReady(entry: AppEntry): DownloadedApk? {
        if (entry.readyFile == null) return null
        if (entry.type == SourceType.GITHUB && entry.latestTag != null && entry.readyTag != null && entry.readyTag != entry.latestTag) {
            discardReady(entry)
            return null
        }
        return readyApk(entry)
    }

    private suspend fun readyApk(entry: AppEntry): DownloadedApk? {
        val path = entry.readyFile ?: return null
        val installedCode = InstalledApps.get(context, entry.packageName)?.versionCode ?: -1
        val apk = withContext(Dispatchers.IO) {
            val file = File(path)
            val apk = if (file.exists()) {
                runCatching { DownloadedApk(file, ApkInspector.inspect(context, file)) }.getOrNull()
            } else {
                null
            }
            if (apk != null && apk.info.versionCode > installedCode) apk else null.also { file.delete() }
        }
        if (apk == null) repository.update(entry.id) { it.clearReady() }
        return apk
    }

    private suspend fun discardReady(entry: AppEntry) {
        entry.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
        repository.update(entry.id) { it.clearReady() }
    }

    private fun AppEntry.clearReady() =
        copy(readyFile = null, readyVersionName = null, readyVersionCode = null, readyTag = null, readyNeedsUser = false)

    private class DownloadedApk(val file: File, val info: ApkInfo, val fingerprint: RemoteFingerprint? = null) {
        val packageName get() = info.packageName
        val label get() = info.label
    }

    /** Whether a result belongs to the op's current install attempt. */
    private fun isCurrentSession(opId: String, sessionId: Int): Boolean {
        if (sessionId < 0) return true
        if (sessionId in abandonedSessions) return false
        val current = sessions[opId]
        return current == null || current == sessionId
    }

    fun onInstallSucceeded(
        entryId: String,
        sessionId: Int,
        releaseTag: String?,
        versionCode: Long,
        filePath: String?,
        background: Boolean = false,
        previousVersionName: String? = null,
        previousVersionCode: Long? = null,
        packageName: String? = null,
        label: String? = null,
    ): Job? {
        // A success is always recorded (the app really was installed), but only the current attempt
        // frees the card: an older attempt finishing late mustn't hide a newer one's progress.
        val current = isCurrentSession(entryId, sessionId)
        if (sessions[entryId] == sessionId) sessions.remove(entryId)
        if (current) {
            confirmIntents.remove(entryId)
            if (_ops.value[entryId] == OpState.Installing) setOp(entryId, null)
        }
        return scope.launch {
            val pkg = packageName ?: repository.get(entryId)?.packageName
            val nowInstalled = InstalledApps.get(context, pkg)
            val code = versionCode.takeIf { it >= 0 } ?: nowInstalled?.versionCode ?: 0
            val record = InstallRecord(
                at = System.currentTimeMillis(),
                versionName = nowInstalled?.versionName,
                versionCode = code,
                previousVersionName = previousVersionName,
                previousVersionCode = previousVersionCode,
                tag = releaseTag,
                kind = History.kindOf(previousVersionCode, code),
                automatic = background,
            )
            repository.update(entryId) { e ->
                val installedFp = e.pendingFingerprint
                val packageChanged = pkg != null && e.packageName != null && e.packageName != pkg
                val useLabel = e.autoName && !label.isNullOrBlank()
                e.clearReady().copy(
                    packageName = pkg ?: e.packageName,
                    name = if (useLabel) label!! else e.name,
                    autoName = e.autoName && !useLabel,
                    history = History.recordInstall(if (packageChanged) emptyList() else e.history, record),
                    fingerprint = installedFp ?: if (packageChanged) null else e.fingerprint,
                    pendingFingerprint = null,
                    remoteChange = if (installedFp != null) RemoteChange.UNCHANGED else e.remoteChange,
                    remoteReason = if (installedFp != null) ChangeReason.SAVED_DETAILS else e.remoteReason,
                    remoteFingerprint = installedFp ?: e.remoteFingerprint,
                    installedTag = releaseTag ?: if (packageChanged) null else e.installedTag,
                    installedVersionCode = code,
                    notifiedTag = releaseTag ?: e.notifiedTag,
                    failedVersionCode = null,
                    failedTag = null,
                    lastUpdateError = null,
                    lastUpdateErrorAt = null,
                    pendingInstall = false,
                    skippedTag = if (releaseTag != null && releaseTag == e.skippedTag) null else e.skippedTag,
                )
            }
            if (settings.current.deleteApkAfterInstall && filePath != null) {
                withContext(Dispatchers.IO) { File(filePath).delete() }
            }
            val entry = repository.get(entryId)
            if (!background) _messages.tryEmit("Installed ${entry?.name ?: "app"}")
            if (background && entry != null) {
                Notifications.showAutoUpdated(context, entry, InstalledApps.get(context, entry.packageName)?.versionName)
            }
            Notifications.showWaitingUpdates(context, waitingUpdates())
        }
    }

    /**
     * Android needs the user to confirm an install. In the foreground the activity shows the
     * screen; otherwise (Android blocks starting screens from the background) the session is
     * abandoned and the download is kept as "ready to install" for the next time the app is opened.
     */
    fun onNeedsConfirmation(entryId: String, sessionId: Int, confirm: Intent, filePath: String?, versionCode: Long, releaseTag: String?) {
        if (!isCurrentSession(entryId, sessionId)) return
        if (isForeground()) {
            confirmIntents[entryId] = confirm
            _confirmRequest.value = confirm
            return
        }
        abandonedSessions += sessionId
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        sessions.remove(entryId)
        keepAsWaiting(entryId, filePath, versionCode, releaseTag)
    }

    /** A scheduled install needs the user to confirm it; keep the download for later. */
    fun onBackgroundInstallNeedsUser(entryId: String, sessionId: Int, filePath: String?, versionCode: Long, releaseTag: String?): Job {
        abandonedSessions += sessionId
        if (sessions[entryId] == sessionId) sessions.remove(entryId)
        return keepAsWaiting(entryId, filePath, versionCode, releaseTag)
    }

    private fun keepAsWaiting(entryId: String, filePath: String?, versionCode: Long, releaseTag: String?, needsUser: Boolean = true): Job {
        setOp(entryId, null)
        return scope.launch {
            repository.update(entryId) {
                if (filePath == null || entryId == SELF_UPDATE_ID) {
                    it
                } else {
                    it.copy(
                        readyFile = filePath,
                        readyVersionCode = versionCode.takeIf { c -> c >= 0 } ?: it.readyVersionCode,
                        readyTag = releaseTag ?: it.readyTag,
                        readyNeedsUser = needsUser,
                    )
                }
            }
            Notifications.showWaitingUpdates(context, waitingUpdates())
        }
    }

    /** Entries with a downloaded update waiting to be installed. */
    fun waitingUpdates(): List<AppEntry> = repository.entries.value.filter { it.readyFile != null && !isBusy(it.id) }

    fun onInstallFailed(
        entryId: String,
        sessionId: Int,
        status: Int,
        message: String?,
        background: Boolean = false,
        versionCode: Long = -1,
        releaseTag: String? = null,
        filePath: String? = null,
    ): Job? {
        if (!isCurrentSession(entryId, sessionId)) return null
        if (sessions[entryId] == sessionId) sessions.remove(entryId)
        confirmIntents.remove(entryId)
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            setOp(entryId, null)
            if (!background) _messages.tryEmit("Installation cancelled")
            return null
        }
        if (background && status == ApkInstaller.STATUS_FAILURE_TIMEOUT) {
            // Android 14+: the app stayed in use, so the gentle update didn't happen. Try again later.
            return keepAsWaiting(entryId, filePath, versionCode, releaseTag, needsUser = false)
        }
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Conflicts with the installed app (it may be signed with a different key; uninstall it first)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This app is not compatible with your device"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked"
            PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid"
            else -> "Installation failed"
        }
        val text = if (message.isNullOrBlank()) reason else "$reason: $message"
        val permanent = status == PackageInstaller.STATUS_FAILURE_INVALID ||
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE || status == PackageInstaller.STATUS_FAILURE_CONFLICT
        val job = scope.launch {
            // Don't keep offering a download that Android refused to install.
            repository.update(entryId) { e ->
                val cleared = e.clearReady()
                if (background) {
                    cleared.copy(
                        failedVersionCode = versionCode.takeIf { it >= 0 } ?: e.readyVersionCode,
                        failedTag = releaseTag ?: e.readyTag,
                        lastUpdateError = text,
                        lastUpdateErrorAt = System.currentTimeMillis(),
                    )
                } else {
                    cleared
                }
            }
            if (filePath != null && (permanent || settings.current.deleteApkAfterInstall)) {
                withContext(Dispatchers.IO) { File(filePath).delete() }
            }
            if (background) repository.get(entryId)?.let { Notifications.showUpdateFailures(context, listOf(it.name)) }
        }
        setOp(entryId, OpState.Failed(text))
        return job
    }

    /**
     * Called when the app returns to the screen: an op still "waiting for installer" whose session
     * no longer exists was cancelled without Android telling us; free it so the card and any batch
     * can move on.
     */
    fun reconcileSessions() {
        scope.launch {
            delay(1500) // Let results that are already on their way arrive first.
            val installer = context.packageManager.packageInstaller
            for ((opId, state) in _ops.value) {
                if (state != OpState.Installing) continue
                val sid = sessions[opId] ?: continue
                if (installer.getSessionInfo(sid) == null) {
                    sessions.remove(opId)
                    confirmIntents.remove(opId)
                    setOp(opId, null)
                }
            }
        }
    }

    /** Deletes downloads that nothing refers to any more (cancelled, failed or removed apps). */
    fun cleanCache() {
        scope.launch {
            val keep = repository.entries.value.mapNotNull { it.readyFile }.toSet()
            val busy = _ops.value.filterValues { it !is OpState.Failed }.keys
            withContext(Dispatchers.IO) {
                apkDir.listFiles()?.forEach { f ->
                    if (f.absolutePath !in keep && busy.none { f.name.startsWith(it) }) f.delete()
                }
                context.cacheDir.listFiles { f -> f.name.startsWith("inspect-") }?.forEach { it.delete() }
            }
        }
    }

    // ---------------------------------------------------------------- checks

    private fun sameSource(a: AppEntry, b: AppEntry) =
        a.type == b.type && a.source == b.source && a.assetFilter == b.assetFilter && a.includePrereleases == b.includePrereleases

    private class CheckOutcome(val entry: AppEntry, val succeeded: Boolean, val connectivity: Boolean)

    /** Checks one GitHub entry for a new release and stores the result (errors are stored on the entry). */
    suspend fun checkGitHub(entry: AppEntry, quiet: Boolean = false): AppEntry = try {
        checkGitHubOutcome(entry, quiet).entry
    } catch (e: RateLimitException) {
        repository.get(entry.id) ?: entry
    }

    private suspend fun checkGitHubOutcome(entry: AppEntry, quiet: Boolean): CheckOutcome {
        if (entry.type != SourceType.GITHUB) return CheckOutcome(entry, true, false)
        val now = System.currentTimeMillis()
        try {
            val match = GitHubClient.findLatest(entry, settings.current.githubToken, Build.SUPPORTED_ABIS.toList(), isTv)
            if (match.tokenRejected && !_tokenExpired.value) {
                _tokenExpired.value = true
                _messages.tryEmit("Your GitHub token has expired or was revoked. Checks continue without it; update it in Settings.")
            }
            repository.update(entry.id) {
                if (!sameSource(it, entry)) return@update it // Edited meanwhile: this result is for the old source.
                it.copy(
                    latestTag = match.release.tagName,
                    latestReleaseName = match.release.name,
                    latestReleaseUrl = match.release.htmlUrl,
                    latestAssetName = match.asset.name,
                    latestAssetUrl = match.asset.downloadUrl,
                    latestAssetApiUrl = match.asset.apiUrl,
                    latestAssetSize = match.asset.size.takeIf { s -> s > 0 },
                    latestAssetDigest = match.asset.digest,
                    latestPublishedAt = match.release.publishedAt,
                    lastCheckedAt = now,
                    lastCheckError = null,
                )
            }
            return CheckOutcome(repository.get(entry.id) ?: entry, true, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val connectivity = Errors.isConnectivity(e)
            // A quiet check while offline shouldn't fill every card with an error.
            if (!(quiet && connectivity)) {
                repository.update(entry.id) { if (sameSource(it, entry)) it.copy(lastCheckedAt = now, lastCheckError = Errors.friendly(e)) else it }
            }
            if (e is RateLimitException) throw e
            return CheckOutcome(repository.get(entry.id) ?: entry, false, connectivity)
        }
    }

    /**
     * Asks the server whether a link / aftv entry's file differs from the installed copy, without
     * downloading it, and stores the result. A failed check keeps the previous result.
     */
    /** A card error; a blocked aftv.news lookup offers to open the code in a browser. */
    private fun failed(e: Exception): OpState.Failed =
        if (e is AftvBlockedException) OpState.Failed(Errors.friendly(e), AftvResolver.shortUrl(e.code))
        else OpState.Failed(Errors.friendly(e))

    suspend fun checkLink(entry: AppEntry, resolvedUrl: String? = null, quiet: Boolean = false): AppEntry =
        checkLinkOutcome(entry, resolvedUrl, quiet).entry

    private suspend fun checkLinkOutcome(entry: AppEntry, resolvedUrl: String?, quiet: Boolean): CheckOutcome {
        if (entry.type == SourceType.GITHUB) return CheckOutcome(entry, true, false)
        val now = System.currentTimeMillis()
        try {
            val url = resolvedUrl ?: when (entry.type) {
                SourceType.AFTV_CODE -> AftvResolver.resolve(entry.source)
                else -> entry.source
            }
            val remote = RemoteProbe.fingerprint(url)
            val (installedApk, installedAt) = withContext(Dispatchers.IO) {
                InstalledApps.localApk(context, entry.packageName) to
                    (InstalledApps.get(context, entry.packageName)?.installedAt ?: 0L)
            }
            val byThisEntry = entry.fingerprint != null || entry.history.any { it.byApp }
            val decision = RemoteProbe.decide(entry.fingerprint, remote, installedApk, installedAt, byThisEntry)
            repository.update(entry.id) {
                if (!sameSource(it, entry)) return@update it
                it.copy(
                    remoteFingerprint = remote,
                    remoteChange = decision.change,
                    remoteReason = decision.reason,
                    remoteCheckedAt = now,
                    lastCheckError = null,
                    fingerprint = decision.newBaseline ?: it.fingerprint,
                )
            }
            return CheckOutcome(repository.get(entry.id) ?: entry, true, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val connectivity = Errors.isConnectivity(e)
            if (!(quiet && connectivity)) {
                repository.update(entry.id) { if (sameSource(it, entry)) it.copy(remoteCheckedAt = now, lastCheckError = Errors.friendly(e)) else it }
            }
            return CheckOutcome(repository.get(entry.id) ?: entry, false, connectivity)
        }
    }

    /** Result of checking every app. */
    class CheckAllResult(val updates: List<AppEntry>, val anySucceeded: Boolean, val allConnectivity: Boolean)

    /** Checks GitHub releases and link changes for every entry, re-reading each one just before checking it. */
    suspend fun checkAll(quiet: Boolean = false): CheckAllResult {
        var succeeded = false
        var failures = 0
        var connectivity = 0
        for (id in repository.entries.value.map { it.id }) {
            val entry = repository.get(id) ?: continue
            if (isBusy(id)) continue
            val outcome = try {
                when {
                    entry.type == SourceType.GITHUB -> checkGitHubOutcome(entry, quiet)
                    // Only installed links are worth checking: there is nothing to compare otherwise.
                    InstalledApps.get(context, entry.packageName) != null -> checkLinkOutcome(entry, null, quiet)
                    else -> null
                }
            } catch (e: RateLimitException) {
                failures++
                break // Every further GitHub request would fail too.
            } ?: continue
            if (outcome.succeeded) succeeded = true else {
                failures++
                if (outcome.connectivity) connectivity++
            }
        }
        return CheckAllResult(entriesWithUpdates(), succeeded || failures == 0, failures > 0 && failures == connectivity)
    }

    /** Background check (worker): skipped (null) when another check is already running. */
    suspend fun checkAllGuarded(): CheckAllResult? {
        if (_checking.value) return null
        _checking.value = true
        return try {
            checkAll(quiet = true).also { if (it.anySucceeded) lastFullCheckAt = System.currentTimeMillis() }
        } finally {
            _checking.value = false
        }
    }

    fun entriesWithUpdates(): List<AppEntry> = repository.entries.value.filter { e ->
        InstalledApps.get(context, e.packageName).let { installed ->
            VersionUtils.updateAvailable(e, installed?.versionName, installed?.versionCode)
        }
    }

    /**
     * Adds history records for version changes made outside App Downloader (or, for apps that have
     * no history yet, the version currently installed). Skips apps that are being installed now.
     */
    suspend fun syncHistoryWithDevice() {
        for (id in repository.entries.value.map { it.id }) {
            if (isBusy(id)) continue
            val pkg = repository.get(id)?.packageName ?: continue
            val installed = InstalledApps.get(context, pkg) ?: continue
            // Computed inside the update from the latest history, so a concurrent install record isn't lost.
            repository.update(id) {
                if (it.packageName != pkg) it
                else it.copy(history = History.syncWithDevice(it.history, installed.versionName, installed.versionCode, installed.installedAt))
            }
        }
    }

    /** When the last full check of all apps finished with at least one success. */
    private var lastFullCheckAt = 0L

    /**
     * Called every time the app is opened: checks every app for a newer version, quietly (the list
     * shows the results). Skipped if a check is running or succeeded in the last few minutes, so
     * switching in and out of the app doesn't use up GitHub's request limit. Retries when the
     * network isn't up yet (TVs just woken from standby).
     */
    fun checkAllOnLaunch() {
        scope.launch { syncHistoryWithDevice() }
        Notifications.cancelUpdates(context) // The list shows the current state now.
        if (!UpdateCheckPolicy.shouldCheckOnLaunch(_checking.value, lastFullCheckAt, System.currentTimeMillis())) return
        checkAllNow(quiet = true)
    }

    /** Checks every app for updates. [quiet] skips the summary message and retries when offline. */
    fun checkAllNow(quiet: Boolean = false) {
        if (_checking.value) return
        _checking.value = true
        scope.launch {
            try {
                var result = checkAll(quiet)
                var attempt = 1
                while (quiet && result.allConnectivity && !result.anySucceeded && attempt < UpdateCheckPolicy.ATTEMPTS) {
                    delay(UpdateCheckPolicy.RETRY_DELAY_MS)
                    result = checkAll(quiet)
                    attempt++
                }
                if (result.anySucceeded) lastFullCheckAt = System.currentTimeMillis()
                if (quiet) return@launch
                val errors = repository.entries.value.count { it.lastCheckError != null }
                _messages.tryEmit(
                    buildString {
                        append(
                            when (result.updates.size) {
                                0 -> "Everything is up to date"
                                1 -> "1 update available"
                                else -> "${result.updates.size} updates available"
                            }
                        )
                        if (errors > 0) append(" ($errors check${if (errors > 1) "s" else ""} failed)")
                    }
                )
            } finally {
                _checking.value = false
            }
        }
    }

    fun checkOne(entryId: String) {
        val entry = repository.get(entryId) ?: return
        if (isBusy(entryId)) return
        setOp(entryId, OpState.Resolving)
        scope.launch {
            val updated = try {
                if (entry.type == SourceType.GITHUB) checkGitHub(entry) else checkLink(entry)
            } finally {
                if (_ops.value[entryId] == OpState.Resolving) setOp(entryId, null)
            }
            when {
                updated.lastCheckError != null -> _messages.tryEmit("${updated.name}: ${updated.lastCheckError}")
                updated.type != SourceType.GITHUB -> _messages.tryEmit(
                    "${updated.name}: " + when {
                        updated.remoteChange == RemoteChange.CHANGED -> "the website has a newer file than your installed copy"
                        updated.remoteReason == ChangeReason.NOT_NEWER -> "the website's file isn't newer than your installed copy"
                        updated.remoteChange == RemoteChange.UNCHANGED -> "same as your installed copy"
                        else -> "the website doesn't give enough details to tell"
                    }
                )
                else -> {
                    val installed = InstalledApps.get(context, updated.packageName)
                    _messages.tryEmit(
                        "${updated.name}: " + if (VersionUtils.hasUpdate(updated, installed?.versionName, installed?.versionCode)) {
                            "update ${updated.latestTag} available"
                        } else {
                            "up to date (latest release ${updated.latestTag ?: "unknown"})"
                        }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------ update all / scheduled

    /** Only one batch (Update all, scheduled run, waiting installs) runs at a time. */
    private val batchMutex = Mutex()
    private var batchJob: Job? = null
    private val _updatingAll = MutableStateFlow(false)
    val updatingAll: StateFlow<Boolean> = _updatingAll.asStateFlow()

    /** Stops the running batch (and the app it is working on). */
    fun stopUpdateAll() {
        batchJob?.cancel()
    }

    /** Runs one batch item as its own registered job, so its card's Cancel works and the batch then moves on. */
    private suspend fun runItem(id: String, block: suspend () -> Unit) = coroutineScope {
        val job = launch { block() }
        jobs[id] = job
        job.join()
        if (jobs[id] === job) jobs.remove(id)
    }

    /** Starts a UI batch unless another batch is running. */
    private fun launchBatch(block: suspend () -> Unit) {
        if (!batchMutex.tryLock()) {
            _messages.tryEmit("An update is already running")
            return
        }
        _updatingAll.value = true
        batchJob = scope.launch {
            try {
                block()
            } finally {
                _updatingAll.value = false
                batchJob = null
                batchMutex.unlock()
            }
        }
    }

    /** "Update all now" from Settings: ticked apps, installing one after another with Android's prompts. */
    fun updateAllNow() {
        launchBatch {
            val summary = updateSelectedLocked(interactive = true)
            _messages.tryEmit(summary.describe())
        }
    }

    /**
     * "Update all" / "Install all" at the top of the list: updates every app in [ids], one at a
     * time so Android's install prompts don't pile up.
     */
    fun updateEntries(ids: List<String>) {
        if (ids.isEmpty()) return
        launchBatch {
            var updated = 0
            var installedNew = 0
            var failed = 0
            var skipped = 0
            try {
                for (id in ids) {
                    val entry = repository.get(id) ?: continue
                    if (isBusy(id)) continue
                    val before = InstalledApps.get(context, entry.packageName)?.versionCode
                    setOp(id, OpState.Resolving)
                    runItem(id) { runInstall(id) }
                    awaitInstallResult(id)
                    val after = InstalledApps.get(context, repository.get(id)?.packageName)?.versionCode
                    when {
                        _ops.value[id] is OpState.Failed -> failed++
                        after != null && before == null -> installedNew++
                        after != null && after != before -> updated++
                        else -> skipped++ // Cancelled, or the download was the same version.
                    }
                }
            } finally {
                _messages.tryEmit(
                    listOfNotNull(
                        "Updated $updated app${if (updated == 1) "" else "s"}".takeIf { updated > 0 || installedNew == 0 },
                        "installed $installedNew".takeIf { installedNew > 0 },
                        "$failed failed".takeIf { failed > 0 },
                        "$skipped not updated".takeIf { skipped > 0 },
                    ).joinToString(", ")
                )
            }
        }
    }

    /** Installs every waiting downloaded update, one after another. */
    fun installWaitingUpdates() {
        launchBatch {
            for (entry in waitingUpdates()) {
                if (isBusy(entry.id)) continue
                setOp(entry.id, OpState.Resolving)
                runItem(entry.id) { runInstall(entry.id) }
                awaitInstallResult(entry.id)
            }
        }
    }

    /**
     * Scheduled run (from the worker). Returns null when another batch is running, so the worker
     * can try again later without using up the schedule.
     */
    suspend fun runScheduledUpdate(): UpdateRunSummary? {
        if (!batchMutex.tryLock()) return null
        _updatingAll.value = true
        return try {
            updateSelectedLocked(interactive = false)
        } finally {
            _updatingAll.value = false
            batchMutex.unlock()
        }
    }

    /**
     * Checks every installed app that has auto-update switched on and installs newer versions.
     * [interactive] runs happen while the user is watching, so Android's confirmation prompts are
     * shown one at a time. Scheduled runs install silently where Android allows it (Android 12+,
     * apps this app installed, not while in use) and otherwise leave the download waiting.
     */
    private suspend fun updateSelectedLocked(interactive: Boolean): UpdateRunSummary {
        val updated = mutableListOf<String>()
        val waiting = mutableListOf<String>()
        val installing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val insecure = mutableListOf<String>()
        var connectivityFailures = 0
        var upToDate = 0
        val candidates = repository.entries.value.filter {
            it.autoUpdate && runCatching { InstalledApps.get(context, it.packageName) != null }.getOrDefault(false)
        }.map { it.id }
        for (id in candidates) {
            val candidate = repository.get(id) ?: continue
            if (isBusy(id)) continue
            if (!interactive && !settings.current.allowInsecureAutoUpdates && candidate.isInsecure()) {
                insecure += candidate.name
                continue
            }
            setOp(id, OpState.Resolving) // Claim before suspending.
            try {
                var apk: DownloadedApk? = null
                runItem(id) { apk = prepareUpdate(id, interactive) }
                val entry = repository.get(id) ?: continue
                val ready = apk
                if (ready == null) {
                    if (_ops.value[id] is OpState.Failed) failed += entry.name
                    else if (entry.readyFile != null && !interactive) waiting += entry.name
                    else upToDate++
                    if (_ops.value[id] == OpState.Resolving) setOp(id, null)
                    continue
                }
                when {
                    interactive -> {
                        runItem(id) { install(id, ready, entry.readyTag) }
                        awaitInstallResult(id)
                        val nowInstalled = InstalledApps.get(context, entry.packageName)?.versionCode ?: -1
                        when {
                            nowInstalled >= ready.info.versionCode -> updated += entry.name
                            _ops.value[id] is OpState.Failed -> failed += entry.name
                            else -> waiting += entry.name // Prompt was cancelled; the download stays.
                        }
                    }
                    canInstallSilently(entry) -> {
                        install(id, ready, entry.readyTag, background = true)
                        installing += entry.name // The receiver reports success, failure or "waiting".
                    }
                    else -> {
                        setOp(id, null)
                        waiting += entry.name
                    }
                }
            } catch (e: CancellationException) {
                setOp(id, null)
                throw e
            } catch (e: Exception) {
                if (Errors.isConnectivity(e)) connectivityFailures++
                setOp(id, failed(e))
                if (!interactive) {
                    repository.update(id) { it.copy(lastUpdateError = Errors.friendly(e), lastUpdateErrorAt = System.currentTimeMillis()) }
                }
                failed += candidate.name
            }
        }
        return UpdateRunSummary(
            updated, waiting, installing, upToDate, failed, insecure,
            onlyConnectivityFailures = failed.isNotEmpty() && connectivityFailures == failed.size,
        )
    }

    private fun AppEntry.isInsecure() = type == SourceType.URL && source.startsWith("http://", ignoreCase = true)

    /**
     * Whether a background install can complete without the user: Android 12+, App Downloader
     * installed the current copy, the update wasn't already refused, and the device isn't in use
     * (Android 12-13) / the app isn't busy (Android 14+, checked again by the installer).
     */
    private suspend fun canInstallSilently(entry: AppEntry): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || entry.readyNeedsUser) return false
        val pkg = entry.packageName ?: return false
        val installerOk = runCatching {
            context.packageManager.getInstallSourceInfo(pkg).installingPackageName == context.packageName
        }.getOrDefault(false)
        if (!installerOk) {
            repository.update(entry.id) { it.copy(readyNeedsUser = true) }
            return false
        }
        if (Build.VERSION.SDK_INT >= 34) return ApkInstaller.gentleUpdatePossible(context, pkg)
        // Android 12-13 can't check whether the app is in use; don't close it under someone watching.
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return power?.isInteractive != true
    }

    /**
     * Downloads a newer version of the entry's app if there is one, records it as waiting and
     * returns it. Returns null when the installed version is already current (or the update was
     * already refused by Android in an earlier background run).
     */
    private suspend fun prepareUpdate(entryId: String, interactive: Boolean): DownloadedApk? {
        var entry = repository.get(entryId) ?: return null
        val installed = InstalledApps.get(context, entry.packageName) ?: return null
        // Links can't be checked for versions without downloading, so reuse a waiting download for them.
        if (entry.type != SourceType.GITHUB) readyApk(entry)?.let { return it }

        setOp(entryId, OpState.Resolving)
        val download: Download = when (entry.type) {
            SourceType.URL, SourceType.AFTV_CODE -> {
                val url = if (entry.type == SourceType.AFTV_CODE) AftvResolver.resolve(entry.source) else entry.source
                if (settings.current.updateOnlyIfChanged) {
                    entry = checkLink(entry, url)
                    entry.lastCheckError?.let { throw IOException(it) }
                    // Only skip when the server says so; "can't tell" still downloads and compares versions.
                    if (entry.remoteChange == RemoteChange.UNCHANGED) {
                        setOp(entryId, null)
                        return null
                    }
                }
                Download(entry, url)
            }
            SourceType.GITHUB -> {
                val resolved = resolveDownload(entry)
                entry = resolved.entry
                if (entry.readyFile != null) {
                    // Reuse the waiting download only if it is still the latest release.
                    if (entry.readyTag == entry.latestTag) {
                        readyApk(entry)?.let { setOp(entryId, null); return it }
                    } else {
                        discardReady(entry)
                    }
                    entry = repository.get(entryId) ?: return null
                }
                if (!VersionUtils.hasUpdate(entry, installed.versionName, installed.versionCode)) {
                    setOp(entryId, null)
                    return null
                }
                // Android already refused this release in the background; don't download it again every run.
                if (!interactive && entry.failedTag != null && entry.failedTag == entry.latestTag) {
                    setOp(entryId, null)
                    return null
                }
                resolved
            }
        }
        val apk = downloadAndInspect(entryId, download.url, download.headers, download.expected)
        setOp(entryId, null)
        if (apk.packageName != entry.packageName) {
            withContext(Dispatchers.IO) { apk.file.delete() }
            throw IOException("The download is a different app (${apk.packageName}); update it manually")
        }
        if (!interactive && entry.failedVersionCode == apk.info.versionCode) {
            withContext(Dispatchers.IO) { apk.file.delete() }
            return null
        }
        val tag = if (entry.type == SourceType.GITHUB) entry.latestTag else null
        if (apk.info.versionCode <= installed.versionCode) {
            withContext(Dispatchers.IO) { apk.file.delete() }
            when {
                // A withdrawn or older release: remember it so it isn't offered or downloaded again.
                tag != null && apk.info.versionCode < installed.versionCode -> repository.update(entryId) { it.copy(skippedTag = tag) }
                // Same build under a new tag: remember it so it isn't offered again.
                tag != null -> repository.update(entryId) { it.copy(installedTag = tag, installedVersionCode = installed.versionCode) }
                apk.fingerprint != null -> repository.update(entryId) {
                    // Nothing newer in this file: use it as the baseline for future change checks.
                    it.copy(
                        fingerprint = apk.fingerprint.copy(notNewer = apk.info.versionCode < installed.versionCode),
                        remoteFingerprint = apk.fingerprint,
                        remoteChange = RemoteChange.UNCHANGED,
                        remoteReason = if (apk.info.versionCode < installed.versionCode) ChangeReason.NOT_NEWER else ChangeReason.SAVED_DETAILS,
                    )
                }
            }
            return null
        }
        repository.update(entryId) {
            it.copy(
                readyFile = apk.file.absolutePath,
                readyVersionName = apk.info.versionName,
                readyVersionCode = apk.info.versionCode,
                readyTag = tag,
                readyNeedsUser = false,
                pendingFingerprint = apk.fingerprint,
            )
        }
        return apk
    }

    /** Waits until Android reports the outcome of the install started for [id]. */
    private suspend fun awaitInstallResult(id: String) {
        withTimeoutOrNull(10.minutes) { _ops.first { it[id] != OpState.Installing } }
        if (_ops.value[id] == OpState.Installing) {
            sessions.remove(id)?.let { abandonedSessions += it }
            setOp(id, null)
        }
    }

    // ---------------------------------------------------------- self-update

    private val _selfUpdate = MutableStateFlow<SelfUpdate?>(null)
    /** A newer version of this app that the user hasn't dismissed yet. */
    val selfUpdate: StateFlow<SelfUpdate?> = _selfUpdate.asStateFlow()

    private val _selfCheckState = MutableStateFlow(SelfCheckState.IDLE)
    val selfCheckState: StateFlow<SelfCheckState> = _selfCheckState.asStateFlow()

    private var selfCheckJob: Job? = null
    private var lastSelfCheckAt = 0L
    private var snoozedVersion: String? = null
    private var snoozedAt = 0L

    /**
     * Called every time the app is opened, including when it is brought back from the background
     * (TVs usually resume the app rather than restart it). Skipped when the tick box is off, while
     * an update or check is in progress, or right after another check. Retries a couple of times
     * because the network is often not up yet right after a TV wakes from standby.
     */
    fun checkSelfUpdateOnLaunch() {
        if (!SelfUpdatePolicy.shouldAutoCheck(
                enabled = settings.current.checkSelfUpdates,
                updateOffered = _selfUpdate.value != null || isBusy(SELF_UPDATE_ID),
                checkRunning = selfCheckJob?.isActive == true,
                busy = anyBusy() || _updatingAll.value,
                lastCheckAt = lastSelfCheckAt,
                now = System.currentTimeMillis(),
            )
        ) return
        selfCheckJob = scope.launch {
            repeat(SelfUpdatePolicy.ATTEMPTS) { attempt ->
                if (checkSelfUpdate(manual = false)) return@launch
                if (attempt < SelfUpdatePolicy.ATTEMPTS - 1) delay(SelfUpdatePolicy.RETRY_DELAY_MS)
            }
        }
    }

    fun checkSelfUpdateNow() {
        if (_selfCheckState.value == SelfCheckState.CHECKING) return
        scope.launch { checkSelfUpdate(manual = true) }
    }

    /** Returns true when the check completed (whether or not an update was found). */
    private suspend fun checkSelfUpdate(manual: Boolean): Boolean {
        val current = BuildConfig.VERSION_NAME
        if (manual) _selfCheckState.value = SelfCheckState.CHECKING
        try {
            val self = AppEntry(
                name = "App Downloader",
                type = SourceType.GITHUB,
                source = BuildConfig.UPDATE_REPO,
                assetFilter = SELF_ASSET_FILTER,
            )
            // The app's own repository is public: never send the token there.
            val match = GitHubClient.findLatest(self, token = null, supportedAbis = Build.SUPPORTED_ABIS.toList())
            lastSelfCheckAt = System.currentTimeMillis()
            val latest = match.release.tagName
            val version = latest.removePrefix("v")
            val newer = (VersionUtils.compare(latest, current) ?: 0) > 0
            if (newer && (manual || SelfUpdatePolicy.shouldOffer(version, snoozedVersion, snoozedAt, lastSelfCheckAt))) {
                _selfUpdate.value = SelfUpdate(
                    version = version,
                    notes = match.release.body?.substringBefore("## How to install")?.trim()?.ifBlank { null },
                    assetUrl = match.asset.downloadUrl,
                    releaseUrl = match.release.htmlUrl,
                    size = match.asset.size.takeIf { it > 0 },
                    digest = match.asset.digest,
                )
                if (manual) _selfCheckState.value = SelfCheckState.IDLE
            } else if (manual) {
                _selfCheckState.value = SelfCheckState.UP_TO_DATE
            }
            return true
        } catch (e: CancellationException) {
            if (manual) _selfCheckState.value = SelfCheckState.IDLE
            throw e
        } catch (e: Exception) {
            if (manual) {
                _selfCheckState.value = SelfCheckState.FAILED
                _messages.tryEmit("Couldn't check for app updates: ${Errors.friendly(e)}")
            }
            return false
        }
    }

    fun dismissSelfUpdate() {
        _selfUpdate.value?.let {
            snoozedVersion = it.version
            snoozedAt = System.currentTimeMillis()
        }
        _selfUpdate.value = null
        clearOp(SELF_UPDATE_ID)
    }

    fun installSelfUpdate() {
        val update = _selfUpdate.value ?: return
        if (jobs[SELF_UPDATE_ID]?.isActive == true) return
        setOp(SELF_UPDATE_ID, OpState.Downloading(null))
        val job = scope.launch {
            val myJob = currentCoroutineContext()[Job]
            try {
                val apk = downloadAndInspect(SELF_UPDATE_ID, update.assetUrl, expected = ExpectedFile(update.size, update.digest))
                if (apk.packageName != context.packageName) {
                    throw IOException("The downloaded file is not an App Downloader update")
                }
                install(SELF_UPDATE_ID, apk, "v${update.version}")
            } catch (e: CancellationException) {
                setOp(SELF_UPDATE_ID, null)
                throw e
            } catch (e: Exception) {
                setOp(SELF_UPDATE_ID, OpState.Failed(Errors.friendly(e)))
            } finally {
                if (owns(SELF_UPDATE_ID, myJob)) jobs.remove(SELF_UPDATE_ID)
            }
        }
        if (job.isActive) jobs[SELF_UPDATE_ID] = job
    }

    fun clearTokenExpired() {
        _tokenExpired.value = false
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }
}
