package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.net.ChangeDecision
import io.github.edrock200.appdownloader.net.ChangeReason
import io.github.edrock200.appdownloader.net.LocalApk
import io.github.edrock200.appdownloader.net.RemoteChange
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChangeTest {

    private fun fp(etag: String? = null, modified: String? = null, length: Long? = null, path: String? = null) =
        RemoteFingerprint(etag, modified, length, path)

    @Test
    fun readsHeaders() {
        val f = RemoteProbe.fromHeaders(
            contentType = "application/vnd.android.package-archive",
            etag = "W/\"abc\"",
            lastModified = "Tue, 03 Nov 2026 18:00:00 GMT",
            contentLength = "1",
            contentRange = "bytes 0-0/7522359",
            finalUrl = "https://cdn.example.com/files/app.apk?token=one-off",
        )!!
        assertEquals("\"abc\"", f.etag)
        assertEquals(7522359L, f.length) // Full size from Content-Range, not the 1-byte body.
        assertEquals("cdn.example.com/files/app.apk", f.path) // Query string dropped.
    }

    @Test
    fun webPagesGiveNoFingerprint() {
        assertNull(RemoteProbe.fromHeaders("text/html; charset=UTF-8", "\"x\"", null, "100", null, "https://x.com/page"))
    }

    @Test
    fun comparesByEtagAndDate() {
        assertEquals(RemoteChange.UNCHANGED, RemoteProbe.compare(fp(etag = "a", length = 5), fp(etag = "a", length = 5)))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(etag = "a"), fp(etag = "b")))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(modified = "Mon"), fp(modified = "Tue")))
        // Same ETag but a different size: the file changed.
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(etag = "a", length = 5), fp(etag = "a", length = 6)))
        // When an ETag/date is available, a different address (e.g. another mirror) doesn't count.
        assertEquals(RemoteChange.UNCHANGED, RemoteProbe.compare(fp(etag = "a", path = "m1/x"), fp(etag = "a", path = "m2/x")))
    }

    @Test
    fun weakSignalsOnlyProveChange() {
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(length = 5), fp(length = 6)))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(path = "h/app-1.apk"), fp(path = "h/app-2.apk")))
        assertEquals(RemoteChange.UNKNOWN, RemoteProbe.compare(fp(length = 5), fp(length = 5)))
        assertEquals(RemoteChange.UNKNOWN, RemoteProbe.compare(null, fp(etag = "a")))
        assertEquals(RemoteChange.UNKNOWN, RemoteProbe.compare(fp(etag = "a"), null))
    }

    @Test
    fun fileTailIsTheStrongestSignal() {
        // Same file (same tail) even though a CDN reports a different ETag.
        assertEquals(RemoteChange.UNCHANGED, RemoteProbe.compare(
            RemoteFingerprint(etag = "a", length = 9, tailHash = "t1"), RemoteFingerprint(etag = "b", length = 9, tailHash = "t1")))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(
            RemoteFingerprint(etag = "a", tailHash = "t1"), RemoteFingerprint(etag = "a", tailHash = "t2")))
    }

    private val date = "Sun, 08 Nov 2026 03:08:43 GMT"
    private val dateMs = RemoteProbe.parseHttpDate(date)!!

    @Test
    fun parsesHttpDates() {
        assertEquals(1794107323000L, dateMs)
        assertNull(RemoteProbe.parseHttpDate("not a date"))
    }

    @Test
    fun decidesFromTheInstalledCopyWithoutSavedDetails() {
        val remote = RemoteFingerprint(etag = "x", length = 100, tailHash = "abc")
        assertEquals(ChangeDecision(RemoteChange.UNCHANGED, ChangeReason.CONTENT, remote),
            RemoteProbe.decide(null, remote, LocalApk(100, "abc"), 0))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.decide(null, remote, LocalApk(100, "zzz"), 0).change)
        assertEquals(RemoteChange.CHANGED, RemoteProbe.decide(null, remote, LocalApk(99, "abc"), 0).change)
    }

    @Test
    fun fallsBackToSavedDetailsThenDates() {
        val saved = RemoteFingerprint(etag = "a")
        assertEquals(ChangeReason.SAVED_DETAILS, RemoteProbe.decide(saved, RemoteFingerprint(etag = "b"), null, 0).reason)
        assertEquals(RemoteChange.CHANGED, RemoteProbe.decide(saved, RemoteFingerprint(etag = "b"), null, 0).change)

        // No saved details and no comparable copy: the website's date versus the install time.
        val remote = RemoteFingerprint(lastModified = date, length = 5)
        val newer = RemoteProbe.decide(null, remote, null, installedAt = dateMs - 3_600_000)
        assertEquals(RemoteChange.CHANGED to ChangeReason.DATE, newer.change to newer.reason)
        val older = RemoteProbe.decide(null, remote, null, installedAt = dateMs + 3_600_000)
        assertEquals(RemoteChange.UNCHANGED to ChangeReason.DATE, older.change to older.reason)
        assertEquals(remote, older.newBaseline) // Later checks can compare against it.
        // Within a couple of minutes counts as the same file (clock differences).
        assertEquals(RemoteChange.UNCHANGED, RemoteProbe.decide(null, remote, null, installedAt = dateMs - 60_000).change)
    }

    @Test
    fun explainsWhyItCantTell() {
        assertEquals(ChangeReason.NO_DETAILS, RemoteProbe.decide(null, null, null, 0).reason)
        assertEquals(ChangeReason.NO_DETAILS, RemoteProbe.decide(null, RemoteFingerprint(length = 5), null, 0).reason)
        // The website gives details but there's nothing to compare them with (no install time either).
        val r = RemoteProbe.decide(null, RemoteFingerprint(etag = "a"), null, 0)
        assertEquals(RemoteChange.UNKNOWN to ChangeReason.NO_BASELINE, r.change to r.reason)
    }

    @Test
    fun localApkHashesTheFileEnd() {
        val f = java.io.File.createTempFile("apk", ".bin").apply { deleteOnExit() }
        f.writeBytes(ByteArray(RemoteProbe.TAIL_BYTES + 10) { (it % 251).toByte() })
        val a = RemoteProbe.localApk(f)!!
        assertEquals(RemoteProbe.TAIL_BYTES + 10L, a.length)
        f.writeBytes(f.readBytes().also { it[0] = 99 }) // Change before the tail: tail hash unchanged.
        assertEquals(a.tailHash, RemoteProbe.localApk(f)!!.tailHash)
        f.writeBytes(f.readBytes().also { it[it.size - 1] = 99 }) // Change inside the tail.
        assertTrue(a.tailHash != RemoteProbe.localApk(f)!!.tailHash)
    }

    @Test
    fun linkUpdateNeedsInstalledAppAndChange() {
        val e = AppEntry(name = "x", type = SourceType.URL, source = "https://x/a.apk", remoteChange = RemoteChange.CHANGED)
        assertTrue(VersionUtils.linkChanged(e, 5))
        assertFalse(VersionUtils.linkChanged(e, null))
        assertFalse(VersionUtils.linkChanged(e.copy(remoteChange = RemoteChange.UNKNOWN), 5))
        assertTrue(VersionUtils.updateAvailable(e, "1.0", 5))
    }
}
