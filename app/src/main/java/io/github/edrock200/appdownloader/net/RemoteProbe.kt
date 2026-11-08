package io.github.edrock200.appdownloader.net

import io.github.edrock200.appdownloader.install.ApkDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** What the server says about a file, without downloading it. */
@Serializable
data class RemoteFingerprint(
    val etag: String? = null,
    val lastModified: String? = null,
    val length: Long? = null,
    /** Host + path of the final address after redirects (query strings often hold one-off tokens). */
    val path: String? = null,
    /**
     * SHA-256 of the file's last [RemoteProbe.TAIL_BYTES]. An APK ends with its signature block and
     * zip directory, which change whenever anything in the app changes, so this identifies the file.
     */
    val tailHash: String? = null,
) {
    val isEmpty get() = etag == null && lastModified == null && length == null && path == null && tailHash == null

    /** Stable text form, used to avoid notifying twice about the same change. */
    val key get() = listOf(etag, lastModified, length, path, tailHash).joinToString("|")
}

@Serializable
enum class RemoteChange { CHANGED, UNCHANGED, UNKNOWN }

/** Why a check came out the way it did (shown on the card). */
@Serializable
enum class ChangeReason {
    /** The website's file was compared with the copy installed on this device. */
    CONTENT,
    /** Compared with the file details saved when App Downloader last installed it. */
    SAVED_DETAILS,
    /** The website's modification date was compared with when the app was installed. */
    DATE,
    /** The website gives no usable details about the file (or the link is a web page without an APK). */
    NO_DETAILS,
    /** The website gives details, but there is nothing to compare them with yet. */
    NO_BASELINE,
}

/** The installed APK, for comparing with the website's file. */
data class LocalApk(val length: Long, val tailHash: String)

data class ChangeDecision(
    val change: RemoteChange,
    val reason: ChangeReason,
    /** When set, store as the entry's fingerprint so later checks can compare against it. */
    val newBaseline: RemoteFingerprint? = null,
)

object RemoteProbe {

    /** Large enough to include an APK's signing block, which changes with any change to the app. */
    const val TAIL_BYTES = 256 * 1024
    private const val MAX_PAGE_BYTES = 2L * 1024 * 1024

    /**
     * Asks the server about the file at [url] without downloading it: headers (HEAD, or a 1-byte
     * ranged GET), plus a hash of the file's last [TAIL_BYTES] when the server supports ranges.
     * A link to a web page is followed to the first APK linked from it. Returns null when nothing
     * useful is available.
     */
    suspend fun fingerprint(url: String): RemoteFingerprint? = withContext(Dispatchers.IO) {
        probe(url, followPage = true)
    }

    private suspend fun probe(url: String, followPage: Boolean): RemoteFingerprint? {
        val head = runCatching {
            Http.execute(Request.Builder().url(url).head().build()).use { HeaderResult.of(it) }
        }.getOrNull()
        val headers = if (head?.fingerprint != null || head?.isPage == true) {
            head
        } else {
            runCatching {
                Http.execute(Request.Builder().url(url).header("Range", "bytes=0-0").get().build()).use { HeaderResult.of(it) }
            }.getOrNull()
        }

        if (headers?.isPage == true) {
            if (!followPage) return null
            val apkUrl = runCatching { findApkOnPage(headers.finalUrl) }.getOrNull() ?: return null
            return probe(apkUrl, followPage = false)
        }

        val target = headers?.finalUrl ?: url
        val base = headers?.fingerprint ?: RemoteFingerprint(path = pathOf(target))
        val tail = runCatching { fetchTail(target, base.length) }.getOrNull()
        val fp = if (tail != null) base.copy(tailHash = tail.hash, length = base.length ?: tail.totalLength) else base
        return fp.takeIf { !it.isEmpty }
    }

    private class HeaderResult(val fingerprint: RemoteFingerprint?, val isPage: Boolean, val finalUrl: String) {
        companion object {
            fun of(response: Response): HeaderResult? {
                if (!response.isSuccessful) return null
                val type = response.header("Content-Type")
                val finalUrl = response.request.url.toString()
                return HeaderResult(
                    fingerprint = fromHeaders(
                        contentType = type,
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        contentLength = response.header("Content-Length"),
                        contentRange = response.header("Content-Range"),
                        finalUrl = finalUrl,
                    ),
                    isPage = isPageType(type),
                    finalUrl = finalUrl,
                )
            }
        }
    }

    private suspend fun findApkOnPage(pageUrl: String): String? =
        Http.execute(Request.Builder().url(pageUrl).get().build()).use { response ->
            val body = response.body ?: return null
            if (!response.isSuccessful || !isPageType(response.header("Content-Type"))) return null
            val source = body.source()
            source.request(MAX_PAGE_BYTES)
            ApkDownloader.findApkLink(source.buffer.clone().readUtf8(), response.request.url.toString())
        }

    private class Tail(val hash: String, val totalLength: Long?)

