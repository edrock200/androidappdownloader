package io.github.edrock200.appdownloader.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import io.github.edrock200.appdownloader.app
import kotlinx.coroutines.Job

/**
 * Receives install session results. Runs on the main thread, where the controller's state lives.
 * Results carry the session id so the controller can ignore results of older attempts.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = context.app().controller
        val entryId = intent.getStringExtra(ApkInstaller.EXTRA_ENTRY_ID) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val background = intent.getBooleanExtra(ApkInstaller.EXTRA_BACKGROUND, false)
        val filePath = intent.getStringExtra(ApkInstaller.EXTRA_FILE)
        val versionCode = intent.getLongExtra(ApkInstaller.EXTRA_VERSION_CODE, -1)
        val tag = intent.getStringExtra(ApkInstaller.EXTRA_TAG)

        // Keep the process alive until the result has been saved.
        val pending = goAsync()
        val job: Job? = try {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (background) {
                        // Nobody is watching a scheduled run: leave the download waiting for the next visit.
                        if (sessionId >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                        controller.onBackgroundInstallNeedsUser(entryId, sessionId, filePath, versionCode, tag)
                    } else {
                        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm == null) {
                            controller.onInstallFailed(entryId, sessionId, status, "Installer did not provide a confirmation screen")
                        } else {
                            // Shown by the activity: Android blocks screens started from the background.
                            controller.onNeedsConfirmation(entryId, sessionId, confirm, filePath, versionCode, tag)
                            null
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> controller.onInstallSucceeded(
                    entryId = entryId,
                    sessionId = sessionId,
                    releaseTag = tag,
                    versionCode = versionCode,
                    filePath = filePath,
                    background = background,
                    previousVersionName = intent.getStringExtra(ApkInstaller.EXTRA_PREV_VERSION_NAME),
                    previousVersionCode = intent.getLongExtra(ApkInstaller.EXTRA_PREV_VERSION_CODE, -1L).takeIf { it >= 0 },
                    packageName = intent.getStringExtra(ApkInstaller.EXTRA_PACKAGE),
                    label = intent.getStringExtra(ApkInstaller.EXTRA_LABEL),
                )
                else -> controller.onInstallFailed(
                    entryId,
                    sessionId,
                    status,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                    background = background,
                    versionCode = versionCode,
                    releaseTag = tag,
                    filePath = filePath,
                )
            }
        } catch (e: Exception) {
            null
        }
        if (job == null) pending.finish() else job.invokeOnCompletion { pending.finish() }
    }
}
