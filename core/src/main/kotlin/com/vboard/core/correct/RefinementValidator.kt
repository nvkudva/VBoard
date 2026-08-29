package com.vboard.core.correct

/** Why a model result was thrown away. Enum only — never carries content. */
enum class RejectReason {
    /** Nothing usable came back. */
    EMPTY,

    /** Chat-template scaffolding leaked into the answer. */
    TEMPLATE_LEAK,

    /** The model deleted most of the message. */
    TOO_SHORT,

    /** The model kept writing instead of stopping. */
    TOO_LONG,

    /** A URL, email address or number present in the input is gone. */
    DROPPED_ENTITY,

    /** The answer opens with chat commentary ("Sure, here's the corrected…"). */
    COMMENTARY,

    /** The answer is too far from the input to be a correction of it. */
    DIVERGED,
}

/**
 * The outcome of checking one model result. Not a data class: the accepted text
 * is user content, so it is reachable only through [text].
 */
class RefinementVerdict private constructor(
    val reason: RejectReason?,
    private val text: String?,
) {
    val accepted: Boolean get() = reason == null

    fun text(): String? = text

    override fun toString(): String =
        if (accepted) "RefinementVerdict(accepted)" else "RefinementVerdict(rejected=$reason)"

    internal companion object {
        fun accept(text: String) = RefinementVerdict(null, text)
        fun reject(reason: RejectReason) = RefinementVerdict(reason, null)
    }
}

/**
 * Treats the refiner's output as untrusted input, which is what it is.
 *
 * A 0.5B instruct model asked to correct a sentence will sometimes answer it,
 * continue it, translate it, summarize it, or wrap it in "Sure! Here's the
 * corrected version:". None of that may ever reach the user's field, so a result
 * only survives if it is plausibly *the same message, spelled better*:
 *
 *  1. non-empty, with no chat-template scaffolding left in it;
 *  2. within a length band of the input — a correction is near-isometric;
 *  3. every URL, email address and number from the input still present verbatim;
 *  4. no conversational preamble the input did not already have;
 *  5. close enough to the input by character edit distance that it cannot be a
 *     different piece of writing.
 *
 * Rule 5 is skipped for very short inputs, where fixing two letters in a
 * six-character word is legitimately a large relative edit and the length band
 * is doing the work instead.
 *
 * Every rejection is a fallback to the rules-only text, never an error the user
 * has to deal with.
 */
object RefinementValidator {

    const val MIN_LENGTH_RATIO = 0.6
    const val MAX_LENGTH_RATIO = 1.8

    /** Below this normalized similarity, the result is a different message. */
    const val MIN_SIMILARITY = 0.6

    /** Inputs shorter than this skip the similarity rule (see class docs). */
    const val SIMILARITY_MIN_INPUT_CHARS = 24

    private val TEMPLATE_MARKERS = listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")

    private val COMMENTARY_PREFIXES = listOf(
        "sure", "certainly", "of course", "here is", "here's", "here are",
        "the corrected", "corrected text", "corrected version", "i have corrected",
        "i've corrected", "i corrected", "as an ai", "output:", "answer:",
        "result:", "note:", "no changes", "your text", "this text",
    )

    private val URL_PATTERN = Regex("""(?:https?://|www\.)[^\s]+""", RegexOption.IGNORE_CASE)
    private val EMAIL_PATTERN = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val NUMBER_PATTERN = Regex("""\d+(?:[.,:/\-]\d+)*""")

    /** Trailing sentence punctuation that is not part of a URL. */
    private const val URL_TRAILING = ".,!?;:)]}\"'"

    fun validate(original: String, candidate: String?): RefinementVerdict {
        val cleaned = sanitize(candidate)
        if (cleaned.isNullOrEmpty()) return RefinementVerdict.reject(RejectReason.EMPTY)
        if (cleaned.contains("<|")) return RefinementVerdict.reject(RejectReason.TEMPLATE_LEAK)

        // Commentary is checked before the length band: "Sure, here's the
        // corrected text: …" is also too long, and the specific diagnosis is the
        // useful one.
        if (hasAddedCommentary(original, cleaned)) {
            return RefinementVerdict.reject(RejectReason.COMMENTARY)
        }

        if (original.isNotEmpty()) {
            val ratio = cleaned.length.toDouble() / original.length
            if (ratio < MIN_LENGTH_RATIO) return RefinementVerdict.reject(RejectReason.TOO_SHORT)
            if (ratio > MAX_LENGTH_RATIO) return RefinementVerdict.reject(RejectReason.TOO_LONG)
        }

        for (entity in entities(original)) {
            if (!cleaned.contains(entity)) {
                return RefinementVerdict.reject(RejectReason.DROPPED_ENTITY)
            }
        }

        if (original.length >= SIMILARITY_MIN_INPUT_CHARS &&
            similarity(original, cleaned) < MIN_SIMILARITY
        ) {
            return RefinementVerdict.reject(RejectReason.DIVERGED)
        }

        return RefinementVerdict.accept(cleaned)
    }

    /**
     * Strips the scaffolding a chat model wraps around an answer: everything from
     * the first template marker onward, and one symmetric pair of quotes.
     */
    fun sanitize(raw: String?): String? {
        var out = raw?.trim() ?: return null
        for (marker in TEMPLATE_MARKERS) {
            val at = out.indexOf(marker)
            if (at >= 0) out = out.substring(0, at)
        }
        out = out.trim()
        if (out.length >= 2 &&
            (out.first() == '"' && out.last() == '"' || out.first() == '“' && out.last() == '”')
        ) {
            out = out.substring(1, out.length - 1).trim()
        }
        return out
    }

    /**
     * URLs, email addresses and numbers found in [text]. Each one must appear
     * verbatim in a result for that result to be accepted.
     */
    fun entities(text: String): List<String> {
        val found = LinkedHashSet<String>()
        URL_PATTERN.findAll(text).forEach { found.add(it.value.trimEnd { c -> c in URL_TRAILING }) }
        EMAIL_PATTERN.findAll(text).forEach { found.add(it.value) }
        NUMBER_PATTERN.findAll(text).forEach { found.add(it.value) }
        return found.filter { it.isNotEmpty() }
    }

    /**
     * True when [candidate] opens with a conversational preamble that [original]
     * did not — "here's the thing" as a message must not be mistaken for one.
     */
    private fun hasAddedCommentary(original: String, candidate: String): Boolean {
        val candidateStart = normalizeForPrefix(candidate)
        val originalStart = normalizeForPrefix(original)
        return COMMENTARY_PREFIXES.any { prefix ->
            val normalized = normalizeForPrefix(prefix)
            startsWithWord(candidateStart, normalized) && !startsWithWord(originalStart, normalized)
        }
    }

    /** Prefix match on a whole-word boundary, so "surely" is not "sure". */
    private fun startsWithWord(text: String, prefix: String): Boolean =
        text == prefix || text.startsWith("$prefix ")

    /**
     * Folds away the punctuation and casing the model may have added, so a user
     * who typed "heres the plan" is recognized in a result that reads "Here's
     * the plan." and is not mistaken for the model editorializing.
     */
    private fun normalizeForPrefix(text: String): String = buildString {
        for (ch in text) {
            when {
                ch.isLetterOrDigit() -> append(ch.lowercaseChar())
                ch.isWhitespace() -> if (isNotEmpty() && last() != ' ') append(' ')
            }
        }
    }

    /** 1.0 for identical strings, falling toward 0 with edit distance. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - editDistance(a.lowercase(), b.lowercase()).toDouble() / longest
    }

    /** Levenshtein distance, two rows of state. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
