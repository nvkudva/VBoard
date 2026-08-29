package com.vboard.app.clipboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Debounced, atomic persistence for the clip history.
 *
 * This mirrors the learned-word store in [com.vboard.app.VBoardApp] exactly,
 * including the two hard-won parts: the write goes to a `.tmp` that is fsynced
 * and then renamed, so a crash or a full disk cannot leave a half-file behind;
 * and a file that fails to parse is left alone rather than saved over, because
 * substituting an empty history and then persisting it turns a recoverable read
 * failure into permanent loss.
 *
 * Nothing here logs clip content, a character count, or a preview — only that an
 * operation failed, plus the exception.
 */
class ClipboardStore(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val dir: File by lazy { File(appContext.filesDir, DIR_NAME) }
    private val file: File by lazy { File(dir, FILE_NAME) }
    private val tempFile: File by lazy { File(dir, "$FILE_NAME.tmp") }

    private val mutex = Mutex()
    private var saveJob: Job? = null

    /**
     * Set when an existing store could not be parsed. While true nothing is
     * written, so the damaged file survives for a post-mortem.
     */
    @Volatile
    private var readFailed = false

    /** Reads the store off the main thread. Returns null when there is nothing to read. */
    suspend fun load(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (file.exists()) file.readText() else null
            } catch (e: IOException) {
                Log.w(TAG, "clip store unreadable", e)
                readFailed = true
                null
            }
        }
    }

    /** Call when [load]'s content failed to parse, so nothing overwrites it. */
    fun markReadFailed() {
        readFailed = true
    }

    /**
     * Persists [snapshot] after a short debounce. The caller serializes on its
     * own thread and hands over an immutable string, so the history object is
     * never touched from the IO dispatcher.
     */
    fun scheduleSave(snapshot: String) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            write(snapshot)
        }
    }

    /** Persists immediately, skipping the debounce (teardown, or a master-switch flip). */
    fun saveNow(snapshot: String) {
        saveJob?.cancel()
        saveJob = scope.launch { write(snapshot) }
    }

    private suspend fun write(snapshot: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (readFailed) return@withContext
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IOException("could not create the clip directory")
                }
                FileOutputStream(tempFile).use { out ->
                    out.write(snapshot.toByteArray())
                    out.flush()
                    out.fd.sync()
                }
                if (!tempFile.renameTo(file)) {
                    throw IOException("could not activate the clip file")
                }
            } catch (e: IOException) {
                Log.w(TAG, "clip save failed", e)
                tempFile.delete()
            }
        }
    }

    /**
     * Deletes the store outright. Cancels any pending save first, so a debounced
     * write from before the deletion cannot resurrect the file behind it.
     */
    fun deleteFile() {
        saveJob?.cancel()
        saveJob = scope.launch {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    // A delete supersedes an unreadable file: the user asked for
                    // it to be gone, which is also the only way out of that state.
                    readFailed = false
                    if (file.exists() && !file.delete()) Log.w(TAG, "clip file delete failed")
                    tempFile.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "VBoardClips"
        private const val DIR_NAME = "clipboard"
        private const val FILE_NAME = "clips.v1.json"
        private const val SAVE_DEBOUNCE_MS = 2_000L

        /**
         * Deletes the store from outside the IME process's object graph (the
         * settings screen). Blocking, so callers must be on an IO dispatcher.
         */
        fun deleteBlocking(context: Context) {
            val dir = File(context.applicationContext.filesDir, DIR_NAME)
            runCatching { File(dir, FILE_NAME).delete() }
                .onFailure { Log.w(TAG, "clip file delete failed", it) }
            runCatching { File(dir, "$FILE_NAME.tmp").delete() }
                .onFailure { Log.w(TAG, "clip temp delete failed", it) }
        }
    }
}
