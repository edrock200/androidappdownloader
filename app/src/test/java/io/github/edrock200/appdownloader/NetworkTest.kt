package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ExpectedFile
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.net.RateLimitException
import io.github.edrock200.appdownloader.net.RemoteProbe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Network-facing logic against a local fake server (no real network). */
class NetworkTest {

    private lateinit var server: MockWebServer
    private val dir = File(System.getProperty("java.io.tmpdir"), "ad-net-test").apply { mkdirs() }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        GitHubClient.apiBase = server.url("/api").toString().trimEnd('/')
    }

    @After
    fun stop() {
        server.shutdown()
        GitHubClient.apiBase = "https://api.github.com"
    }

    private fun release(tag: String, asset: String = "app.apk") =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[{"name":"$asset","browser_download_url":"https://x/$asset","size":5,"url":"https://api/$asset","digest":"sha256:00"}]}"""

    private val entry = AppEntry(name = "x", type = SourceType.GITHUB, source = "o/r")

    @Test
    fun latestReleaseIsTriedFirstWithChecksumDetails() = runBlocking {
        server.enqueue(MockResponse().setBody(release("v2.0")))
        val m = GitHubClient.findLatest(entry, token = null, supportedAbis = listOf("arm64-v8a"))
        assertEquals("v2.0", m.release.tagName)
        assertEquals("sha256:00", m.asset.digest)
        assertEquals("https://api/app.apk", m.asset.apiUrl)
        assertTrue(server.takeRequest().path!!.endsWith("/repos/o/r/releases/latest"))
    }

    @Test
    fun expiredTokenFallsBackToAnonymous() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.getHeader("Authorization") != null) MockResponse().setResponseCode(401)
                else MockResponse().setBody(release("v3.0"))
        }
        val m = GitHubClient.findLatest(entry, token = "old-token", supportedAbis = listOf("arm64-v8a"))
        assertEquals("v3.0", m.release.tagName)
        assertTrue(m.tokenRejected)
    }

    @Test
    fun rateLimitIsReported() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setBody("{\"message\":\"API rate limit exceeded\"}")
        }
        try {
            GitHubClient.findLatest(entry, null, listOf("arm64-v8a"))
            fail("expected rate limit")
        } catch (e: RateLimitException) {
            assertTrue(e.message!!.contains("limit"))
        }
    }

    @Test
    fun unchangedAnswersUseTheCache() = runBlocking {
        val e = entry.copy(source = "o/cached", includePrereleases = true) // Uses the list endpoint.
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("[${release("v1.0")}]"))
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals("v1.0", GitHubClient.findLatest(e, null, listOf("x86")).release.tagName)
        assertEquals("v1.0", GitHubClient.findLatest(e, null, listOf("x86")).release.tagName)
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun unreachableWebsiteIsAnErrorNotAChange() = runBlocking {
        val url = server.url("/file.apk").toString()
        server.shutdown()
        try {
            RemoteProbe.fingerprint(url)
            fail("an unreachable site must throw")
        } catch (e: IOException) {
            // Expected: the check is reported as failed and the previous result is kept.
        }
    }

    @Test
    fun serverErrorsAreErrors() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        try {
            RemoteProbe.fingerprint(server.url("/file.apk").toString())
            fail("a server error must throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    @Test
    fun fileEndIsFetchedByPosition() = runBlocking {
        val body = ByteArray(300_000) { (it % 97).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val base = MockResponse().setHeader("Content-Type", "application/vnd.android.package-archive").setHeader("ETag", "\"e\"")
                return when {
                    request.method == "HEAD" -> base.setHeader("Content-Length", body.size)
                    range != null && range.startsWith("bytes=-") -> MockResponse().setResponseCode(501) // Like GitHub's storage.
                    range != null -> {
                        val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                        base.setResponseCode(206).setHeader("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                            .setBody(Buffer().write(body.copyOfRange(start, body.size)))
                    }
                    else -> base.setBody(Buffer().write(body))
                }
            }
        }
        val fp = RemoteProbe.fingerprint(server.url("/app.apk").toString())
        assertNotNull(fp?.tailHash)
        assertEquals(300_000L, fp?.length)
    }

    private fun serveFile(bytes: ByteArray) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(bytes))
        }
    }

    private val fakeApk = "PK".toByteArray() + ByteArray(100) { 7 }

    @Test
    fun downloadsAreCheckedAgainstPublishedChecksum() = runBlocking {
        serveFile(fakeApk)
        val good = MessageDigest.getInstance("SHA-256").digest(fakeApk).joinToString("") { "%02x".format(it) }
        val ok = ApkDownloader.download(server.url("/a.apk").toString(), dir, "ok", expected = ExpectedFile(fakeApk.size.toLong(), "sha256:$good")) {}
        assertTrue(ok.file.exists())
        try {
            ApkDownloader.download(server.url("/a.apk").toString(), dir, "bad", expected = ExpectedFile(null, "sha256:" + "0".repeat(64))) {}
            fail("checksum mismatch must fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("doesn't match"))
        }
        try {
            ApkDownloader.download(server.url("/a.apk").toString(), dir, "short", expected = ExpectedFile(fakeApk.size + 10L, null)) {}
            fail("size mismatch must fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
        // No temporary files are left behind.
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun securePagesDontLeadToInsecureDownloads() {
        val html = """<a href="http://evil.example/app.apk">x</a> <a href="/files/app.apk">y</a>"""
        assertEquals("https://site.example/files/app.apk", ApkDownloader.findApkLink(html, "https://site.example/page"))
        assertEquals(null, ApkDownloader.findApkLink("""<a href="http://evil.example/app.apk">x</a>""", "https://site.example/page"))
    }
}
