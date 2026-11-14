package io.github.edrock200.appdownloader.data

/**
 * When to show the "Update ready to install" prompt: once per downloaded version, remembered across
 * restarts (the caller stores [Decision.remember]). Pure, so it is unit-tested.
 */
object WaitingPrompt {
    data class Decision(val show: Boolean, val remember: Set<String>)

    fun key(entry: AppEntry): String = "${entry.id}:${entry.readyVersionCode ?: entry.readyFile}"

    /**
     * [waiting] are the updates the prompt would list now, [offered] those already shown, and
     * [allReady] every download still waiting (busy ones included), so finished updates are forgotten
     * without forgetting one that is only busy for a moment.
     */
    fun decide(waiting: Set<String>, offered: Set<String>, allReady: Set<String>): Decision =
        Decision(show = (waiting - offered).isNotEmpty(), remember = (offered + waiting) intersect allReady)
}
