package io.github.edrock200.appdownloader.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.resume

/** Installs APKs and split bundles through the PackageInstaller session API. */
object ApkInstaller {

    const val EXTRA_ENTRY_ID = "entry_id"
    const val EXTRA_TAG = "release_tag"
    const val EXTRA_VERSION_CODE = "version_code"
    const val EXTRA_FILE = "file"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_LABEL = "label"
    /** Set for scheduled runs: don't pop up the confirmation screen, leave the update waiting instead. */
    const val EXTRA_BACKGROUND = "background"
    /** Version on the device before this install (for the update history); absent if not installed. */
    const val EXTRA_PREV_VERSION_NAME = "prev_version_name"
    const val EXTRA_PREV_VERSION_CODE = "prev_version_code"

    /** PackageInstaller.STATUS_FAILURE_TIMEOUT (Android 14): install constraints weren't met in time. */
    const val STATUS_FAILURE_TIMEOUT = 8

    /** How long a background update on Android 14+ may wait for the app to stop being used. */
    private const val GENTLE_TIMEOUT_MS = 6 * 60 * 60 * 1000L

    /**
     * Abandons earlier sessions for [packageName] that were never confirmed. Returns their ids so
     * their "aborted" results can be ignored.
     */
    fun abandonOldSessions(context: Context, packageName: String): List<Int> {
        val installer = context.packageManager.packageInstaller
        return installer.mySessions.filter { it.appPackageName == packageName }.mapNotNull {
            runCatching { installer.abandonSession(it.sessionId) }.map { _ -> it.sessionId }.getOrNull()
        }
    }

    /** Starts the install and returns the session id; the result arrives in [InstallResultReceiver]. */
    fun install(
        context: Context,
        file: File,
        info: ApkInfo,
        entryId: String,
        releaseTag: String?,
        background: Boolean = false,
        previousVersionName: String? = null,
        previousVersionCode: Long? = null,
        label: String? = null,
    ): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(info.packageName)
            if (!info.isSplit) setSize(file.length()) // Lets Android check for space before copying.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets updates of apps we installed proceed without a prompt where allowed.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            if (info.isSplit) {
                ZipFile(file).use { zip ->
                    info.splitEntries.forEachIndexed { index, name ->
                        val entry = zip.getEntry(name)
                        val splitName = "${index}_" + name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                        session.openWrite(splitName, 0, entry.size).use { out ->
                            zip.getInputStream(entry).use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                }
            } else {
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }

            val callback = Intent(context, InstallResultReceiver::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_TAG, releaseTag)
                .putExtra(EXTRA_VERSION_CODE, info.versionCode)
                .putExtra(EXTRA_FILE, file.absolutePath)
                .putExtra(EXTRA_PACKAGE, info.packageName)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_BACKGROUND, background)
                .putExtra(EXTRA_PREV_VERSION_NAME, previousVersionName)
                .putExtra(EXTRA_PREV_VERSION_CODE, previousVersionCode ?: -1L)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            if (background && Build.VERSION.SDK_INT >= 34) {
                // Wait until the app isn't being used, so a background update doesn't close it mid-use.
                session.close()
                commitGently(installer, sessionId, pending)
            } else {
                session.commit(pending.intentSender)
                session.close()
            }
            return sessionId
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    @RequiresApi(34)
    private fun commitGently(installer: PackageInstaller, sessionId: Int, pending: PendingIntent) {
        installer.commitSessionAfterInstallConstraintsAreMet(
            sessionId,
            pending.intentSender,
            PackageInstaller.InstallConstraints.GENTLE_UPDATE,
            GENTLE_TIMEOUT_MS,
        )
    }

    /** Android 14+: whether [packageName] could be updated right now without interrupting the user. */
    suspend fun gentleUpdatePossible(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return runCatching { checkGentle(context, packageName) }.getOrDefault(true)
    }

    @RequiresApi(34)
    private suspend fun checkGentle(context: Context, packageName: String): Boolean = suspendCancellableCoroutine { cont ->
        context.packageManager.packageInstaller.checkInstallConstraints(
            listOf(packageName),
            PackageInstaller.InstallConstraints.GENTLE_UPDATE,
            context.mainExecutor,
        ) { result -> if (cont.isActive) cont.resume(result.areAllConstraintsSatisfied()) }
    }
}
