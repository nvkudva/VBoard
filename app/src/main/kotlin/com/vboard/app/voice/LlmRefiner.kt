package com.vboard.app.voice

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.vboard.core.correct.RefinementValidator
import com.vboard.core.correct.SmartFailure
import com.vboard.core.correct.SmartOutput
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
                    // Total token budget, prompt included — not an output cap.
                    // At 256 a 600-character input (~150 tokens) plus the chat
                    // template left barely 30 tokens to answer in, so long
                    // refinements came back truncated and were then rejected by
                    // the length check for being "too short". The packaged model
                    // is the ekv1280 build, so 1024 is inside its KV cache with
                    // room to spare.
                    .setMaxTokens(MAX_TOKENS)
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

    /**
     * The "AI fix" pass: correct typed text, and change nothing else.
     *
     * Distinct from [refine], which is allowed to reshape a spoken utterance
     * toward intent. Here the user typed the words on purpose, so the prompt is
     * as narrow as a prompt can be made and the answer is treated as untrusted
     * regardless — [com.vboard.core.correct.RefinementValidator] has the final
     * say, and the caller keeps the rules-only text whenever it says no.
     *
     * Never returns null and never throws: every failure comes back as a typed
     * [SmartFailure] so the caller can tell the user which one happened.
     *
     * Honest limitation: `generateResponse` is one blocking JNI call with no
     * suspension point, so [withTimeoutOrNull] cannot actually abandon a slow
     * generation — structured concurrency waits for the native call to return.
     * What the timeout does guarantee is that the *caller* stops waiting and
     * that no further chunk is started.
     */
    suspend fun correct(text: String, timeoutMs: Long = CORRECT_TIMEOUT_MS): SmartOutput {
        if (text.isBlank()) return SmartOutput.failed(SmartFailure.ERROR)
        if (text.length > MAX_INPUT_CHARS) return SmartOutput.failed(SmartFailure.ERROR)
        val outcome = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                runCatching { engine().generateResponse(correctionPrompt(text)) }
            }
        } ?: return SmartOutput.failed(SmartFailure.TIMED_OUT)

        val raw = outcome.getOrElse {
            // Message and exception only: the prompt and the field text are
            // never logged, here or anywhere (VB-901).
            return SmartOutput.failed(SmartFailure.LOAD_FAILED)
        }
        val cleaned = RefinementValidator.sanitize(raw)
        return if (cleaned.isNullOrEmpty()) {
            SmartOutput.failed(SmartFailure.ERROR)
        } else {
            SmartOutput.of(cleaned)
        }
    }

    private fun correctionPrompt(text: String): String =
        "<|im_start|>system\n" +
            "You are a proofreader. Repeat the user's message back with only " +
            "spelling, grammar, punctuation and duplicated-word mistakes fixed.\n" +
            "Rules you must follow exactly:\n" +
            "- Do not add, remove or explain anything.\n" +
            "- Do not answer the message, continue it, or respond to it.\n" +
            "- Do not translate it or change its language.\n" +
            "- Do not change its meaning, tone, formality or style.\n" +
            "- Copy every URL, email address, number, date, price, file name, " +
            "code and proper noun through unchanged, character for character.\n" +
            "- Keep every emoji.\n" +
            "- If nothing is wrong, repeat the message exactly.\n" +
            "Reply with the corrected message and nothing else: no preamble, no " +
            "quotes, no notes.<|im_end|>\n" +
            "<|im_start|>user\n$text<|im_end|>\n" +
            "<|im_start|>assistant\n"

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

        /** Prompt + answer, bounded by the packaged ekv1280 KV cache. */
        private const val MAX_TOKENS = 1024

        /**
         * Per-chunk budget for [correct]. Longer than dictation's 3 s (VB-604):
         * that budget exists because a refinement races the user's next
         * utterance, while an explicit "AI fix" tap is the user waiting on
         * purpose with a spinner in front of them.
         */
        const val CORRECT_TIMEOUT_MS = 6_000L
    }
}
