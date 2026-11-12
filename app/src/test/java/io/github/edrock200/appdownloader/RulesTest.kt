package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.Schedule
import io.github.edrock200.appdownloader.data.SourceParser
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.net.AssetSelector
import io.github.edrock200.appdownloader.net.ChangeReason
import io.github.edrock200.appdownloader.net.Errors
import io.github.edrock200.appdownloader.net.GhAsset
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.net.InsecureRedirectException
import io.github.edrock200.appdownloader.net.LinkNormalizer
import io.github.edrock200.appdownloader.net.LocalApk
import io.github.edrock200.appdownloader.net.RemoteChange
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipException

class RulesTest {

    private fun gh(latest: String?, installedTag: String? = null, installedCode: Long? = null, skipped: String? = null) = AppEntry(
        name = "x", type = SourceType.GITHUB, source = "o/r", packageName = "com.x",
        latestTag = latest, latestAssetUrl = latest?.let { "https://x/$it.apk" },
        installedTag = installedTag, installedVersionCode = installedCode, skippedTag = skipped,
    )

    @Test
    fun githubUpdatesOnlyGoForwards() {
        // Release withdrawn: latest is now older than what was installed.
        assertFalse(VersionUtils.hasUpdate(gh("v1.0", "v1.1", 11), "1.1", 11))
        assertTrue(VersionUtils.hasUpdate(gh("v1.2", "v1.1", 11), "1.1", 11))
        // A tag already found to contain an older build isn't offered again.
        assertFalse(VersionUtils.hasUpdate(gh("v9.9", skipped = "v9.9"), "1.0", 5))
        // Non-numeric tags still compare by difference.
        assertTrue(VersionUtils.hasUpdate(gh("nightly-b", "nightly-a", 3), "x", 3))
    }

    @Test
    fun androidRefusesVeryOldApps() {
        assertEquals(0, VersionUtils.minInstallableTargetSdk(33))
        assertEquals(23, VersionUtils.minInstallableTargetSdk(34))
        assertEquals(24, VersionUtils.minInstallableTargetSdk(35))
    }

    private fun assets(vararg names: String) = names.map { GhAsset(it, "https://dl/$it") }

    @Test
    fun assetTieBreakers() {
        val list = assets("app-debug.apk", "app-release.apk")
        assertEquals("app-release.apk", AssetSelector.select(list, null, listOf("arm64-v8a"))?.name)
        val tv = assets("app-phone-universal.apk", "app-tv-universal.apk")
        assertEquals("app-tv-universal.apk", AssetSelector.select(tv, null, listOf("arm64-v8a"), isTv = true)?.name)
        assertEquals("app-phone-universal.apk", AssetSelector.select(tv, null, listOf("arm64-v8a"), isTv = false)?.name)
        // The device ABI still matters most.
        val abi = assets("app-arm64-v8a-debug.apk", "app-x86-release.apk")
        assertEquals("app-arm64-v8a-debug.apk", AssetSelector.select(abi, null, listOf("arm64-v8a"))?.name)
        // A file named like last time wins a tie.
        val same = assets("foo-a-1.2.apk", "foo-b-1.2.apk")
        assertEquals("foo-b-1.2.apk", AssetSelector.select(same, null, listOf("x86"), previousName = "foo-b-1.1.apk")?.name)
    }

    @Test
    fun githubFileLinksAndReservedPages() {
        assertEquals(SourceType.URL, SourceParser.parse("https://github.com/o/r/releases/latest/download/App.apk")?.type)
        assertNull(GitHubClient.parseRepo("https://github.com/settings/tokens"))
        assertNull(GitHubClient.parseRepo("https://github.com/orgs/acme"))
        assertEquals("o/r", GitHubClient.parseRepo("https://github.com/o/r/releases")?.slug)
    }

    @Test
    fun sameSourceRespectsCaseWhereItMatters() {
        assertTrue(SourceParser.sameSource(SourceType.GITHUB, "Owner/Repo", "owner/repo"))
        assertTrue(SourceParser.sameSource(SourceType.URL, "HTTPS://Example.com/a.apk", "https://example.com/a.apk"))
        assertFalse(SourceParser.sameSource(SourceType.URL, "https://bit.ly/AbC", "https://bit.ly/abc"))
        assertFalse(SourceParser.sameSource(SourceType.AFTV_CODE, "123", "1234"))
    }

    @Test
    fun shareLinksBecomeDirectDownloads() {
        assertEquals("https://drive.google.com/uc?export=download&id=AbC_1-2",
            LinkNormalizer.normalize("https://drive.google.com/file/d/AbC_1-2/view?usp=sharing"))
        assertEquals("https://www.dropbox.com/s/x/app.apk?dl=1", LinkNormalizer.normalize("https://www.dropbox.com/s/x/app.apk?dl=0"))
        assertEquals("https://www.dropbox.com/s/x/app.apk?dl=1", LinkNormalizer.normalize("https://www.dropbox.com/s/x/app.apk"))
        assertEquals("https://example.com/a.apk", LinkNormalizer.normalize("https://example.com/a.apk"))
    }

