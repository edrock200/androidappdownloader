package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.edrock200.appdownloader.app
import io.github.edrock200.appdownloader.data.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodically checks every app (GitHub releases and link changes) and notifies about updates. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val app = applicationContext.app()
        // The controller's state lives on the main thread; null means a check is already running.
        val result = app.controller.checkAllGuarded() ?: return@withContext Result.success()
        val fresh = result.updates.filter { e ->
            if (e.type == SourceType.GITHUB) e.notifiedTag != e.latestTag
            else e.notifiedChangeKey != e.remoteFingerprint?.key
        }
        // Only remember what was announced if the notification could actually be shown.
        if (fresh.isNotEmpty() && Notifications.showUpdates(applicationContext, result.updates)) {
            fresh.forEach { entry ->
                app.repository.update(entry.id) {
                    it.copy(notifiedTag = it.latestTag, notifiedChangeKey = it.remoteFingerprint?.key)
                }
            }
        }
        Result.success()
    }
}
