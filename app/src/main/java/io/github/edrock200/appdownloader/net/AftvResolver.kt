package io.github.edrock200.appdownloader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Resolves AFTVnews short codes (as used by the "Downloader" app on Fire TV),
 * e.g. `12345` or `aftv.news/12345`, into the URL they point to.
 */
object AftvResolver {

    /** Overridable for tests. */
    @Volatile
    var resolveBase = "https://go.aftvnews.com/"
    private val SHORT_URL = Regex(
        "^(?:https?://)?(?:www\\.)?(?:aftv\\.news|go\\.aftvnews\\.com)/(\\d{1,10})/?$",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_CODE = Regex("^\\d{1,10}$")

    /** Returns the numeric code if [input] is an aftv.news code or short URL, else null. */
    fun normalizeCode(input: String): String? {
        val s = input.trim()
        if (BARE_CODE.matches(s)) return s
        return SHORT_URL.find(s)?.groupValues?.get(1)
    }

    fun shortUrl(code: String) = "https://aftv.news/$code"

    suspend fun resolve(code: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(resolveBase + code).get().build()
        Http.execute(request).use { response ->
            val finalUrl = response.request.url
            // The shortener may redirect straight to the target in the future.
            if (!finalUrl.host.endsWith("aftvnews.com") && !finalUrl.host.endsWith("aftv.news")) {
                return@withContext finalUrl.toString()
            }
            if (!response.isSuccessful) throw IOException("aftv.news returned HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            parseTarget(html) ?: throw IOException(
                if (isNotFound(html)) "aftv.news code $code does not exist"
                else "Could not find the destination of aftv.news code $code"
            )
        }
    }

    fun isNotFound(html: String) = html.contains("does not exist", ignoreCase = true)

    /** Extracts the destination URL from the shortener's redirect page. */
    fun parseTarget(html: String): String? {
        val patterns = listOf(
            Regex("""window\.location(?:\.href)?\s*=\s*["']([^"']+)["']"""),
            Regex("""Redirecting.{0,200}?<a\s+[^>]*href=["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL),
            Regex("""http-equiv=["']refresh["'][^>]*url=([^"'>\s]+)""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val url = p.find(html)?.groupValues?.get(1)?.let(::decodeEntities)
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) return url
        }
        return null
    }

    private fun decodeEntities(s: String) = s
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("\\/", "/")
}
