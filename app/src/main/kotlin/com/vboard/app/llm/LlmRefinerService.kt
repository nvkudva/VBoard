package com.vboard.app.llm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.vboard.app.VBoardApp
import com.vboard.app.voice.LlmRefiner
import com.vboard.core.correct.SmartFailure
import kotlinx.coroutines.runBlocking

/**
 * Hosts the 0.5B refiner in its own process (`:llm`).
 *
 * The keyboard is not an app the user can decide to reopen: when its process
 * dies mid-sentence they lose the ability to type, in whatever app they were
 * typing in. A native OOM or a MediaPipe crash inside the refiner used to do
 * exactly that, because the model was loaded into the IME process — which also
 * set the keyboard's memory ceiling at the size of a feature nobody has to use.
 * Both problems are the same problem, and this service is the fix
 * (V2_PLAN Wave 0.5).
 *
 * Everything here is deliberately dumb: no state beyond the loaded model, no
 * callbacks, one call at a time. The interesting half of the change lives in
 * [LlmRefinerClient], which has to treat every call as failable.
 */
class LlmRefinerService : Service() {

    /**
     * MediaPipe's `generateResponse` is one blocking JNI call and the binder
     * thread pool will happily deliver several at once, so calls are serialized
     * here rather than trusting callers to do it.
     */
    private val engineLock = Any()

    @Volatile
    private var refiner: LlmRefiner? = null

    private val binder = object : ILlmRefiner.Stub() {

        override fun preload(): Boolean = synchronized(engineLock) {
            val engine = engineOrNull() ?: return false
            runBlocking { engine.preload() }
            true
        }

        override fun refine(text: String?, timeoutMs: Long): String? {
            val input = text ?: return null
            return synchronized(engineLock) {
                val engine = engineOrNull() ?: return null
                runBlocking { engine.refine(input, timeoutMs) }
            }
        }

        override fun correct(text: String?, timeoutMs: Long): Bundle {
            val input = text ?: return failure(SmartFailure.ERROR)
            return synchronized(engineLock) {
                val engine = engineOrNull() ?: return failure(SmartFailure.LOAD_FAILED)
                val out = runBlocking { engine.correct(input, timeoutMs) }
                val corrected = out.text()
                if (corrected.isNullOrEmpty()) {
                    failure(out.failure ?: SmartFailure.ERROR)
                } else {
                    Bundle().apply { putString(KEY_TEXT, corrected) }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // The process usually just goes away when the last client unbinds, which
        // is how the model's memory is actually reclaimed; releasing here covers
        // the case where it does not.
        synchronized(engineLock) {
            runCatching { refiner?.release() }
            refiner = null
        }
        super.onDestroy()
    }

    /** Builds the engine on first use; null when no refiner pack is installed. */
    private fun engineOrNull(): LlmRefiner? {
        refiner?.let { return it }
        val app = application as? VBoardApp ?: return null
        val path = app.modelStore.refinerModelPath(app.packInstaller) ?: run {
            Log.i(TAG, "no refiner pack installed")
            return null
        }
        return LlmRefiner(applicationContext, path).also { refiner = it }
    }

    private fun failure(kind: SmartFailure): Bundle =
        Bundle().apply { putString(KEY_FAILURE, kind.name) }

    companion object {
        private const val TAG = "VBoardLlmService"
        const val KEY_TEXT = "text"
        const val KEY_FAILURE = "failure"
    }
}
