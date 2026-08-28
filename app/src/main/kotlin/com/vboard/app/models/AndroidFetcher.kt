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

    override suspend fun contentLength(url: String): Long = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            connection.requestMethod = "HEAD"
            connection.connect()
            connection.contentLengthLong
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
        return connection
    }
}
