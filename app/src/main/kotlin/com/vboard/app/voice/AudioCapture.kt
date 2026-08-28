package com.vboard.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * 16kHz mono PCM capture for ASR. Reads ~100ms chunks; the caller pulls from
 * [read] on a background thread. Caller is responsible for holding the
 * RECORD_AUDIO permission before constructing.
 */
class AudioCapture {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600 // 100ms
    }

    private var record: AudioRecord? = null
    private val shortBuffer = ShortArray(CHUNK_SAMPLES)

    val isRecording: Boolean get() = record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @SuppressLint("MissingPermission")
    @Throws(IllegalStateException::class)
    fun start() {
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

    /**
     * Blocking read of one chunk as normalized floats in [-1, 1].
     * Returns null once stopped or on read error.
     */
    fun read(): FloatArray? {
        val r = record ?: return null
        val n = r.read(shortBuffer, 0, CHUNK_SAMPLES)
        if (n <= 0) return null
        val floats = FloatArray(n)
        for (i in 0 until n) {
            floats[i] = shortBuffer[i] / 32768f
        }
        return floats
    }

    fun stop() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
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
