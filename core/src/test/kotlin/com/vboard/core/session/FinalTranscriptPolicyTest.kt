package com.vboard.core.session

import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.DictationStateMachine.State
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The final pass (Parakeet TDT) is an optional ~480MB upgrade, so a user with
 * only the streaming recognizer installed must still get committed text. These
 * pin the rule that makes that true — and the one that stops a blank or failed
 * final pass from silently deleting a sentence the user watched appear.
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
    fun `no final pass installed commits the streaming partial`() {
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
    fun `a streaming-only install still commits a full utterance`() {
        // End-to-end at the state-machine level: partial -> endpoint -> the
        // streaming text as the final transcript -> a real commit.
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        machine.onEvent(Event.Partial("no accuracy pack here"))
        machine.onEvent(Event.EndpointDetected)

        val chosen = FinalTranscriptPolicy.choose(
            finalPassText = null, // no Parakeet installed
            streamingPartial = "no accuracy pack here",
        )
        val effects = machine.onEvent(Event.FinalTranscript(chosen))

        assertTrue(
            Effect.CommitUtterance("no accuracy pack here", 0, refine = false) in effects,
            "a streaming-only install must still commit: $effects",
        )
        assertEquals(State.Listening("", 1), machine.state)
    }
}
