package com.vboard.core.clipboard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A clock the test drives by hand; retention is never asserted against wall time. */
private class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis(): Long = now

    fun advanceMinutes(minutes: Long) {
        now += minutes * 60_000L
    }

    fun advanceSeconds(seconds: Long) {
        now += seconds * 1_000L
    }
}

class ClipboardHistoryTest {

    private val clock = TestClock()
    private val history = ClipboardHistory(clock)

    private fun store(text: String): ClipEntry {
        val result = history.offer(text)
        return (result as? OfferResult.Stored)?.entry ?: fail("expected Stored, got $result")
    }

    // ------------------------------------------------------------------ capture

    @Test
    fun `a normal clip is stored and a one-time code is not`() {
        assertTrue(history.offer("hello there") is OfferResult.Stored)
        assertTrue(history.offer("483920") is OfferResult.SessionOnly)
        assertEquals(listOf("hello there"), history.recent().map { it.text })
        assertEquals("{\"v\":1,\"clips\":[{\"t\":\"hello there\",\"at\":1700000000000,\"p\":false}]}", history.serialize())
    }

    @Test
    fun `a session-only clip never reaches the persisted list`() {
        history.offer("483920")
        history.offer("4111 1111 1111 1111")
        history.offer("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----")
        assertTrue(history.persistable().isEmpty())
        assertTrue(history.isEmpty())
    }

    @Test
    fun `a session-only clip is offered as a chip but is not pinnable`() {
        history.offer("483920")
        assertEquals("483920", history.chip()?.text)
        assertEquals(PinResult.NOT_FOUND, history.pin("483920"))
    }

    @Test
    fun `clearing session-only state drops the code`() {
        history.offer("483920")
        history.clearSessionOnly()
        assertNull(history.chip())
    }

    // --------------------------------------------------------- de-duplication

    @Test
    fun `re-copying the newest clip bumps it rather than inserting a duplicate`() {
        store("first")
        clock.advanceSeconds(10)
        store("second")
        clock.advanceSeconds(10)

        val again = history.offer("second") as? OfferResult.Stored
            ?: fail("expected the duplicate to be stored")
        assertTrue(again.deduplicated)
        assertEquals(clock.now, again.entry.capturedAtMillis)
        assertEquals(listOf("second", "first"), history.recent().map { it.text })
        assertEquals(2, history.persistable().size)
    }

    @Test
    fun `re-copying an older clip moves it to newest and keeps its pin`() {
        store("older")
        history.pin("older")
        clock.advanceSeconds(30)
        store("newer")
        clock.advanceSeconds(30)

        history.offer("older")
        assertEquals(listOf("older", "newer"), history.persistable().map { it.text })
        assertEquals(listOf("older"), history.pinned().map { it.text })
        assertEquals(2, history.persistable().size)
    }

    // ------------------------------------------------------------- retention

    @Test
    fun `an unpinned clip survives 59 minutes and not 61`() {
        store("perishable")
        clock.advanceMinutes(59)
        assertEquals(listOf("perishable"), history.recent().map { it.text })

        clock.advanceMinutes(2) // 61 minutes total
        assertTrue(history.recent().isEmpty())
        assertTrue(history.persistable().isEmpty())
    }

    @Test
    fun `a pinned clip survives both`() {
        store("keeper")
        history.pin("keeper")
        clock.advanceMinutes(59)
        assertEquals(listOf("keeper"), history.pinned().map { it.text })
        clock.advanceMinutes(2)
        assertEquals(listOf("keeper"), history.pinned().map { it.text })
        clock.advanceMinutes(60 * 48)
        assertEquals(listOf("keeper"), history.pinned().map { it.text })
    }

    @Test
    fun `retention is enforced on read, with no intervening write`() {
        store("perishable")
        clock.advanceMinutes(61)
        // Nothing has been offered since; a stale clip must still never render.
        assertTrue(history.recent().isEmpty())
        assertNull(history.chip())
        assertEquals("{\"v\":1,\"clips\":[]}", history.serialize())
    }

    @Test
    fun `unpinning an hour-old clip lets it expire`() {
        store("keeper")
        history.pin("keeper")
        clock.advanceMinutes(90)
        history.unpin("keeper")
        assertTrue(history.persistable().isEmpty())
    }

    // ---------------------------------------------------------------- limits

