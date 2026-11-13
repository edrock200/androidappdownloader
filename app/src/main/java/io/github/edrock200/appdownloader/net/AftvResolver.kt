package io.github.edrock200.appdownloader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

/** aftv.news answered with a bot check (Cloudflare) instead of the code's page. */
class AftvBlockedException(val code: String) : IOException(
    "aftv.news is blocking automatic lookups right now. Open aftv.news/$code in a browser to see where it leads, " +
        "then edit this app to use that link instead"
)

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
            val ownHost = finalUrl.host.endsWith("aftvnews.com") || finalUrl.host.endsWith("aftv.news") ||
                finalUrl.host == resolveBase.toHttpUrlOrNull()?.host
            if (!ownHost) {
                return@withContext finalUrl.toString()
            }
            if (!response.isSuccessful) {
                val body = runCatching { response.peekBody(64 * 1024).string() }.getOrDefault("")
                if (isBotCheck(response.code, response.header("cf-mitigated"), response.header("Server"), body)) {
                    throw AftvBlockedException(code)
                }
                throw IOException("aftv.news returned HTTP ${response.code}")
            }
            val html = response.body?.string().orEmpty()
            parseTarget(html) ?: throw IOException(
                if (isNotFound(html)) "aftv.news code $code does not exist"
                else "Could not find the destination of aftv.news code $code"
            )
        }
    }

    /** Whether a failed answer is a bot check page (Cloudflare's "Just a moment..."), not a missing code. */
    fun isBotCheck(status: Int, cfMitigated: String?, server: String?, body: String): Boolean {
        if (status != 403 && status != 429 && status != 503) return false
        if (cfMitigated.equals("challenge", ignoreCase = true)) return true
        val cloudflare = server?.contains("cloudflare", ignoreCase = true) == true
        return cloudflare && (body.contains("Just a moment", ignoreCase = true) || body.contains("challenges.cloudflare.com"))
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
