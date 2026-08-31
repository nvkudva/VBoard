package com.vboard.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.vboard.app.VBoardApp
import com.vboard.app.R
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.session.AudioPipeline
import com.vboard.core.session.DictationStateMachine
import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.ErrorKind
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.FinalTranscriptPolicy
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.CleanupResult
import com.vboard.core.text.FieldKind
import com.vboard.core.text.UtteranceCommand
import com.vboard.app.llm.LlmRefinerClient
import com.vboard.app.llm.RemoteRefiner
import com.vboard.app.llm.refinerClientOrNull
import com.vboard.app.settings.SettingsSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Drives one dictation session: microphone -> streaming Zipformer partials ->
 * endpoint -> Parakeet final pass -> rules cleanup -> optional LLM refinement.
 * All state transitions flow through the core [DictationStateMachine]; this
 * class executes its effects on Android.
 *
 * Threading, because most of the ways this can go wrong are threading bugs:
 *  - the state machine and every [Host] callback are touched only on the main
 *    thread;
 *  - [AudioCapture] lifecycle calls (start/release) are serialized onto one
 *    dedicated thread so a record is never opened before the previous one has
 *    been freed;
 *  - exactly one reader coroutine exists at a time, and it is the only owner of
 *    the capture buffer;
 *  - the final ASR decode gets its own single thread: decodes cannot overlap,
 *    and a slow one cannot starve Dispatchers.Default (which the suggestion
 *    strip also runs on).
 *
 * Reading and decoding are deliberately separate coroutines with an
 * [AudioPipeline] between them (VB-106). The reader does nothing but read,
 * buffer and hand off; it never touches the main thread and never waits on the
 * decoder. That is what stops a slow decode — or a busy UI thread — from
 * silently overrunning the ~400ms AudioRecord buffer, which used to hand
 * Parakeet audio with holes in it and make the model look inaccurate.
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
        /**
         * The engines are still loading, so nothing said right now is heard.
         * Only called when they were not already resident — a warm press goes
         * straight to [showListening].
         */
        fun showPreparing()
        fun showListening()
        fun showFinalizing()
        fun showRefining()
        fun onAmplitude(rms: Float)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Deliberately outlives [destroy]: releasing the microphone requires joining
     * a reader that may be parked in a blocking native read, and that join must
     * still happen when the session scope is being torn down.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var machine = DictationStateMachine()
    private var settings: SettingsSnapshot = SettingsSnapshot()
    private var fieldKind: FieldKind = FieldKind.TEXT

    private val capture = AudioCapture()
    private var audioJob: Job? = null
    private var monitorJob: Job? = null
    private var prepareJob: Job? = null
    private var finalizeJob: Job? = null
    private var audioTeardownJob: Job? = null

    /**
     * Focus is requested when the session enters Listening and abandoned from
     * [stopAudio], i.e. on every path out of it. The callback lands on the main
     * thread, which is where the state machine lives.
     */
    private val focus = AudioFocusGuard(service) { reason ->
        Log.w(TAG, "microphone interrupted: $reason")
        scope.launch { dispatch(Event.AudioFocusLost) }
    }

    /** True between a stop request and the reader actually exiting. */
    @Volatile
    private var audioStopRequested = false

    /**
     * Buffers the in-flight utterance for the Parakeet re-pass and hands chunks
     * to the streaming decoder. Recreated per session; see [AudioPipeline] for
     * why the two sides have different loss guarantees.
     */
    private var pipeline: AudioPipeline? = null

    /**
     * Latest chunk level, published by the reader and read by the monitor tick.
     * The reader used to hop to the main thread once per 100ms chunk to deliver
     * this, which meant a busy UI thread could stall the microphone read loop —
     * the exact stall that overruns the capture buffer.
     */
    @Volatile
    private var latestAmplitude = 0f

    private var lastSpeechAt = 0L

    @Volatile
    private var lastChunkAt = 0L

    // ------------------------------------------------------------ public API

    fun startSession(fieldKind: FieldKind, settings: SettingsSnapshot) {
        // A rapid second tap must not leave the first session's preparation or
        // microphone running: that produced two live AudioRecords feeding two
        // reader loops through one shared buffer.
        prepareJob?.cancel()
        prepareJob = null
        finalizeJob?.cancel()
        finalizeJob = null
        stopAudio()

        VoiceEngines.cancelIdleRelease()
        this.fieldKind = fieldKind
        this.settings = settings
        machine = DictationStateMachine(
            DictationStateMachine.Config(refineEnabled = settings.llmRefineEnabled),
        )
        dispatch(Event.MicPressed)
        prepare()
    }

    /**
     * Orb tap: finalize what's been said, then end.
     *
     * The mic is cut first so the utterance buffer stops growing under the
     * snapshot. Whether the machine is still Listening or has already moved to
     * Finalizing (the 0.8s endpoint usually beats the tap, because users are
     * taught to stop talking first), the stop is deferred until the commit lands.
     */
    fun stopAndFinalize() {
        stopAudio()
        val state = machine.state
        if (state is DictationStateMachine.State.Listening && state.partial.isNotBlank()) {
            dispatch(Event.EndpointDetected)
        }
        dispatch(Event.StopRequested)
    }

    fun cancelSession() {
        stopAudio()
        dispatch(Event.StopRequested)
    }

    /**
     * The editor is going away but anything already spoken must still land in the
     * field (VB-107), so this runs the same deferred-stop path as an orb tap
     * rather than discarding the buffered audio.
     *
     * The commit is asynchronous, so the input connection can still die before
     * the final pass returns. That no longer loses the utterance: the host holds
     * it and replays it into the next editor of the same app (W0.2, see
     * VBoardImeService.replayPendingVoiceCommit).
     */
    fun finishSession() {
        stopAndFinalize()
    }

    /** Teardown without UI callbacks (view is going away). */
    fun cancelSessionSilently() {
        prepareJob?.cancel()
        prepareJob = null
        finalizeJob?.cancel()
        finalizeJob = null
        stopAudio()
        machine.reset()
        VoiceEngines.scheduleIdleRelease()
    }

    fun destroy() {
        prepareJob?.cancel()
        finalizeJob?.cancel()
        stopAudio()
        scope.cancel()
        VoiceEngines.scheduleIdleRelease()
    }

    // ----------------------------------------------------------- preparation

    private fun prepare() {
        val granted = service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            dispatch(Event.PermissionDenied)
            return
        }
        // Say so rather than showing the listening UI over a mic that is not
        // recording yet: a cold press takes seconds, and the old bar spent them
        // claiming to listen.
        if (!VoiceEngines.isLoaded) host.showPreparing()
        prepareJob = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    // Extract archives if a download just finished.
                    ModelCatalog.packs
                        .filter { it.kind != ModelKind.REFINER_LLM }
                        .forEach { app.modelStore.ensureExtracted(app.packInstaller, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A pack that will not extract has already had its installed
                    // marker cleared, so the error screen's Download action leads
                    // somewhere that can actually repair it.
                    Log.e(TAG, "model extraction failed", e)
                    return@withContext VoiceEngines.LoadResult.BROKEN
                }
                VoiceEngines.load(app)
            }
            when (outcome) {
                VoiceEngines.LoadResult.READY -> {
                    if (settings.llmRefineEnabled) {
                        // Warm the refiner here, outside refine()'s own 3s budget:
                        // paying multi-second model init inside that budget made
                        // the first refinement time out every single time.
                        scope.launch(Dispatchers.IO) {
                            runCatching { VoiceEngines.loadRefiner(service, app)?.preload() }
                                .onFailure { Log.w(TAG, "refiner preload failed", it) }
                        }
                    }
                    dispatch(Event.ModelsReady)
                }
                VoiceEngines.LoadResult.MISSING -> dispatch(Event.ModelsMissing)
                VoiceEngines.LoadResult.BROKEN -> dispatch(Event.ModelsUnusable)
            }
        }
    }

    // ------------------------------------------------------------ audio loop

    private fun startAudio() {
        val streaming = VoiceEngines.streaming ?: run {
            dispatch(Event.ModelsMissing)
            return
        }
        val pipe = AudioPipeline()
        pipeline = pipe
        val now = System.currentTimeMillis()
        lastSpeechAt = now
        lastChunkAt = now
        latestAmplitude = 0f
        audioStopRequested = false
        streaming.resetUtterance()

        // Never leave a previous reader running against a new record.
        audioJob?.cancel()
        val pendingTeardown = audioTeardownJob
        audioJob = scope.launch {
            // The previous record must be freed before we open the next one.
            pendingTeardown?.join()
            // VB-123: hold focus for the length of the session, so other apps
            // stop playing into the microphone and the platform tells us when
            // something more important (a call) needs the audio path.
            focus.request()
            val started = withContext(audioDispatcher) {
                try {
                    capture.start()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The AudioRecord constructor throws IllegalArgumentException for
                    // an unsupported config and SecurityException without the
                    // permission; either one used to crash the IME process under the
                    // user's finger, because only IllegalStateException was caught.
                    Log.e(TAG, "microphone start failed", e)
                    false
                }
            }
            if (!started) {
                focus.abandon()
                dispatch(Event.AudioError)
                return@launch
            }
            lastChunkAt = System.currentTimeMillis()
            // Claim the engines for the reader's lifetime: freeing a recognizer
            // out from under a decode is a native crash, not a dropped result.
            VoiceEngines.beginUse()
            try {
                coroutineScope {
                    val consumer = launch(streamDispatcher) { decodeLoop(streaming, pipe) }
                    withContext(Dispatchers.IO) { readLoop(pipe) }
                    // The reader has exited, so nothing more will be offered;
                    // let the decoder finish what is already queued rather than
                    // cutting a partial off mid-word.
                    pipe.close()
                    consumer.join()
                }
            } finally {
                VoiceEngines.endUse()
            }
        }

        monitorJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(MONITOR_TICK_MS)
                tick++
                // Coalesced UI work: one main-thread update per tick regardless
                // of how many chunks arrived, and the reader never waits for it.
                host.onAmplitude(latestAmplitude)
                val dropped = pipe.drainDroppedSamples()
                if (dropped > 0) dispatch(Event.AudioOverrun(dropped))

                if (tick % WATCHDOG_EVERY_N_TICKS != 0) continue
                // A call can take the microphone without a focus callback ever
                // arriving (or before it does), and a silent AudioRecord looks
                // exactly like a quiet room. Poll for it.
                if (focus.callStarted()) {
                    focus.reportCallActive()
                    break
                }
                val elapsed = System.currentTimeMillis()
                if (elapsed - lastChunkAt > AUDIO_STALL_TIMEOUT_MS) {
                    // Chunks arrive every ~100ms. Two seconds of nothing means the
                    // mic is gone, however cheerful the bar looks.
                    Log.w(TAG, "no audio for ${elapsed - lastChunkAt}ms; treating as an audio error")
                    dispatch(Event.AudioError)
                    break
                }
                val silenceLimit = settings.silenceTimeout.millis
                if (silenceLimit != null && elapsed - lastSpeechAt > silenceLimit) {
                    dispatch(Event.SilenceTimeout)
                    break
                }
            }
        }
    }

    /**
     * Producer. Runs on Dispatchers.IO, is the sole owner of [AudioCapture.read],
     * and does nothing that can block on another thread: every chunk is buffered
     * for the final pass and offered to the decoder, and that is all. Anything
     * slower than the microphone in here is a driver overrun.
     */
    private suspend fun readLoop(pipe: AudioPipeline) {
        var failure: Int? = null
        while (coroutineContext.isActive) {
            when (val chunk = capture.read()) {
                is AudioCapture.Read.Failed -> {
                    failure = chunk.code
                    break
                }
                AudioCapture.Read.Stopped -> break
                is AudioCapture.Read.Chunk -> {
                    val samples = chunk.samples
                    lastChunkAt = System.currentTimeMillis()
                    latestAmplitude = samples.rmsLevel()
                    // Never lost for the final pass; may be dropped (and counted)
                    // for the streaming decoder if it has fallen behind.
                    pipe.offer(samples)
                }
            }
        }
        if (failure != null && !audioStopRequested) {
            Log.e(TAG, "audio read failed with code $failure")
            withContext(Dispatchers.Main.immediate) { dispatch(Event.AudioError) }
        } else if (failure == null && !audioStopRequested) {
            // The record stopped without us asking. Silent in the old code; the
            // user simply spoke into nothing until the silence timeout.
            Log.w(TAG, "audio capture ended unexpectedly")
            withContext(Dispatchers.Main.immediate) { dispatch(Event.AudioError) }
        }
    }

    /**
     * Consumer. Owns the streaming recognizer's stream, on a thread of its own
     * so a slow decode delays only the live partial — never the microphone, and
     * never the final pass.
     */
    private suspend fun decodeLoop(streaming: StreamingAsr, pipe: AudioPipeline) {
        var lastPartial = ""
        while (true) {
            val chunk = pipe.take() ?: break
            streaming.acceptAudio(chunk.samples)
            val partial = streaming.partialText()
            if (partial != lastPartial) {
                lastPartial = partial
                lastSpeechAt = System.currentTimeMillis()
                // Partials go to the voice bar only (VB-103 is not implemented:
                // this 20M-parameter stream would show the user wrong words
                // being rewritten in their text field).
                withContext(Dispatchers.Main.immediate) { dispatch(Event.Partial(partial)) }
            }
            if (streaming.isEndpoint()) {
                lastPartial = ""
                withContext(Dispatchers.Main.immediate) {
                    val wasListening = machine.state is DictationStateMachine.State.Listening
                    // Any finalize this triggers takes the utterance audio from
                    // inside dispatch, split at this decoder's stream position,
                    // so the reset has to come after it.
                    dispatch(Event.EndpointDetected)
                    // The recognizer stream is owned by this loop, which is
                    // suspended for the duration of this block; resetting it
                    // here rather than off the finalize path keeps it that way.
                    streaming.resetUtterance()
                    if (wasListening && machine.state is DictationStateMachine.State.Listening) {
                        // Endpoint on silence with nothing recognized: drop the
                        // buffered dead air up to here — but not the audio the
                        // reader has captured since, which belongs to whatever
                        // the user is saying now.
                        pipe.discardUtteranceThrough(pipe.decodedPosition)
                    }
                }
            }
        }
    }

    /**
     * Stops capture cooperatively. Safe on the main thread and non-blocking: the
     * record is stopped (which unblocks a parked read), the reader is cancelled,
     * and the join-then-release runs on [teardownScope]. Releasing without that
     * join is a native use-after-free, not a dropped chunk.
     */
    private fun stopAudio() {
        // Only the first stop of a live session logs, so the two teardown paths
        // (StopAudio then HideVoiceBar) do not report the same session twice.
        if (monitorJob != null) logDropSummary()
        monitorJob?.cancel()
        monitorJob = null
        val reader = audioJob
        audioJob = null
        audioStopRequested = true
        // Well inside the 500ms budget for giving focus back (VB-123): this runs
        // on every path out of the listening state, before the microphone
        // teardown that has to be joined off the main thread.
        focus.abandon()
        capture.requestStop()
        reader?.cancel()
        val previous = audioTeardownJob
        audioTeardownJob = teardownScope.launch {
            previous?.join()
            if (reader != null) {
                val joined = withTimeoutOrNull(AUDIO_JOIN_TIMEOUT_MS) { reader.join() }
                if (joined == null) {
                    Log.w(TAG, "audio reader did not exit within ${AUDIO_JOIN_TIMEOUT_MS}ms")
                }
            }
            withContext(audioDispatcher) { capture.release() }
        }
    }

    /**
     * One line per session when the streaming decoder lost audio, so a session
     * too short to have ticked a report still leaves a trace. Counts only.
     */
    private fun logDropSummary() {
        val pipe = pipeline ?: return
        if (pipe.droppedSamples <= 0L && pipe.evictedSamples <= 0L) return
        val ms = pipe.droppedSamples * 1_000L / AudioCapture.SAMPLE_RATE
        Log.w(
            TAG,
            "session captured ${pipe.producedSamples} samples; the streaming decoder " +
                "missed ${pipe.droppedSamples} of them (~${ms}ms in ${pipe.droppedChunkCount} " +
                "chunks) and the final pass missed ${pipe.evictedSamples}",
        )
    }

    // ---------------------------------------------------- utterance buffer

    /**
     * The audio the final pass should re-transcribe for the utterance ending now.
     *
     * Two cases, and the difference matters:
     *  - the microphone is still live, so this is an endpoint in a continuing
     *    session: take only up to the decoder's position, because anything the
     *    reader captured after that belongs to the next utterance;
     *  - the microphone has been cut (user stop, editor gone, or an interruption
     *    under VB-123): nothing more is coming, so take everything buffered —
     *    including audio the decoder never caught up with. Leaving it behind is
     *    exactly the "my last sentence vanished" bug.
     */
    private fun takeUtteranceAudio(): FloatArray {
        val pipe = pipeline ?: return FloatArray(0)
        return if (audioStopRequested) {
            pipe.takeAllUtterance()
        } else {
            pipe.takeUtteranceThrough(pipe.decodedPosition)
        }
    }

    // ------------------------------------------------------------ finalizing

    /**
     * Runs the final pass for one utterance. Re-entry is refused twice over: the
     * state machine only emits BeginFinalize on the Listening -> Finalizing edge,
     * and an in-flight [finalizeJob] is turned away here.
     */
    private fun beginFinalize(partial: String, utteranceIndex: Int) {
        if (finalizeJob?.isActive == true) {
            Log.w(TAG, "finalize already in flight for utterance $utteranceIndex")
            return
        }
        val samples = takeUtteranceAudio()
        host.showFinalizing()

        finalizeJob = scope.launch {
            val decoded = if (samples.isEmpty()) {
                null
            } else {
                VoiceEngines.beginUse()
                try {
                    withTimeoutOrNull(FINALIZE_WATCHDOG_MS) {
                        withContext(decodeDispatcher) {
                            runCatching { VoiceEngines.finalPass?.transcribe(samples) }
                                .onFailure { Log.e(TAG, "final ASR pass failed", it) }
                                .getOrNull()
                        }
                    }
                } finally {
                    VoiceEngines.endUse()
                }
            }
            // A blank final result is not a valid transcription of speech the user
            // watched the partial spell out: falling through with "" deleted the
            // sentence silently. Blank counts as failure, same as a timeout.
            val finalText = FinalTranscriptPolicy.choose(decoded, partial)

            val cleaned = cleanTranscript(finalText)
            when (cleaned.command) {
                UtteranceCommand.SCRATCH_THAT -> {
                    dispatch(Event.FinalTranscript(""))
                    dispatch(Event.ScratchThat)
                    showListeningIfListening()
                }
                UtteranceCommand.STOP_LISTENING -> {
                    dispatch(Event.FinalTranscript(""))
                    dispatch(Event.StopRequested)
                }
                UtteranceCommand.NONE -> {
                    dispatch(Event.FinalTranscript(cleaned.text))
                    showListeningIfListening()
                }
            }
        }
    }

    private fun showListeningIfListening() {
        if (machine.state is DictationStateMachine.State.Listening) host.showListening()
    }

    /**
     * Rules cleanup for one utterance, with the expensive half off the main
     * thread.
     *
     * The 500-line tokenizer pipeline used to run on the main dispatcher at the
     * exact frame the commit animation had to draw. It is pure computation over
     * a string, so it moves to Default with no threading questions attached.
     *
     * [Host.precedingText] deliberately stays on the main thread. It is an IPC
     * into the host app, but a bounded one (a fixed, small character count), and
     * every InputConnection call site in this keyboard — like AOSP LatinIME — is
     * main-thread. Cross-thread InputConnection use is not a documented
     * guarantee, and trading a ~1ms bounded IPC for an untestable threading
     * change is the wrong side of that bargain.
     */
    private suspend fun cleanTranscript(raw: String): CleanupResult {
        val request = CleanupRequest(
            transcript = raw,
            precedingText = host.precedingText(),
            fieldKind = fieldKind,
            options = settings.cleanupOptions(),
            ensureTerminalPunctuation = settings.autoPunctuate && fieldKind == FieldKind.TEXT,
        )
        return withContext(Dispatchers.Default) { app.cleaner.clean(request) }
    }

    // ------------------------------------------------------------ refinement

    private fun refineAsync(text: String, utteranceIndex: Int) {
        scope.launch {
            val refiner = withContext(Dispatchers.IO) {
                runCatching { VoiceEngines.loadRefiner(service, app) }
                    .onFailure { Log.w(TAG, "refiner unavailable", it) }
                    .getOrNull()
            } ?: return@launch
            host.showRefining()
            VoiceEngines.beginUse()
            val refined = try {
                refiner.refine(text)
            } finally {
                VoiceEngines.endUse()
            }
            showListeningIfListening()
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
                stopAudio()
                // ~1.2GB of native memory should not stay pinned in the keyboard
                // process because someone dictated once this morning.
                VoiceEngines.scheduleIdleRelease()
                host.onSessionEnded()
            }
            Effect.StartAudio -> {
                host.showListening()
                startAudio()
            }
            Effect.StopAudio -> stopAudio()
            is Effect.UpdatePartial -> host.updatePartial(effect.text)
            is Effect.BeginFinalize -> beginFinalize(effect.partial, effect.utteranceIndex)
            is Effect.CommitUtterance -> {
                host.commitUtterance(effect.utteranceIndex, effect.text)
                if (effect.refine) refineAsync(effect.text, effect.utteranceIndex)
            }
            Effect.DeleteLastUtterance -> host.deleteLastUtterance()
            is Effect.NoteAudioOverrun -> {
                // Counts only: never audio, never transcript text. This is the
                // line that turns "the model is inaccurate" into a diagnosable
                // defect (VB-106).
                val ms = effect.droppedSamples * 1_000L / AudioCapture.SAMPLE_RATE
                Log.w(
                    TAG,
                    "streaming decoder fell behind: dropped ${effect.droppedSamples} samples " +
                        "(~${ms}ms), ${effect.sessionTotalSamples} this session; " +
                        "the final pass is unaffected",
                )
            }
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
            ErrorKind.MODEL_CORRUPT ->
                service.getString(R.string.voice_error_model_corrupt) to
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
        private const val TAG = "VBoardVoice"

        /**
         * Budget for the final pass.
         *
         * Honest limitation: the decode is a single blocking JNI call with no
         * suspension points, so [withTimeoutOrNull] cannot actually abandon it —
         * structured concurrency waits for the native call to return whatever the
         * timer says. What the dedicated [decodeDispatcher] does guarantee is that
         * a slow decode cannot overlap the next one and cannot starve
         * Dispatchers.Default. Genuinely bounding this needs cancellation support
         * inside the recognizer wrapper (a decode running on a thread we can
         * abandon, with the native handle owned by that thread).
         */
        private const val FINALIZE_WATCHDOG_MS = 5_000L

        private const val AUDIO_STALL_TIMEOUT_MS = 2_000L
        private const val AUDIO_JOIN_TIMEOUT_MS = 1_500L

        /** UI/overrun tick; matches the ~100ms capture cadence. */
        private const val MONITOR_TICK_MS = 100L

        /** Mic-health, call and silence checks run every 500ms. */
        private const val WATCHDOG_EVERY_N_TICKS = 5

        /** Serializes AudioRecord construction and release; see [AudioCapture]. */
        private val audioDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-audio") }
                .asCoroutineDispatcher()

        /** One decode at a time, and never on a dispatcher the UI shares. */
        private val decodeDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-asr-decode") }
                .asCoroutineDispatcher()

        /**
         * The streaming decoder's own thread. Separate from [decodeDispatcher]
         * on purpose: serializing the live stream behind a multi-second final
         * pass is precisely the stall that used to overrun the capture buffer.
         */
        private val streamDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-asr-stream") }
                .asCoroutineDispatcher()
    }
}

