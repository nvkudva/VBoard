package com.vboard.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.vboard.app.VBoardApp
import com.vboard.app.R
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.session.DictationStateMachine
import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.ErrorKind
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.UtteranceCommand
import com.vboard.app.settings.SettingsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives one dictation session: microphone -> streaming Zipformer partials ->
 * endpoint -> Parakeet final pass -> rules cleanup -> optional LLM refinement.
 * All state transitions flow through the core [DictationStateMachine]; this
 * class executes its effects on Android.
 */
class VoiceSessionController(
    private val service: Context,
    private val app: VBoardApp,
    private val host: Host,
) {
    interface Host {
        fun precedingText(): String
        fun fieldKind(): FieldKind
        fun updatePartial(text: String)
        fun commitUtterance(index: Int, text: String)
        fun replaceUtterance(index: Int, newText: String)
        fun deleteLastUtterance()
        fun onSessionEnded()
        fun showError(message: String, action: VoiceBarView.ErrorActionKind)
        fun showListening()
        fun showFinalizing()
        fun showRefining()
        fun onAmplitude(rms: Float)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var machine = DictationStateMachine()
    private var settings: SettingsSnapshot = SettingsSnapshot()
    private var fieldKind: FieldKind = FieldKind.TEXT

    private val capture = AudioCapture()
    private var audioJob: Job? = null
    private var silenceJob: Job? = null

    /** Raw samples of the in-flight utterance, for the Parakeet re-pass. */
    private val utteranceAudio = ArrayList<FloatArray>(64)
    private var utteranceSamples = 0
    private var stopAfterFinalize = false
    private var lastSpeechAt = 0L

    // ------------------------------------------------------------ public API

    fun startSession(fieldKind: FieldKind, settings: SettingsSnapshot) {
        this.fieldKind = fieldKind
        this.settings = settings
        machine = DictationStateMachine(
            DictationStateMachine.Config(refineEnabled = settings.llmRefineEnabled),
        )
        dispatch(Event.MicPressed)
        prepare()
    }

    /** Orb tap while listening: finalize what's been said, then end. */
    fun stopAndFinalize() {
        val state = machine.state
        if (state is DictationStateMachine.State.Listening && state.partial.isNotBlank()) {
            stopAfterFinalize = true
            finalizeUtterance()
        } else {
            dispatch(Event.StopRequested)
        }
    }

    fun cancelSession() {
        dispatch(Event.StopRequested)
    }

    /** Teardown without UI callbacks (view is going away). */
    fun cancelSessionSilently() {
        stopAudioInternals()
        machine.reset()
    }

    fun destroy() {
        stopAudioInternals()
        scope.cancel()
    }

    // ----------------------------------------------------------- preparation

    private fun prepare() {
        val granted = service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            dispatch(Event.PermissionDenied)
            return
        }
        scope.launch {
            val ready = withContext(Dispatchers.IO) {
                runCatching {
                    // Extract archives if a download just finished.
                    ModelCatalog.packs
                        .filter { it.kind != ModelKind.REFINER_LLM }
                        .forEach { app.modelStore.ensureExtracted(app.packInstaller, it) }
                    VoiceEngines.load(app)
                }.isSuccess && VoiceEngines.isLoaded
            }
            if (!ready) {
                dispatch(Event.ModelsMissing)
                return@launch
            }
            if (settings.llmRefineEnabled) {
                scope.launch(Dispatchers.IO) { VoiceEngines.loadRefiner(service, app) }
            }
            dispatch(Event.ModelsReady)
        }
    }

    // ------------------------------------------------------------ audio loop

    private fun startAudio() {
        utteranceAudio.clear()
        utteranceSamples = 0
        lastSpeechAt = System.currentTimeMillis()
        try {
            capture.start()
        } catch (_: IllegalStateException) {
            dispatch(Event.AudioError)
            return
        }
        val streaming = VoiceEngines.streaming ?: run {
            dispatch(Event.ModelsMissing)
            return
        }
        streaming.resetUtterance()
        audioJob = scope.launch(Dispatchers.IO) {
            var lastPartial = ""
            while (isActive && capture.isRecording) {
                val chunk = capture.read() ?: break
                utteranceAudio.add(chunk)
                utteranceSamples += chunk.size
                val rms = chunk.rmsLevel()
                withContext(Dispatchers.Main.immediate) { host.onAmplitude(rms) }

                streaming.acceptAudio(chunk)
                val partial = streaming.partialText()
                if (partial != lastPartial) {
                    lastPartial = partial
                    lastSpeechAt = System.currentTimeMillis()
                    withContext(Dispatchers.Main.immediate) { dispatch(Event.Partial(partial)) }
                }
                if (streaming.isEndpoint()) {
                    lastPartial = ""
                    withContext(Dispatchers.Main.immediate) {
                        if (machine.state is DictationStateMachine.State.Listening &&
                            (machine.state as DictationStateMachine.State.Listening).partial.isBlank()
                        ) {
                            // Endpoint on silence only: drop buffered audio, keep listening.
                            streaming.resetUtterance()
                            utteranceAudio.clear()
                            utteranceSamples = 0
                        } else {
                            finalizeUtterance()
                        }
                    }
                }
            }
        }
        silenceJob = scope.launch {
            while (isActive) {
                delay(1_000)
                if (System.currentTimeMillis() - lastSpeechAt > SILENCE_TIMEOUT_MS) {
                    dispatch(Event.SilenceTimeout)
                    break
                }
            }
        }
    }

    private fun stopAudioInternals() {
        audioJob?.cancel()
        audioJob = null
        silenceJob?.cancel()
        silenceJob = null
        capture.stop()
    }

    // ------------------------------------------------------------ finalizing

    private fun finalizeUtterance() {
        val samples = snapshotUtteranceAudio()
        VoiceEngines.streaming?.resetUtterance()
        dispatch(Event.EndpointDetected)
        if (machine.state !is DictationStateMachine.State.Finalizing) return
        host.showFinalizing()

        scope.launch {
            val finalText = withTimeoutOrNull(FINALIZE_WATCHDOG_MS) {
                withContext(Dispatchers.Default) {
                    runCatching { VoiceEngines.finalPass?.transcribe(samples) }.getOrNull()
                }
            } ?: fallbackPartial()

            val cleaned = cleanTranscript(finalText ?: "")
            when (cleaned.command) {
                UtteranceCommand.SCRATCH_THAT -> {
                    dispatch(Event.FinalTranscript(""))
                    dispatch(Event.ScratchThat)
                    host.showListening()
                }
                UtteranceCommand.STOP_LISTENING -> {
                    dispatch(Event.FinalTranscript(""))
                    dispatch(Event.StopRequested)
                }
                UtteranceCommand.NONE -> {
                    dispatch(Event.FinalTranscript(cleaned.text))
                    if (stopAfterFinalize) {
                        dispatch(Event.StopRequested)
                    } else {
                        host.showListening()
                    }
                }
            }
            stopAfterFinalize = false
        }
    }

    private fun fallbackPartial(): String {
        val state = machine.state
        return when (state) {
            is DictationStateMachine.State.Finalizing -> state.partial
            is DictationStateMachine.State.Listening -> state.partial
            else -> ""
        }
    }

    private fun snapshotUtteranceAudio(): FloatArray {
        val out = FloatArray(utteranceSamples)
        var offset = 0
        for (chunk in utteranceAudio) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        utteranceAudio.clear()
        utteranceSamples = 0
        return out
    }

    private fun cleanTranscript(raw: String) = app.cleaner.clean(
        CleanupRequest(
            transcript = raw,
            precedingText = host.precedingText(),
            fieldKind = fieldKind,
            options = settings.cleanupOptions(),
            ensureTerminalPunctuation = settings.autoPunctuate && fieldKind == FieldKind.TEXT,
        ),
    )

    // ------------------------------------------------------------ refinement

    private fun refineAsync(text: String, utteranceIndex: Int) {
        scope.launch {
            val refiner = withContext(Dispatchers.IO) {
                VoiceEngines.loadRefiner(service, app)
            } ?: return@launch
            host.showRefining()
            val refined = refiner.refine(text)
            if (machine.state is DictationStateMachine.State.Listening) host.showListening()
            if (refined != null && refined != text) {
                host.replaceUtterance(utteranceIndex, refined)
            }
        }
    }

    // ---------------------------------------------------------------- effects

    private fun dispatch(event: Event) {
        for (effect in machine.onEvent(event)) {
            execute(effect)
        }
    }

    private fun execute(effect: Effect) {
        when (effect) {
            Effect.ShowVoiceBar -> host.showListening()
            Effect.HideVoiceBar -> {
                stopAudioInternals()
                host.onSessionEnded()
            }
            Effect.StartAudio -> {
                host.showListening()
                startAudio()
            }
            Effect.StopAudio -> stopAudioInternals()
            is Effect.UpdatePartial -> host.updatePartial(effect.text)
            is Effect.CommitUtterance -> {
                host.commitUtterance(effect.utteranceIndex, effect.text)
                if (effect.refine) refineAsync(effect.text, effect.utteranceIndex)
            }
            Effect.DeleteLastUtterance -> host.deleteLastUtterance()
            is Effect.SignalError -> showError(effect.kind)
            is Effect.Haptic -> Unit // views already emit haptics on their own events
        }
    }

    private fun showError(kind: ErrorKind) {
        val (message, action) = when (kind) {
            ErrorKind.MIC_PERMISSION_DENIED ->
                service.getString(R.string.voice_error_no_permission) to
                    VoiceBarView.ErrorActionKind.OPEN_PERMISSION
            ErrorKind.MODEL_MISSING ->
                service.getString(R.string.voice_error_no_model) to
                    VoiceBarView.ErrorActionKind.OPEN_DOWNLOAD
            ErrorKind.AUDIO_UNAVAILABLE ->
                service.getString(R.string.voice_error_mic_busy) to
                    VoiceBarView.ErrorActionKind.DISMISS
            ErrorKind.INTERNAL ->
                service.getString(R.string.voice_error_generic) to
                    VoiceBarView.ErrorActionKind.DISMISS
        }
        host.showError(message, action)
    }

    companion object {
        private const val SILENCE_TIMEOUT_MS = 30_000L
        private const val FINALIZE_WATCHDOG_MS = 5_000L
    }
}

