package com.vboard.app.voice

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.vboard.app.models.ModelStore
import com.vboard.core.text.RecognizerCase

/**
 * Thin wrappers around sherpa-onnx so the rest of the app never touches its
 * API directly (and tests can fake these).
 */
class StreamingAsr(paths: ModelStore.SpeechModelPaths) {

    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.encoder,
                    decoder = paths.decoder,
                    joiner = paths.joiner,
                ),
                tokens = paths.tokens,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            enableEndpoint = true,
            endpointConfig = EndpointConfig(
                // rule1: even mid-word, 2.4s of pure silence ends the utterance.
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                // rule2: 0.8s trailing silence after speech (PM spec VB endpoint target).
                rule2 = EndpointRule(true, 0.8f, 0.0f),
                // rule3: hard cap on utterance length.
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
        ),
    )

    private var stream: OnlineStream = recognizer.createStream()

    fun acceptAudio(samples: FloatArray) {
        stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }

    /**
     * [RecognizerCase] here rather than at the display: this model decodes
     * against an uppercase token table, and the partial is not only shown in the
     * voice bar — it is what gets committed when the final pass fails.
     */
    fun partialText(): String = RecognizerCase.normalize(recognizer.getResult(stream).text)

    fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

    /** Resets decoding state for the next utterance. */
    fun resetUtterance() {
        recognizer.reset(stream)
    }

    fun release() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }
}

class FinalAsr(paths: ModelStore.SpeechModelPaths) {

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = paths.encoder,
                    decoder = paths.decoder,
                    joiner = paths.joiner,
                ),
                tokens = paths.tokens,
                numThreads = 4,
                provider = "cpu",
                modelType = "nemo_transducer",
            ),
        ),
    )

    /** Transcribes one complete utterance; blocking, call on a worker thread. */
    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            recognizer.decode(stream)
            // A no-op for a model that already returns mixed case, which this
            // one does; it is here so a model swap cannot start shouting.
            RecognizerCase.normalize(recognizer.getResult(stream).text)
        } finally {
            runCatching { stream.release() }
        }
    }

    fun release() {
        runCatching { recognizer.release() }
    }
}
