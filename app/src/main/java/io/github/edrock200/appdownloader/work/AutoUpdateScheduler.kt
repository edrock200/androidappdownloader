package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.edrock200.appdownloader.data.SettingsState
import java.util.concurrent.TimeUnit

/** Schedules the user's "update all apps" run for the date and time set in Settings. */
object AutoUpdateScheduler {
    private const val WORK_NAME = "scheduled-update-all"

    /** Network (Wi-Fi only if chosen), battery and storage conditions for scheduled downloads. */
    fun constraints(state: SettingsState): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (state.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    /**
     * Enqueues (or cancels) the next run. [fromWorker] is true when called by the run that is in
     * progress, so the next one is queued behind it instead of cancelling it. [keepExisting] only
     * re-creates a missing job (used at app start, e.g. after a device transfer).
     */
    fun apply(context: Context, state: SettingsState, fromWorker: Boolean = false, keepExisting: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!state.hasSchedule) {
            if (!fromWorker) wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = (state.scheduleAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(state))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        val policy = when {
            fromWorker -> ExistingWorkPolicy.APPEND_OR_REPLACE
            keepExisting -> ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }
        wm.enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
