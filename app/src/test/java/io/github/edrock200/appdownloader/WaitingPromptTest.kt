package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.WaitingPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingPromptTest {
    @Test
    fun shownOncePerVersionAcrossRestarts() {
        val v1 = AppEntry(id = "a", name = "silo", type = SourceType.URL, source = "https://x/a.apk", readyFile = "/f", readyVersionCode = 10)
        val k1 = WaitingPrompt.key(v1)

        val first = WaitingPrompt.decide(setOf(k1), offered = emptySet(), allReady = setOf(k1))
        assertTrue(first.show)
        // After a restart the stored set is read back: the same version isn't offered again.
        val again = WaitingPrompt.decide(setOf(k1), offered = first.remember, allReady = setOf(k1))
        assertFalse(again.show)

        // A newer download of the same app is offered.
        val k2 = WaitingPrompt.key(v1.copy(readyVersionCode = 11))
        val newer = WaitingPrompt.decide(setOf(k2), offered = again.remember, allReady = setOf(k2))
        assertTrue(newer.show)
        assertEquals(setOf(k2), newer.remember) // The installed/replaced version is forgotten.
    }

    @Test
    fun aBusyUpdateIsNotForgotten() {
        val a = "a:10"
        val b = "b:5"
        // b is being installed right now (busy), so the prompt only lists a; b stays remembered.
        val d = WaitingPrompt.decide(setOf(a), offered = setOf(b), allReady = setOf(a, b))
        assertTrue(d.show)
        assertEquals(setOf(a, b), d.remember)
        assertFalse(WaitingPrompt.decide(setOf(a, b), d.remember, setOf(a, b)).show)
    }
}