/**
 * Process-wide engine cache: models stay loaded across dictation sessions so
 * the second mic press starts in tens of milliseconds.
 */
object VoiceEngines {
    @Volatile var streaming: StreamingAsr? = null
        private set

    @Volatile var finalPass: FinalAsr? = null
        private set

    @Volatile private var refiner: LlmRefiner? = null

    val isLoaded: Boolean get() = streaming != null && finalPass != null

    @Synchronized
    fun load(app: VBoardApp) {
        if (isLoaded) return
        val streamingPaths = app.modelStore.streamingPaths(app.packInstaller) ?: return
        val parakeetPaths = app.modelStore.parakeetPaths(app.packInstaller) ?: return
        streaming = StreamingAsr(streamingPaths)
        finalPass = FinalAsr(parakeetPaths)
    }

    @Synchronized
    fun loadRefiner(context: Context, app: VBoardApp): LlmRefiner? {
        refiner?.let { return it }
        val path = app.modelStore.refinerModelPath(app.packInstaller) ?: return null
        return LlmRefiner(context.applicationContext, path).also { refiner = it }
    }

    @Synchronized
    fun releaseAll() {
        streaming?.release()
        finalPass?.release()
        refiner?.release()
        streaming = null
        finalPass = null
        refiner = null
    }
}
