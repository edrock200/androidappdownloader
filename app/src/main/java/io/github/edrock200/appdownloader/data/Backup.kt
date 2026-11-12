package io.github.edrock200.appdownloader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InputStream
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

data class RestoreResult(
    val entries: List<AppEntry>,
    val added: Int,
    val skipped: Int,
    /** Apps left out because their source wasn't a valid link, code or repository. */
    val invalid: Int = 0,
)

/** Turns the user's list and settings into a backup and back (pure, for tests). */
object Backup {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    /** Largest backup accepted (files, links and the unpacked QR payload). */
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_APPS = 500

    /** Reads at most [max] bytes; a longer stream is rejected instead of filling memory. */
    fun readBounded(input: InputStream, max: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw IOException("That file is too large to be a backup")
        }
        return out.toByteArray()
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
        if (trimmed.length > MAX_BYTES) throw IllegalArgumentException("That file is too large to be a backup")
        val jsonText = if (trimmed.startsWith(QR_PREFIX)) {
            try {
                val bytes = Base64.UrlSafe.decode(trimmed.removePrefix(QR_PREFIX))
                GZIPInputStream(ByteArrayInputStream(bytes)).use { readBounded(it) }.decodeToString()
            } catch (e: Exception) {
                throw IllegalArgumentException("This QR code isn't an App Downloader backup (or it's damaged)")
            }
        } else {
            trimmed
        }
        // Check what it is before decoding everything, so newer formats get an accurate message.
        val header = runCatching { json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("This isn't an App Downloader backup")
        if (header["format"]?.jsonPrimitive?.contentOrNull != BackupFile.FORMAT) {
            throw IllegalArgumentException("This isn't an App Downloader backup")
        }
        if ((header["version"]?.jsonPrimitive?.intOrNull ?: 1) > 1) {
            throw IllegalArgumentException("This backup was made by a newer App Downloader. Update the app first.")
        }
        return try {
            json.decodeFromJsonElement(BackupFile.serializer(), header)
        } catch (e: Exception) {
            throw IllegalArgumentException("This backup is damaged and can't be read")
        }
    }

    /**
     * Merges the backup's apps into [existing] (skipping ones already there), or replaces the
     * list when [replace] is true. Restored apps start fresh: release and install details are
     * re-learned on this device.
     */
    fun restoreApps(existing: List<AppEntry>, backup: BackupFile, replace: Boolean, trusted: Boolean = true): RestoreResult {
        val base = if (replace) emptyList() else existing
        val result = base.toMutableList()
        var added = 0
        var skipped = 0
        var invalid = 0
        for (raw in backup.apps.take(MAX_APPS)) {
            // Only accept sources that parse as what they claim to be, and keep strings to sane sizes.
            val parsed = SourceParser.parse(if (raw.type == SourceType.GITHUB) "github:${raw.source}" else raw.source.take(2000))
            if (parsed == null || parsed.type != raw.type) {
                invalid++
                continue
            }
            val app = raw.copy(
                name = raw.name.take(200).ifBlank { parsed.suggestedName },
                source = parsed.source,
                assetFilter = raw.assetFilter?.take(200),
                packageName = raw.packageName?.take(255),
            )
            if (result.any { it.type == app.type && SourceParser.sameSource(app.type, it.source, app.source) }) {
                skipped++
                continue
            }
            // Keep the existing entry (and its download history) when replacing with the same source.
            val previous = existing.firstOrNull { it.type == app.type && SourceParser.sameSource(app.type, it.source, app.source) }
            result += (previous ?: AppEntry(name = app.name, type = app.type, source = app.source, pendingInstall = true)).copy(
                name = app.name,
                autoName = app.autoName,
                // A backup from someone else can't say which installed app an entry controls.
                packageName = if (trusted) app.packageName ?: previous?.packageName else previous?.packageName,
                includePrereleases = app.includePrereleases,
                assetFilter = app.assetFilter,
                autoUpdate = app.autoUpdate,
            )
            added++
        }
        return RestoreResult(result, added, skipped, invalid)
    }

    /** Applies the backup's settings. A token is only replaced if the backup contains one. */
    fun restoreSettings(current: SettingsState, backup: BackupSettings, now: Long, includeToken: Boolean = true): SettingsState {
        val next = if (backup.scheduleAt > 0) Schedule.nextRun(backup.scheduleAt, backup.scheduleRepeat, now) else null
        val (minute, weekday) = next?.let { Schedule.localTimeOf(it) } ?: (-1 to 0)
        return current.copy(
            githubToken = backup.githubToken?.takeIf { includeToken }?.take(500) ?: current.githubToken,
            checkIntervalHours = backup.checkIntervalHours.takeIf { it in Settings.INTERVAL_OPTIONS } ?: current.checkIntervalHours,
            scheduleMinuteOfDay = minute,
            scheduleWeekday = weekday,
            deleteApkAfterInstall = backup.deleteApkAfterInstall,
            checkSelfUpdates = backup.checkSelfUpdates,
            scheduleAt = next ?: 0,
            scheduleRepeat = backup.scheduleRepeat,
            updateOnlyIfChanged = backup.updateOnlyIfChanged,
        )
    }
}
