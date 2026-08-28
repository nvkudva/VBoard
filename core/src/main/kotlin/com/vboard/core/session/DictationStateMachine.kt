package com.vboard.core.session

/**
 * Pure reducer for the dictation lifecycle. The Android layer feeds it events
 * (mic press, ASR partials, endpoints, errors) and executes the effects it
 * returns (audio control, UI updates, text commits). Keeping this platform-free
 * makes the entire voice UX unit-testable.
 *
 * Lifecycle:
 *   Idle -> PreparingModels -> Listening <-> Finalizing -> Listening ... -> Idle
 * Continuous dictation: after an utterance finalizes and commits, the machine
 * returns to Listening with the next utterance index — the mic stays hot until
 * the user stops, an error occurs, or the silence timeout fires.
 * LLM refinement is deliberately NOT a state: it runs post-commit in parallel
 * (see CommitUtterance.refine) so it never blocks the next utterance.
 */
class DictationStateMachine(private val config: Config = Config()) {

    data class Config(
        /** When true, committed utterances are sent for LLM refinement. */
        val refineEnabled: Boolean = false,
    )

    sealed interface State {
        data object Idle : State
        data object PreparingModels : State
        data class Listening(val partial: String, val utteranceIndex: Int) : State
        data class Finalizing(val partial: String, val utteranceIndex: Int) : State
        data class Error(val kind: ErrorKind) : State
    }

    enum class ErrorKind { MIC_PERMISSION_DENIED, MODEL_MISSING, AUDIO_UNAVAILABLE, INTERNAL }

    sealed interface Event {
        data object MicPressed : Event
        data object ModelsReady : Event
        data object ModelsMissing : Event
        data object PermissionDenied : Event
        data object AudioError : Event
        data class Partial(val text: String) : Event
        data object EndpointDetected : Event
        /** Final transcript for the in-flight utterance (Parakeet, or the kept partial on watchdog timeout). */
        data class FinalTranscript(val text: String) : Event
        data object ScratchThat : Event
        data object StopRequested : Event
        data object SilenceTimeout : Event
        data object ErrorDismissed : Event
    }

    sealed interface Effect {
        data object ShowVoiceBar : Effect
        data object HideVoiceBar : Effect
        data object StartAudio : Effect
        data object StopAudio : Effect
        data class UpdatePartial(val text: String) : Effect
        /** Commit [text] to the field; when [refine] the app runs LLM refinement async. */
        data class CommitUtterance(val text: String, val utteranceIndex: Int, val refine: Boolean) : Effect
        data object DeleteLastUtterance : Effect
        data class SignalError(val kind: ErrorKind) : Effect
        data class Haptic(val kind: HapticKind) : Effect
    }

    enum class HapticKind { SESSION_START, UTTERANCE_COMMIT, SESSION_END, ERROR }

    var state: State = State.Idle
        private set

    fun onEvent(event: Event): List<Effect> {
        val (next, effects) = reduce(state, event)
        state = next
        return effects
    }

    fun reset() {
        state = State.Idle
    }

    private fun reduce(state: State, event: Event): Pair<State, List<Effect>> = when (state) {
        is State.Idle -> when (event) {
            Event.MicPressed -> State.PreparingModels to listOf(
                Effect.ShowVoiceBar,
                Effect.Haptic(HapticKind.SESSION_START),
            )
            else -> state to emptyList()
        }

        is State.PreparingModels -> when (event) {
            Event.ModelsReady -> State.Listening("", 0) to listOf(Effect.StartAudio)
            Event.ModelsMissing -> errorState(ErrorKind.MODEL_MISSING)
            Event.PermissionDenied -> errorState(ErrorKind.MIC_PERMISSION_DENIED)
            Event.AudioError -> errorState(ErrorKind.AUDIO_UNAVAILABLE)
            Event.StopRequested, Event.MicPressed -> stopEffects(started = false)
            else -> state to emptyList()
        }

        is State.Listening -> when (event) {
            is Event.Partial ->
                State.Listening(event.text, state.utteranceIndex) to
                    listOf(Effect.UpdatePartial(event.text))
            Event.EndpointDetected ->
                if (state.partial.isBlank()) {
                    // Nothing was said in this window; stay listening.
                    state to emptyList()
                } else {
                    State.Finalizing(state.partial, state.utteranceIndex) to emptyList()
                }
            is Event.FinalTranscript -> commitAndContinue(event.text, state.utteranceIndex)
            Event.ScratchThat ->
                State.Listening("", state.utteranceIndex) to listOf(
                    Effect.DeleteLastUtterance,
                    Effect.Haptic(HapticKind.UTTERANCE_COMMIT),
                )
            Event.StopRequested, Event.MicPressed -> stopEffects(started = true)
            Event.SilenceTimeout -> stopEffects(started = true)
            Event.AudioError -> errorState(ErrorKind.AUDIO_UNAVAILABLE, stopAudio = true)
            else -> state to emptyList()
        }

        is State.Finalizing -> when (event) {
            is Event.FinalTranscript -> commitAndContinue(event.text, state.utteranceIndex)
            // Watchdog in the app layer sends FinalTranscript(partial) on timeout,
            // so a stuck Parakeet can never swallow speech.
            is Event.Partial ->
                // Next utterance already started while the previous finalizes.
                state to listOf(Effect.UpdatePartial(event.text))
            Event.StopRequested, Event.MicPressed -> stopEffects(started = true)
            Event.AudioError -> errorState(ErrorKind.AUDIO_UNAVAILABLE, stopAudio = true)
            else -> state to emptyList()
        }

        is State.Error -> when (event) {
            Event.ErrorDismissed, Event.StopRequested ->
                State.Idle to listOf(Effect.HideVoiceBar)
            Event.MicPressed -> State.PreparingModels to listOf(
                Effect.Haptic(HapticKind.SESSION_START),
            )
            else -> state to emptyList()
        }
    }

    private fun commitAndContinue(text: String, index: Int): Pair<State, List<Effect>> {
        val effects = mutableListOf<Effect>()
        if (text.isNotBlank()) {
            effects.add(Effect.CommitUtterance(text, index, refine = config.refineEnabled))
            effects.add(Effect.Haptic(HapticKind.UTTERANCE_COMMIT))
        } else {
            effects.add(Effect.UpdatePartial(""))
        }
        return State.Listening("", index + 1) to effects
    }

    private fun stopEffects(started: Boolean): Pair<State, List<Effect>> =
        State.Idle to buildList {
            if (started) add(Effect.StopAudio)
            add(Effect.HideVoiceBar)
            add(Effect.Haptic(HapticKind.SESSION_END))
        }

    private fun errorState(kind: ErrorKind, stopAudio: Boolean = false): Pair<State, List<Effect>> =
        State.Error(kind) to buildList {
            if (stopAudio) add(Effect.StopAudio)
            add(Effect.SignalError(kind))
            add(Effect.Haptic(HapticKind.ERROR))
        }
}