    @Test
    fun `the 26th unpinned clip evicts the oldest`() {
        repeat(25) {
            store("clip $it")
            clock.advanceSeconds(1)
        }
        assertEquals(25, history.recent().size)
        assertEquals("clip 0", history.recent().last().text)

        store("clip 25")
        assertEquals(25, history.recent().size)
        assertEquals("clip 25", history.recent().first().text)
        assertEquals("clip 1", history.recent().last().text)
        assertTrue(history.recent().none { it.text == "clip 0" })
    }

    @Test
    fun `pinned clips do not count against the unpinned limit`() {
        store("pinned one")
        history.pin("pinned one")
        repeat(25) {
            clock.advanceSeconds(1)
            store("clip $it")
        }
        assertEquals(25, history.recent().size)
        assertEquals(1, history.pinned().size)
        assertEquals(26, history.persistable().size)
    }

    @Test
    fun `the 21st pin is refused`() {
        repeat(20) {
            store("clip $it")
            clock.advanceSeconds(1)
            assertEquals(PinResult.PINNED, history.pin("clip $it"))
        }
        store("clip 20")
        assertEquals(PinResult.LIMIT_REACHED, history.pin("clip 20"))
        assertEquals(20, history.pinned().size)
        assertEquals(listOf("clip 20"), history.recent().map { it.text })
    }

    @Test
    fun `unpinning makes room for another pin`() {
        repeat(20) {
            store("clip $it")
            clock.advanceSeconds(1)
            history.pin("clip $it")
        }
        store("clip 20")
        assertEquals(PinResult.LIMIT_REACHED, history.pin("clip 20"))
        history.unpin("clip 0")
        assertEquals(PinResult.PINNED, history.pin("clip 20"))
    }

    @Test
    fun `pinning something already pinned is a no-op`() {
        store("clip")
        assertEquals(PinResult.PINNED, history.pin("clip"))
        assertEquals(PinResult.ALREADY_PINNED, history.pin("clip"))
        assertEquals(1, history.pinned().size)
    }

    @Test
    fun `an oversize clip is discarded and the previous clip stays newest`() {
        store("the good one")
        clock.advanceSeconds(5)
        val result = history.offer("x".repeat(5_001))
        assertEquals(OfferResult.Discarded(DiscardReason.TOO_LONG), result)
        assertEquals(listOf("the good one"), history.recent().map { it.text })
        assertEquals("the good one", history.chip()?.text)
    }

    @Test
    fun `the byte budget drops oldest unpinned rather than failing the write`() {
        val limits = ClipLimits(maxFileBytes = 4_096)
        val small = ClipboardHistory(clock, limits)
        repeat(10) {
            small.offer("${'a' + it}".repeat(1_000))
            clock.advanceSeconds(1)
        }
        val bytes = small.serialize().toByteArray(Charsets.UTF_8).size
        assertTrue(bytes <= limits.maxFileBytes, "store was $bytes bytes")
        assertTrue(small.persistable().isNotEmpty())
        // The newest survives; the oldest are the ones dropped.
        assertEquals("j".repeat(1_000), small.persistable().first().text)
    }

    @Test
    fun `pinned clips are kept even when they alone exceed the byte budget`() {
        val limits = ClipLimits(maxFileBytes = 512)
        val small = ClipboardHistory(clock, limits)
        small.offer("a".repeat(400))
        small.pin("a".repeat(400))
        clock.advanceSeconds(1)
        small.offer("b".repeat(400))
        assertEquals(listOf("a".repeat(400)), small.persistable().map { it.text })
    }

    // ------------------------------------------------------------------ chip

    @Test
    fun `the chip is offered for 60 seconds and then not`() {
        store("chip me")
        assertEquals("chip me", history.chip()?.text)
        clock.advanceSeconds(59)
        assertEquals("chip me", history.chip()?.text)
        clock.advanceSeconds(2) // 61s
        assertNull(history.chip())
        // The clip itself is still in history; only the chip window closed.
        assertEquals(listOf("chip me"), history.recent().map { it.text })
    }

    @Test
    fun `dismissing the chip hides it while the clip stays in history`() {
        store("chip me")
        history.dismissChip()
        assertNull(history.chip())
        assertEquals(listOf("chip me"), history.recent().map { it.text })
    }

