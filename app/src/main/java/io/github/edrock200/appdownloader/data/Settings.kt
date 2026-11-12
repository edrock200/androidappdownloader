package io.github.edrock200.appdownloader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val githubToken: String = "",
    /** 0 disables background update checks. */
    val checkIntervalHours: Int = 12,
    val deleteApkAfterInstall: Boolean = true,
    /** Check this app's own GitHub releases when it is opened. */
    val checkSelfUpdates: Boolean = true,
    /** Next scheduled "update all" run (epoch millis), or 0 when none is scheduled. */
    val scheduleAt: Long = 0,
    val scheduleRepeat: Repeat = Repeat.ONCE,
    /** Scheduled / "update all" runs skip links whose file the server says hasn't changed. */
    val updateOnlyIfChanged: Boolean = true,
    /** Local time of day (minutes after midnight) and weekday for daily/weekly schedules, so they follow time-zone changes. */
    val scheduleMinuteOfDay: Int = -1,
    val scheduleWeekday: Int = 0,
    /** Scheduled downloads only on Wi-Fi / unmetered networks. */
    val wifiOnly: Boolean = false,
    /** Allow scheduled automatic updates from unencrypted (http) links. */
    val allowInsecureAutoUpdates: Boolean = false,
    /** Result of the last scheduled run, shown in Settings (TVs rarely show notifications). */
    val lastRunAt: Long = 0,
    val lastRunSummary: String = "",
) {
    val hasSchedule get() = scheduleAt > 0
}

/** How a scheduled update repeats. [hours] > 0 means "every N hours throughout the day". */
enum class Repeat(val label: String, val hours: Int = 0) {
    ONCE("Once"),
    DAILY("Every day"),
    WEEKLY("Every week"),
    EVERY_1H("Every hour", 1),
    EVERY_2H("Every 2 hours", 2),
    EVERY_4H("Every 4 hours", 4),
    EVERY_8H("Every 8 hours", 8),
    EVERY_12H("Every 12 hours", 12),
    ;

    val isHourly get() = hours > 0
}

class Settings(context: Context, private val defaultWifiOnly: Boolean = false) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val current: SettingsState get() = _state.value

    fun update(newState: SettingsState) {
        prefs.edit()
            .putString(KEY_TOKEN, newState.githubToken.trim())
            .putInt(KEY_INTERVAL, newState.checkIntervalHours)
            .putBoolean(KEY_DELETE, newState.deleteApkAfterInstall)
            .putBoolean(KEY_SELF_UPDATE, newState.checkSelfUpdates)
            .putLong(KEY_SCHEDULE_AT, newState.scheduleAt)
            .putString(KEY_SCHEDULE_REPEAT, newState.scheduleRepeat.name)
            .putBoolean(KEY_ONLY_IF_CHANGED, newState.updateOnlyIfChanged)
            .putInt(KEY_SCHEDULE_MINUTE, newState.scheduleMinuteOfDay)
            .putInt(KEY_SCHEDULE_WEEKDAY, newState.scheduleWeekday)
            .putBoolean(KEY_WIFI_ONLY, newState.wifiOnly)
            .putBoolean(KEY_ALLOW_INSECURE, newState.allowInsecureAutoUpdates)
            .putLong(KEY_LAST_RUN_AT, newState.lastRunAt)
            .putString(KEY_LAST_RUN_SUMMARY, newState.lastRunSummary)
            .apply()
        _state.value = newState.copy(githubToken = newState.githubToken.trim())
    }

    private fun read() = SettingsState(
        githubToken = prefs.getString(KEY_TOKEN, "") ?: "",
        checkIntervalHours = prefs.getInt(KEY_INTERVAL, 12),
        deleteApkAfterInstall = prefs.getBoolean(KEY_DELETE, true),
        checkSelfUpdates = prefs.getBoolean(KEY_SELF_UPDATE, true),
        scheduleAt = prefs.getLong(KEY_SCHEDULE_AT, 0),
        scheduleRepeat = runCatching { Repeat.valueOf(prefs.getString(KEY_SCHEDULE_REPEAT, null)!!) }
            .getOrDefault(Repeat.ONCE),
        updateOnlyIfChanged = prefs.getBoolean(KEY_ONLY_IF_CHANGED, true),
        scheduleMinuteOfDay = prefs.getInt(KEY_SCHEDULE_MINUTE, -1),
        scheduleWeekday = prefs.getInt(KEY_SCHEDULE_WEEKDAY, 0),
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, defaultWifiOnly),
        allowInsecureAutoUpdates = prefs.getBoolean(KEY_ALLOW_INSECURE, false),
        lastRunAt = prefs.getLong(KEY_LAST_RUN_AT, 0),
        lastRunSummary = prefs.getString(KEY_LAST_RUN_SUMMARY, "") ?: "",
    )

    companion object {
        private const val KEY_TOKEN = "github_token"
        private const val KEY_INTERVAL = "check_interval_hours"
        private const val KEY_DELETE = "delete_after_install"
        private const val KEY_SELF_UPDATE = "check_self_updates"
        private const val KEY_SCHEDULE_AT = "schedule_at"
        private const val KEY_SCHEDULE_REPEAT = "schedule_repeat"
        private const val KEY_ONLY_IF_CHANGED = "update_only_if_changed"
        private const val KEY_SCHEDULE_MINUTE = "schedule_minute_of_day"
        private const val KEY_SCHEDULE_WEEKDAY = "schedule_weekday"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_ALLOW_INSECURE = "allow_insecure_auto_updates"
        private const val KEY_LAST_RUN_AT = "last_run_at"
        private const val KEY_LAST_RUN_SUMMARY = "last_run_summary"
        val INTERVAL_OPTIONS = listOf(0, 3, 6, 12, 24)
    }
}