/**
 * Process-wide engine cache: models stay loaded across dictation sessions so
 * the second mic press starts in tens of milliseconds — but not forever, see
 * [scheduleIdleRelease].
 */
object VoiceEngines {

    private const val TAG = "VBoardEngines"

    /**
     * How long the engines stay resident after the keyboard goes away.
     *
     * Was 90s, which is shorter than the gap between two messages in the same
     * conversation, so almost every mic press paid the full multi-second load
     * again. The timer is only armed once the keyboard is hidden, so an open
     * keyboard now keeps the engines for as long as it is open.
     */
    private const val IDLE_RELEASE_MS = 600_000L

    enum class LoadResult {
        READY,

        /** No installed pack to load from — the user has not downloaded them. */
        MISSING,

        /**
         * Files are present but unusable: corrupt payload, native load failure,
         * or out of memory. Distinct from [MISSING] so the UI can offer a repair
         * instead of pointing at a screen that says the pack is installed.
         */
        BROKEN,
    }

    @Volatile var streaming: StreamingAsr? = null
        private set

    @Volatile var finalPass: FinalAsr? = null
        private set

    /**
     * The refiner is a *connection* now, not an engine: the model itself lives in
     * the `:llm` process (V2_PLAN Wave 0.5), so what this object holds is a
     * binder that costs nothing to keep and everything to forget at the wrong
     * moment. Releasing it is what lets that process — and the half-gigabyte
     * model in it — be reclaimed.
     */
    @Volatile private var refiner: LlmRefinerClient? = null

    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Guards [idleJob] only. Deliberately not the object monitor: the idle timer
     * is armed and disarmed from the main thread, and a model load can hold the
     * object monitor for seconds — sharing one lock would turn a mic press into
     * an ANR.
     */
    private val idleLock = Any()
    private var idleJob: Job? = null
    private var warmJob: Job? = null

