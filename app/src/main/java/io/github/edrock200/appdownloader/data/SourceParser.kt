package io.github.edrock200.appdownloader.data

import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.GitHubClient

/** Figures out what kind of source the user typed and normalises it. */
object SourceParser {

    data class Parsed(val type: SourceType, val source: String, val suggestedName: String)

    fun parse(raw: String): Parsed? {
        val input = raw.trim()
        if (input.isEmpty()) return null

        AftvResolver.normalizeCode(input)?.let { code ->
            return Parsed(SourceType.AFTV_CODE, code, "AFTV code $code")
        }
        // Direct file links on github.com (including ".../releases/latest/download/<file>") are plain
        // downloads of that exact file, not repositories to monitor.
        val isGitHubFileLink = Regex("/(releases/download|releases/latest/download|raw|blob)/").containsMatchIn(input)
        if (!isGitHubFileLink) {
            GitHubClient.parseRepo(input)?.let { ref ->
                return Parsed(SourceType.GITHUB, ref.slug, ref.repo)
            }
        }
        if (input.any { it.isWhitespace() }) return null

        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)) input else "https://$input"
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isBlank() || !host.contains('.') && !host.startsWith("localhost")) return null
        val fileName = afterScheme.substringBefore('?').substringBefore('#')
            .substringAfterLast('/', "").takeIf { it.isNotBlank() }
        return Parsed(SourceType.URL, url, fileName ?: host)
    }

    /**
     * Whether two saved sources are the same. GitHub repositories ignore case; aftv.news codes are
     * exact; for links only the scheme and host ignore case (paths and short links are case-sensitive).
     */
    fun sameSource(type: SourceType, a: String, b: String): Boolean = when (type) {
        SourceType.GITHUB -> a.equals(b, ignoreCase = true)
        SourceType.AFTV_CODE -> a.trim() == b.trim()
        SourceType.URL -> {
            fun split(u: String): Pair<String, String> {
                val t = u.trim()
                val schemeEnd = t.indexOf("://")
                if (schemeEnd < 0) return "" to t
                val rest = t.substring(schemeEnd + 3)
                val slash = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
                return (t.substring(0, schemeEnd + 3) + rest.substring(0, slash)).lowercase() to rest.substring(slash)
            }
            split(a) == split(b)
        }
    }
}
