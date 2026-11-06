package io.github.edrock200.appdownloader.data

import android.content.Context
import kotlinx.serialization.Serializable
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
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    val hasSchedule get() = scheduleAt > 0
}

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
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

class Settings(context: Context) {
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
            .putString(KEY_THEME_MODE, newState.themeMode.name)
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
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null)!!) }
            .getOrDefault(ThemeMode.SYSTEM),
    )

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_INTERVAL = "check_interval_hours"
        private const val KEY_DELETE = "delete_after_install"
        private const val KEY_SELF_UPDATE = "check_self_updates"
        private const val KEY_SCHEDULE_AT = "schedule_at"
        private const val KEY_SCHEDULE_REPEAT = "schedule_repeat"
        private const val KEY_ONLY_IF_CHANGED = "update_only_if_changed"
        val INTERVAL_OPTIONS = listOf(0, 3, 6, 12, 24)
    }
}
