package com.vboard.core.session

import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.DictationStateMachine.State
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the rule that stops a blank, failed or timed-out final pass from silently
 * deleting a sentence the user watched the streaming partial spell out.
 */
class FinalTranscriptPolicyTest {

    @Test
    fun `the final pass wins when it produces text`() {
        assertEquals(
            "Meet me at eight.",
            FinalTranscriptPolicy.choose("Meet me at eight.", "meet me at 8"),
        )
    }

    @Test
    fun `a final pass that produced nothing commits the streaming partial`() {
        assertEquals(
            "meet me at 8",
            FinalTranscriptPolicy.choose(null, "meet me at 8"),
        )
    }

    @Test
    fun `a blank or whitespace final pass result is treated as a failure`() {
        assertEquals("hello", FinalTranscriptPolicy.choose("", "hello"))
        assertEquals("hello", FinalTranscriptPolicy.choose("   ", "hello"))
    }

    @Test
    fun `nothing heard at all commits nothing`() {
        assertEquals("", FinalTranscriptPolicy.choose(null, ""))
    }

    @Test
    fun `a timed-out final pass still commits a full utterance`() {
        // End-to-end at the state-machine level: partial -> endpoint -> the
        // streaming text standing in for a final pass that never answered ->
        // a real commit, not a swallowed sentence.
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        machine.onEvent(Event.Partial("the final pass timed out"))
        machine.onEvent(Event.EndpointDetected)

        val chosen = FinalTranscriptPolicy.choose(
            finalPassText = null,
            streamingPartial = "the final pass timed out",
        )
        val effects = machine.onEvent(Event.FinalTranscript(chosen))

        assertTrue(
            Effect.CommitUtterance("the final pass timed out", 0, refine = false) in effects,
            "a failed final pass must still commit the partial: $effects",
        )
        assertEquals(State.Listening("", 1), machine.state)
    }
}
