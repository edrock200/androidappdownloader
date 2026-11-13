package io.github.edrock200.appdownloader.net

import io.github.edrock200.appdownloader.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    /** API address of the asset; with a token it can download files of private repositories. */
    @SerialName("url") val apiUrl: String? = null,
    /** e.g. "sha256:ab12…" (published by GitHub for newer uploads). */
    val digest: String? = null,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    /** Release notes (Markdown). */
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

data class RepoRef(val owner: String, val repo: String) {
    val slug get() = "$owner/$repo"
    val url get() = "https://github.com/$slug"
}

/** The release chosen for an entry. [tokenRejected] means the saved token was refused (expired) and was skipped. */
data class ReleaseMatch(val release: GhRelease, val asset: GhAsset, val tokenRejected: Boolean = false)

/** Thrown when GitHub refuses requests because the hourly limit was used up. */
class RateLimitException(message: String) : IOException(message)

object GitHubClient {

    /** Overridable for tests. */
    @Volatile
    var apiBase = "https://api.github.com"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val REPO_URL = Regex(
        "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val SLUG = Regex("^(?:gh:|github:)([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$", RegexOption.IGNORE_CASE)

    /** First path segments of github.com pages that are not repositories. */
    private val RESERVED_OWNERS = setOf(
        "about", "apps", "codespaces", "collections", "contact", "customer-stories", "enterprise", "events",
        "explore", "features", "issues", "join", "login", "logout", "marketplace", "new", "notifications",
        "organizations", "orgs", "pricing", "pulls", "readme", "search", "security", "settings", "site",
        "sponsors", "topics", "trending",
    )

    /** Accepts `https://github.com/owner/repo[/...]`, `github.com/owner/repo` or `github:owner/repo`. */
    fun parseRepo(input: String): RepoRef? {
        val s = input.trim()
        val m = REPO_URL.find(s) ?: SLUG.find(s) ?: return null
        val (owner, repo) = m.destructured
        if (owner.isBlank() || repo.isBlank() || owner.lowercase() in RESERVED_OWNERS) return null
        return RepoRef(owner, repo)
    }

    /**
     * Returns the newest (non-draft) release that has an installable asset matching the entry's
     * filter and this device. A token that GitHub rejects (expired) is dropped and the request
     * repeated anonymously; the result then says so.
     */
    suspend fun findLatest(
        entry: AppEntry,
        token: String?,
        supportedAbis: List<String>,
        isTv: Boolean = false,
    ): ReleaseMatch = withContext(Dispatchers.IO) {
        val ref = parseRepo("github.com/" + entry.source)
            ?: throw IOException("Invalid repository: ${entry.source}")
        var tokenRejected = false
        suspend fun <T> withAuth(block: suspend (String?) -> T): T = try {
            block(token?.takeIf { it.isNotBlank() && !tokenRejected })
        } catch (e: TokenRejectedException) {
            tokenRejected = true
            block(null)
        }
        // Cheap path: the single "latest" release, when it has a suitable file.
        if (!entry.includePrereleases) {
            val latest = runCatching { withAuth { fetchLatest(ref, it) } }
                .onFailure { if (it is RateLimitException) throw it }
                .getOrNull()
            if (latest != null) {
                runCatching { pickRelease(listOf(latest), entry, supportedAbis, isTv) }.getOrNull()
                    ?.let { return@withContext it.copy(tokenRejected = tokenRejected) }
            }
        }
        val releases = withAuth { fetchReleases(ref, it) }
        pickRelease(releases, entry, supportedAbis, isTv).copy(tokenRejected = tokenRejected)
    }

    /** Chooses the newest eligible release with a suitable asset, or throws explaining why none fits. */
    fun pickRelease(
        releases: List<GhRelease>,
        entry: AppEntry,
        supportedAbis: List<String>,
        isTv: Boolean = false,
    ): ReleaseMatch {
        val slug = entry.source
        val candidates = releases.filter { !it.draft && (entry.includePrereleases || !it.prerelease) }
        if (candidates.isEmpty()) {
            throw IOException(
                if (releases.isEmpty()) "$slug has no releases"
                else "$slug has no stable releases (enable pre-releases to include them)"
            )
        }
        for (release in candidates) {
            val asset = AssetSelector.select(release.assets, entry.assetFilter, supportedAbis, isTv, entry.latestAssetName)
            if (asset != null) return ReleaseMatch(release, asset)
        }
        throw IOException(
            if (entry.assetFilter.isNullOrBlank()) "No APK for this device in the recent releases of $slug"
            else "No APK matching \"${entry.assetFilter}\" in the recent releases of $slug"
        )
    }

    fun parseReleases(body: String): List<GhRelease> =
        json.decodeFromString(ListSerializer(GhRelease.serializer()), body)

    private class TokenRejectedException : IOException("GitHub token is invalid or expired")

    /** ETag + body of earlier answers, so unchanged checks are answered with 304 (free of the rate limit). */
    private val cache = ConcurrentHashMap<String, Pair<String, String>>()

    private suspend fun get(url: String, token: String?, slug: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", Http.API_USER_AGENT)
        val cacheKey = "$url|${token != null}"
        cache[cacheKey]?.let { builder.header("If-None-Match", it.first) }
        if (token != null) builder.header("Authorization", "Bearer $token")
        Http.execute(builder.build()).use { response ->
            if (response.code == 304) cache[cacheKey]?.let { return it.second }
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    response.header("ETag")?.let { cache[cacheKey] = it to body }
                    return body
                }
                response.code == 401 && token != null -> throw TokenRejectedException()
                response.code == 404 ->
                    throw IOException("Repository $slug not found (private repositories need a GitHub token with access)")
                (response.code == 403 || response.code == 429) &&
                    (response.header("X-RateLimit-Remaining") == "0" || body.contains("rate limit", true)) ->
                    throw RateLimitException(rateLimitMessage(response.header("X-RateLimit-Reset"), token != null))
                response.code == 403 -> throw IOException("GitHub denied access to $slug (HTTP 403)")
                else -> throw IOException("GitHub returned HTTP ${response.code}")
            }
        }
    }

    /** Plain message for an exhausted rate limit, with the wait time when GitHub gives it (pure, for tests). */
    fun rateLimitMessage(resetEpochSeconds: String?, hasToken: Boolean, nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = resetEpochSeconds?.toLongOrNull()?.let { ((it * 1000 - nowMillis) / 60_000 + 1).coerceAtLeast(1) }
        val wait = minutes?.let { " Try again in about $it minute${if (it == 1L) "" else "s"}." } ?: " Try again later."
        return if (hasToken) "GitHub's hourly limit was reached.$wait"
        else "GitHub's limit for checks without a token was reached.$wait Adding a GitHub token in Settings raises the limit."
    }

    private suspend fun fetchLatest(ref: RepoRef, token: String?): GhRelease =
        json.decodeFromString(GhRelease.serializer(), get("$apiBase/repos/${ref.owner}/${ref.repo}/releases/latest", token, ref.slug))

    private suspend fun fetchReleases(ref: RepoRef, token: String?): List<GhRelease> =
        parseReleases(get("$apiBase/repos/${ref.owner}/${ref.repo}/releases?per_page=100", token, ref.slug))
}
