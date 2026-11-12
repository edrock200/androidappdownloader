package io.github.edrock200.appdownloader.install

import io.github.edrock200.appdownloader.net.AssetSelector
import io.github.edrock200.appdownloader.net.GhAsset
import io.github.edrock200.appdownloader.net.Http
import io.github.edrock200.appdownloader.net.LinkNormalizer
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Thrown when a link leads to a web page with no APK on it. */
class NotAnApkException(message: String, val pageUrl: String) : IOException(message)

/** What the download should match (from GitHub's published asset details), when known. */
data class ExpectedFile(val size: Long? = null, val sha256: String? = null)

object ApkDownloader {

    data class Result(
        val file: File,
        val finalUrl: String,
        val fileName: String,
        /** Server details of the downloaded file, for later change checks. */
        val fingerprint: RemoteFingerprint?,
    )

    private val APK_LINK = Regex(
        """href\s*=\s*["']([^"'\s>]+?\.(?:apk|apks|xapk)(?:\?[^"'\s>]*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_HTML_BYTES = 2L * 1024 * 1024

    /** Room needed on disk for a download: the file, the installer's copy, and some slack. */
    const val SPACE_FACTOR = 2.5

    private val counter = AtomicLong()

    /**
     * Downloads [url] into [dir]. If the URL turns out to be a web page, the page is scanned for a
     * link to an APK which is downloaded instead. [headers] are sent with the first request only
     * (OkHttp drops Authorization on redirects to other hosts). Cancelling the coroutine stops the
     * transfer straight away.
     */
    suspend fun download(
        url: String,
        dir: File,
        baseName: String,
        headers: Map<String, String> = emptyMap(),
        expected: ExpectedFile? = null,
        supportedAbis: List<String> = emptyList(),
        onProgress: (Float?) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        downloadInternal(LinkNormalizer.normalize(url), dir, baseName, headers, expected, supportedAbis, onProgress, allowPageScan = true)
    }

    private suspend fun downloadInternal(
        url: String,
        dir: File,
        baseName: String,
        headers: Map<String, String>,
        expected: ExpectedFile?,
        supportedAbis: List<String>,
        onProgress: (Float?) -> Unit,
        allowPageScan: Boolean,
    ): Result {
        val request = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = Http.client.newCall(request)
        // Stop the transfer as soon as the coroutine is cancelled, not after the next blocking read.
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            Http.execute(call).use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Download failed: empty response")
                val finalUrl = response.request.url.toString()
                val contentType = body.contentType()?.toString()?.lowercase().orEmpty()

                if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                    val html = body.source().let { src ->
                        src.request(MAX_HTML_BYTES)
                        src.buffer.clone().readUtf8()
                    }
                    val apkUrl = if (allowPageScan) findApkLink(html, finalUrl, supportedAbis) else null
                    if (apkUrl != null) {
                        return downloadInternal(apkUrl, dir, baseName, emptyMap(), expected, supportedAbis, onProgress, allowPageScan = false)
                    }
                    throw NotAnApkException("This link opens a web page, not an APK file.", finalUrl)
                }

                val fileName = fileNameFrom(response.header("Content-Disposition"), finalUrl)
                val ext = when {
                    fileName.endsWith(".xapk", true) -> "xapk"
                    fileName.endsWith(".apks", true) -> "apks"
                    else -> "apk"
                }
                dir.mkdirs()
                val total = body.contentLength().takeIf { it > 0 } ?: expected?.size?.takeIf { it > 0 }
                if (total != null) {
                    val needed = (total * SPACE_FACTOR).toLong()
                    if (dir.usableSpace in 0 until needed) {
                        throw IOException("Not enough storage space: about ${needed / (1024 * 1024) + 1} MB free is needed")
                    }
                }
                // A unique temporary file per attempt, so a retry can't clash with a cancelled download.
                val part = File(dir, "$baseName-${counter.incrementAndGet()}.part")
                try {
                    onProgress(if (total != null) 0f else null)
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPercent = -1
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                if (total != null) {
                                    val percent = (done * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }
                    }
                    verify(part, digest.digest(), expected)
                    if (!looksLikeZip(part)) throw NotAnApkException("The downloaded file is not an APK.", finalUrl)
                    val dest = File(dir, "$baseName.$ext")
                    dest.delete()
                    if (!part.renameTo(dest)) throw IOException("Could not save the downloaded file")
                    onProgress(1f)
                    val fingerprint = RemoteProbe.fromHeaders(
                        contentType = response.header("Content-Type"),
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        contentLength = dest.length().toString(),
                        contentRange = null,
                        finalUrl = finalUrl,
                    )?.let { fp -> RemoteProbe.localApk(dest)?.let { fp.copy(tailHash = it.tailHash) } ?: fp }
                    return Result(dest, finalUrl, fileName, fingerprint)
                } finally {
                    part.delete()
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private fun verify(file: File, sha256: ByteArray, expected: ExpectedFile?) {
        expected ?: return
        if (expected.size != null && expected.size > 0 && file.length() != expected.size) {
            throw IOException("The download was incomplete (${file.length()} of ${expected.size} bytes). Try again.")
        }
        val want = expected.sha256?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 } ?: return
        val got = sha256.joinToString("") { "%02x".format(it) }
        if (got != want) throw IOException("The download doesn't match the file published on GitHub, so it was not installed.")
    }

    /**
     * Picks the APK link to follow on a web page: links found on a secure page must be secure too,
     * links on the same site are preferred, and among several the one best suited to this device wins.
     */
    fun findApkLink(html: String, pageUrl: String, supportedAbis: List<String> = emptyList()): String? {
        val base = pageUrl.toHttpUrlOrNull() ?: return null
        val links = APK_LINK.findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .mapNotNull { base.resolve(it) }
            .filter { !base.isHttps || it.isHttps }
            .distinct()
            .toList()
        if (links.isEmpty()) return null
        val sameHost = links.filter { it.host.equals(base.host, ignoreCase = true) }.ifEmpty { links }
        if (supportedAbis.isEmpty() || sameHost.size == 1) return sameHost.first().toString()
        val asAssets = sameHost.map { GhAsset(name = it.pathSegments.lastOrNull().orEmpty(), downloadUrl = it.toString()) }
        return (AssetSelector.select(asAssets, null, supportedAbis) ?: asAssets.first()).downloadUrl
    }

    fun fileNameFrom(contentDisposition: String?, url: String): String {
        contentDisposition?.let { cd ->
            Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8") }
                    .getOrDefault(it.groupValues[1])
            }
            Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
                return it.groupValues[1].trim()
            }
        }
        val path = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() }
        return path ?: "download.apk"
    }

    private fun looksLikeZip(file: File): Boolean = try {
        file.inputStream().use { s ->
            val header = ByteArray(2)
            s.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    } catch (e: IOException) {
        false
    }
}
