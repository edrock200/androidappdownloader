package io.github.edrock200.appdownloader.net

/** Turns common share links into direct-download links (pure, for tests). */
object LinkNormalizer {
    private val DRIVE_FILE = Regex("^https?://drive\\.google\\.com/file/d/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val DRIVE_OPEN = Regex("^https?://drive\\.google\\.com/(?:open|uc)\\?(?:.*&)?id=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val DROPBOX = Regex("^https?://(www\\.)?dropbox\\.com/", RegexOption.IGNORE_CASE)

    fun normalize(url: String): String {
        val u = url.trim()
        DRIVE_FILE.find(u)?.let { return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}" }
        DRIVE_OPEN.find(u)?.let { return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}" }
        if (DROPBOX.containsMatchIn(u)) {
            return when {
                Regex("[?&]dl=0").containsMatchIn(u) -> u.replace(Regex("([?&])dl=0"), "$1dl=1")
                Regex("[?&]dl=1").containsMatchIn(u) -> u
                '?' in u -> "$u&dl=1"
                else -> "$u?dl=1"
            }
        }
        return u
    }
}
