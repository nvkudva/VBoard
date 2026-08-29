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
 * A stop requested while an utterance is finalizing is deferred until that
 * utterance commits (Finalizing.stopAfterCommit); ending the session at the
 * moment of the tap is what used to throw away the user's last sentence.
 * LLM refinement is deliberately NOT a state: it runs post-commit in parallel
 * (see CommitUtterance.refine) so it never blocks the next utterance.
 * Losing the microphone to a call or another app (Event.AudioFocusLost) takes
 * the same deferral route as a stop: the buffered utterance is transcribed and
 * committed first, and only then does the session end in an error (VB-123).
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

        /**
         * The final ASR pass is running for [utteranceIndex]. Being a real state
         * is what makes "a finalize is already in flight" expressible: a second
         * endpoint for the same utterance is absorbed here rather than starting a
         * second decode that commits the same words twice.
         *
         * @property stopAfterCommit the user asked to stop while the final pass
         *   was still running. The session must not end until that commit lands,
         *   or the sentence they just watched being transcribed is discarded.
         * @property endWithError something outside the app took the microphone
         *   (an incoming call, another app grabbing audio focus). Same deferral
         *   as [stopAfterCommit] — the buffered speech is still transcribed and
         *   committed — but the session ends in this error rather than silently,
         *   so the user is told why the mic stopped (VB-123).
         */
        data class Finalizing(
            val partial: String,
            val utteranceIndex: Int,
            val stopAfterCommit: Boolean = false,
            val endWithError: ErrorKind? = null,
        ) : State

        data class Error(val kind: ErrorKind) : State
    }

    enum class ErrorKind {
        MIC_PERMISSION_DENIED,
        MODEL_MISSING,

        /**
         * The pack is on disk but unusable: corrupt archive, failed native load,
         * or out of memory. Distinct from [MODEL_MISSING] because the repair is a
         * re-download, and collapsing the two sent the user to a screen that
         * already reported the pack as Installed and offered nothing to do.
         */
        MODEL_CORRUPT,
        AUDIO_UNAVAILABLE,
        INTERNAL,
    }

    sealed interface Event {
        data object MicPressed : Event
        data object ModelsReady : Event
        data object ModelsMissing : Event
        /** Models are installed but could not be loaded; see [ErrorKind.MODEL_CORRUPT]. */
        data object ModelsUnusable : Event
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

        /**
         * The microphone was taken away mid-session: audio focus lost, or the
         * telephony stack went off-hook. Distinct from [AudioError] because the
         * buffered speech is still good — the machine finalizes it and only then
         * reports the interruption (VB-123).
         */
        data object AudioFocusLost : Event

        /**
         * The capture-to-decode queue overflowed: [droppedSamples] never reached
         * the streaming recognizer. Purely observational — the final pass still
         * has those samples — but it must not be invisible, because the way this
         * failure presents to a user is "the model is inaccurate" (VB-106).
         */
        data class AudioOverrun(val droppedSamples: Int) : Event
    }

    sealed interface Effect {
        data object ShowVoiceBar : Effect
        data object HideVoiceBar : Effect
        data object StartAudio : Effect
        data object StopAudio : Effect
        data class UpdatePartial(val text: String) : Effect

        /**
         * Run the final ASR pass for [utteranceIndex]. Emitted only on the
         * Listening -> Finalizing edge, so however many endpoints the recognizer
         * fires the app layer is told to finalize an utterance exactly once.
         * [partial] is the text to keep when the final pass yields nothing.
         */
        data class BeginFinalize(val partial: String, val utteranceIndex: Int) : Effect

        /** Commit [text] to the field; when [refine] the app runs LLM refinement async. */
        data class CommitUtterance(val text: String, val utteranceIndex: Int, val refine: Boolean) : Effect
        data object DeleteLastUtterance : Effect
        data class SignalError(val kind: ErrorKind) : Effect
        data class Haptic(val kind: HapticKind) : Effect

        /**
         * Audio was dropped before it reached the streaming recognizer. The app
         * layer logs it (counts only — never audio, never transcript text); it
         * exists as an effect so the loss is observable in a bug report instead
         * of being a silent driver overrun.
         */
        data class NoteAudioOverrun(
            val droppedSamples: Int,
            val sessionTotalSamples: Long,
        ) : Effect
    }

    enum class HapticKind { SESSION_START, UTTERANCE_COMMIT, SESSION_END, ERROR }

    var state: State = State.Idle
        private set

    /** Samples lost before reaching the streaming recognizer this session. */
    var droppedSamplesThisSession: Long = 0L
        private set

    fun onEvent(event: Event): List<Effect> {
        val (next, effects) = reduce(state, event)
        state = next
        return effects
    }

    fun reset() {
        state = State.Idle
        droppedSamplesThisSession = 0L
    }

    private fun reduce(state: State, event: Event): Pair<State, List<Effect>> = when (state) {
        is State.Idle -> when (event) {
            Event.MicPressed -> {
                droppedSamplesThisSession = 0L
                State.PreparingModels to listOf(
                    Effect.ShowVoiceBar,
                    Effect.Haptic(HapticKind.SESSION_START),
                )
            }
            else -> state to emptyList()
        }

        is State.PreparingModels -> when (event) {
            Event.ModelsReady -> State.Listening("", 0) to listOf(Effect.StartAudio)
            Event.ModelsMissing -> errorState(ErrorKind.MODEL_MISSING)
            Event.ModelsUnusable -> errorState(ErrorKind.MODEL_CORRUPT)
            Event.PermissionDenied -> errorState(ErrorKind.MIC_PERMISSION_DENIED)
            Event.AudioError -> errorState(ErrorKind.AUDIO_UNAVAILABLE)
            // Nothing is buffered yet, so there is nothing to rescue: report it.
            Event.AudioFocusLost -> errorState(ErrorKind.AUDIO_UNAVAILABLE)
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
                    State.Finalizing(state.partial, state.utteranceIndex) to
                        listOf(Effect.BeginFinalize(state.partial, state.utteranceIndex))
                }
            is Event.FinalTranscript -> commitThen(event.text, state.utteranceIndex, stop = false)
            Event.ScratchThat ->
                State.Listening("", state.utteranceIndex) to listOf(
                    Effect.DeleteLastUtterance,
                    Effect.Haptic(HapticKind.UTTERANCE_COMMIT),
                )
            Event.StopRequested, Event.MicPressed -> stopEffects(started = true)
            Event.SilenceTimeout -> stopEffects(started = true)
            Event.AudioError -> errorState(ErrorKind.AUDIO_UNAVAILABLE, stopAudio = true)
            // VB-123. The mic is gone either way, but what has already been said
            // is not: cut capture, then transcribe the buffered utterance and
            // commit it before the error is shown. Discarding it here is the
            // whole defect — an incoming call must not eat the user's sentence.
            // StopAudio is emitted first on purpose: the app layer takes the
            // *entire* buffer for a finalize that follows a stopped mic, rather
            // than only the part the decoder had caught up with.
            Event.AudioFocusLost ->
                if (state.partial.isBlank()) {
                    errorState(ErrorKind.AUDIO_UNAVAILABLE, stopAudio = true)
                } else {
                    State.Finalizing(
                        partial = state.partial,
                        utteranceIndex = state.utteranceIndex,
                        stopAfterCommit = true,
                        endWithError = ErrorKind.AUDIO_UNAVAILABLE,
                    ) to listOf(
                        Effect.StopAudio,
                        Effect.BeginFinalize(state.partial, state.utteranceIndex),
                    )
                }
            is Event.AudioOverrun -> state to noteOverrun(event.droppedSamples)
            // The mic is already hot when the engines turn out to be gone (a pack
            // deleted mid-session). Without this the machine sat in Listening with
            // the halo up, no reader loop and no way out.
            Event.ModelsMissing -> errorState(ErrorKind.MODEL_MISSING, stopAudio = true)
            Event.ModelsUnusable -> errorState(ErrorKind.MODEL_CORRUPT, stopAudio = true)
            else -> state to emptyList()
        }

        is State.Finalizing -> when (event) {
            is Event.FinalTranscript ->
                commitThen(
                    text = event.text,
                    index = state.utteranceIndex,
                    stop = state.stopAfterCommit,
                    endWithError = state.endWithError,
                )
            is Event.Partial ->
                // Next utterance already started while the previous finalizes.
                state to listOf(Effect.UpdatePartial(event.text))
            // A second endpoint for the utterance already being finalized: absorb
            // it. Emitting BeginFinalize again would run a second decode over the
            // same audio and commit the utterance twice.
            Event.EndpointDetected -> state to emptyList()
            Event.StopRequested, Event.MicPressed ->
                // Deferred stop. Users are taught "stop talking, then tap stop", so
                // the tap almost always lands after the 0.8s endpoint has already
                // moved us here. Tearing down now would drop the commit on the
                // floor; instead cut the mic and end the session once it lands.
                state.copy(stopAfterCommit = true) to
                    buildList { if (!state.stopAfterCommit) add(Effect.StopAudio) }
            Event.SilenceTimeout -> state to emptyList()
            // The final pass for this utterance is already running over audio
            // that was captured before the interruption, so let it land — but
            // remember to end in the error rather than back in Listening.
            Event.AudioFocusLost ->
                state.copy(
                    stopAfterCommit = true,
                    endWithError = state.endWithError ?: ErrorKind.AUDIO_UNAVAILABLE,
                ) to buildList { if (!state.stopAfterCommit) add(Effect.StopAudio) }
            is Event.AudioOverrun -> state to noteOverrun(event.droppedSamples)
            // stopAudio only when the mic has not already been cut: a second
            // StopAudio for one session is a teardown of a record that is
            // already gone.
            Event.AudioError ->
                errorState(ErrorKind.AUDIO_UNAVAILABLE, stopAudio = !state.stopAfterCommit)
            Event.ModelsMissing ->
                errorState(ErrorKind.MODEL_MISSING, stopAudio = !state.stopAfterCommit)
            Event.ModelsUnusable ->
                errorState(ErrorKind.MODEL_CORRUPT, stopAudio = !state.stopAfterCommit)
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

    /**
     * Commits [text] as utterance [index]. When [stop] the session ends here —
     * that is the deferred stop a tap during Finalizing asked for, and the commit
     * is emitted *before* the teardown so the words reach the field.
     */
    private fun commitThen(
        text: String,
        index: Int,
        stop: Boolean,
        endWithError: ErrorKind? = null,
    ): Pair<State, List<Effect>> {
        val effects = mutableListOf<Effect>()
        if (text.isNotBlank()) {
            effects.add(Effect.CommitUtterance(text, index, refine = config.refineEnabled))
            effects.add(Effect.Haptic(HapticKind.UTTERANCE_COMMIT))
        } else {
            effects.add(Effect.UpdatePartial(""))
        }
        if (endWithError != null) {
            // The commit is emitted first, then the explanation: the user's
            // words reach the field and the bar then says why the mic stopped.
            // StopAudio already went out when the interruption was reported.
            effects.add(Effect.SignalError(endWithError))
            effects.add(Effect.Haptic(HapticKind.ERROR))
            return State.Error(endWithError) to effects
        }
        if (!stop) return State.Listening("", index + 1) to effects
        // StopAudio already went out when the stop was requested.
        effects.add(Effect.HideVoiceBar)
        effects.add(Effect.Haptic(HapticKind.SESSION_END))
        return State.Idle to effects
    }

    private fun noteOverrun(droppedSamples: Int): List<Effect> {
        if (droppedSamples <= 0) return emptyList()
        droppedSamplesThisSession += droppedSamples
        return listOf(Effect.NoteAudioOverrun(droppedSamples, droppedSamplesThisSession))
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
