package io.github.edrock200.appdownloader.data

import java.util.Calendar
import java.util.TimeZone

/** Date/time arithmetic for scheduled updates (kept free of Android APIs for unit tests). */
object Schedule {

    /**
     * Returns the first run time that is after [now]: [at] itself if it is still in the
     * future, otherwise [at] moved forward by whole days/weeks. Returns null for a
     * one-time schedule that has already passed. Wall-clock time is kept across DST changes.
     */
    fun nextRun(at: Long, repeat: Repeat, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (at > now) return at
        if (repeat.isHourly) {
            // Fixed intervals of real time, so a DST change doesn't skip or double a run.
            val interval = repeat.hours * 3_600_000L
            return at + ((now - at) / interval + 1) * interval
        }
        val field = when (repeat) {
            Repeat.DAILY -> Calendar.DAY_OF_YEAR
            Repeat.WEEKLY -> Calendar.WEEK_OF_YEAR
            else -> return null
        }
        val cal = Calendar.getInstance(zone).apply { timeInMillis = at }
        while (cal.timeInMillis <= now) cal.add(field, 1)
        return cal.timeInMillis
    }

    /** Midnight-relative helper: the given date (any time on that day) at [hour]:[minute]. */
    fun atTime(day: Long, hour: Int, minute: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** [day] moved by [days] calendar days (wall-clock safe). */
    fun addDays(day: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, days) }.timeInMillis

    /** Whole calendar days from the day of [now] to the day of [target] (0 = same day). */
    fun daysFromToday(target: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        var day = atTime(now, 12, 0, zone)
        val goal = atTime(target, 12, 0, zone)
        var n = 0
        if (goal < day) return 0
        while (day < goal && n < 366) {
            day = addDays(day, 1, zone)
            n++
        }
        return n
    }

    /** Local minutes after midnight and day of week (Calendar.DAY_OF_WEEK) of [at]. */
    fun localTimeOf(at: Long, zone: TimeZone = TimeZone.getDefault()): Pair<Int, Int> {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = at }
        return (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) to cal.get(Calendar.DAY_OF_WEEK)
    }

    /**
     * Next run of a daily/weekly schedule from its local wall-clock time, so it stays at e.g. 3:00
     * after a time-zone change. Returns null for other repeat types or when no local time is stored.
     */
    fun nextLocal(minuteOfDay: Int, weekday: Int, repeat: Repeat, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (minuteOfDay < 0 || (repeat != Repeat.DAILY && repeat != Repeat.WEEKLY)) return null
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (repeat == Repeat.WEEKLY && weekday in Calendar.SUNDAY..Calendar.SATURDAY) {
            while (cal.get(Calendar.DAY_OF_WEEK) != weekday) cal.add(Calendar.DAY_OF_YEAR, 1)
            if (cal.timeInMillis <= now) cal.add(Calendar.WEEK_OF_YEAR, 1)
        } else {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Default suggestion: the next 3:00 AM. */
    fun defaultTime(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val today = atTime(now, 3, 0, zone)
        return nextRun(today, Repeat.DAILY, now, zone)!!
    }
}
