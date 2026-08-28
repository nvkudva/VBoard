package com.vboard.app.voice

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional on-device LLM pass that rewrites a finalized utterance toward the
 * speaker's intent (Superwhisper-style). Failure is always safe: callers keep
 * the rule-cleaned text whenever this returns null.
 */
class LlmRefiner(
    private val context: Context,
    private val modelPath: String,
) {
    @Volatile
    private var llm: LlmInference? = null

    private fun engine(): LlmInference {
        return llm ?: synchronized(this) {
            llm ?: LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(256)
                    .build(),
            ).also { llm = it }
        }
    }

    /** Warms the model so the first refinement doesn't pay init cost. */
    suspend fun preload() = withContext(Dispatchers.IO) {
        runCatching { engine() }
    }

    /**
     * Returns refined text, or null when refinement fails, times out, or the
     * model output fails sanity checks (never make the text worse).
     */
    suspend fun refine(text: String, timeoutMs: Long = 3_000): String? {
        if (text.isBlank() || text.length > MAX_INPUT_CHARS) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val raw = engine().generateResponse(prompt(text))
                    sanitize(raw, text)
                }.getOrNull()
            }
        }
    }

    private fun prompt(text: String): String =
        // Qwen2.5 chat template, single turn.
        "<|im_start|>system\n" +
            "You clean up dictated speech. Fix grammar, remove filler words and " +
            "false starts, keep the speaker's meaning and tone, and preserve all " +
            "facts, names and numbers. Reply with ONLY the cleaned text - no " +
            "explanations, no quotes.<|im_end|>\n" +
            "<|im_start|>user\n$text<|im_end|>\n" +
            "<|im_start|>assistant\n"

    /** Rejects hallucinated or degenerate outputs. */
    private fun sanitize(raw: String?, original: String): String? {
        var out = raw?.trim() ?: return null
        out = out.removePrefix("\"").removeSuffix("\"").trim()
        out = out.substringBefore("<|im_end|>").trim()
        if (out.isEmpty()) return null
        // Length sanity: refinement shouldn't shrink below a third or grow past double.
        val ratio = out.length.toDouble() / original.length
        if (ratio < 0.33 || ratio > 2.0) return null
        if (out.equals(original, ignoreCase = true)) return null
        return out
    }

    fun release() {
        runCatching { llm?.close() }
        llm = null
    }

    companion object {
        private const val MAX_INPUT_CHARS = 600
    }
}
