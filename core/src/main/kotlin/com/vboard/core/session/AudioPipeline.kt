package com.vboard.core.session

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One chunk of captured audio, tagged with its position in the capture stream.
 *
 * [endPosition] counts every sample the microphone reader has produced since the
 * session started — including samples that were later dropped from the decode
 * queue. That is what lets the consumer say "I have decoded up to here" in terms
 * the producer's utterance buffer understands, however far behind it has fallen.
 */
class AudioChunk(val samples: FloatArray, val endPosition: Long)

/**
 * The hand-off between the microphone reader and the streaming decoder.
 *
 * Why this exists: the reader used to decode inline, so any decode stall longer
 * than the AudioRecord buffer (~400ms) dropped samples *in the driver* — with no
 * counter, no log and no way to detect it. Parakeet then re-transcribed audio
 * with holes in it and the model looked bad. Splitting producer from consumer
 * moves that loss from an invisible driver overrun to an accounted, logged,
 * observable event (VB-106).
 *
 * Two buffers, with deliberately different guarantees:
 *
 *  - **The utterance buffer** ([takeUtteranceThrough]) feeds the final Parakeet
 *    pass, i.e. the text the user actually gets. Every sample the reader hands
 *    to [offer] lands here, whatever the decoder is doing. It is bounded only by
 *    [utteranceCapacitySamples], a last-resort guard against an endlessly hot
 *    mic, set far above the 20s hard cap on an utterance.
 *  - **The decode queue** ([take]) feeds the streaming Zipformer, i.e. the live
 *    partial in the voice bar. It is bounded at [queueCapacitySamples]; when a
 *    slow decode fills it the *newest* chunk is dropped and counted. A degraded
 *    partial is a cosmetic loss; the final text is unaffected because the
 *    utterance buffer still has those samples.
 *
 * Threading: one producer thread calling [offer], one consumer coroutine calling
 * [take], and finalize/reset calls from the session's main thread. All shared
 * state is either atomic or guarded by [utteranceLock]; nothing here blocks the
 * producer, which is the whole point.
 */
