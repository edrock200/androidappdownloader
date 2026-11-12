package io.github.edrock200.appdownloader.data

object VersionUtils {

    /** Extracts the numeric components of a version-ish string, e.g. "v1.2.10-beta" -> [1, 2, 10]. */
    fun numericParts(version: String): List<Long> {
        val match = Regex("\\d+(?:\\.\\d+)*").find(version) ?: return emptyList()
        return match.value.split('.').mapNotNull { it.toLongOrNull() }
    }

    /**
     * Compares two versions numerically. Returns null when either side has no
     * numbers to compare.
     */
    fun compare(a: String, b: String): Int? {
        val pa = numericParts(a)
        val pb = numericParts(b)
        if (pa.isEmpty() || pb.isEmpty()) return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * Decides whether a GitHub entry has a newer release than what is installed.
     * [installedVersionName]/[installedVersionCode] describe the app currently on
     * the device, or are null when it is not installed.
     */
    /** A link / aftv entry whose file changed on the server since the installed copy was downloaded. */
    fun linkChanged(entry: AppEntry, installedVersionCode: Long?): Boolean =
        entry.type != SourceType.GITHUB && installedVersionCode != null &&
            entry.remoteChange == io.github.edrock200.appdownloader.net.RemoteChange.CHANGED

    /** Whether the card should offer an update, for any kind of source. */
    fun updateAvailable(entry: AppEntry, installedVersionName: String?, installedVersionCode: Long?): Boolean =
        if (entry.type == SourceType.GITHUB) hasUpdate(entry, installedVersionName, installedVersionCode)
        else linkChanged(entry, installedVersionCode)

    /**
     * Lowest target SDK Android will install on [sdkInt] (Android 14 blocks apps targeting below 23,
     * Android 15 below 24). 0 = no limit.
     */
    fun minInstallableTargetSdk(sdkInt: Int): Int = when {
        sdkInt >= 35 -> 24
        sdkInt >= 34 -> 23
        else -> 0
    }

    fun hasUpdate(entry: AppEntry, installedVersionName: String?, installedVersionCode: Long?): Boolean {
        val latest = entry.latestTag ?: return false
        if (entry.latestAssetUrl == null) return false
        if (installedVersionCode == null) return false // Not installed: offer "Install", not "Update".

        // A tag whose file was already found to be older than what is installed (withdrawn release).
        if (latest == entry.skippedTag) return false

        // If the installed build is the one we installed, compare tags, and only ever forwards.
        if (entry.installedTag != null && entry.installedVersionCode == installedVersionCode) {
            val c = compare(latest, entry.installedTag)
            return if (c != null) c > 0 else latest != entry.installedTag
        }
        val cmp = installedVersionName?.let { compare(latest, it) }
        return when {
            cmp != null -> cmp > 0
            else -> latest != entry.installedTag
        }
    }
}
