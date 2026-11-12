package io.github.edrock200.appdownloader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Small helpers that hand off to the system or other apps. */
object SystemActions {

    /**
     * Android 8+: the per-app "Install unknown apps" permission. Older versions (old Fire TVs) have
     * one system-wide "Unknown sources" switch; if it can't be read, assume installs are allowed.
     */
    fun canInstallPackages(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        @Suppress("DEPRECATION")
        runCatching { Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1 }
            .getOrDefault(true)
    }

    /** Opens the "Install unknown apps" screen for this app (or the closest available settings page). */
    fun openInstallPermissionSettings(context: Context): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        return intents.any { start(context, it) }
    }

    fun launchApp(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: return false
        return start(context, intent)
    }

    fun canLaunch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return pm.getLaunchIntentForPackage(packageName) != null ||
            pm.getLeanbackLaunchIntentForPackage(packageName) != null
    }

    @Suppress("DEPRECATION")
    fun uninstall(context: Context, packageName: String): Boolean =
        start(context, Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))

    /** Many TV devices have no web browser installed. */
    fun hasBrowser(context: Context): Boolean =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).resolveActivity(context.packageManager) != null

    fun openUrl(context: Context, url: String): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
