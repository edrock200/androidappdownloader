package io.github.edrock200.appdownloader.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.edrock200.appdownloader.data.VersionUtils
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
        var manifestPackage: String? = null
        val splits = ZipFile(file).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") != null) {
                emptyList()
            } else {
                // XAPK bundles describe themselves in manifest.json; use it to find the base APK.
                manifestPackage = zip.getEntry("manifest.json")?.let { e ->
                    runCatching {
                        zip.getInputStream(e).use { it.readBytes().decodeToString() }
                            .let { Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                    }.getOrNull()
                }
                selectSplits(zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList())
                    .ifEmpty { throw IOException("The file is not a valid APK") }
            }
        }
        if (splits.isEmpty()) return readInfo(context, file, emptyList())

        // Read the package details from the base split (its own temporary file, so parallel installs can't clash).
        val baseName = baseSplit(splits, manifestPackage)
        val tmp = File.createTempFile("inspect-", ".apk", context.cacheDir)
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

    private val ABI_QUALIFIER = Regex("(arm64[_-]v8a|armeabi[_-]v7a|armeabi|x86[_-]64|x86|mips64|mips)", RegexOption.IGNORE_CASE)

    /** A configuration split (density, language, ABI) rather than an app module. */
    fun isConfigSplit(name: String): Boolean {
        val base = name.substringAfterLast('/').lowercase()
        return base.startsWith("config.") || base.startsWith("split_config.") ||
            (base.startsWith("base-") && base != "base-master.apk" && !base.startsWith("base-master_"))
    }

    /** The base APK of a bundle: "base.apk", bundletool's "base-master.apk", XAPK's "<package>.apk", else the first non-config APK. */
    fun baseSplit(names: List<String>, manifestPackage: String? = null): String {
        fun file(n: String) = n.substringAfterLast('/').lowercase()
        return names.firstOrNull { file(it) == "base.apk" }
            ?: names.firstOrNull { file(it) == "base-master.apk" || file(it).startsWith("base-master_") }
            ?: manifestPackage?.let { pkg -> names.firstOrNull { file(it) == "${pkg.lowercase()}.apk" } }
            ?: names.firstOrNull { !isConfigSplit(it) }
            ?: names.first()
    }

    /**
     * Chooses which APKs inside a bundle should be installed on this device: one copy of each
     * module/qualifier, and only the processor-specific parts for the best ABI this device supports.
     */
    fun selectSplits(names: List<String>, supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()): List<String> {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        // bundletool .apks files may contain both "splits/" and "standalones/" variants.
        val preferred = apks.filter { it.startsWith("splits/") }.ifEmpty {
            apks.filterNot { it.startsWith("standalones/") }
        }.ifEmpty { apks }
        // Variants of the same split ("base-xxhdpi.apk", "base-xxhdpi_2.apk"): keep the first.
        val deduped = preferred.distinctBy { it.substringAfterLast('/').lowercase().replace(Regex("_\\d+\\.apk$"), ".apk") }
        val abis = supportedAbis.map { if (it == "armeabi") "armeabi-v7a" else it }
        fun abiOf(name: String): String? {
            val base = name.substringAfterLast('/')
            if (!isConfigSplit(name)) return null
            val q = ABI_QUALIFIER.find(base)?.value ?: return null
            return AssetSelector.detectAbi(q.replace('_', '-').replace("x86-64", "x86_64"))
        }
        val available = deduped.mapNotNull { abiOf(it) }.toSet()
        val best = abis.firstOrNull { it in available }
        return deduped.filter { name ->
            val abi = abiOf(name)
            abi == null || abi == best
        }
    }

    private fun readInfo(context: Context, apk: File, splits: List<String>): ApkInfo {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException("Could not read the APK (it may be corrupt or incompatible)")
        val target = info.applicationInfo?.targetSdkVersion ?: 0
        val minTarget = VersionUtils.minInstallableTargetSdk(Build.VERSION.SDK_INT)
        if (target in 1 until minTarget) {
            throw IOException("This app was made for a much older Android version and this device's Android refuses to install it")
        }
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
