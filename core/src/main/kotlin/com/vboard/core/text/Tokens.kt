package com.vboard.core.text

/**
 * Minimal token model for transcript cleanup. ASR output is plain-ish text;
 * we tokenize into words, punctuation, and hard line breaks, transform the
 * token list, then render back to a string with normalized spacing.
 */
sealed interface Tok {
    /** A word: letters/digits with internal apostrophes or hyphens. */
    data class Word(val text: String) : Tok

    /** A single punctuation character, e.g. "." "," "?" "\"" */
    data class Punct(val text: String) : Tok

    /** A hard break: "\n" or "\n\n". */
    data class Break(val text: String) : Tok
}

object Tokenizer {

    private const val PUNCT_CHARS = ".,!?;:\"“”&@#%()-—"

    fun tokenize(input: String): MutableList<Tok> {
        val tokens = mutableListOf<Tok>()
        val normalized = input
            .replace('’', '\'')
            .replace('‘', '\'')
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

        while (i < n) {
            val c = normalized[i]
            when {
                c == '\n' -> {
                    flushWord()
                    var count = 0
                    while (i < n && (normalized[i] == '\n' || normalized[i] == ' ')) {
                        if (normalized[i] == '\n') count++
                        i++
                    }
                    tokens.add(Tok.Break(if (count >= 2) "\n\n" else "\n"))
                    continue
                }
                c.isLetterOrDigit() -> word.append(c)
                c == '\'' && word.isNotEmpty() && i + 1 < n && normalized[i + 1].isLetterOrDigit() ->
                    word.append(c)
                c == '-' && word.isNotEmpty() && i + 1 < n && normalized[i + 1].isLetterOrDigit() ->
                    word.append(c)
                c == '.' && i + 2 < n && normalized[i + 1] == '.' && normalized[i + 2] == '.' -> {
                    flushWord()
                    tokens.add(Tok.Punct("..."))
                    i += 2
                }
                c == '…' -> {
                    flushWord()
                    tokens.add(Tok.Punct("..."))
                }
                c in PUNCT_CHARS -> {
                    flushWord()
                    val ch = when (c) {
                        '“', '”' -> "\""
                        '—' -> "-"
                        else -> c.toString()
                    }
                    tokens.add(Tok.Punct(ch))
                }
                c.isWhitespace() -> flushWord()
                else -> flushWord() // drop unrecognized symbols from ASR output
            }
            i++
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
        var pendingNoSpace = false // set when next token should attach directly (open quote, @, #)

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
                        "@", "#", "(" -> {
                            if (needsSpace()) sb.append(' ')
                            sb.append(tok.text)
                            pendingNoSpace = true
                        }
                        "-", "&" -> {
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
