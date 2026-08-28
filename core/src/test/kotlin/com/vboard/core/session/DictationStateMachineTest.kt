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
}
