package com.vboard.core.session

import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.ErrorKind
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.DictationStateMachine.State
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * VB-123: losing the microphone mid-utterance (incoming call, another app taking
 * audio focus) must **commit** the buffered speech, not discard it.
 *
 * The bug these guard against is not a crash — it is the user watching their
 * sentence appear in the voice bar, taking a call, and finding nothing in the
 * field afterwards.
 */
class DictationInterruptTest {

    private fun listening(): DictationStateMachine {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        return machine
    }

    @Test
    fun `focus loss mid-utterance finalizes instead of discarding`() {
        val machine = listening()
        machine.onEvent(Event.Partial("meet me at the usual place"))

        val effects = machine.onEvent(Event.AudioFocusLost)

        assertTrue(
            Effect.BeginFinalize("meet me at the usual place", 0) in effects,
            "focus loss must transcribe the buffered utterance: $effects",
        )
        assertTrue(Effect.StopAudio in effects, "the mic must be released for the call")
        assertEquals(
            State.Finalizing(
                partial = "meet me at the usual place",
                utteranceIndex = 0,
                stopAfterCommit = true,
                endWithError = ErrorKind.AUDIO_UNAVAILABLE,
            ),
            machine.state,
        )
    }

    @Test
    fun `the mic is cut before the finalize snapshot is taken`() {
        // Ordering is load-bearing: the app layer takes the whole buffer for a
        // finalize that follows a stopped mic, and only the decoded prefix
        // otherwise. StopAudio has to be executed first for that to hold.
        val machine = listening()
        machine.onEvent(Event.Partial("half a sentence"))
        val effects = machine.onEvent(Event.AudioFocusLost)
        val stopAt = effects.indexOf(Effect.StopAudio)
        val finalizeAt = effects.indexOfFirst { it is Effect.BeginFinalize }
        assertTrue(stopAt in 0 until finalizeAt, "StopAudio must precede BeginFinalize: $effects")
    }

    @Test
    fun `the interrupted utterance commits before the error is shown`() {
        val machine = listening()
        machine.onEvent(Event.Partial("call me back"))
        machine.onEvent(Event.AudioFocusLost)

        val effects = machine.onEvent(Event.FinalTranscript("Call me back."))

        val commitAt = effects.indexOfFirst { it is Effect.CommitUtterance }
        val errorAt = effects.indexOfFirst { it is Effect.SignalError }
        assertTrue(commitAt >= 0, "the buffered utterance was discarded: $effects")
        assertTrue(commitAt < errorAt, "commit must precede the error: $effects")
        assertEquals(
            Effect.CommitUtterance("Call me back.", 0, refine = false),
            effects[commitAt],
        )
        assertEquals(State.Error(ErrorKind.AUDIO_UNAVAILABLE), machine.state)
    }

    @Test
    fun `focus loss with nothing said is just an error`() {
        val machine = listening()
        val effects = machine.onEvent(Event.AudioFocusLost)
        assertTrue(effects.none { it is Effect.BeginFinalize })
        assertTrue(Effect.StopAudio in effects)
        assertEquals(State.Error(ErrorKind.AUDIO_UNAVAILABLE), machine.state)
    }

    @Test
    fun `focus loss during an in-flight finalize keeps the commit and adds the error`() {
        val machine = listening()
        machine.onEvent(Event.Partial("already finalizing"))
        machine.onEvent(Event.EndpointDetected)

        val lost = machine.onEvent(Event.AudioFocusLost)
        assertTrue(Effect.StopAudio in lost)
        // No second BeginFinalize: the decode already running covers this audio.
        assertTrue(lost.none { it is Effect.BeginFinalize })

        val effects = machine.onEvent(Event.FinalTranscript("Already finalizing."))
        assertTrue(Effect.CommitUtterance("Already finalizing.", 0, refine = false) in effects)
        assertEquals(State.Error(ErrorKind.AUDIO_UNAVAILABLE), machine.state)
    }