    /**
     * Outstanding claims on the native handles (a live reader loop, a decode, a
     * refinement). Memory pressure can arrive at any moment, and freeing a
     * recognizer while one of those is running is a segfault rather than a lost
     * result, so a release is refused while anything holds a claim.
     */
    private val claims = AtomicInteger(0)

    val isLoaded: Boolean get() = streaming != null && finalPass != null

    fun beginUse() {
        claims.incrementAndGet()
    }

    fun endUse() {
        claims.decrementAndGet()
    }

    @Synchronized
    fun load(app: VBoardApp): LoadResult {
        if (isLoaded) return LoadResult.READY
        val streamingPaths = app.modelStore.streamingPaths(app.packInstaller)
            ?: return LoadResult.MISSING
        val parakeetPaths = app.modelStore.parakeetPaths(app.packInstaller)
            ?: return LoadResult.MISSING

        // Built into locals and published only on full success: assigning field by
        // field meant a throw from the second constructor (OOM is realistic at
        // this size) left the first recognizer as an orphaned native handle that
        // nothing could reach, and the next mic press allocated another on top.
        var streamingAsr: StreamingAsr? = null
        var finalAsr: FinalAsr? = null
        return try {
            streamingAsr = StreamingAsr(streamingPaths)
            finalAsr = FinalAsr(parakeetPaths)
            streaming = streamingAsr
            finalPass = finalAsr
            LoadResult.READY
        } catch (e: Throwable) {
            runCatching { streamingAsr?.release() }
            runCatching { finalAsr?.release() }
            Log.e(TAG, "ASR engine load failed", e)
            LoadResult.BROKEN
        }
    }

