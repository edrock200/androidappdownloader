package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.Backup
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.ThemeMode
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.net.RemoteChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {

    private val now = 1_800_000_000_000L
    private val entries = listOf(
        AppEntry(name = "Kodi", type = SourceType.AFTV_CODE, source = "12345", packageName = "org.xbmc.kodi",
            autoUpdate = false, remoteChange = RemoteChange.CHANGED, readyFile = "/cache/x.apk"),
        AppEntry(name = "NewPipe", type = SourceType.GITHUB, source = "TeamNewPipe/NewPipe", assetFilter = "universal",
            includePrereleases = true, latestTag = "v1.0", installedTag = "v0.9"),
    )
    private val settings = SettingsState(githubToken = "ghp_secret", checkIntervalHours = 6, scheduleAt = now + 3_600_000, scheduleRepeat = Repeat.EVERY_4H)

    @Test
    fun fileRoundTripKeepsWhatTheUserSetButNotDeviceState() {
        val json = Backup.toJson(Backup.create(entries, settings, includeToken = false, appVersion = "1.5.0", now = now))
        val back = Backup.parse(json)
        assertEquals(2, back.apps.size)
        assertEquals("12345", back.apps[0].source)
        assertEquals(false, back.apps[0].autoUpdate)
        assertEquals("universal", back.apps[1].assetFilter)
        assertNull(back.settings.githubToken) // Not included unless asked.
        assertEquals(Repeat.EVERY_4H, back.settings.scheduleRepeat)
        assertTrue("download state must not be saved", !json.contains("readyFile") && !json.contains("installedTag"))
    }

    @Test
    fun themeSurvivesFileAndQrBackupAndRestore() {
        for (mode in ThemeMode.entries) {
            val backup = Backup.create(entries, settings.copy(themeMode = mode), false, null, now)
            val file = Backup.parse(Backup.toJson(backup))
            val qr = Backup.parse(Backup.toQrText(backup)!!)
            for (parsed in listOf(file, qr)) {
                assertEquals(mode, parsed.settings.themeMode)
                assertEquals(mode, Backup.restoreSettings(SettingsState(), parsed.settings, now).themeMode)
            }
        }
    }

    @Test
    fun olderBackupDefaultsToSystemTheme() {
        val backup = Backup.parse("""{"format":"app-downloader-backup","settings":{}}""")
        assertEquals(ThemeMode.SYSTEM, backup.settings.themeMode)
    }

    @Test
    fun tokenIncludedOnlyWhenAsked() {
        val back = Backup.parse(Backup.toJson(Backup.create(entries, settings, includeToken = true, appVersion = null, now = now)))
        assertEquals("ghp_secret", back.settings.githubToken)
    }

    @Test
    fun qrRoundTrip() {
        val text = Backup.toQrText(Backup.create(entries, settings, includeToken = false, appVersion = "1.5.0", now = now))!!
        assertTrue(text.startsWith(Backup.QR_PREFIX))
        val back = Backup.parse(text)
        assertEquals(listOf("Kodi", "NewPipe"), back.apps.map { it.name })
    }

    @Test
    fun aTypicalListFitsInOneQrCode() {
        val many = (1..40).map {
            AppEntry(name = "Some App $it", type = SourceType.URL, source = "https://downloads.example.com/apps/some-app-$it-release.apk", packageName = "com.example.app$it")
        }
        val text = Backup.toQrText(Backup.create(many, SettingsState(), includeToken = false, appVersion = "1.5.0", now = now))
        assertNotNull("40 apps should fit in a QR code", text)
    }

    @Test
    fun rejectsOtherFiles() {
        for (bad in listOf("{}", "not json", "{\"format\":\"something-else\"}", "ADBK1:!!!notbase64")) {
            try {
                Backup.parse(bad)
                fail("Should reject: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("backup"))
            }
        }
        try {
            Backup.parse("""{"format":"app-downloader-backup","version":2}""")
            fail("Should reject newer versions")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("newer"))
        }
    }

    @Test
    fun mergeSkipsDuplicatesAndReplaceStartsFresh() {
        val backup = Backup.create(entries, settings, includeToken = false, appVersion = null, now = now)
        val mine = listOf(AppEntry(name = "Mine", type = SourceType.URL, source = "https://x/a.apk"), entries[0])

        val merged = Backup.restoreApps(mine, backup, replace = false)
        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)
        assertEquals(listOf("Mine", "Kodi", "NewPipe"), merged.entries.map { it.name })

        val replaced = Backup.restoreApps(mine, backup, replace = true)
        assertEquals(listOf("Kodi", "NewPipe"), replaced.entries.map { it.name })
        // An app that was already here keeps its id (and download history) when replacing.
        assertEquals(entries[0].id, replaced.entries[0].id)
        // A new app starts without release details; they are re-checked on this device.
        assertNull(Backup.restoreApps(emptyList(), backup, replace = false).entries[1].latestTag)
    }

    @Test
    fun settingsRestoreKeepsTokenWhenBackupHasNoneAndMovesPastSchedules() {
        val current = SettingsState(githubToken = "mine")
        val backup = Backup.create(entries, settings.copy(scheduleAt = now - 3_600_000 * 5), includeToken = false, appVersion = null, now = now)
        val restored = Backup.restoreSettings(current, backup.settings, now)
        assertEquals("mine", restored.githubToken)
        assertEquals(6, restored.checkIntervalHours)
        assertTrue("schedule moved into the future", restored.scheduleAt > now)

        val once = Backup.create(entries, settings.copy(scheduleAt = now - 1, scheduleRepeat = Repeat.ONCE), false, null, now)
        assertEquals(0L, Backup.restoreSettings(current, once.settings, now).scheduleAt) // A past one-off is dropped.
    }
}
