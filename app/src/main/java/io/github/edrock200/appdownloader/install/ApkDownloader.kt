package io.github.edrock200.appdownloader.install

import io.github.edrock200.appdownloader.net.Http
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext

/** Thrown when a link leads to a web page with no APK on it. */
class NotAnApkException(message: String, val pageUrl: String) : IOException(message)

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

    /**
     * Downloads [url] into [dir]. If the URL turns out to be a web page, the page is
     * scanned for a link to an APK which is downloaded instead.
     */
    suspend fun download(
        url: String,
        dir: File,
        baseName: String,
        onProgress: (Float?) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        downloadInternal(url, dir, baseName, onProgress, allowPageScan = true)
    }

    private suspend fun downloadInternal(
        url: String,
        dir: File,
        baseName: String,
        onProgress: (Float?) -> Unit,
        allowPageScan: Boolean,
    ): Result {
        val request = Request.Builder().url(url).get().build()
        Http.execute(request).use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Download failed: empty response")
            val finalUrl = response.request.url.toString()
            val contentType = body.contentType()?.toString()?.lowercase().orEmpty()

            if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                val html = body.source().let { src ->
                    src.request(MAX_HTML_BYTES)
                    src.buffer.clone().readUtf8()
                }
                val apkUrl = if (allowPageScan) findApkLink(html, finalUrl) else null
                if (apkUrl != null) {
                    return downloadInternal(apkUrl, dir, baseName, onProgress, allowPageScan = false)
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
            val part = File(dir, "$baseName.part")
            val total = body.contentLength().takeIf { it > 0 }
            onProgress(if (total != null) 0f else null)

            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total != null && done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (!looksLikeZip(part)) {
                part.delete()
                throw NotAnApkException("The downloaded file is not an APK.", finalUrl)
            }
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
        }
    }

    fun findApkLink(html: String, pageUrl: String): String? {
        val base = pageUrl.toHttpUrlOrNull() ?: return null
        return APK_LINK.findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .mapNotNull { base.resolve(it)?.toString() }
            .firstOrNull()
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
