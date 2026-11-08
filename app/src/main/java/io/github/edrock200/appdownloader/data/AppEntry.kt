package io.github.edrock200.appdownloader.data

import io.github.edrock200.appdownloader.net.ChangeReason
import io.github.edrock200.appdownloader.net.RemoteChange
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class SourceType { URL, AFTV_CODE, GITHUB }

/**
 * A saved download source. [source] holds a URL, a numeric aftv.news code, or a
 * GitHub "owner/repo" slug depending on [type].
 */
@Serializable
data class AppEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** True while [name] was generated automatically and may be replaced by the app's label. */
    val autoName: Boolean = false,
    val type: SourceType,
    val source: String,
    /** Learned from the first downloaded APK. */
    val packageName: String? = null,
    val addedAt: Long = System.currentTimeMillis(),

    // ---- GitHub-only settings ----
    val includePrereleases: Boolean = false,
    /** Optional regex or plain text used to pick the right release asset. */
    val assetFilter: String? = null,

    // ---- GitHub release tracking ----
    val latestTag: String? = null,
    val latestReleaseName: String? = null,
    val latestReleaseUrl: String? = null,
    val latestAssetName: String? = null,
    val latestAssetUrl: String? = null,
    val latestPublishedAt: String? = null,
    val lastCheckedAt: Long? = null,
    val lastCheckError: String? = null,
    /** Release tag the app last installed for this entry. */
    val installedTag: String? = null,
    /** Version code of the APK the app last installed for this entry. */
    val installedVersionCode: Long? = null,
    /** Tag we already notified the user about, so we don't notify twice. */
    val notifiedTag: String? = null,

    // ---- Scheduled updates ----
    /** Include this app when scheduled / "update all" runs happen. */
    val autoUpdate: Boolean = true,
    /** A downloaded update waiting to be installed (Android needed the user to confirm). */
    val readyFile: String? = null,
    val readyVersionName: String? = null,
    val readyVersionCode: Long? = null,
    /** Release tag of [readyFile], for GitHub entries. */
    val readyTag: String? = null,

    // ---- Change detection for links / aftv codes ----
    /** Server details of the file that was last installed from this entry. */
    val fingerprint: RemoteFingerprint? = null,
    /** Details of a downloaded file whose install hasn't finished yet. */
    val pendingFingerprint: RemoteFingerprint? = null,
    /** What the server reported at the last check. */
    val remoteFingerprint: RemoteFingerprint? = null,
    /** Result of the last check; null when never checked. */
    val remoteChange: RemoteChange? = null,
    /** How [remoteChange] was decided (for the card's explanation). */
    val remoteReason: ChangeReason? = null,
    val remoteCheckedAt: Long? = null,
    /** [RemoteFingerprint.key] we already notified about. */
    val notifiedChangeKey: String? = null,

    /** Installs and updates of this app on this device, newest first (see [History]). */
    val history: List<InstallRecord> = emptyList(),
)
