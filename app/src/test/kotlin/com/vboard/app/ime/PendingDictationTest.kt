package com.vboard.app.ime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * W0.2 draft rescue. The hole this closes is data loss, so the tests that matter
 * are the ones proving the rescued text goes to the right editor and nowhere
 * else: a keyboard that replays speech into the next app's field would be a
 * worse bug than the one being fixed.
 */
class PendingDictationTest {

    private fun held(text: String = "call me back", at: Long = 0L, pkg: String? = "com.example.chat") =
        PendingDictation(text, pkg, at)

    @Test
    fun `replays into the same app, soon, in a field that takes voice`() {
        assertEquals(
            ReplayVerdict.REPLAY,
            held().verdictFor("com.example.chat", fieldAcceptsVoice = true, nowMs = 1_000L),
        )
    }

    @Test
    fun `does not replay into a different app`() {
        assertEquals(
            ReplayVerdict.OTHER_APP,
            held().verdictFor("com.example.bank", fieldAcceptsVoice = true, nowMs = 1_000L),
        )
    }

    @Test
    fun `does not replay when the holding package was never known`() {
        assertEquals(
            ReplayVerdict.OTHER_APP,
            held(pkg = null).verdictFor(null, fieldAcceptsVoice = true, nowMs = 1_000L),
        )
    }

    @Test
    fun `does not replay into a field that refuses voice`() {
        assertEquals(
            ReplayVerdict.FIELD_REFUSES,
            held().verdictFor("com.example.chat", fieldAcceptsVoice = false, nowMs = 1_000L),
        )
    }

    @Test
    fun `expires rather than surfacing minutes later`() {
        assertEquals(
            ReplayVerdict.EXPIRED,
            held().verdictFor("com.example.chat", fieldAcceptsVoice = true, nowMs = PendingDictation.TTL_MS + 1),
        )
    }

    @Test
    fun `still replays at the moment the window closes`() {
        assertEquals(
            ReplayVerdict.REPLAY,
            held().verdictFor("com.example.chat", fieldAcceptsVoice = true, nowMs = PendingDictation.TTL_MS),
        )
    }

    @Test
    fun `expiry is checked before the app match, so a stale hold cannot be revived`() {
        assertEquals(
            ReplayVerdict.EXPIRED,
            held().verdictFor("com.example.bank", fieldAcceptsVoice = false, nowMs = PendingDictation.TTL_MS + 1),
        )
    }

    @Test
    fun `first drop is held verbatim`() {
        val first = PendingDictation.hold(null, "call me back", "com.example.chat", 5L)
        assertEquals("call me back", first?.text)
        assertEquals("com.example.chat", first?.packageName)
        assertEquals(5L, first?.atMs)
    }

    @Test
    fun `a burst of drops accumulates in speaking order`() {
        var held = PendingDictation.hold(null, "call me back", "com.example.chat", 0L)
        held = PendingDictation.hold(held, "after lunch", "com.example.chat", 10L)
        assertEquals("call me back after lunch", held?.text)
    }

    @Test
    fun `each drop restarts the window`() {
        var held = PendingDictation.hold(null, "call me back", "com.example.chat", 0L)
        held = PendingDictation.hold(held, "after lunch", "com.example.chat", 10L)
        assertEquals(10L, held?.atMs)
    }

    @Test
    fun `a blank utterance neither creates nor disturbs a hold`() {
        assertNull(PendingDictation.hold(null, "   ", "com.example.chat", 0L))
        val existing = held()
        assertEquals(existing, PendingDictation.hold(existing, "", "com.example.chat", 99L))
    }
}
