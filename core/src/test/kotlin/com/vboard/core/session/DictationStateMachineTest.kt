package com.vboard.core.session

import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.ErrorKind
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.DictationStateMachine.State
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DictationStateMachineTest {

    private fun startedMachine(refine: Boolean = false): DictationStateMachine {
        val machine = DictationStateMachine(DictationStateMachine.Config(refineEnabled = refine))
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        return machine
    }

    @Test
    fun `mic press shows voice bar and prepares models`() {
        val machine = DictationStateMachine()
        val effects = machine.onEvent(Event.MicPressed)
        assertIs<State.PreparingModels>(machine.state)
        assertTrue(Effect.ShowVoiceBar in effects)
        assertTrue(effects.any { it is Effect.Haptic })
    }

    @Test
    fun `models ready starts audio and listening`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        val effects = machine.onEvent(Event.ModelsReady)
        assertEquals(State.Listening("", 0), machine.state)
        assertTrue(Effect.StartAudio in effects)
    }

    @Test
    fun `missing models produce error state`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        val effects = machine.onEvent(Event.ModelsMissing)
        assertEquals(State.Error(ErrorKind.MODEL_MISSING), machine.state)
        assertTrue(Effect.SignalError(ErrorKind.MODEL_MISSING) in effects)
    }

    @Test
    fun `permission denied produces error state`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.PermissionDenied)
        assertEquals(State.Error(ErrorKind.MIC_PERMISSION_DENIED), machine.state)
    }

    @Test
    fun `partials update state and ui`() {
        val machine = startedMachine()
        val effects = machine.onEvent(Event.Partial("hello wor"))
        assertEquals(State.Listening("hello wor", 0), machine.state)
        assertEquals(listOf<Effect>(Effect.UpdatePartial("hello wor")), effects)
    }

    @Test
    fun `endpoint with speech moves to finalizing`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("hello world"))
        machine.onEvent(Event.EndpointDetected)
        assertEquals(State.Finalizing("hello world", 0), machine.state)
    }

    @Test
    fun `endpoint with no speech stays listening`() {
        val machine = startedMachine()
        machine.onEvent(Event.EndpointDetected)
        assertEquals(State.Listening("", 0), machine.state)
    }

    @Test
    fun `final transcript commits and continues listening`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("hello world"))
        machine.onEvent(Event.EndpointDetected)
        val effects = machine.onEvent(Event.FinalTranscript("Hello world."))
        assertEquals(State.Listening("", 1), machine.state)
        assertTrue(Effect.CommitUtterance("Hello world.", 0, refine = false) in effects)
    }

    @Test
    fun `refine flag propagates to commit effect`() {
        val machine = startedMachine(refine = true)
        machine.onEvent(Event.Partial("hi"))
        machine.onEvent(Event.EndpointDetected)
        val effects = machine.onEvent(Event.FinalTranscript("Hi."))
        assertTrue(Effect.CommitUtterance("Hi.", 0, refine = true) in effects)
    }

    @Test
    fun `blank final transcript commits nothing`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("um"))
        machine.onEvent(Event.EndpointDetected)
        val effects = machine.onEvent(Event.FinalTranscript(""))
        assertEquals(State.Listening("", 1), machine.state)
        assertTrue(effects.none { it is Effect.CommitUtterance })
    }

    @Test
    fun `continuous dictation increments utterance index`() {
        val machine = startedMachine()
        repeat(3) { round ->
            machine.onEvent(Event.Partial("utterance $round"))
            machine.onEvent(Event.EndpointDetected)
            machine.onEvent(Event.FinalTranscript("Utterance $round."))
        }
        assertEquals(State.Listening("", 3), machine.state)
    }

    @Test
    fun `partial during finalizing keeps pipeline flowing`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("first"))
        machine.onEvent(Event.EndpointDetected)
        val effects = machine.onEvent(Event.Partial("second utt"))
        assertIs<State.Finalizing>(machine.state)
        assertEquals(listOf<Effect>(Effect.UpdatePartial("second utt")), effects)
    }

    @Test
    fun `scratch that deletes last utterance`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("hello"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.FinalTranscript("Hello."))
        val effects = machine.onEvent(Event.ScratchThat)
        assertTrue(Effect.DeleteLastUtterance in effects)
        assertEquals(State.Listening("", 1), machine.state)
    }

    @Test
    fun `stop request tears down session`() {
        val machine = startedMachine()
        val effects = machine.onEvent(Event.StopRequested)
        assertEquals(State.Idle, machine.state)
        assertTrue(Effect.StopAudio in effects)
        assertTrue(Effect.HideVoiceBar in effects)
    }

    @Test
    fun `mic press while listening stops session`() {
        val machine = startedMachine()
        machine.onEvent(Event.MicPressed)
        assertEquals(State.Idle, machine.state)
    }

    @Test
    fun `silence timeout ends session`() {
        val machine = startedMachine()
        val effects = machine.onEvent(Event.SilenceTimeout)
        assertEquals(State.Idle, machine.state)
        assertTrue(Effect.StopAudio in effects)
    }

    @Test
    fun `audio error while listening stops audio and reports`() {
        val machine = startedMachine()
        val effects = machine.onEvent(Event.AudioError)
        assertEquals(State.Error(ErrorKind.AUDIO_UNAVAILABLE), machine.state)
        assertTrue(Effect.StopAudio in effects)
    }

    @Test
    fun `error can be dismissed back to idle`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsMissing)
        val effects = machine.onEvent(Event.ErrorDismissed)
        assertEquals(State.Idle, machine.state)
        assertTrue(Effect.HideVoiceBar in effects)
    }

    @Test
    fun `mic press from error retries`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsMissing)
        machine.onEvent(Event.MicPressed)
        assertIs<State.PreparingModels>(machine.state)
    }

    @Test
    fun `irrelevant events in idle are ignored`() {
        val machine = DictationStateMachine()
        val effects = machine.onEvent(Event.Partial("stray"))
        assertEquals(State.Idle, machine.state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `reset returns to idle`() {
        val machine = startedMachine()
        machine.reset()
        assertEquals(State.Idle, machine.state)
    }

    // ------------------------------------------------------- finalize plumbing

    @Test
    fun `endpoint with speech asks the app layer to finalize exactly once`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("hello world"))
        val first = machine.onEvent(Event.EndpointDetected)
        assertTrue(Effect.BeginFinalize("hello world", 0) in first)

        // H2: a second endpoint for the same utterance used to leave the state
        // untouched, so the app's "am I finalizing?" check passed and a second
        // decode committed the same words again.
        val second = machine.onEvent(Event.EndpointDetected)
        assertTrue(second.none { it is Effect.BeginFinalize })
        assertEquals(State.Finalizing("hello world", 0), machine.state)
    }

    @Test
    fun `overlapping endpoints cannot double-commit one utterance`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("only once"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.EndpointDetected)
        val commits = machine.onEvent(Event.FinalTranscript("Only once."))
            .filterIsInstance<Effect.CommitUtterance>()
        assertEquals(1, commits.size)
        assertEquals(0, commits.single().utteranceIndex)
    }

    // ---------------------------------------------------------- deferred stop

    @Test
    fun `stop while finalizing still commits the utterance`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("last sentence"))
        machine.onEvent(Event.EndpointDetected)

        // The user taps stop after the 0.8s endpoint has already fired — the
        // common path, since they are taught to stop talking before tapping.
        val stopEffects = machine.onEvent(Event.StopRequested)
        assertTrue(Effect.StopAudio in stopEffects)
        assertTrue(Effect.HideVoiceBar !in stopEffects)
        assertEquals(State.Finalizing("last sentence", 0, stopAfterCommit = true), machine.state)

        val finalEffects = machine.onEvent(Event.FinalTranscript("Last sentence."))
        assertTrue(Effect.CommitUtterance("Last sentence.", 0, refine = false) in finalEffects)
        assertTrue(Effect.HideVoiceBar in finalEffects)
        assertEquals(State.Idle, machine.state)
    }

    @Test
    fun `deferred stop commits before it hides the bar`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("ordering matters"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.StopRequested)
        val effects = machine.onEvent(Event.FinalTranscript("Ordering matters."))
        val commitAt = effects.indexOfFirst { it is Effect.CommitUtterance }
        val hideAt = effects.indexOf(Effect.HideVoiceBar)
        assertTrue(commitAt in 0 until hideAt, "commit must precede teardown: $effects")
    }

    @Test
    fun `repeated stop while finalizing does not restart audio teardown`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("hi"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.StopRequested)
        val again = machine.onEvent(Event.StopRequested)
        assertTrue(again.isEmpty())
        assertEquals(State.Finalizing("hi", 0, stopAfterCommit = true), machine.state)
    }

    @Test
    fun `deferred stop with a blank final still ends the session`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("um"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.StopRequested)
        val effects = machine.onEvent(Event.FinalTranscript(""))
        assertTrue(effects.none { it is Effect.CommitUtterance })
        assertTrue(Effect.HideVoiceBar in effects)
        assertEquals(State.Idle, machine.state)
    }

    @Test
    fun `stop while listening still ends immediately`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("mid word"))
        val effects = machine.onEvent(Event.StopRequested)
        assertEquals(State.Idle, machine.state)
        assertTrue(Effect.StopAudio in effects)
        assertTrue(Effect.HideVoiceBar in effects)
    }

    // --------------------------------------------------- models lost mid-session

    @Test
    fun `models missing while listening stops audio and reports`() {
        val machine = startedMachine()
        // H1: this used to fall through to a no-op, leaving the machine in
        // Listening with the halo up, no reader loop and no way out.
        val effects = machine.onEvent(Event.ModelsMissing)
        assertEquals(State.Error(ErrorKind.MODEL_MISSING), machine.state)
        assertTrue(Effect.StopAudio in effects)
        assertTrue(Effect.SignalError(ErrorKind.MODEL_MISSING) in effects)
    }

    @Test
    fun `models missing while finalizing stops audio and reports`() {
        val machine = startedMachine()
        machine.onEvent(Event.Partial("hello"))
        machine.onEvent(Event.EndpointDetected)
        val effects = machine.onEvent(Event.ModelsMissing)
        assertEquals(State.Error(ErrorKind.MODEL_MISSING), machine.state)
        assertTrue(Effect.StopAudio in effects)
    }

    @Test
    fun `unusable models report a corrupt install, not a missing one`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        val effects = machine.onEvent(Event.ModelsUnusable)
        assertEquals(State.Error(ErrorKind.MODEL_CORRUPT), machine.state)
        assertTrue(Effect.SignalError(ErrorKind.MODEL_CORRUPT) in effects)
    }
}
