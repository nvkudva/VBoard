package com.vboard.core.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VB-106. The defect these tests exist for: the microphone reader used to run
 * the streaming decode inline, so any decode slower than the ~400ms AudioRecord
 * buffer dropped samples **in the driver** — uncounted, unlogged, undetectable.
 * Parakeet then re-transcribed audio with holes in it, and the user concluded
 * the model was bad. The failure presented as inaccuracy, not as a defect.
 *
 * The contract now is asymmetric on purpose:
 *  - the audio that reaches the final pass loses nothing, ever;
 *  - the audio that reaches the live streaming partial may be dropped under
 *    sustained overload, but every dropped sample is counted and reportable.
 */
class AudioPipelineTest {

    private companion object {
        const val CHUNK = 1_600            // 100ms at 16kHz
        const val CHUNK_MS = 100L
        const val REALTIME_DECODE_MS = CHUNK_MS
        const val SLOW_DECODE_MS = CHUNK_MS * 3  // 3x real time
    }

    /** Chunk of a globally unique ramp, so a lost or reordered sample is visible. */
    private fun chunkAt(index: Int): FloatArray =
        FloatArray(CHUNK) { (index.toLong() * CHUNK + it).toFloat() }

    private fun signal(chunks: Int): FloatArray {
        val out = FloatArray(chunks * CHUNK)
        for (i in 0 until chunks) chunkAt(i).copyInto(out, i * CHUNK)
        return out
    }

    /**
     * THE test for VB-106: with a decoder running at 3x real time for twenty
     * seconds of speech, every single captured sample still reaches the final
     * pass, in order, with no gaps — and the samples the *streaming* decoder had
     * to skip are counted rather than silently lost in the driver.
     */
    @Test
    fun `a decoder at three times real time loses no sample from the final pass`() = runTest {
        val totalChunks = 200 // 20s of speech
        val pipeline = AudioPipeline()
        val utterances = mutableListOf<FloatArray>()

        val consumer = launch {
            var sinceEndpoint = 0
            while (true) {
                val chunk = pipeline.take() ?: break
                // Stand-in for StreamingAsr.acceptAudio(): three times slower
                // than the audio arrives, which is what a thermally throttled
                // mid-range phone actually does under load.
                delay(SLOW_DECODE_MS)
                sinceEndpoint++
                if (sinceEndpoint == 20) { // an endpoint every ~2s of decoded audio
                    sinceEndpoint = 0
                    utterances += pipeline.takeUtteranceThrough(pipeline.decodedPosition)
                }
            }
        }

        val producer = launch {
            repeat(totalChunks) { i ->
                pipeline.offer(chunkAt(i))
                delay(CHUNK_MS)
            }
        }

        producer.join()
        pipeline.close()
        consumer.join()
        // Whatever had not endpointed when capture stopped is finalized as the
        // last utterance — the same thing the session does when the user stops.
        utterances += pipeline.takeAllUtterance()

        val delivered = concat(utterances)
        assertContentEquals(
            signal(totalChunks),
            delivered,
            "audio reaching the final pass must be gap-free: " +
                "${totalChunks * CHUNK} samples captured, ${delivered.size} delivered",
        )
        assertEquals((totalChunks * CHUNK).toLong(), pipeline.producedSamples)

        // Sustained 3x overload cannot be absorbed by any bounded queue; what
        // matters is that the loss is on the cosmetic path and is *counted*.
        assertTrue(
            pipeline.droppedSamples > 0,
            "the test did not actually overload the decoder",
        )
        assertTrue(pipeline.droppedChunkCount > 0)
    }

    @Test
    fun `a two second decode stall costs nothing at all`() = runTest {
        // The realistic case, and the one the old inline loop failed: one slow
        // decode (GC, a big beam search, the final pass hogging the CPU) inside
        // a session that is otherwise keeping up.
        val totalChunks = 100
        val pipeline = AudioPipeline()
        val decoded = mutableListOf<FloatArray>()

        val consumer = launch {
            var index = 0
            while (true) {
                val chunk = pipeline.take() ?: break
                delay(if (index == 5) 2_000L else 0L)
                decoded += chunk.samples
                index++
            }
        }
        val producer = launch {
            repeat(totalChunks) { i ->
                pipeline.offer(chunkAt(i))
                delay(CHUNK_MS)
            }
        }
        producer.join()
        pipeline.close()
        consumer.join()

        assertEquals(0L, pipeline.droppedSamples, "a 2s stall fits inside the queue")
        assertContentEquals(signal(totalChunks), concat(decoded))
        assertContentEquals(signal(totalChunks), pipeline.takeAllUtterance())
    }

