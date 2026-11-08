package io.github.edrock200.appdownloader.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.edrock200.appdownloader.net.AssetSelector
import io.github.edrock200.appdownloader.net.LocalApk
import io.github.edrock200.appdownloader.net.RemoteProbe
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

data class ApkInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    /** Zip entries to install for split bundles (.xapk / .apks); empty for a plain APK. */
    val splitEntries: List<String>,
) {
    val isSplit get() = splitEntries.isNotEmpty()
}

object ApkInspector {

    fun inspect(context: Context, file: File): ApkInfo {
        val splits = ZipFile(file).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") != null) {
                emptyList()
            } else {
                selectSplits(zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList())
                    .ifEmpty { throw IOException("The file is not a valid APK") }
            }
        }
        if (splits.isEmpty()) return readInfo(context, file, emptyList())

        // Read the package details from the base split.
        val baseName = splits.firstOrNull { it.substringAfterLast('/').startsWith("base") } ?: splits.first()
        val tmp = File(context.cacheDir, "inspect-base.apk")
        try {
            ZipFile(file).use { zip ->
                zip.getInputStream(zip.getEntry(baseName)).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
            return readInfo(context, tmp, splits)
        } finally {
            tmp.delete()
        }
    }

    /** Chooses which APKs inside a bundle should be installed on this device. */
    fun selectSplits(names: List<String>, supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()): List<String> {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        // bundletool .apks files may contain both "splits/" and "standalones/" variants.
        val preferred = apks.filter { it.startsWith("splits/") }.ifEmpty {
            apks.filterNot { it.startsWith("standalones/") }
        }.ifEmpty { apks }
        val abis = supportedAbis.map { if (it == "armeabi") "armeabi-v7a" else it }
        return preferred.filter { name ->
            val base = name.substringAfterLast('/')
            val abi = AssetSelector.detectAbi(base.replace('_', '-').replace("x86-64", "x86_64"))
            abi == null || abi in abis
        }
    }

    private fun readInfo(context: Context, apk: File, splits: List<String>): ApkInfo {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException("Could not read the APK (it may be corrupt or incompatible)")
        val label = info.applicationInfo?.let { ai ->
            ai.sourceDir = apk.absolutePath
            ai.publicSourceDir = apk.absolutePath
            runCatching { ai.loadLabel(pm).toString() }.getOrNull()
        }?.takeIf { it.isNotBlank() && it != info.packageName }
        return ApkInfo(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            label = label,
            splitEntries = splits,
        )
    }
}

data class InstalledInfo(
    val versionName: String?,
    val versionCode: Long,
    /** When Android says this version was installed (PackageInfo.lastUpdateTime). */
    val installedAt: Long = 0,
)

object InstalledApps {
    /**
     * The installed APK file of a single-APK app, for comparing with a website's file. Null for
     * apps installed as split bundles (e.g. from the Play Store), whose files can't be compared.
     */
    fun localApk(context: Context, packageName: String?): LocalApk? {
        if (packageName.isNullOrBlank()) return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            if (!info.splitSourceDirs.isNullOrEmpty()) return null
            RemoteProbe.localApk(File(info.sourceDir))
        }.getOrNull()
    }

    fun get(context: Context, packageName: String?): InstalledInfo? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            InstalledInfo(info.versionName, PackageInfoCompat.getLongVersionCode(info), info.lastUpdateTime)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
