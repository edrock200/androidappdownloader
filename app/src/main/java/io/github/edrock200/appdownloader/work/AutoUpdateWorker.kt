package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.edrock200.appdownloader.app
import io.github.edrock200.appdownloader.data.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs at the user's scheduled time and updates every app that has auto-update switched on. */
class AutoUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val app = applicationContext.app()
        val settings = app.settings.current
        // Turned off after this run was queued. (A retry carries on: a one-time schedule is already
        // cleared by then, and turning the schedule off cancels pending retries.)
        if (!settings.hasSchedule && runAttemptCount == 0) return@withContext Result.success()

        // Another update (e.g. "Update all" in the app) is running: try again shortly without
        // using up the schedule.
        if (app.controller.updatingAll.value) {
            return@withContext if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success().also { advance(app) }
        }

        // Book the next run first, so a crash or the system stopping this job can't end the schedule.
        advance(app)

        val summary = app.controller.runScheduledUpdate()
            ?: return@withContext if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()

        app.settings.update(
            app.settings.current.copy(lastRunAt = System.currentTimeMillis(), lastRunSummary = summary.describe())
        )
        Notifications.showWaitingUpdates(applicationContext, app.controller.waitingUpdates())
        Notifications.showUpdateFailures(applicationContext, summary.failed)
        // Every failure was the network: try again soon (WorkManager waits for a connection).
        if (summary.onlyConnectivityFailures && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    /** Moves the schedule to its next run (idempotent: a future run time is kept as it is). */
    private fun advance(app: io.github.edrock200.appdownloader.App) {
        val settings = app.settings.current
        if (!settings.hasSchedule || settings.scheduleAt > System.currentTimeMillis()) return
        val now = System.currentTimeMillis()
        val next = Schedule.nextLocal(settings.scheduleMinuteOfDay, settings.scheduleWeekday, settings.scheduleRepeat, now)
            ?: Schedule.nextRun(settings.scheduleAt, settings.scheduleRepeat, now)
        val updated = settings.copy(scheduleAt = next ?: 0)
        app.settings.update(updated)
        AutoUpdateScheduler.apply(applicationContext, updated, fromWorker = true)
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
