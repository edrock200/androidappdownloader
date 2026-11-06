package io.github.edrock200.appdownloader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A saved app as stored in a backup: only what the user set, nothing device-specific. */
@Serializable
data class BackupApp(
    val name: String,
    val autoName: Boolean = false,
    val type: SourceType,
    val source: String,
    val packageName: String? = null,
    val includePrereleases: Boolean = false,
    val assetFilter: String? = null,
    val autoUpdate: Boolean = true,
)

@Serializable
data class BackupSettings(
    val githubToken: String? = null,
    val checkIntervalHours: Int = 12,
    val deleteApkAfterInstall: Boolean = true,
    val checkSelfUpdates: Boolean = true,
    val scheduleAt: Long = 0,
    val scheduleRepeat: Repeat = Repeat.ONCE,
    val updateOnlyIfChanged: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

@Serializable
data class BackupFile(
    /** Must be [FORMAT]; no default, so files without it (or `{}`) are rejected. */
    val format: String = "",
    val version: Int = 1,
    val createdAt: Long = 0,
    val appVersion: String? = null,
    val settings: BackupSettings = BackupSettings(),
    val apps: List<BackupApp> = emptyList(),
) {
    companion object {
        const val FORMAT = "app-downloader-backup"
    }
}

data class RestoreResult(val entries: List<AppEntry>, val added: Int, val skipped: Int)

/** Turns the user's list and settings into a backup and back (pure, for tests). */
object Backup {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val compactJson = Json(json) { prettyPrint = false; encodeDefaults = false }

    /** Prefix of the text inside a settings QR code. */
    const val QR_PREFIX = "ADBK1:"

    /** QR codes hold at most 2,953 bytes; keep a little room. */
    const val QR_MAX_CHARS = 2900

    fun create(
        entries: List<AppEntry>,
        settings: SettingsState,
        includeToken: Boolean,
        appVersion: String?,
        now: Long,
    ) = BackupFile(
        format = BackupFile.FORMAT,
        createdAt = now,
        appVersion = appVersion,
        settings = BackupSettings(
            githubToken = settings.githubToken.takeIf { includeToken && it.isNotBlank() },
            checkIntervalHours = settings.checkIntervalHours,
            deleteApkAfterInstall = settings.deleteApkAfterInstall,
            checkSelfUpdates = settings.checkSelfUpdates,
            scheduleAt = settings.scheduleAt,
            scheduleRepeat = settings.scheduleRepeat,
            updateOnlyIfChanged = settings.updateOnlyIfChanged,
            themeMode = settings.themeMode,
        ),
        apps = entries.map {
            BackupApp(
                name = it.name,
                autoName = it.autoName,
                type = it.type,
                source = it.source,
                packageName = it.packageName,
                includePrereleases = it.includePrereleases,
                assetFilter = it.assetFilter,
                autoUpdate = it.autoUpdate,
            )
        },
    )

    /** Readable JSON for backup files. */
    fun toJson(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Compact, compressed text for a QR code, or null if the list is too long to fit. */
    @OptIn(ExperimentalEncodingApi::class)
    fun toQrText(backup: BackupFile): String? {
        val raw = compactJson.encodeToString(BackupFile.serializer(), backup).toByteArray()
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        val text = QR_PREFIX + Base64.UrlSafe.encode(zipped)
        return text.takeIf { it.length <= QR_MAX_CHARS }
    }

    /** Reads a backup from file contents, a QR code's text, or a downloaded page. */
    @OptIn(ExperimentalEncodingApi::class)
    fun parse(text: String): BackupFile {
        val trimmed = text.trim()
        val jsonText = if (trimmed.startsWith(QR_PREFIX)) {
            val bytes = try {
                Base64.UrlSafe.decode(trimmed.removePrefix(QR_PREFIX))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("This QR code isn't an App Downloader backup")
            }
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.decodeToString()
        } else {
            trimmed
        }
        val backup = try {
            json.decodeFromString(BackupFile.serializer(), jsonText)
        } catch (e: Exception) {
            throw IllegalArgumentException("This isn't an App Downloader backup")
        }
        require(backup.format == BackupFile.FORMAT) { "This isn't an App Downloader backup" }
        require(backup.version <= 1) { "This backup was made by a newer App Downloader. Update the app first." }
        return backup
    }

    /**
     * Merges the backup's apps into [existing] (skipping ones already there), or replaces the
     * list when [replace] is true. Restored apps start fresh: release and install details are
     * re-learned on this device.
     */
    fun restoreApps(existing: List<AppEntry>, backup: BackupFile, replace: Boolean): RestoreResult {
        val base = if (replace) emptyList() else existing
        val result = base.toMutableList()
        var added = 0
        var skipped = 0
        for (app in backup.apps) {
            if (result.any { it.type == app.type && it.source.equals(app.source, ignoreCase = true) }) {
                skipped++
                continue
            }
            // Keep the existing entry (and its download history) when replacing with the same source.
            val previous = existing.firstOrNull { it.type == app.type && it.source.equals(app.source, ignoreCase = true) }
            result += (previous ?: AppEntry(name = app.name, type = app.type, source = app.source)).copy(
                name = app.name,
                autoName = app.autoName,
                packageName = app.packageName ?: previous?.packageName,
                includePrereleases = app.includePrereleases,
                assetFilter = app.assetFilter,
                autoUpdate = app.autoUpdate,
            )
            added++
        }
        return RestoreResult(result, added, skipped)
    }

    /** Applies the backup's settings. A token is only replaced if the backup contains one. */
    fun restoreSettings(current: SettingsState, backup: BackupSettings, now: Long): SettingsState {
        val next = if (backup.scheduleAt > 0) Schedule.nextRun(backup.scheduleAt, backup.scheduleRepeat, now) else null
        return current.copy(
            githubToken = backup.githubToken ?: current.githubToken,
            checkIntervalHours = backup.checkIntervalHours,
            deleteApkAfterInstall = backup.deleteApkAfterInstall,
            checkSelfUpdates = backup.checkSelfUpdates,
            scheduleAt = next ?: 0,
            scheduleRepeat = backup.scheduleRepeat,
            updateOnlyIfChanged = backup.updateOnlyIfChanged,
            themeMode = backup.themeMode,
        )
    }
}