    /**
     * Downloads only the end of the file. Asks by position when the size is known, because some
     * storage services (including GitHub's) refuse "last N bytes" requests. Servers without range
     * support are skipped rather than downloading the whole file.
     */
    private suspend fun fetchTail(url: String, knownLength: Long?): Tail? {
        val range = if (knownLength != null && knownLength > 0) {
            "bytes=${maxOf(0L, knownLength - TAIL_BYTES)}-"
        } else {
            "bytes=-$TAIL_BYTES"
        }
        return Http.execute(Request.Builder().url(url).header("Range", range).get().build()).use { response ->
            if (response.code != 206) return null
            if (isPageType(response.header("Content-Type"))) return null
            val total = response.header("Content-Range")?.substringAfterLast('/', "")?.toLongOrNull()
            val bytes = response.body?.source()?.let { src ->
                src.request(TAIL_BYTES + 1L)
                src.buffer.clone().readByteArray().takeIf { it.size <= TAIL_BYTES }
            } ?: return null
            if (total != null && bytes.size.toLong() != minOf(total, TAIL_BYTES.toLong())) return null
            Tail(sha256(bytes), total)
        }
    }

    /** Length and tail hash of a local APK file, using the same rule as [fetchTail]. */
    fun localApk(file: File): LocalApk? {
        if (!file.isFile || !file.canRead()) return null
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val size = minOf(length, TAIL_BYTES.toLong()).toInt()
            val buffer = ByteArray(size)
            raf.seek(length - size)
            raf.readFully(buffer)
            return LocalApk(length, sha256(buffer))
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun isPageType(contentType: String?): Boolean {
        val type = contentType?.lowercase().orEmpty()
        return type.startsWith("text/html") || type.startsWith("application/xhtml")
    }

    private fun pathOf(url: String) = url.toHttpUrlOrNull()?.let { it.host + it.encodedPath }

    /** Builds a fingerprint from response headers (pure, for tests). Web pages give null. */
    fun fromHeaders(
        contentType: String?,
        etag: String?,
        lastModified: String?,
        contentLength: String?,
        contentRange: String?,
        finalUrl: String,
    ): RemoteFingerprint? {
        if (isPageType(contentType)) return null
        // "bytes 0-0/12345" gives the full size when only one byte was requested.
        val length = contentRange?.substringAfterLast('/', "")?.toLongOrNull()
            ?: contentLength?.toLongOrNull()?.takeIf { contentRange == null }
        val fp = RemoteFingerprint(
            etag = etag?.removePrefix("W/")?.trim()?.ifBlank { null },
            lastModified = lastModified?.trim()?.ifBlank { null },
            length = length?.takeIf { it > 0 },
            path = pathOf(finalUrl),
        )
        return fp.takeIf { !it.isEmpty }
    }

    /**
     * Compares the fingerprint saved when the installed copy was downloaded ([old]) with what the
     * server reports now ([new]). The file's tail hash is the strongest signal, then ETag/date;
     * size and address alone can only prove a change.
     */
    fun compare(old: RemoteFingerprint?, new: RemoteFingerprint?): RemoteChange {
        if (old == null || new == null) return RemoteChange.UNKNOWN
        fun <T> differs(a: T?, b: T?) = a != null && b != null && a != b

        if (old.tailHash != null && new.tailHash != null) {
            return if (old.tailHash != new.tailHash || differs(old.length, new.length)) RemoteChange.CHANGED
            else RemoteChange.UNCHANGED
        }
        val strongComparable = (old.etag != null && new.etag != null) ||
            (old.lastModified != null && new.lastModified != null)
        if (strongComparable) {
            return if (differs(old.etag, new.etag) || differs(old.lastModified, new.lastModified) ||
                differs(old.length, new.length)
            ) {
                RemoteChange.CHANGED
            } else {
                RemoteChange.UNCHANGED
            }
        }
        if (differs(old.length, new.length) || differs(old.path, new.path)) return RemoteChange.CHANGED
        // A matching size or address alone isn't proof that the file is the same.
        return RemoteChange.UNKNOWN
    }

    /**
     * Decides whether the website's file differs from what is installed, using the best evidence
     * available: the installed APK itself, then the details saved at the last install, then the
     * website's modification date versus when the app was installed.
     */
    fun decide(
        saved: RemoteFingerprint?,
        remote: RemoteFingerprint?,
        installed: LocalApk?,
        installedAt: Long,
    ): ChangeDecision {
        if (remote == null) return ChangeDecision(RemoteChange.UNKNOWN, ChangeReason.NO_DETAILS)

        if (remote.tailHash != null && installed != null) {
            val same = remote.tailHash == installed.tailHash && (remote.length == null || remote.length == installed.length)
            return if (same) {
                ChangeDecision(RemoteChange.UNCHANGED, ChangeReason.CONTENT, newBaseline = remote)
            } else {
                ChangeDecision(RemoteChange.CHANGED, ChangeReason.CONTENT)
            }
        }

        val bySaved = compare(saved, remote)
        if (bySaved != RemoteChange.UNKNOWN) return ChangeDecision(bySaved, ChangeReason.SAVED_DETAILS)

        val modified = parseHttpDate(remote.lastModified)
        if (modified != null && installedAt > 0) {
            // A file last changed before the app was installed is the one that was installed (or older).
            return if (modified > installedAt + DATE_SLACK_MS) {
                ChangeDecision(RemoteChange.CHANGED, ChangeReason.DATE)
            } else {
                ChangeDecision(RemoteChange.UNCHANGED, ChangeReason.DATE, newBaseline = saved ?: remote)
            }
        }
        return ChangeDecision(
            RemoteChange.UNKNOWN,
            if (saved == null && (remote.etag != null || remote.lastModified != null)) ChangeReason.NO_BASELINE
            else ChangeReason.NO_DETAILS,
        )
    }

    /** Clock differences between the website and the device. */
    private const val DATE_SLACK_MS = 2 * 60_000L

    fun parseHttpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val formats = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(value.trim())
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }
}