    @Synchronized
    fun loadRefiner(context: Context, app: VBoardApp): RemoteRefiner? {
        refiner?.let { return it }
        return refinerClientOrNull(context, app)?.also { refiner = it }
    }

    @Synchronized
    fun releaseRefiner() {
        if (claims.get() > 0) {
            Log.w(TAG, "refiner release refused: engines in use")
            return
        }
        refiner?.let { runCatching { it.disconnect() } }
        refiner = null
    }

    @Synchronized
    fun releaseAll() {
        if (claims.get() > 0) {
            Log.w(TAG, "engine release refused: engines in use")
            return
        }
        runCatching { streaming?.release() }
        runCatching { finalPass?.release() }
        runCatching { refiner?.disconnect() }
        streaming = null
        finalPass = null
        refiner = null
    }

    /**
     * Loads the engines before the user asks for them, off the main thread.
     *
     * Called when the keyboard becomes visible: the load is the whole of the
     * delay between the mic press and the first word being heard, and doing it
     * here means the press usually finds them already resident. Idempotent, and
     * safe to race with a session — [load] is synchronized and returns early
     * when the engines are up.
     */
    fun warmUp(app: VBoardApp) {
        cancelIdleRelease()
        if (isLoaded) return
        synchronized(idleLock) {
            if (warmJob?.isActive == true) return
            warmJob = idleScope.launch(Dispatchers.IO) {
                val result = runCatching { load(app) }.getOrNull()
                if (result != LoadResult.READY) Log.i(TAG, "warm-up did not load engines: $result")
            }
        }
    }

    /** Cancels a pending idle release; call when a session is starting. */
    fun cancelIdleRelease() {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob = null
        }
    }

    /**
     * Frees the engines once dictation has been idle for a while. Without this the
     * keyboard process holds roughly 1.2GB of native memory from the first mic
     * press until the process dies, which on a mid-range phone means it dies.
     */
    fun scheduleIdleRelease() {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob = idleScope.launch {
                delay(IDLE_RELEASE_MS)
                releaseAll()
            }
        }
    }
}
