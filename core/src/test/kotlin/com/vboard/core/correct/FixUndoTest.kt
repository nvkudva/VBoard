package com.vboard.core.correct

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo (VB-232) — the part of this feature that makes the rest safe to try.
 *
 * One tap replaces everything the user has written, so the snapshot has to give
 * it all back: the same characters, the same cursor, and never into the wrong
 * field.
 */
class FixUndoTest {

    private val fieldA = 1L
    private val fieldB = 2L

    private fun store() = FixUndoStore()

    @Test
    fun `undo restores the original exactly`() {
        val original = "i went too the stor yesterday"
        val applied = "I went to the store yesterday."
        val store = store()
        store.record(original, 12, 12, applied, fieldA, nowMs = 0)

        val snapshot = store.consume(nowMs = 1_000, fieldToken = fieldA)!!
        assertEquals(original, snapshot.originalText())
        assertEquals(12, snapshot.selectionStart)
        assertEquals(12, snapshot.selectionEnd)
    }

    @Test
    fun `undo restores multi-line text byte for byte`() {
        val original = "hi there\n\n  see the notes\n\ttab indented\n"
        val store = store()
        store.record(original, 3, 7, "Hi there.", fieldA, nowMs = 0)
        assertEquals(original, store.consume(1, fieldA)!!.originalText())
    }

    @Test
    fun `undo works when the correction changed nothing`() {
        // The button still becomes Undo; tapping it must be a faithful no-op.
        val text = "The report is ready for review."
        val store = store()
        store.record(text, 5, 5, text, fieldA, nowMs = 0)
        val snapshot = store.consume(1, fieldA)!!
        assertEquals(text, snapshot.originalText())
        assertTrue(snapshot.matchesField(text))
    }

    @Test
    fun `undo is offered only once`() {
        val store = store()
        store.record("before", 0, 0, "After.", fieldA, nowMs = 0)
        assertTrue(store.consume(1, fieldA) != null)
        assertNull(store.consume(2, fieldA))
    }

    @Test
    fun `undo expires after the window`() {
        val store = FixUndoStore(windowMs = 15_000)
        store.record("before", 0, 0, "After.", fieldA, nowMs = 1_000)
        assertTrue(store.peek(nowMs = 15_999, fieldToken = fieldA) != null)
        assertNull(store.peek(nowMs = 16_000, fieldToken = fieldA))
    }

    @Test
    fun `undo never crosses fields`() {
        val store = store()
        store.record("a field's private text", 0, 0, "Applied.", fieldA, nowMs = 0)
        assertNull(store.peek(1, fieldB), "an undo must never fire into another field")
        // ...and the foreign lookup drops it, so it cannot come back either.
        assertNull(store.peek(1, fieldA))
    }

    @Test
    fun `clearing drops the snapshot`() {
        val store = store()
        store.record("before", 0, 0, "After.", fieldA, nowMs = 0)
        store.clear()
        assertFalse(store.isArmed)
        assertNull(store.peek(1, fieldA))
    }

    @Test
    fun `an edited field refuses the undo`() {
        val store = store()
        store.record("before", 0, 0, "After.", fieldA, nowMs = 0)
        val snapshot = store.peek(1, fieldA)!!
        assertTrue(snapshot.matchesField("After."))
        assertFalse(snapshot.matchesField("After. And more typing."))
    }

    @Test
    fun `reapply keeps the original and the deadline after a per-change revert`() {
        val store = FixUndoStore(windowMs = 15_000)
        store.record("i went too the stor", 0, 0, "I went to the store.", fieldA, nowMs = 1_000)
        assertTrue(store.reapply("I went to the stor.", nowMs = 5_000, fieldToken = fieldA))

        val snapshot = store.peek(6_000, fieldA)!!
        assertEquals("i went too the stor", snapshot.originalText())
        assertTrue(snapshot.matchesField("I went to the stor."))
        assertFalse(snapshot.matchesField("I went to the store."))
        // The 15s clock still runs from the fix, not from the revert.
        assertEquals(16_000, snapshot.expiresAtMs)
    }

    @Test
    fun `reapply does nothing when no undo is armed`() {
        assertFalse(store().reapply("anything", nowMs = 0, fieldToken = fieldA))
    }

    @Test
    fun `a second fix replaces the outstanding undo`() {
        val store = store()
        store.record("first", 0, 0, "First.", fieldA, nowMs = 0)
        store.record("second", 0, 0, "Second.", fieldA, nowMs = 100)
        assertEquals("second", store.consume(200, fieldA)!!.originalText())
    }

    // ------------------------------------------------------------ button state

    @Test
    fun `button state follows the store`() {
        val store = store()
        assertEquals(
            FixButtonState.IDLE,
            store.buttonState(0, fieldA, running = false, enabled = true),
        )
        assertEquals(
            FixButtonState.RUNNING,
            store.buttonState(0, fieldA, running = true, enabled = true),
        )
        assertEquals(
            FixButtonState.DISABLED,
            store.buttonState(0, fieldA, running = false, enabled = false),
        )
        store.record("before", 0, 0, "After.", fieldA, nowMs = 0)
        assertEquals(
            FixButtonState.UNDO,
            store.buttonState(1, fieldA, running = false, enabled = true),
        )
        assertEquals(
            FixButtonState.IDLE,
            store.buttonState(FixUndoStore.DEFAULT_WINDOW_MS + 1, fieldA, running = false, enabled = true),
        )
    }

    @Test
    fun `a disabled field never shows undo`() {
        val store = store()
        store.record("before", 0, 0, "After.", fieldA, nowMs = 0)
        assertEquals(
            FixButtonState.DISABLED,
            store.buttonState(1, fieldA, running = false, enabled = false),
        )
    }

    @Test
    fun `remaining time counts down and floors at zero`() {
        val store = FixUndoStore(windowMs = 15_000)
        store.record("before", 0, 0, "After.", fieldA, nowMs = 0)
        assertEquals(15_000, store.remainingMs(0, fieldA))
        assertEquals(5_000, store.remainingMs(10_000, fieldA))
        assertEquals(0, store.remainingMs(20_000, fieldA))
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `snapshot toString carries no content and no lengths`() {
        val store = store()
        val snapshot = store.record(
            original = "the acme merger closes friday",
            selectionStart = 0,
            selectionEnd = 0,
            applied = "The Acme merger closes Friday.",
            fieldToken = fieldA,
            nowMs = 0,
        )
        val printed = snapshot.toString()
        assertFalse("acme" in printed.lowercase(), printed)
        assertFalse("merger" in printed.lowercase(), printed)
        assertFalse("29" in printed, "no content lengths may leak: $printed")
        assertFalse("30" in printed, "no content lengths may leak: $printed")
    }

    @Test
    fun `store toString carries no content`() {
        val store = store()
        store.record("the acme merger", 0, 0, "The Acme merger.", fieldA, nowMs = 0)
        assertFalse("acme" in store.toString().lowercase(), store.toString())
    }
}