    @Test
    fun `a decoder keeping up drops nothing and stays close behind`() = runTest {
        val pipeline = AudioPipeline()
        var maxQueued = 0
        val consumer = launch {
            while (true) {
                pipeline.take() ?: break
                maxQueued = maxOf(maxQueued, pipeline.queuedSamples)
                delay(REALTIME_DECODE_MS)
            }
        }
        val producer = launch {
            repeat(50) { i ->
                pipeline.offer(chunkAt(i))
                delay(CHUNK_MS)
            }
        }
        producer.join()
        pipeline.close()
        consumer.join()
        assertEquals(0L, pipeline.droppedSamples)
        assertTrue(maxQueued <= 2 * CHUNK, "queue depth ran away at real time: $maxQueued")
    }

    // ------------------------------------------------------- drop accounting

    @Test
    fun `overflow drops the newest chunk and counts every sample of it`() {
        val pipeline = AudioPipeline(queueCapacitySamples = 3 * CHUNK)
        repeat(3) { assertTrue(pipeline.offer(chunkAt(it)).queuedForDecode) }

        val overflow = pipeline.offer(chunkAt(3))
        assertFalse(overflow.queuedForDecode)
        assertEquals(CHUNK, overflow.decodeDropped)
        assertEquals(CHUNK.toLong(), pipeline.droppedSamples)
        assertEquals(1, pipeline.droppedChunkCount)

        // ...and the final pass still has all four chunks.
        assertContentEquals(signal(4), pipeline.takeAllUtterance())
    }

    @Test
    fun `pending drops drain once so a report is never counted twice`() {
        val pipeline = AudioPipeline(queueCapacitySamples = CHUNK)
        pipeline.offer(chunkAt(0))
        pipeline.offer(chunkAt(1))
        pipeline.offer(chunkAt(2))
        assertEquals(2 * CHUNK, pipeline.drainDroppedSamples())
        assertEquals(0, pipeline.drainDroppedSamples())
        // The cumulative total survives draining; it is what a bug report wants.
        assertEquals(2L * CHUNK, pipeline.droppedSamples)
    }

    @Test
    fun `an oversized capture is admitted rather than dropped whole`() = runTest {
        // A device handing back a chunk larger than the queue must not livelock
        // the pipeline into dropping 100% of the audio.
        val pipeline = AudioPipeline(queueCapacitySamples = CHUNK)
        assertTrue(pipeline.offer(chunkAt(0)).queuedForDecode)
        val chunk = pipeline.take()
        assertEquals(CHUNK.toLong(), chunk?.endPosition)
    }

    // ---------------------------------------------------- utterance splitting

    @Test
    fun `the utterance splits at the consumer position, not at the reader's`() {
        // The endpoint is observed by the decoder, which may be chunks behind
        // the microphone. Handing the final pass everything buffered at that
        // moment would fold the start of the next utterance into this one.
        val pipeline = AudioPipeline()
        repeat(5) { pipeline.offer(chunkAt(it)) }

        val first = pipeline.takeUtteranceThrough(3L * CHUNK)
        assertContentEquals(signal(3), first)

        // The two chunks the decoder had not reached are kept for the next one.
        repeat(2) { pipeline.offer(chunkAt(5 + it)) }
        val second = pipeline.takeAllUtterance()
        assertEquals(4 * CHUNK, second.size)
        assertEquals((3L * CHUNK).toFloat(), second.first())
    }

    @Test
    fun `a split inside a chunk keeps both halves exactly once`() {
        val pipeline = AudioPipeline()
        pipeline.offer(chunkAt(0))
        pipeline.offer(chunkAt(1))

        val head = pipeline.takeUtteranceThrough(CHUNK + 400L)
        val tail = pipeline.takeAllUtterance()
        assertEquals(CHUNK + 400, head.size)
        assertEquals(CHUNK - 400, tail.size)
        assertContentEquals(signal(2), concat(listOf(head, tail)))
    }

