package com.vboard.core.text

/**
 * Minimal token model for transcript cleanup. ASR output is plain-ish text;
 * we tokenize into words, punctuation, and hard line breaks, transform the
 * token list, then render back to a string with normalized spacing.
 */
sealed interface Tok {
    /** A word: any run of characters that is not structural punctuation or space. */
    data class Word(val text: String) : Tok

    /** A single punctuation character, e.g. "." "," "?" "\"" */
    data class Punct(val text: String) : Tok

    /** A hard break: "\n" or "\n\n". */
    data class Break(val text: String) : Tok
}

object Tokenizer {

    /**
     * The punctuation the cleanup pipeline reasons about *structurally* — the
     * characters later stages align on, collapse, or attach to a neighbour.
     *
     * This is deliberately NOT an allow-list of "characters the user may keep".
     * Everything that is neither one of these, nor whitespace, nor a member of
     * [isAsrArtifact]'s closed deny-list, is carried through inside a [Tok.Word].
     * The old allow-list-keep policy deleted every currency sign, math symbol,
     * combining mark and astral code point in the user's text (VB-QA-13…-17).
     */
    private val STRUCTURAL_PUNCT: Set<Int> = ".,!?;:\"&@#%()-".map { it.code }.toSet()

    /**
     * The only characters the tokenizer drops. Everything here is a recognizer
     * or transport artifact that no user dictated and no field can render:
     * control codes, unpaired surrogates (which an InputConnection write would
     * reject outright), code points Unicode has not assigned, the byte-order
     * mark, and the decoding replacement character.
     *
     * Unlike the old `else -> flushWord()`, dropping one of these does not end
     * the current word — a deletion must never manufacture a word boundary the
     * user never spoke.
     */
    private fun isAsrArtifact(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONTROL.toInt(),    // C0/C1; '\n' and '\t' are handled before this
        Character.SURROGATE.toInt(),  // unpaired: would corrupt the target field
        Character.UNASSIGNED.toInt(),
        -> true
        else -> cp == 0xFEFF || cp == 0xFFFD // BOM / replacement character
    }

    /** True for horizontal or vertical space, including non-breaking forms. */
    private fun isSpace(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    /** A code point that belongs inside a word: anything not space, structure or artifact. */
    private fun isWordCp(cp: Int): Boolean =
        cp != '\n'.code &&
            cp != '…'.code &&
            cp !in STRUCTURAL_PUNCT &&
            !isSpace(cp) &&
            !isAsrArtifact(cp)

    fun tokenize(input: String): MutableList<Tok> {
        val tokens = mutableListOf<Tok>()
        // Typographic folding happens up front so the rules below only ever see
        // the canonical ASCII form of a quote, an apostrophe or a dash.
        val normalized = input
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('—', '-')
            .replace("\r\n", "\n")
        var i = 0
        val n = normalized.length
        val word = StringBuilder()

        fun flushWord() {
            if (word.isNotEmpty()) {
                tokens.add(Tok.Word(word.toString()))
                word.clear()
            }
        }

        /** True when the code point after the one at [at] can continue a word. */
        fun nextContinuesWord(at: Int, width: Int): Boolean {
            val j = at + width
            return j < n && isWordCp(normalized.codePointAt(j))
        }

        while (i < n) {
            val cp = normalized.codePointAt(i)
            val width = Character.charCount(cp)
            when {
                cp == '\n'.code -> {
                    flushWord()
                    var count = 0
                    while (i < n && (normalized[i] == '\n' || normalized[i] == ' ')) {
                        if (normalized[i] == '\n') count++
                        i++
                    }
                    tokens.add(Tok.Break(if (count >= 2) "\n\n" else "\n"))
                    continue
                }
                cp == '.'.code && i + 2 < n && normalized[i + 1] == '.' && normalized[i + 2] == '.' -> {
                    flushWord()
                    tokens.add(Tok.Punct("..."))
                    i += 3
                    continue
                }
                cp == '…'.code -> {
                    flushWord()
                    tokens.add(Tok.Punct("..."))
                }
                cp in STRUCTURAL_PUNCT -> {
                    // Punctuation with a word character on both sides is *inside* a
                    // word, not between two: "a_b@c.com", "well-known", "I'll".
                    if (word.isNotEmpty() && nextContinuesWord(i, width)) {
                        word.appendCodePoint(cp)
                    } else {
                        flushWord()
                        tokens.add(Tok.Punct(String(Character.toChars(cp))))
                    }
                }
                isSpace(cp) -> flushWord()
                isAsrArtifact(cp) -> Unit // dropped, and deliberately without flushing
                else -> word.appendCodePoint(cp)
            }
            i += width
        }
        flushWord()
        return tokens
    }

    /**
     * Renders tokens back to text with normalized spacing:
     * closing punctuation attaches left, quotes alternate open/close,
     * breaks are emitted verbatim, everything else is space-separated.
     */
    fun render(tokens: List<Tok>): String {
        val sb = StringBuilder()
        var quoteOpen = false
        var pendingNoSpace = false // set when next token should attach directly (open quote, @, ()

        fun needsSpace(): Boolean {
            if (sb.isEmpty()) return false
            if (pendingNoSpace) return false
            val last = sb.last()
            return last != '\n' && last != ' '
        }

        for (tok in tokens) {
            when (tok) {
                is Tok.Break -> {
                    while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    sb.append(tok.text)
                    pendingNoSpace = false
                }
                is Tok.Word -> {
                    if (needsSpace()) sb.append(' ')
                    sb.append(tok.text)
                    pendingNoSpace = false
                }
                is Tok.Punct -> {
                    when (tok.text) {
                        ".", ",", "!", "?", ";", ":", "%", ")", "..." -> {
                            // attach to previous token
                            while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                            sb.append(tok.text)
                            pendingNoSpace = false
                        }
                        "\"" -> {
                            if (quoteOpen) {
                                while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                                sb.append('"')
                                quoteOpen = false
                                pendingNoSpace = false
                            } else {
                                if (needsSpace()) sb.append(' ')
                                sb.append('"')
                                quoteOpen = true
                                pendingNoSpace = true
                            }
                        }
                        "@", "(" -> {
                            if (needsSpace()) sb.append(' ')
                            sb.append(tok.text)
                            pendingNoSpace = true
                        }
                        // "#" is spaced on both sides, not prefix-attached like "@".
                        // Dictated "hashtag" is a spoken symbol, and gluing it right
                        // produced "Use #now" from "use hashtag now" (VB-QA-18).
                        "-", "&", "#" -> {
                            if (needsSpace()) sb.append(' ')
                            sb.append(tok.text)
                            pendingNoSpace = false
                        }
                        else -> {
                            if (needsSpace()) sb.append(' ')
                            sb.append(tok.text)
                            pendingNoSpace = false
                        }
                    }
                }
            }
        }
        return sb.toString().trim { it == ' ' }
    }
}
