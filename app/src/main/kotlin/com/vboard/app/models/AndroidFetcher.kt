package com.vboard.app.models

import com.vboard.core.model.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** HttpURLConnection-based [Fetcher] with HTTP Range resume support. */
class AndroidFetcher : Fetcher {

    override suspend fun fetch(
        url: String,
        rangeStart: Long,
        sink: OutputStream,
        onBytes: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            if (rangeStart > 0) {
                connection.setRequestProperty("Range", "bytes=$rangeStart-")
            }
            connection.connect()
            val code = connection.responseCode
            if (code == RANGE_NOT_SATISFIABLE && rangeStart > 0) {
                // Nothing left to fetch: the local .part already holds the whole file.
                return@withContext
            }
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            if (rangeStart > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                // Server ignored the Range header; restarting from zero would
                // corrupt the resume logic, so treat as an error.
                throw IOException("Server does not support resume (HTTP $code)")
            }
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            connection.inputStream.use { input ->
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    total += read
                    onBytes(total)
                }
            }
            sink.flush()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Authoritative size of the artifact, or -1 when the server won't say. Tries HEAD
     * first and falls back to a single-byte ranged GET, because some CDNs answer HEAD
     * with 405 or omit Content-Length on it.
     */
    override suspend fun contentLength(url: String): Long = withContext(Dispatchers.IO) {
        headLength(url).takeIf { it >= 0 } ?: rangeProbeLength(url)
    }

    private fun headLength(url: String): Long {
        val connection = open(url)
        return try {
            connection.requestMethod = "HEAD"
            connection.connect()
            if (connection.responseCode in 200..299) connection.contentLengthLong else -1L
        } catch (_: IOException) {
            -1L
        } finally {
            connection.disconnect()
        }
    }

    /** Reads the total from `Content-Range: bytes 0-0/<total>`. */
    private fun rangeProbeLength(url: String): Long {
        val connection = open(url)
        return try {
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return -1L
            val total = connection.getHeaderField("Content-Range")?.substringAfterLast('/')
            total?.trim()?.toLongOrNull() ?: -1L
        } catch (_: IOException) {
            -1L
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        // Without this the stack may request gzip and silently inflate the body, so the
        // bytes we write would no longer match the Content-Length the installer verifies.
        connection.setRequestProperty("Accept-Encoding", "identity")
        return connection
    }

    private companion object {
        const val RANGE_NOT_SATISFIABLE = 416
    }
}