    @Test
    fun `a new capture revives the chip after a dismissal`() {
        store("first")
        history.dismissChip()
        clock.advanceSeconds(5)
        store("second")
        assertEquals("second", history.chip()?.text)
    }

    @Test
    fun `a normal clip supersedes a live one-time code in the chip`() {
        history.offer("483920")
        clock.advanceSeconds(5)
        store("ordinary text")
        assertEquals("ordinary text", history.chip()?.text)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun `delete removes one entry and delete-all removes everything`() {
        store("one")
        clock.advanceSeconds(1)
        store("two")
        history.pin("two")
        history.delete("one")
        assertEquals(listOf("two"), history.persistable().map { it.text })

        history.deleteAll()
        assertTrue(history.persistable().isEmpty())
        assertNull(history.chip())
    }

    @Test
    fun `a clear cutoff removes everything copied before it, pinned included`() {
        store("old")
        history.pin("old")
        clock.advanceSeconds(10)
        val cutoff = clock.now
        clock.advanceSeconds(10)
        store("new")

        assertTrue(history.deleteCapturedBefore(cutoff))
        assertEquals(listOf("new"), history.persistable().map { it.text })
    }

    @Test
    fun `a clear cutoff spares anything copied after it`() {
        val cutoff = clock.now
        clock.advanceSeconds(10)
        store("after the clear")
        assertFalse(history.deleteCapturedBefore(cutoff))
        assertEquals(listOf("after the clear"), history.persistable().map { it.text })
    }

    @Test
    fun `replaying the same clear cutoff is a no-op`() {
        // The IME sees the settings screen's cutoff again on every restart; it
        // must not be able to eat clips copied since.
        store("before")
        clock.advanceSeconds(5)
        val cutoff = clock.now
        clock.advanceSeconds(5)
        store("since")

        assertTrue(history.deleteCapturedBefore(cutoff))
        assertFalse(history.deleteCapturedBefore(cutoff))
        assertFalse(history.deleteCapturedBefore(cutoff))
        assertEquals(listOf("since"), history.persistable().map { it.text })
    }

    @Test
    fun `a clear cutoff drops a session-only clip captured before it`() {
        history.offer("483920")
        clock.advanceSeconds(5)
        assertTrue(history.deleteCapturedBefore(clock.now))
        assertNull(history.chip())
    }

    // ----------------------------------------------------------- persistence

    @Test
    fun `a store round-trips through serialize and restore`() {
        store("plain")
        clock.advanceSeconds(1)
        store("quotes \" backslash \\ newline \n tab \t")
        clock.advanceSeconds(1)
        store("unicode ✨ emoji 🙂 and ünïcödé")
        history.pin("plain")

        val document = history.serialize()
        val reloaded = ClipboardHistory(clock)
        assertTrue(reloaded.restore(document))
        assertEquals(history.persistable(), reloaded.persistable())
        assertEquals(listOf("plain"), reloaded.pinned().map { it.text })
        assertEquals(document, reloaded.serialize())
    }

    @Test
    fun `restoring an empty store yields an empty history`() {
        assertTrue(history.restore("{\"v\":1,\"clips\":[]}"))
        assertTrue(history.persistable().isEmpty())
    }

    @Test
    fun `restore drops entries that expired while the process was gone`() {
        store("perishable")
        history.pin("perishable")
        clock.advanceSeconds(1)
        store("also perishable")
        val document = history.serialize()

        clock.advanceMinutes(61)
        val reloaded = ClipboardHistory(clock)
        assertTrue(reloaded.restore(document))
        assertEquals(listOf("perishable"), reloaded.persistable().map { it.text })
    }

    @Test
    fun `restore rejects a duplicate-bearing file rather than storing both copies`() {
        val document = "{\"v\":1,\"clips\":[" +
            "{\"t\":\"same\",\"at\":1700000000000,\"p\":false}," +
            "{\"t\":\"same\",\"at\":1699999999000,\"p\":false}]}"
        assertTrue(history.restore(document))
        assertEquals(1, history.persistable().size)
    }

    @Test
    fun `restore reports failure for a corrupt document and leaves history untouched`() {
        store("survivor")
        assertFalse(history.restore("{\"v\":1,\"clips\":[{\"t\":\"trunca"))
        assertEquals(listOf("survivor"), history.persistable().map { it.text })
    }
}