    @Test
    fun `focus loss after a user stop does not stop the audio twice`() {
        val machine = listening()
        machine.onEvent(Event.Partial("tail end"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.StopRequested) // StopAudio goes out here
        val effects = machine.onEvent(Event.AudioFocusLost)
        assertTrue(Effect.StopAudio !in effects, "double teardown of one record: $effects")
        val finalEffects = machine.onEvent(Event.FinalTranscript("Tail end."))
        assertTrue(Effect.CommitUtterance("Tail end.", 0, refine = false) in finalEffects)
    }

    @Test
    fun `an audio error after the mic was already cut does not stop it twice`() {
        // Pre-existing hazard uncovered while adding the interrupt path: a
        // deferred stop had already emitted StopAudio, and a late AudioError
        // emitted a second one for a record that no longer exists.
        val machine = listening()
        machine.onEvent(Event.Partial("hi"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.StopRequested)
        val effects = machine.onEvent(Event.AudioError)
        assertTrue(Effect.StopAudio !in effects, "second StopAudio for one record: $effects")
        assertEquals(State.Error(ErrorKind.AUDIO_UNAVAILABLE), machine.state)
    }

    @Test
    fun `an interrupted blank utterance still reports the interruption`() {
        val machine = listening()
        machine.onEvent(Event.Partial("um"))
        machine.onEvent(Event.AudioFocusLost)
        val effects = machine.onEvent(Event.FinalTranscript(""))
        assertTrue(effects.none { it is Effect.CommitUtterance })
        assertTrue(Effect.SignalError(ErrorKind.AUDIO_UNAVAILABLE) in effects)
        assertEquals(State.Error(ErrorKind.AUDIO_UNAVAILABLE), machine.state)
    }

    @Test
    fun `the error can be dismissed and dictation restarted after an interruption`() {
        val machine = listening()
        machine.onEvent(Event.Partial("interrupted"))
        machine.onEvent(Event.AudioFocusLost)
        machine.onEvent(Event.FinalTranscript("Interrupted."))
        assertIs<State.Error>(machine.state)

        // VB-123 is explicit that there is no auto-retry: the user re-taps.
        machine.onEvent(Event.MicPressed)
        assertIs<State.PreparingModels>(machine.state)
        machine.onEvent(Event.ModelsReady)
        assertEquals(State.Listening("", 0), machine.state)
    }

    @Test
    fun `focus loss in idle or error is ignored`() {
        val idle = DictationStateMachine()
        assertTrue(idle.onEvent(Event.AudioFocusLost).isEmpty())
        assertEquals(State.Idle, idle.state)

        val errored = DictationStateMachine()
        errored.onEvent(Event.MicPressed)
        errored.onEvent(Event.ModelsMissing)
        assertTrue(errored.onEvent(Event.AudioFocusLost).isEmpty())
        assertEquals(State.Error(ErrorKind.MODEL_MISSING), errored.state)
    }

    @Test
    fun `focus loss while preparing reports an unavailable mic`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        val effects = machine.onEvent(Event.AudioFocusLost)
        // Nothing was captured yet, so nothing to rescue — and no StopAudio for
        // a record that was never opened.
        assertTrue(Effect.StopAudio !in effects)
        assertEquals(State.Error(ErrorKind.AUDIO_UNAVAILABLE), machine.state)
    }

    // ------------------------------------------------------ overrun reporting

    @Test
    fun `overruns are reported with a running session total`() {
        val machine = listening()
        val first = machine.onEvent(Event.AudioOverrun(1_600))
        assertEquals(listOf<Effect>(Effect.NoteAudioOverrun(1_600, 1_600)), first)

        machine.onEvent(Event.Partial("still going"))
        val second = machine.onEvent(Event.AudioOverrun(3_200))
        assertEquals(listOf<Effect>(Effect.NoteAudioOverrun(3_200, 4_800)), second)
        assertEquals(4_800L, machine.droppedSamplesThisSession)
    }

    @Test
    fun `an overrun never disturbs the utterance in flight`() {
        val machine = listening()
        machine.onEvent(Event.Partial("keep this partial"))
        machine.onEvent(Event.AudioOverrun(1_600))
        assertEquals(State.Listening("keep this partial", 0), machine.state)

        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.AudioOverrun(1_600))
        assertEquals(State.Finalizing("keep this partial", 0), machine.state)

        val effects = machine.onEvent(Event.FinalTranscript("Keep this partial."))
        assertTrue(Effect.CommitUtterance("Keep this partial.", 0, refine = false) in effects)
    }

    @Test
    fun `the drop total resets with each session`() {
        val machine = listening()
        machine.onEvent(Event.AudioOverrun(1_600))
        machine.onEvent(Event.StopRequested)
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        assertEquals(0L, machine.droppedSamplesThisSession)
    }

    @Test
    fun `a zero-sample overrun is not reported`() {
        val machine = listening()
        assertTrue(machine.onEvent(Event.AudioOverrun(0)).isEmpty())
    }
}
