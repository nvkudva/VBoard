package com.vboard.core.model

import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * In-memory [Fetcher] with scriptable behaviors for tests: bodies per url, one-shot simulated
 * network failure after N bytes, and hanging (until cancellation) after N bytes.
 */
class FakeFetcher : Fetcher {

    data class Call(val url: String, val rangeStart: Long)

    /** url -> full body bytes. */
    val bodies = mutableMapOf<String, ByteArray>()

    /** Every fetch invocation, in order. */
    val calls = mutableListOf<Call>()

    /** url -> throw IOException once, after at least this many NEW bytes were written. */
    val failAfterBytes = mutableMapOf<String, Long>()

    /** url -> suspend forever (awaiting cancellation) after at least this many NEW bytes. */
    val hangAfterBytes = mutableMapOf<String, Long>()

    /** Cumulative bytes actually served per url, across all calls. */
    val servedBytes = mutableMapOf<String, Long>()

    var chunkSize = 10

    override suspend fun fetch(url: String, rangeStart: Long, sink: OutputStream, onBytes: (Long) -> Unit) {
        calls += Call(url, rangeStart)
        val body = bodies[url] ?: throw IOException("no body scripted for $url")
        val failAt = failAfterBytes.remove(url)
        val hangAt = hangAfterBytes[url]
        require(rangeStart in 0..body.size.toLong()) { "bad rangeStart $rangeStart for $url" }

        var newBytes = 0L
        var pos = rangeStart.toInt()
        while (pos < body.size) {
            coroutineContext.ensureActive()
            if (hangAt != null && newBytes >= hangAt) awaitCancellation()
            val end = minOf(body.size, pos + chunkSize)
            sink.write(body, pos, end - pos)
            sink.flush()
            newBytes += (end - pos).toLong()
            servedBytes[url] = (servedBytes[url] ?: 0L) + (end - pos)
            pos = end
            onBytes(newBytes)
            if (failAt != null && newBytes >= failAt) throw IOException("simulated network failure for $url")
        }
    }

    override suspend fun contentLength(url: String): Long = bodies[url]?.size?.toLong() ?: -1L
}
