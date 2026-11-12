package io.github.edrock200.appdownloader.net

/** Picks the best installable asset of a release for this device. */
object AssetSelector {

    private val INSTALLABLE = listOf(".apk", ".apks", ".xapk")
    private val ARM32_BARE = Regex("(^|[^a-z0-9])(arm|arm32|armhf|armv7a?)([^a-z0-9]|$)")
    private val TV_WORDS = Regex("(^|[^a-z0-9])(tv|androidtv|android-tv|leanback|firetv|fire-tv)([^a-z0-9]|$)")
    private val DEBUG_WORDS = Regex("(^|[^a-z0-9])(debug|unsigned|androidtest|test)([^a-z0-9]|$)")
    private val RELEASE_WORDS = Regex("(^|[^a-z0-9])release([^a-z0-9]|$)")

    fun isInstallable(name: String) = INSTALLABLE.any { name.lowercase().endsWith(it) }

    /** Returns the Android ABI an asset was built for, or null if it looks universal. */
    fun detectAbi(name: String): String? {
        val n = name.lowercase()
        return when {
            "arm64" in n || "aarch64" in n || "armv8" in n -> "arm64-v8a"
            "armeabi-v7a" in n || "armeabi_v7a" in n || "armeabi" in n || ARM32_BARE.containsMatchIn(n) -> "armeabi-v7a"
            "x86_64" in n || "x86-64" in n || "amd64" in n || Regex("(^|[^a-z0-9])x64([^a-z0-9]|$)").containsMatchIn(n) -> "x86_64"
            "x86" in n || "i686" in n || "i386" in n -> "x86"
            else -> null
        }
    }

    /** Name with numbers blanked, so "app-1.2.apk" and "app-1.3.apk" look alike. */
    private fun shape(name: String) = name.lowercase().replace(Regex("\\d+"), "#")

    /**
     * Chooses the asset to install. The device's ABI matters most; after that release builds beat
     * debug/test builds, TV builds are preferred on TVs (and avoided on phones), and a file named
     * like the one chosen last time wins ties.
     */
    fun select(
        assets: List<GhAsset>,
        filter: String?,
        supportedAbis: List<String>,
        isTv: Boolean = false,
        previousName: String? = null,
    ): GhAsset? {
        var candidates = assets.filter { isInstallable(it.name) }
        if (!filter.isNullOrBlank()) {
            val regex = runCatching { Regex(filter, RegexOption.IGNORE_CASE) }.getOrNull()
            candidates = candidates.filter { asset ->
                regex?.containsMatchIn(asset.name) ?: asset.name.contains(filter, ignoreCase = true)
            }
        }
        if (candidates.isEmpty()) return null

        val abis = supportedAbis.map { if (it == "armeabi") "armeabi-v7a" else it }.distinct()
        fun score(asset: GhAsset): Int {
            val n = asset.name.lowercase()
            val abi = detectAbi(n)
            var s = when {
                abi == null -> if ("universal" in n) 60 else 50
                abi in abis -> 100 - abis.indexOf(abi) * 10
                else -> return -1 // Built for an ABI this device can't run.
            }
            if (n.endsWith(".apk")) s += 1 // Prefer plain APKs over bundles.
            if (DEBUG_WORDS.containsMatchIn(n)) s -= 5
            if (RELEASE_WORDS.containsMatchIn(n)) s += 3
            if (TV_WORDS.containsMatchIn(n)) s += if (isTv) 4 else -4
            if (previousName != null && shape(n) == shape(previousName)) s += 2
            return s
        }
        val best = candidates.maxByOrNull(::score) ?: return null
        return best.takeIf { score(it) >= 0 }
    }
}
