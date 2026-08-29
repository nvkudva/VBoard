package com.vboard.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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
    private var silenceJob: Job? = null
    private var prepareJob: Job? = null
    private var finalizeJob: Job? = null
    private var audioTeardownJob: Job? = null

    /** True between a stop request and the reader actually exiting. */
    @Volatile
    private var audioStopRequested = false

    /**
     * Raw samples of the in-flight utterance, for the Parakeet re-pass.
     *
     * Appended from the reader thread and drained from the main thread when an
     * utterance finalizes, so every access goes through [utteranceLock]. Sizing
     * a FloatArray from a count while the list behind it was still growing threw
     * IndexOutOfBounds on the single most common gesture there is.
     */
    private val utteranceLock = Any()
    private val utteranceAudio = ArrayList<FloatArray>(64)
    private var utteranceSamples = 0

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
     * TODO: the commit is asynchronous, so an input connection that dies before
     *  the final pass returns still loses the utterance. Closing that hole needs
     *  the commit to survive the editor session (a pending-commit replayed on the
     *  next onStartInput), which is a larger change than this fix.
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
        clearUtteranceAudio()
        val now = System.currentTimeMillis()
        lastSpeechAt = now
        lastChunkAt = now
        audioStopRequested = false
        streaming.resetUtterance()

        // Never leave a previous reader running against a new record.
        audioJob?.cancel()
        val pendingTeardown = audioTeardownJob
        audioJob = scope.launch {
            // The previous record must be freed before we open the next one.
            pendingTeardown?.join()
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
                dispatch(Event.AudioError)
                return@launch
            }
            lastChunkAt = System.currentTimeMillis()
            // Claim the engines for the reader's lifetime: freeing a recognizer
            // out from under a decode is a native crash, not a dropped result.
            VoiceEngines.beginUse()
            try {
                withContext(Dispatchers.IO) { readLoop(streaming) }
            } finally {
                VoiceEngines.endUse()
            }
        }

        silenceJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val elapsed = System.currentTimeMillis()
                if (elapsed - lastChunkAt > AUDIO_STALL_TIMEOUT_MS) {
                    // Chunks arrive every ~100ms. Two seconds of nothing means the
                    // mic is gone, however cheerful the bar looks.
                    Log.w(TAG, "no audio for ${elapsed - lastChunkAt}ms; treating as an audio error")
                    dispatch(Event.AudioError)
                    break
                }
                if (elapsed - lastSpeechAt > SILENCE_TIMEOUT_MS) {
                    dispatch(Event.SilenceTimeout)
                    break
                }
            }
        }
    }

    /** Runs on Dispatchers.IO; the sole owner of [AudioCapture.read]. */
    private suspend fun readLoop(streaming: StreamingAsr) {
        var lastPartial = ""
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
                    appendUtteranceAudio(samples)
                    val rms = samples.rmsLevel()
                    withContext(Dispatchers.Main.immediate) { host.onAmplitude(rms) }

                    streaming.acceptAudio(samples)
                    val partial = streaming.partialText()
                    if (partial != lastPartial) {
                        lastPartial = partial
                        lastSpeechAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main.immediate) { dispatch(Event.Partial(partial)) }
                    }
                    if (streaming.isEndpoint()) {
                        lastPartial = ""
                        withContext(Dispatchers.Main.immediate) {
                            val wasListening =
                                machine.state is DictationStateMachine.State.Listening
                            // Any finalize this triggers snapshots the buffer from
                            // inside dispatch, so the reset has to come after it.
                            dispatch(Event.EndpointDetected)
                            // The recognizer stream is owned by this loop, which is
                            // suspended for the duration of this block; resetting it
                            // here rather than off the finalize path keeps it that
                            // way.
                            streaming.resetUtterance()
                            if (wasListening &&
                                machine.state is DictationStateMachine.State.Listening
                            ) {
                                // Endpoint on silence with nothing recognized: drop
                                // the buffered dead air and keep listening.
                                clearUtteranceAudio()
                            }
                        }
                    }
                }
            }
        }
        if (failure != null && !audioStopRequested) {
            Log.e(TAG, "audio read failed with code $failure")
            withContext(Dispatchers.Main.immediate) { dispatch(Event.AudioError) }
        } else if (failure == null && !audioStopRequested) {
            // The record stopped without us asking. Silent in the old code; the
            // user simply spoke into nothing until the 30s silence timeout.
            Log.w(TAG, "audio capture ended unexpectedly")
            withContext(Dispatchers.Main.immediate) { dispatch(Event.AudioError) }
        }
    }

    /**
     * Stops capture cooperatively. Safe on the main thread and non-blocking: the
     * record is stopped (which unblocks a parked read), the reader is cancelled,
     * and the join-then-release runs on [teardownScope]. Releasing without that
     * join is a native use-after-free, not a dropped chunk.
     */
    private fun stopAudio() {
        silenceJob?.cancel()
        silenceJob = null
        val reader = audioJob
        audioJob = null
        audioStopRequested = true
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

    // ---------------------------------------------------- utterance buffer

    private fun appendUtteranceAudio(chunk: FloatArray) {
        synchronized(utteranceLock) {
            utteranceAudio.add(chunk)
            utteranceSamples += chunk.size
        }
    }

    private fun clearUtteranceAudio() {
        synchronized(utteranceLock) {
            utteranceAudio.clear()
            utteranceSamples = 0
        }
    }

    private fun snapshotUtteranceAudio(): FloatArray = synchronized(utteranceLock) {
        val out = FloatArray(utteranceSamples)
        var offset = 0
        for (chunk in utteranceAudio) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        utteranceAudio.clear()
        utteranceSamples = 0
        out
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
        val samples = snapshotUtteranceAudio()
        host.showFinalizing()

        finalizeJob = scope.launch {
            VoiceEngines.beginUse()
            val decoded = try {
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
            // A blank final result is not a valid transcription of speech the user
            // watched the partial spell out: falling through with "" deleted the
            // sentence silently. Blank counts as failure, same as a timeout.
            val finalText = if (decoded.isNullOrBlank()) partial else decoded

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
        private const val SILENCE_TIMEOUT_MS = 30_000L

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
        private const val WATCHDOG_TICK_MS = 500L

        /** Serializes AudioRecord construction and release; see [AudioCapture]. */
        private val audioDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-audio") }
                .asCoroutineDispatcher()

        /** One decode at a time, and never on a dispatcher the UI shares. */
        private val decodeDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "vboard-asr-decode") }
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

    /** How long the engines stay resident after the last dictation. */
    private const val IDLE_RELEASE_MS = 90_000L

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

    @Volatile private var refiner: LlmRefiner? = null

    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Guards [idleJob] only. Deliberately not the object monitor: the idle timer
     * is armed and disarmed from the main thread, and a model load can hold the
     * object monitor for seconds — sharing one lock would turn a mic press into
     * an ANR.
     */
    private val idleLock = Any()
    private var idleJob: Job? = null

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
    fun loadRefiner(context: Context, app: VBoardApp): LlmRefiner? {
        refiner?.let { return it }
        val path = app.modelStore.refinerModelPath(app.packInstaller) ?: return null
        return LlmRefiner(context.applicationContext, path).also { refiner = it }
    }

    @Synchronized
    fun releaseRefiner() {
        if (claims.get() > 0) {
            Log.w(TAG, "refiner release refused: engines in use")
            return
        }
        refiner?.let { runCatching { it.release() } }
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
        runCatching { refiner?.release() }
        streaming = null
        finalPass = null
        refiner = null
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
