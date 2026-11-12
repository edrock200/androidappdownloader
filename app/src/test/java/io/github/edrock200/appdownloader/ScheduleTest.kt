package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.Schedule
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class ScheduleTest {

    private val ny = TimeZone.getTimeZone("America/New_York")
    private fun t(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = ny }.parse(s)!!.time
    private fun f(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = ny }.format(ms)

    @Test
    fun futureTimeIsKept() {
        assertEquals(t("2026-11-06 03:00"), Schedule.nextRun(t("2026-11-06 03:00"), Repeat.ONCE, t("2026-11-05 12:00"), ny))
    }

    @Test
    fun pastOneTimeScheduleEnds() {
        assertNull(Schedule.nextRun(t("2026-11-05 03:00"), Repeat.ONCE, t("2026-11-05 03:00"), ny))
    }

    @Test
    fun repeatsMoveForward() {
        val now = t("2026-11-05 12:00")
        assertEquals("2026-11-06 03:00", f(Schedule.nextRun(t("2026-11-05 03:00"), Repeat.DAILY, now, ny)!!))
        assertEquals("2026-11-12 03:00", f(Schedule.nextRun(t("2026-11-05 03:00"), Repeat.WEEKLY, now, ny)!!))
        // Several missed runs (device was off) skip straight to the next one.
        assertEquals("2026-11-06 03:00", f(Schedule.nextRun(t("2026-10-20 03:00"), Repeat.DAILY, now, ny)!!))
    }

    @Test
    fun keepsWallClockTimeAcrossDaylightSavingChange() {
        // US clocks go back on 2026-11-01; a daily 3:00 run must stay at 3:00 local time.
        assertEquals("2026-11-01 03:00", f(Schedule.nextRun(t("2026-10-31 03:00"), Repeat.DAILY, t("2026-10-31 04:00"), ny)!!))
    }

    @Test
    fun hourlyRepeats() {
        val start = t("2026-11-05 03:00")
        assertEquals("2026-11-05 04:00", f(Schedule.nextRun(start, Repeat.EVERY_1H, t("2026-11-05 03:00"), ny)!!))
        assertEquals("2026-11-05 13:00", f(Schedule.nextRun(start, Repeat.EVERY_2H, t("2026-11-05 12:10"), ny)!!))
        assertEquals("2026-11-05 15:00", f(Schedule.nextRun(start, Repeat.EVERY_4H, t("2026-11-05 12:10"), ny)!!))
        assertEquals("2026-11-05 19:00", f(Schedule.nextRun(start, Repeat.EVERY_8H, t("2026-11-05 12:10"), ny)!!))
        assertEquals("2026-11-05 15:00", f(Schedule.nextRun(start, Repeat.EVERY_12H, t("2026-11-05 12:10"), ny)!!))
        // A start time still in the future is used as is.
        assertEquals(start, Schedule.nextRun(start, Repeat.EVERY_4H, t("2026-11-05 01:00"), ny))
    }

    @Test
    fun hourlyRunsAreEvenlySpacedAcrossDaylightSavingChange() {
        // Clocks go back at 2:00 on 2026-11-01, so 01:00-02:00 happens twice; runs stay one real hour apart.
        val start = t("2026-11-01 00:00")
        val next = Schedule.nextRun(start, Repeat.EVERY_1H, start, ny)!!
        val after = Schedule.nextRun(start, Repeat.EVERY_1H, next, ny)!!
        assertEquals(3_600_000L, next - start)
        assertEquals(3_600_000L, after - next)
    }

    @Test
    fun dayOffsets() {
        val now = t("2026-11-05 23:30") // Thursday
        assertEquals(0, Schedule.daysFromToday(t("2026-11-05 03:00"), now, ny))
        assertEquals(1, Schedule.daysFromToday(t("2026-11-06 00:10"), now, ny))
        assertEquals(6, Schedule.daysFromToday(t("2026-11-11 03:00"), now, ny))
        assertEquals(0, Schedule.daysFromToday(t("2026-11-01 03:00"), now, ny)) // Past dates count as today.
        assertEquals("2026-11-07 23:30", f(Schedule.addDays(now, 2, ny)))
    }

    @Test
    fun atTimeAndDefault() {
        assertEquals("2026-11-07 22:45", f(Schedule.atTime(t("2026-11-07 09:13"), 22, 45, ny)))
        assertEquals("2026-11-06 03:00", f(Schedule.defaultTime(t("2026-11-05 12:00"), ny)))
        assertEquals("2026-11-05 03:00", f(Schedule.defaultTime(t("2026-11-05 01:00"), ny)))
    }

    @Test
    fun summariesReadNaturally() {
        assertEquals(
            "Updated 2, 1 waiting to install, 3 up to date, 1 failed (d)",
            UpdateRunSummary(listOf("a", "b"), listOf("c"), emptyList(), 3, listOf("d")).describe(),
        )
        assertEquals("No apps are selected for auto-update", UpdateRunSummary().describe())
    }

    @Test
    fun listsSavedByOlderVersionsStillLoad() {
        val old = """[{"id":"1","name":"Old","type":"URL","source":"https://x/a.apk"}]"""
        val entry = EntryRepository.JSON.decodeFromString(ListSerializer(AppEntry.serializer()), old).single()
        assertTrue(entry.autoUpdate)
        assertNull(entry.readyFile)
    }
}