    @Test
    fun friendlyErrors() {
        assertEquals("No internet connection, or the website can't be reached", Errors.friendly(UnknownHostException("api.github.com")))
        assertTrue(Errors.isConnectivity(IOException("wrap", UnknownHostException("x"))))
        assertEquals("Not enough storage space", Errors.friendly(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("The file is damaged or isn't an app", Errors.friendly(ZipException("bad")))
        assertTrue(Errors.friendly(InsecureRedirectException()).contains("unencrypted"))
    }

    @Test
    fun bundleSplitSelection() {
        val xapk = listOf("com.app.apk", "config.arm64_v8a.apk", "config.armeabi_v7a.apk", "config.en.apk")
        // Only the best supported ABI is kept, not every supported one.
        assertEquals(listOf("com.app.apk", "config.arm64_v8a.apk", "config.en.apk"),
            ApkInspector.selectSplits(xapk, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("com.app.apk", ApkInspector.baseSplit(xapk, "com.app"))
        assertEquals("com.app.apk", ApkInspector.baseSplit(listOf("config.en.apk", "com.app.apk")))
        val apks = listOf("splits/base-master.apk", "splits/base-master_2.apk", "splits/base-xxhdpi.apk", "splits/base-xxhdpi_2.apk")
        assertEquals(listOf("splits/base-master.apk", "splits/base-xxhdpi.apk"), ApkInspector.selectSplits(apks, listOf("x86")))
        assertEquals("splits/base-master.apk", ApkInspector.baseSplit(apks))
        assertTrue(ApkInspector.isConfigSplit("splits/base-xxhdpi.apk"))
        assertFalse(ApkInspector.isConfigSplit("splits/feature.apk"))
    }

    @Test
    fun notNewerFilesStayUnstarred() {
        val remote = RemoteFingerprint(etag = "a", length = 10, tailHash = "remote")
        val saved = remote.copy(notNewer = true)
        val d = RemoteProbe.decide(saved, remote, LocalApk(12, "installed-newer"), installedAt = 1)
        assertEquals(RemoteChange.UNCHANGED to ChangeReason.NOT_NEWER, d.change to d.reason)
        // Once the website serves something else, it's a change again.
        val changed = RemoteProbe.decide(saved, remote.copy(tailHash = "brand-new", etag = "b"), LocalApk(12, "installed-newer"), 1)
        assertEquals(RemoteChange.CHANGED, changed.change)
    }

    @Test
    fun notificationKeyUsesStrongestSignal() {
        assertEquals("t:h", RemoteFingerprint(etag = "e", path = "mirror1/x", tailHash = "h").key)
        // A different mirror (path) with the same file doesn't look like a new file.
        assertEquals(RemoteFingerprint(etag = "e", length = 1, path = "m1").key, RemoteFingerprint(etag = "e", length = 1, path = "m2").key)
    }

    @Test
    fun selfUpdatePolicyAndFilter() {
        val now = 10_000_000L
        assertTrue(SelfUpdatePolicy.shouldAutoCheck(true, false, false, busy = false, lastCheckAt = 0, now = now))
        assertFalse(SelfUpdatePolicy.shouldAutoCheck(true, false, false, busy = false, lastCheckAt = now - 1000, now = now))
        assertFalse(SelfUpdatePolicy.shouldAutoCheck(true, false, false, busy = true, lastCheckAt = 0, now = now))
        assertFalse(SelfUpdatePolicy.shouldOffer("1.8.0", "1.8.0", now - 1000, now))
        assertTrue(SelfUpdatePolicy.shouldOffer("1.8.0", "1.8.0", now - SelfUpdatePolicy.SNOOZE_MS, now))
        assertTrue(SelfUpdatePolicy.shouldOffer("1.8.1", "1.8.0", now - 1000, now))
        // The release ships AppDownloader.apk; the debug build must never be picked.
        val release = assets("AppDownloader-debug.apk", "AppDownloader.apk", "notes.txt")
        assertEquals("AppDownloader.apk", AssetSelector.select(release, SELF_ASSET_FILTER, listOf("arm64-v8a"))?.name)
    }

    @Test
    fun versionCodeMatchesVersionName() {
        // CI test builds append "-dev+<commit>" (versionNameSuffix); the code comes from the number part.
        val (a, b, c) = BuildConfig.VERSION_NAME.substringBefore('-').split('.').map { it.toInt() }
        assertEquals(a * 10_000 + b * 100 + c, BuildConfig.VERSION_CODE)
    }

    @Test
    fun localSchedulesFollowTimeZoneChanges() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val la = TimeZone.getTimeZone("America/Los_Angeles")
        fun t(s: String, z: TimeZone) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = z }.parse(s)!!.time
        fun f(ms: Long, z: TimeZone) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = z }.format(ms)
        // 03:00 daily; after moving to Los Angeles it is still 03:00 local time.
        assertEquals("2026-11-06 03:00", f(Schedule.nextLocal(180, 0, Repeat.DAILY, t("2026-11-05 12:00", la), la)!!, la))
        assertEquals("2026-11-05 03:00", f(Schedule.nextLocal(180, 0, Repeat.DAILY, t("2026-11-05 01:00", ny), ny)!!, ny))
        // Weekly on Monday 22:30.
        assertEquals("2026-11-09 22:30", f(Schedule.nextLocal(22 * 60 + 30, Calendar.MONDAY, Repeat.WEEKLY, t("2026-11-05 12:00", ny), ny)!!, ny))
        assertNull(Schedule.nextLocal(180, 0, Repeat.EVERY_4H, 0, ny))
        assertEquals(180 to Calendar.THURSDAY, Schedule.localTimeOf(t("2026-11-05 03:00", ny), ny))
    }

    @Test
    fun rateLimitMessageSaysWhenToRetry() {
        val now = 1_000_000_000_000L
        val msg = GitHubClient.rateLimitMessage(((now / 1000) + 600).toString(), hasToken = false, nowMillis = now)
        assertTrue(msg, msg.contains("11 minutes") && msg.contains("token"))
        assertFalse(GitHubClient.rateLimitMessage(null, hasToken = true).contains("Adding"))
    }
}