    @Test
    fun `dropped decode audio still reaches the final pass across a split`() {
        val pipeline = AudioPipeline(queueCapacitySamples = CHUNK)
        pipeline.offer(chunkAt(0)) // queued
        pipeline.offer(chunkAt(1)) // dropped from the decode queue
        pipeline.offer(chunkAt(2)) // dropped from the decode queue
        assertEquals(2L * CHUNK, pipeline.droppedSamples)

        // Positions count dropped audio too, so a split at the decoder's
        // position still carries the samples it never saw.
        assertContentEquals(signal(3), pipeline.takeUtteranceThrough(3L * CHUNK))
    }

    @Test
    fun `discarding dead air keeps the audio the decoder has not reached`() {
        val pipeline = AudioPipeline()
        repeat(4) { pipeline.offer(chunkAt(it)) }
        pipeline.discardUtteranceThrough(2L * CHUNK)
        val kept = pipeline.takeAllUtterance()
        assertEquals(2 * CHUNK, kept.size)
        assertEquals((2L * CHUNK).toFloat(), kept.first())
    }

    @Test
    fun `taking through a stale position yields nothing and keeps the buffer`() {
        val pipeline = AudioPipeline()
        pipeline.offer(chunkAt(0))
        pipeline.takeUtteranceThrough(CHUNK.toLong())
        pipeline.offer(chunkAt(1))
        // A position already consumed must not re-emit or corrupt the buffer.
        assertEquals(0, pipeline.takeUtteranceThrough(CHUNK.toLong()).size)
        assertContentEquals(chunkAt(1), pipeline.takeAllUtterance())
    }

    @Test
    fun `an endlessly hot mic evicts the oldest audio instead of growing forever`() {
        val pipeline = AudioPipeline(utteranceCapacitySamples = 4 * CHUNK)
        repeat(10) { pipeline.offer(chunkAt(it)) }
        val kept = pipeline.takeAllUtterance()
        assertEquals(4 * CHUNK, kept.size)
        assertEquals((6L * CHUNK).toFloat(), kept.first(), "the newest audio is what is kept")
        // Eviction is a loss like any other and is reported as one — but
        // counted apart from decode drops, because this is the only kind that
        // reaches the final pass.
        assertEquals(6L * CHUNK, pipeline.evictedSamples)
        assertEquals(0L, pipeline.droppedSamples)
        assertEquals(6 * CHUNK, pipeline.drainDroppedSamples())
    }

    @Test
    fun `resetting the utterance does not disturb stream positions`() {
        val pipeline = AudioPipeline()
        repeat(3) { pipeline.offer(chunkAt(it)) }
        pipeline.resetUtterance()
        pipeline.offer(chunkAt(3))
        assertContentEquals(chunkAt(3), pipeline.takeUtteranceThrough(4L * CHUNK))
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun `close drains what is queued before ending the consumer`() = runTest {
        val pipeline = AudioPipeline()
        repeat(3) { pipeline.offer(chunkAt(it)) }
        pipeline.close()
        val drained = mutableListOf<FloatArray>()
        while (true) drained += (pipeline.take() ?: break).samples
        assertContentEquals(signal(3), concat(drained))
        assertNull(pipeline.take())
    }

    @Test
    fun `offers after close still buffer for the final pass`() {
        // The reader can be a chunk or two behind the stop request; those
        // samples are part of the utterance the session is about to finalize.
        val pipeline = AudioPipeline()
        pipeline.close()
        val result = pipeline.offer(chunkAt(0))
        assertFalse(result.queuedForDecode)
        assertContentEquals(chunkAt(0), pipeline.takeAllUtterance())
    }

    @Test
    fun `an empty chunk is a no-op`() {
        val pipeline = AudioPipeline()
        val result = pipeline.offer(FloatArray(0))
        assertFalse(result.queuedForDecode)
        assertEquals(0L, pipeline.producedSamples)
        assertEquals(0, pipeline.bufferedUtteranceSamples)
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