class AudioPipeline(
    private val queueCapacitySamples: Int = DEFAULT_QUEUE_CAPACITY_SAMPLES,
    private val utteranceCapacitySamples: Int = DEFAULT_UTTERANCE_CAPACITY_SAMPLES,
) {

    /** Outcome of one [offer]; the caller only needs it for logging. */
    data class OfferResult(
        /** The chunk reached the streaming decoder. */
        val queuedForDecode: Boolean,
        /** Samples dropped from the decode queue by this offer (0 or the whole chunk). */
        val decodeDropped: Int,
        /** Samples evicted from the utterance buffer by this offer; normally 0. */
        val utteranceEvicted: Int,
    )

    /**
     * Unbounded by construction and bounded by [queuedSamples] accounting instead:
     * a rendezvous or fixed-size channel would suspend the producer, and a
     * producer that waits is a producer that is not reading the microphone.
     */
    private val queue = Channel<AudioChunk>(Channel.UNLIMITED)

    private val queued = AtomicInteger(0)
    private val produced = AtomicLong(0)
    private val droppedTotal = AtomicLong(0)
    private val evictedTotal = AtomicLong(0)
    private val droppedPending = AtomicInteger(0)
    private val droppedChunks = AtomicInteger(0)

    @Volatile
    private var closed = false

    /** End position of the last chunk handed to the consumer. */
    @Volatile
    var decodedPosition: Long = 0L
        private set

    private val utteranceLock = Any()
    private val utteranceChunks = ArrayDeque<FloatArray>()
    private var utteranceSamples = 0

    /** Stream position of the first sample still held in the utterance buffer. */
    private var utteranceStart = 0L

    val producedSamples: Long get() = produced.get()
    val queuedSamples: Int get() = queued.get()
    /** Samples that never reached the streaming decoder. */
    val droppedSamples: Long get() = droppedTotal.get()
    val droppedChunkCount: Int get() = droppedChunks.get()

    /**
     * Samples evicted from the utterance buffer because it hit its cap — the
     * only way audio is ever lost to the *final* pass, and only when the mic has
     * been hot far longer than any real utterance. Counted separately so a log
     * line can tell "the partial degraded" from "the transcript lost audio".
     */
    val evictedSamples: Long get() = evictedTotal.get()

    val bufferedUtteranceSamples: Int get() = synchronized(utteranceLock) { utteranceSamples }

    // ------------------------------------------------------------- producer

    /**
     * Producer side: buffer [samples] for the final pass and, if the decoder is
     * keeping up, hand them to it. Never blocks and never throws.
     *
     * The chunk is taken by reference, so the caller must not reuse the array —
     * [com.vboard.core.session] has no way to enforce that, and a shared read
     * buffer scribbled over by the next read is exactly the class of bug this
     * type is here to avoid.
     */
    fun offer(samples: FloatArray): OfferResult {
        if (samples.isEmpty()) return OfferResult(false, 0, 0)
        val end = produced.addAndGet(samples.size.toLong())
        val evicted = appendUtterance(samples)

        if (closed) return OfferResult(false, 0, evicted)

        // Admission control. Single producer, so a plain read-modify-write of
        // `queued` is safe here; the consumer only ever decrements it. An empty
        // queue always admits, whatever the chunk size: a device that hands back
        // a chunk bigger than the capacity must not livelock into dropping 100%
        // of the audio.
        val queuedNow = queued.get()
        if (queuedNow > 0 && queuedNow + samples.size > queueCapacitySamples) {
            droppedTotal.addAndGet(samples.size.toLong())
            droppedPending.addAndGet(samples.size)
            droppedChunks.incrementAndGet()
            return OfferResult(false, samples.size, evicted)
        }
        queued.addAndGet(samples.size)
        val sent = queue.trySend(AudioChunk(samples, end)).isSuccess
        if (!sent) {
            // Only reachable if the channel was closed between the check above
            // and here; account it rather than pretending it was decoded.
            queued.addAndGet(-samples.size)
            droppedTotal.addAndGet(samples.size.toLong())
            droppedPending.addAndGet(samples.size)
            droppedChunks.incrementAndGet()
            return OfferResult(false, samples.size, evicted)
        }
        return OfferResult(true, 0, evicted)
    }

    // ------------------------------------------------------------- consumer

    /**
     * Consumer side: the next chunk, or null once the pipeline is closed and
     * drained. Suspends while the queue is empty.
     */
    suspend fun take(): AudioChunk? {
        val chunk = queue.receiveCatching().getOrNull() ?: return null
        queued.addAndGet(-chunk.samples.size)
        decodedPosition = chunk.endPosition
        return chunk
    }

    /** No more audio will be offered; [take] drains what is left, then returns null. */
    fun close() {
        closed = true
        queue.close()
    }

    // ----------------------------------------------------- utterance buffer

    /**
     * Removes and returns utterance audio up to [endPosition], keeping anything
     * after it for the next utterance.
     *
     * This is what stops the decoupling from creating a new defect: the endpoint
     * is observed by the consumer, which may be a few chunks behind the reader,
     * and handing the final pass *everything* buffered at that moment would fold
     * the first words of the next utterance into this one. Splitting on the
     * consumer's stream position keeps the boundary exactly where the recognizer
     * put it, with no gap between utterances (VB-106).
     */
    fun takeUtteranceThrough(endPosition: Long): FloatArray = synchronized(utteranceLock) {
        val wanted = (endPosition - utteranceStart).coerceIn(0L, utteranceSamples.toLong()).toInt()
        cutLocked(wanted)
    }

    /** Removes and returns everything buffered; for finalizing after the mic is cut. */
    fun takeAllUtterance(): FloatArray = synchronized(utteranceLock) { cutLocked(utteranceSamples) }

    /** Drops utterance audio up to [endPosition] (silence nobody needs to re-transcribe). */
    fun discardUtteranceThrough(endPosition: Long) {
        synchronized(utteranceLock) {
            val wanted =
                (endPosition - utteranceStart).coerceIn(0L, utteranceSamples.toLong()).toInt()
            cutLocked(wanted)
        }
    }

    /** Drops all buffered utterance audio (session start / restart). */
    fun resetUtterance() {
        synchronized(utteranceLock) {
            utteranceStart += utteranceSamples
            utteranceChunks.clear()
            utteranceSamples = 0
        }
    }

    /**
     * Samples dropped from the decode queue since the last call. Drained by the
     * session's reporting tick so overruns surface as one coalesced event
     * instead of a cross-thread hop per dropped chunk.
     */
    fun drainDroppedSamples(): Int = droppedPending.getAndSet(0)

    // --------------------------------------------------------------- internals

    /** @return samples evicted from the far end because the buffer was full. */
    private fun appendUtterance(samples: FloatArray): Int = synchronized(utteranceLock) {
        utteranceChunks.addLast(samples)
        utteranceSamples += samples.size
        var evicted = 0
        // A mic that never endpoints (a jammed recognizer, a user who never
        // stops) would otherwise grow this without limit inside a keyboard
        // process. Evict the oldest audio and account it: losing the start of a
        // 60s "utterance" is bad, being killed by the OOM killer is worse.
        while (utteranceSamples > utteranceCapacitySamples && utteranceChunks.size > 1) {
            val head = utteranceChunks.removeFirst()
            utteranceSamples -= head.size
            utteranceStart += head.size
            evicted += head.size
        }
        if (evicted > 0) {
            evictedTotal.addAndGet(evicted.toLong())
            droppedPending.addAndGet(evicted)
        }
        evicted
    }

    /** Caller must hold [utteranceLock]. Removes the first [wanted] samples. */
    private fun cutLocked(wanted: Int): FloatArray {
        if (wanted <= 0) return EMPTY
        val out = FloatArray(wanted)
        var written = 0
        while (written < wanted && utteranceChunks.isNotEmpty()) {
            val head = utteranceChunks.first()
            val remaining = wanted - written
            if (head.size <= remaining) {
                head.copyInto(out, written)
                written += head.size
                utteranceChunks.removeFirst()
            } else {
                head.copyInto(out, written, 0, remaining)
                utteranceChunks[0] = head.copyOfRange(remaining, head.size)
                written += remaining
            }
        }
        utteranceSamples -= written
        utteranceStart += written
        return if (written == wanted) out else out.copyOf(written)
    }

    companion object {
        private val EMPTY = FloatArray(0)

        /**
         * ~3s at 16kHz. Wide enough that a normal decode hiccup costs nothing,
         * narrow enough that the live partial cannot fall seconds behind the
         * speaker before the pipeline admits it is losing.
         */
        const val DEFAULT_QUEUE_CAPACITY_SAMPLES = 48_000

        /** ~60s at 16kHz: three times the 20s hard cap on a single utterance. */
        const val DEFAULT_UTTERANCE_CAPACITY_SAMPLES = 960_000
    }
}
