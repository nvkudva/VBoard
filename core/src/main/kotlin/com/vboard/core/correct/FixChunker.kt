package com.vboard.core.correct

/**
 * The length policy for the smart (LLM) half of "AI fix".
 *
 * The packaged refiner is Qwen2.5-0.5B-Instruct in the `ekv1280` build: a
 * 1280-token KV cache shared between the prompt and the answer. A correction
 * pass is near-isometric, so the budget is roughly *prompt + input + input*.
 * With ~70 tokens of chat template that leaves about 600 tokens of input, and
 * English averages well under a token per character — [MAX_CHUNK_CHARS] of 600
 * is the conservative reading of that, and matches the cap the dictation
 * refiner already uses.
 *
 * Longer text is therefore split, not truncated. Splitting happens on sentence
 * and paragraph boundaries, and the pieces are cut so that
 *
 *     assemble(split(t), split(t).map { it.body() }) == t
 *
 * holds for every input: whitespace between sentences is carried in [Segment]
 * prefixes and suffixes that the model never sees and can never reformat. A
 * sentence that is itself longer than the cap is marked non-refinable rather
 * than cut mid-thought — a chunk whose result cannot be put back faithfully is
 * never sent at all.
 *
 * Above [MAX_SMART_CHARS] the smart tier declines entirely (the caller still
 * applies the deterministic pass and says so); above [MAX_FIELD_CHARS] the whole
 * operation is refused.
 */
object FixChunker {

    /** Largest piece handed to the refiner in one call. */
    const val MAX_CHUNK_CHARS = 600

    /** Total text the smart tier will attempt; beyond this it declines honestly. */
    const val MAX_SMART_CHARS = 3_000

    /** Total text "AI fix" will touch at all. */
    const val MAX_FIELD_CHARS = 20_000

    /**
     * One reassembly unit. [prefix] and [suffix] are whitespace held back from
     * the model; [body] is what gets corrected.
     *
     * Not a data class — [body] is user content and must stay out of logs.
     */
    class Segment internal constructor(
        val prefix: String,
        private val body: String,
        val suffix: String,
        val refinable: Boolean,
    ) {
        fun body(): String = body

        override fun toString(): String = "Segment(refinable=$refinable)"
    }

    fun split(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val groups = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in sentencePieces(text)) {
            if (current.isNotEmpty() && current.length + piece.length > MAX_CHUNK_CHARS) {
                groups.add(current.toString())
                current.setLength(0)
            }
            current.append(piece)
        }
        if (current.isNotEmpty()) groups.add(current.toString())

        return groups.map { group ->
            val prefix = group.takeWhile { it.isWhitespace() }
            val rest = group.substring(prefix.length)
            val suffix = rest.takeLastWhile { it.isWhitespace() }
            val body = rest.substring(0, rest.length - suffix.length)
            Segment(
                prefix = prefix,
                body = body,
                suffix = suffix,
                refinable = body.isNotBlank() && body.length <= MAX_CHUNK_CHARS,
            )
        }
    }

    /**
     * Rebuilds the full text from [segments] and one replacement body each,
     * in order. Throws when the counts disagree, because a silent mismatch here
     * would mean writing scrambled text into the user's field.
     */
    fun assemble(segments: List<Segment>, bodies: List<String>): String {
        require(segments.size == bodies.size) {
            "segment/body count mismatch: ${segments.size} vs ${bodies.size}"
        }
        val out = StringBuilder()
        for (i in segments.indices) {
            out.append(segments[i].prefix).append(bodies[i]).append(segments[i].suffix)
        }
        return out.toString()
    }

    /**
     * Cuts [text] after every sentence terminator followed by whitespace, and
     * after every run of newlines. The pieces concatenate back to [text] exactly.
     */
    private fun sentencePieces(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                i++
                while (i < text.length && text[i] == '\n') i++
                out.add(text.substring(start, i))
                start = i
                continue
            }
            if (c == '.' || c == '!' || c == '?') {
                var last = i
                while (last + 1 < text.length && text[last + 1] in TERMINATORS) last++
                val next = last + 1
                if (next >= text.length || text[next].isWhitespace()) {
                    var k = next
                    while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
                    out.add(text.substring(start, k))
                    start = k
                    i = k
                    continue
                }
                i = last + 1
                continue
            }
            i++
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    private const val TERMINATORS = ".!?"
}
