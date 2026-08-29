package com.vboard.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * 16kHz mono PCM capture for ASR. Reads ~100ms chunks; the caller pulls from
 * [read] on a background thread. Caller is responsible for holding the
 * RECORD_AUDIO permission before constructing.
 *
 * Teardown is deliberately two-phase. Coroutine cancellation cannot interrupt a
 * thread parked inside the blocking JNI [AudioRecord.read], so releasing the
 * record from another thread while that read is in flight is a native
 * use-after-free that takes down the whole IME process. Instead:
 *
 *  1. [requestStop] — safe from any thread. AudioRecord.stop() is the documented
 *     way to make a blocked read() return promptly; unlike release() it is
 *     defined against a concurrent read.
 *  2. join the reader thread.
 *  3. [release] — only once the reader has provably exited.
 *
 * [start], [release] and [read] must all be reached from a single owner. The
 * controller serializes start/release on one dedicated thread and runs exactly
 * one reader at a time, which is also what keeps [shortBuffer] private to one
 * reader: two concurrent loops used to scribble over each other's chunk.
 */
class AudioCapture {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600 // 100ms
        private const val TAG = "VBoardAudio"
    }

    /** Outcome of one [read]; a clean stop and a dead mic are not the same thing. */
    sealed interface Read {
        /** One chunk of normalized samples in [-1, 1]. */
        class Chunk(val samples: FloatArray) : Read

        /** The record was stopped; the loop should exit quietly. */
        data object Stopped : Read

        /**
         * The record failed. [code] is the raw AudioRecord error: ERROR_DEAD_OBJECT
         * (-6) when the audio server died, ERROR_INVALID_OPERATION (-3) when the
         * record was never started, ERROR (-1) otherwise. Collapsing these into
         * "stopped" is what let the user talk into a dead mic for 30 seconds while
         * the bar cheerfully said "Listening...".
         */
        data class Failed(val code: Int) : Read
    }

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var stopping = false

    private val shortBuffer = ShortArray(CHUNK_SAMPLES)

    val isRecording: Boolean
        get() = !stopping && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    /**
     * Opens and starts the microphone. Blocking HAL work (100-300ms on some
     * devices): call from the controller's audio thread, never the main thread.
     *
     * Throws [IllegalStateException] for an unusable device, and lets the
     * AudioRecord constructor's own [IllegalArgumentException] / [SecurityException]
     * through; the caller maps every failure to the audio-error path.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        // Defensive: a leftover record here would mean two live AudioRecords and
        // two reader loops sharing one buffer. Reclaim it rather than leak it.
        record?.let {
            Log.w(TAG, "start() found a live record; releasing the previous one")
            releaseInternal(it)
        }
        stopping = false

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord unsupported config")
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, CHUNK_SAMPLES * 4),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("Microphone unavailable")
        }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            throw IllegalStateException("Microphone busy")
        }
        record = r
    }

    /** Blocking read of one chunk. See [Read] for how the outcomes differ. */
    fun read(): Read {
        val r = record ?: return Read.Stopped
        val n = try {
            r.read(shortBuffer, 0, CHUNK_SAMPLES)
        } catch (e: IllegalStateException) {
            // Raced with a stop we did not initiate.
            Log.w(TAG, "read on an uninitialized record", e)
            return Read.Failed(AudioRecord.ERROR_INVALID_OPERATION)
        }
        if (n < 0) return Read.Failed(n)
        // A blocking read only returns short of a full chunk when the record has
        // been stopped underneath us.
        if (n == 0) return Read.Stopped
        val floats = FloatArray(n)
        for (i in 0 until n) {
            floats[i] = shortBuffer[i] / 32768f
        }
        return Read.Chunk(floats)
    }

    /**
     * Signals the reader to finish and unblocks it. Idempotent, and safe to call
     * from the main thread — it stops the record but never releases it, so a
     * reader parked in [read] returns instead of touching freed memory.
     */
    fun requestStop() {
        stopping = true
        val r = record ?: return
        runCatching { r.stop() }
            .onFailure { Log.w(TAG, "AudioRecord.stop failed", it) }
    }

    /**
     * Frees the native record. The caller must have joined the reader first:
     * releasing under a live [read] is undefined behaviour, not a lost chunk.
     */
    fun release() {
        record?.let { releaseInternal(it) }
    }

    private fun releaseInternal(r: AudioRecord) {
        record = null
        stopping = true
        runCatching { r.stop() }
        runCatching { r.release() }
            .onFailure { Log.w(TAG, "AudioRecord.release failed", it) }
    }
}

/** Root-mean-square of a chunk, mapped to a perceptual-ish 0..1 level. */
fun FloatArray.rmsLevel(): Float {
    if (isEmpty()) return 0f
    var sum = 0.0
    for (s in this) sum += s * s
    val rms = sqrt(sum / size).toFloat()
    // Speech RMS typically sits around 0.02..0.2; scale into 0..1.
    return (rms * 8f).coerceIn(0f, 1f)
}
