package com.vboard.core.model

/**
 * Abstraction over HTTP so tests inject fakes.
 * Implementations must honor [fetch]'s rangeStart (HTTP Range).
 */
interface Fetcher {
    /**
     * Streams the body of [url] starting at byte [rangeStart] to [sink], calling [onBytes] with
     * cumulative NEW bytes written this call. Throws [java.io.IOException] on network failure.
     * Must check for coroutine cancellation.
     */
    suspend fun fetch(url: String, rangeStart: Long, sink: java.io.OutputStream, onBytes: (Long) -> Unit)

    /** Total size in bytes, or -1 if unknown. */
    suspend fun contentLength(url: String): Long
}
