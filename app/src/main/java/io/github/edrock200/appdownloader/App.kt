package io.github.edrock200.appdownloader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.Schedule
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.net.Http
import io.github.edrock200.appdownloader.ui.Device
import io.github.edrock200.appdownloader.work.AutoUpdateScheduler
import io.github.edrock200.appdownloader.work.Notifications
import io.github.edrock200.appdownloader.work.UpdateScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    /** Last-resort safety net: an unexpected error in background work must not crash the app. */
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Log.e("AppDownloader", "Unexpected error", e)
        if (::controller.isInitialized) controller.message("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)
    lateinit var repository: EntryRepository
        private set
    lateinit var settings: Settings
        private set
    lateinit var controller: AppController
        private set

    /** Number of started activities; > 0 means the app is on screen. */
    @Volatile
    private var startedActivities = 0
    val isForeground get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        val tv = Device.isTv(this)
        repository = EntryRepository(this)
        settings = Settings(this, defaultWifiOnly = !tv)
        controller = AppController(this, repository, settings, scope, isForeground = { isForeground }, isTv = tv)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        Notifications.createChannel(this)
        UpdateScheduler.schedule(this, settings.current.checkIntervalHours, settings.current.wifiOnly)
        refreshSchedule(keepExisting = true)
        controller.cleanCache()
    }

    /**
     * Makes sure the scheduled "update all" job exists (it can be lost, e.g. after moving to a new
     * device) and that daily/weekly runs stay at their local time after a time-zone change.
     */
    fun refreshSchedule(keepExisting: Boolean) {
        val s = settings.current
        if (!s.hasSchedule) return
        val now = System.currentTimeMillis()
        val local = if (s.scheduleRepeat == Repeat.DAILY || s.scheduleRepeat == Repeat.WEEKLY) {
            Schedule.nextLocal(s.scheduleMinuteOfDay, s.scheduleWeekday, s.scheduleRepeat, now)
        } else {
            null
        }
        // Only move a future run whose local time no longer matches (a missed run still runs once).
        val moved = local != null && s.scheduleAt > now && Schedule.localTimeOf(s.scheduleAt).first != s.scheduleMinuteOfDay
        if (moved) {
            val updated = s.copy(scheduleAt = local!!)
            settings.update(updated)
            AutoUpdateScheduler.apply(this, updated)
        } else {
            AutoUpdateScheduler.apply(this, s, keepExisting = keepExisting)
        }
    }
}

fun Context.app(): App = applicationContext as App
