package com.vboard.app.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.vboard.app.VBoardApp
import com.vboard.core.correct.SmartFailure
import com.vboard.core.correct.SmartOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The refiner as the keyboard sees it: a connection to [LlmRefinerService] in
 * the `:llm` process.
 *
 * Every method answers the same way the in-process refiner did when it could not
 * do its job — null, or a typed [SmartFailure] — so the callers' existing "keep
 * the rules-only text" paths cover the new failure modes for free. What is new
 * is that there are more of them: the process can be killed for memory at any
 * moment, and a call in flight then comes back as a [RemoteException] rather
 * than a result.
 *
 * Honest limitation, unchanged by the move: generation is one blocking call, so
 * a timeout stops the *caller* waiting, not the model working. What the split
 * buys is that a model which wedges or dies takes its own process with it and
 * leaves the keyboard typing.
 */
class LlmRefinerClient(context: Context) : RemoteRefiner {

    private val appContext = context.applicationContext

    /** Serializes binding; the service serializes the calls themselves. */
    private val connectLock = Mutex()

    @Volatile
    private var binder: ILlmRefiner? = null

    private var pending: CompletableDeferred<ILlmRefiner?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = ILlmRefiner.Stub.asInterface(service)
            binder = stub
            pending?.complete(stub)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The process died — with the model in it, which is the outcome this
            // whole arrangement exists to make survivable.
            Log.w(TAG, "refiner process went away")
            binder = null
            pending?.complete(null)
            pending = null
        }
    }

    override suspend fun preload() {
        call { it.preload() }
    }

    override suspend fun refine(text: String, timeoutMs: Long): String? =
        call { it.refine(text, timeoutMs) }

    override suspend fun correct(text: String, timeoutMs: Long): SmartOutput {
        val bundle = call { it.correct(text, timeoutMs) }
            ?: return SmartOutput.failed(SmartFailure.LOAD_FAILED)
        val corrected = bundle.getString(LlmRefinerService.KEY_TEXT)
        if (!corrected.isNullOrEmpty()) return SmartOutput.of(corrected)
        val failure = bundle.getString(LlmRefinerService.KEY_FAILURE)
            ?.let { name -> SmartFailure.entries.firstOrNull { it.name == name } }
        return SmartOutput.failed(failure ?: SmartFailure.ERROR)
    }

    /**
     * Drops the connection so the `:llm` process can be reclaimed with the model
     * in it. Called from the same idle path that releases the recognizers.
     */
    fun disconnect() {
        if (binder == null) return
        binder = null
        runCatching { appContext.unbindService(connection) }
    }

    private suspend fun <T> call(block: (ILlmRefiner) -> T): T? {
        val service = connect() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                block(service)
            } catch (e: RemoteException) {
                // Includes DeadObjectException: the model process was killed
                // between binding and answering.
                Log.w(TAG, "refiner call failed", e)
                binder = null
                null
            }
        }
    }

    private suspend fun connect(): ILlmRefiner? {
        binder?.let { if (it.asBinder().isBinderAlive) return it else binder = null }
        return connectLock.withLock {
            binder?.let { return@withLock it }
            val deferred = CompletableDeferred<ILlmRefiner?>()
            pending = deferred
            val intent = Intent(appContext, LlmRefinerService::class.java)
            val bound = runCatching {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) {
                Log.w(TAG, "could not bind the refiner process")
                pending = null
                runCatching { appContext.unbindService(connection) }
                return@withLock null
            }
            val result = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            pending = null
            if (result == null) Log.w(TAG, "refiner process did not start in time")
            result
        }
    }

    private companion object {
        const val TAG = "VBoardLlmClient"

        /**
         * Starting a process and handing back a binder is fast; loading the model
         * is not, and does not happen here. A wait longer than this means the
         * device is in trouble, and the caller is better served by its
         * deterministic fallback than by more waiting.
         */
        const val BIND_TIMEOUT_MS = 2_000L
    }
}

/** What the keyboard calls; implemented over binder, faked in tests. */
interface RemoteRefiner {
    suspend fun preload()
    suspend fun refine(text: String, timeoutMs: Long = 3_000L): String?
    suspend fun correct(text: String, timeoutMs: Long = CORRECT_TIMEOUT_MS): SmartOutput

    companion object {
        /** Mirrors LlmRefiner.CORRECT_TIMEOUT_MS, which now lives in another process. */
        const val CORRECT_TIMEOUT_MS = 6_000L
    }
}

/** Null when no refiner pack is installed; the model path is readable from any process. */
fun refinerClientOrNull(context: Context, app: VBoardApp): LlmRefinerClient? {
    app.modelStore.refinerModelPath(app.packInstaller) ?: return null
    return LlmRefinerClient(context)
}
