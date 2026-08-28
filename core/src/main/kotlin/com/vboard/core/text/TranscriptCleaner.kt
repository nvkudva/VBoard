package com.vboard.core.text

/**
 * The "Whisper superpower": turns raw ASR output into clean, intentional text.
 *
 * Pipeline (order matters — see stage comments):
 *  1. artifact scrub (ASR junk like `<unk>`)
 *  2. whole-utterance command detection ("scratch that", "stop listening")
 *  3. spoken punctuation & navigation ("comma", "new line", ...)
 *  4. hesitation filler removal ("um", "uh", ...)
 *  5. self-correction resolution ("to John no wait to Mary" -> "to Mary")
 *  6. discourse filler removal (aggressive mode: "you know", ...)
 *  7. stutter/repetition collapse ("the the" -> "the")
 *  8. punctuation & capitalization normalization
 *
 * Every stage is conservative: when uncertain, keep the user's words.
 * The pipeline is idempotent — cleaning already-clean text is a no-op.
 */
class TranscriptCleaner {

    fun clean(request: CleanupRequest): CleanupResult {
        val options = request.options
        var tokens = Tokenizer.tokenize(scrubArtifacts(request.transcript))
        if (tokens.isEmpty()) return CleanupResult("")

        detectUtteranceCommand(tokens)?.let { return CleanupResult("", command = it) }

        if (options.spokenCommands) {
            tokens = applySpokenCommands(tokens)
        }

        var fillersRemoved = 0
        var correctionsResolved = 0
        var repetitionsCollapsed = 0

        if (options.removeFillers && !options.rawMode) {
            val (result, removed) = removeHesitations(tokens)
            tokens = result
            fillersRemoved += removed
        }
        if (options.resolveSelfCorrections && !options.rawMode) {
            val (result, resolved) = resolveSelfCorrections(tokens)
            tokens = result
            correctionsResolved += resolved
        }
        if (options.removeFillers && options.aggressiveFillers && !options.rawMode) {
            val (result, removed) = removeDiscourseFillers(tokens)
            tokens = result
            fillersRemoved += removed
        }
        if (options.collapseRepetitions && !options.rawMode) {
            val (result, collapsed) = collapseRepetitions(tokens)
            tokens = result
            repetitionsCollapsed += collapsed
        }

        tokens = normalizePunctuationSequence(tokens)

        if (request.ensureTerminalPunctuation &&
            options.autoPunctuate && !options.rawMode &&
            request.fieldKind == FieldKind.TEXT
        ) {
            tokens = ensureTerminalPeriod(tokens)
        }

        if (options.autoCapitalize && !options.rawMode) {
            capitalize(tokens, sentenceStartsAt(request.precedingText, request.fieldKind))
        } else {
            capitalizeStandaloneI(tokens)
        }

        return CleanupResult(
            text = Tokenizer.render(tokens),
            fillersRemoved = fillersRemoved,
            correctionsResolved = correctionsResolved,
            repetitionsCollapsed = repetitionsCollapsed,
        )
    }

    // ---------------------------------------------------------------- stage 1

    private fun scrubArtifacts(text: String): String =
        text.replace(ARTIFACT_REGEX, " ")

    // ---------------------------------------------------------------- stage 2

    /** Command only when the utterance consists SOLELY of the phrase (fillers aside). */
    private fun detectUtteranceCommand(tokens: List<Tok>): UtteranceCommand? {
        val words = tokens.filterIsInstance<Tok.Word>()
            .map { it.text.lowercase() }
            .filter { it !in HESITATIONS }
        val phrase = words.joinToString(" ")
        return when (phrase) {
            "scratch that", "delete that", "undo that", "scratch it" -> UtteranceCommand.SCRATCH_THAT
            "stop listening", "stop dictation", "stop dictating" -> UtteranceCommand.STOP_LISTENING
            else -> null
        }
    }

    // ---------------------------------------------------------------- stage 3

    private fun applySpokenCommands(tokens: MutableList<Tok>): MutableList<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok !is Tok.Word) {
                out.add(tok); i++; continue
            }
            val match = matchSpokenPhrase(tokens, i)
            if (match != null) {
                val (replacement, length, singleWordAmbiguous) = match
                val prevWord = out.lastOrNull { it is Tok.Word } as? Tok.Word
                val guarded = singleWordAmbiguous &&
                    prevWord != null &&
                    prevWord.text.lowercase() in DETERMINERS &&
                    out.lastOrNull() === prevWord
                if (guarded) {
                    out.add(tok); i++
                } else {
                    out.add(replacement)
                    i += length
                }
            } else {
                out.add(tok); i++
            }
        }
        return out
    }

    private data class PhraseMatch(val replacement: Tok, val length: Int, val singleWordAmbiguous: Boolean)

    private fun matchSpokenPhrase(tokens: List<Tok>, start: Int): PhraseMatch? {
        fun wordAt(offset: Int): String? =
            (tokens.getOrNull(start + offset) as? Tok.Word)?.text?.lowercase()

        val w0 = wordAt(0) ?: return null
        val w1 = wordAt(1)
        val w2 = wordAt(2)

        // Multi-word phrases: unambiguous, always converted.
        when {
            w0 == "new" && w1 == "paragraph" -> return PhraseMatch(Tok.Break("\n\n"), 2, false)
            w0 == "new" && w1 == "line" -> return PhraseMatch(Tok.Break("\n"), 2, false)
            w0 == "next" && w1 == "line" -> return PhraseMatch(Tok.Break("\n"), 2, false)
            w0 == "full" && w1 == "stop" -> return PhraseMatch(Tok.Punct("."), 2, false)
            w0 == "question" && w1 == "mark" -> return PhraseMatch(Tok.Punct("?"), 2, false)
            w0 == "exclamation" && (w1 == "mark" || w1 == "point") ->
                return PhraseMatch(Tok.Punct("!"), 2, false)
            w0 == "open" && (w1 == "quote" || w1 == "quotes") -> return PhraseMatch(Tok.Punct("\""), 2, false)
            w0 == "close" && (w1 == "quote" || w1 == "quotes") -> return PhraseMatch(Tok.Punct("\""), 2, false)
            w0 == "open" && (w1 == "paren" || w1 == "parenthesis") -> return PhraseMatch(Tok.Punct("("), 2, false)
            w0 == "close" && (w1 == "paren" || w1 == "parenthesis") -> return PhraseMatch(Tok.Punct(")"), 2, false)
            w0 == "at" && w1 == "sign" -> return PhraseMatch(Tok.Punct("@"), 2, false)
            w0 == "percent" && w1 == "sign" -> return PhraseMatch(Tok.Punct("%"), 2, false)
            w0 == "dot" && w1 == "dot" && w2 == "dot" -> return PhraseMatch(Tok.Punct("..."), 3, false)
        }

        // Single-word punctuation: ambiguous with real words, so guarded by a
        // preceding determiner ("add a comma" keeps the word "comma").
        val single = when (w0) {
            "period" -> "."
            "comma" -> ","
            "colon" -> ":"
            "semicolon" -> ";"
            "hyphen", "dash" -> "-"
            "ellipsis" -> "..."
            "ampersand" -> "&"
            "hashtag" -> "#"
            else -> null
        } ?: return null
        return PhraseMatch(Tok.Punct(single), 1, true)
    }

    // ---------------------------------------------------------------- stage 4

    private fun removeHesitations(tokens: List<Tok>): Pair<MutableList<Tok>, Int> {
        val out = mutableListOf<Tok>()
        var removed = 0
        for (tok in tokens) {
            if (tok is Tok.Word && tok.text.lowercase() in HESITATIONS) {
                removed++ // any comma the ASR attached to the filler becomes an orphan, dropped below
            } else {
                out.add(tok)
            }
        }
        return dropOrphanCommas(out) to removed
    }

    private fun removeDiscourseFillers(tokens: List<Tok>): Pair<MutableList<Tok>, Int> {
        val out = mutableListOf<Tok>()
        var removed = 0
        var i = 0
        while (i < tokens.size) {
            val w0 = (tokens.getOrNull(i) as? Tok.Word)?.text?.lowercase()
            val w1 = (tokens.getOrNull(i + 1) as? Tok.Word)?.text?.lowercase()
            when {
                w0 == "you" && w1 == "know" -> { removed++; i += 2 }
                w0 == "i" && w1 == "mean" -> { removed++; i += 2 }
                w0 == "basically" || w0 == "literally" -> { removed++; i += 1 }
                else -> { out.add(tokens[i]); i++ }
            }
        }
        return dropOrphanCommas(out) to removed
    }

    /** Removes commas left dangling next to other punctuation or at the edges. */
    private fun dropOrphanCommas(tokens: MutableList<Tok>): MutableList<Tok> {
        val out = mutableListOf<Tok>()
        for (tok in tokens) {
            if (tok is Tok.Punct && tok.text == ",") {
                val prev = out.lastOrNull()
                if (prev == null || prev is Tok.Punct || prev is Tok.Break) continue
            }
            out.add(tok)
        }
        while (out.firstOrNull().let { it is Tok.Punct && it.text == "," }) out.removeAt(0)
        return out
    }

    // ---------------------------------------------------------------- stage 5

    /**
     * Resolves spoken self-corrections by aligning the replacement phrase with
     * the text before the marker:  "send it to john no wait to mary"
     *   marker  = "no wait", replacement head = "to"
     *   look back for latest "to" -> cut "to john" + marker, keep "to mary".
     * Strong markers fall back to marker-removal when alignment fails; weak
     * markers ("sorry", "rather") only act when alignment succeeds.
     */
    private fun resolveSelfCorrections(tokens: MutableList<Tok>): Pair<MutableList<Tok>, Int> {
        var work = tokens
        var resolved = 0
        var guard = 0
        while (guard++ < 4) { // resolve at most a few corrections per utterance
            val marker = findMarker(work) ?: break
            val (start, end, strong, isScratch) = marker
            val headIdx = nextWordIndex(work, end + 1)
            if (headIdx == null) {
                // Marker at end of utterance: "…to john, no wait" — drop marker only.
                if (!strong) break
                work = dropOrphanCommas(work.subList(0, start).toMutableList())
                resolved++
                continue
            }

            val head = (work[headIdx] as Tok.Word).text.lowercase()
            val alignIdx = findAlignment(work, start, head)
            // Resume from the replacement head, skipping commas the ASR wrapped
            // around the marker ("…john, no wait, to mary").
            work = when {
                alignIdx != null -> {
                    resolved++
                    (work.subList(0, alignIdx) + work.subList(headIdx, work.size)).toMutableList()
                }
                isScratch -> {
                    // "…tell him about it scratch that forget it": drop back to clause start
                    resolved++
                    val clauseStart = clauseStartBefore(work, start)
                    (work.subList(0, clauseStart) + work.subList(headIdx, work.size)).toMutableList()
                }
                strong -> {
                    resolved++
                    (work.subList(0, start) + work.subList(headIdx, work.size)).toMutableList()
                }
                else -> break // weak marker without alignment: leave untouched
            }
            work = dropOrphanCommas(work)
        }
        return work to resolved
    }

    private data class Marker(val start: Int, val end: Int, val strong: Boolean, val isScratch: Boolean)

    private fun findMarker(tokens: List<Tok>): Marker? {
        var i = 0
        while (i < tokens.size) {
            val w0 = (tokens.getOrNull(i) as? Tok.Word)?.text?.lowercase()
            if (w0 == null) { i++; continue }
            val w1 = (nextWordAfter(tokens, i))?.second
            val w1Idx = nextWordAfter(tokens, i)?.first
            when {
                (w0 == "no" && w1 == "wait") || (w0 == "wait" && w1 == "no") ->
                    return Marker(i, w1Idx!!, strong = true, isScratch = false)
                (w0 == "i" && (w1 == "mean" || w1 == "meant")) && i > 0 ->
                    return Marker(i, w1Idx!!, strong = false, isScratch = false)
                (w0 == "scratch" || w0 == "strike") && w1 == "that" && i > 0 ->
                    return Marker(i, w1Idx!!, strong = true, isScratch = true)
                w0 == "make" && w1 == "that" && i > 0 ->
                    return Marker(i, w1Idx!!, strong = false, isScratch = false)
                (w0 == "sorry" || w0 == "rather") && i > 0 ->
                    return Marker(i, i, strong = false, isScratch = false)
            }
            i++
        }
        return null
    }

    private fun nextWordAfter(tokens: List<Tok>, index: Int): Pair<Int, String>? {
        var j = index + 1
        while (j < tokens.size) {
            val t = tokens[j]
            if (t is Tok.Word) return j to t.text.lowercase()
            if (t is Tok.Break) return null
            if (t is Tok.Punct && t.text !in setOf(",")) return null
            j++
        }
        return null
    }

    private fun nextWordIndex(tokens: List<Tok>, from: Int): Int? {
        var j = from
        while (j < tokens.size) {
            val t = tokens[j]
            if (t is Tok.Word) return j
            if (t is Tok.Break) return null
            if (t is Tok.Punct && t.text != ",") return null
            j++
        }
        return null
    }

    /**
     * Finds the index in [0, markerStart) of the latest token matching [head]
     * (equal word, or both number-like for "make that" swaps), scanning back at
     * most [ALIGN_WINDOW] words and never across sentence punctuation.
     */
    private fun findAlignment(tokens: List<Tok>, markerStart: Int, head: String): Int? {
        var scanned = 0
        var j = markerStart - 1
        val headCategory = categoryOf(head)
        while (j >= 0 && scanned < ALIGN_WINDOW) {
            when (val t = tokens[j]) {
                is Tok.Break -> return null
                is Tok.Punct -> if (t.text in SENTENCE_ENDERS) return null
                is Tok.Word -> {
                    val w = t.text.lowercase()
                    if (w == head || (headCategory != null && categoryOf(w) == headCategory)) return j
                    scanned++
                }
            }
            j--
        }
        return null
    }

    /** Loose semantic categories so "at 5 make that 6" and "tuesday scratch that wednesday" align. */
    private fun categoryOf(word: String): String? = when {
        word.isNumberLike() -> "number"
        word in WEEKDAYS -> "weekday"
        word in MONTHS -> "month"
        else -> null
    }

    private fun clauseStartBefore(tokens: List<Tok>, index: Int): Int {
        var j = index - 1
        while (j >= 0) {
            val t = tokens[j]
            if (t is Tok.Break) return j + 1
            if (t is Tok.Punct && (t.text in SENTENCE_ENDERS || t.text == ",")) return j + 1
            j--
        }
        return 0
    }

    // ---------------------------------------------------------------- stage 7

    private fun collapseRepetitions(tokens: List<Tok>): Pair<MutableList<Tok>, Int> {
        // First collapse repeated bigrams ("i want i want to"), then repeated words.
        var collapsed = 0
        val afterBigrams = mutableListOf<Tok>()
        var i = 0
        while (i < tokens.size) {
            val a = tokens.getOrNull(i) as? Tok.Word
            val b = tokens.getOrNull(i + 1) as? Tok.Word
            val c = tokens.getOrNull(i + 2) as? Tok.Word
            val d = tokens.getOrNull(i + 3) as? Tok.Word
            if (a != null && b != null && c != null && d != null &&
                a.text.equals(c.text, ignoreCase = true) &&
                b.text.equals(d.text, ignoreCase = true)
            ) {
                afterBigrams.add(a); afterBigrams.add(b)
                collapsed++
                i += 4
            } else {
                afterBigrams.add(tokens[i]); i++
            }
        }
        val out = mutableListOf<Tok>()
        for (tok in afterBigrams) {
            val prev = out.lastOrNull()
            if (tok is Tok.Word && prev is Tok.Word &&
                tok.text.equals(prev.text, ignoreCase = true) &&
                tok.text.lowercase() !in INTENTIONAL_REPEATS
            ) {
                collapsed++
                continue
            }
            out.add(tok)
        }
        return out to collapsed
    }

    // ---------------------------------------------------------------- stage 8

    /** Collapses doubled punctuation and drops leading punctuation after edits. */
    private fun normalizePunctuationSequence(tokens: MutableList<Tok>): MutableList<Tok> {
        val out = mutableListOf<Tok>()
        for (tok in tokens) {
            val prev = out.lastOrNull()
            if (tok is Tok.Punct && prev is Tok.Punct && prev.text == tok.text && tok.text != "\"") continue
            if (tok is Tok.Punct && tok.text == "," && prev is Tok.Punct && prev.text in SENTENCE_ENDERS) continue
            if (tok is Tok.Punct && tok.text in SENTENCE_ENDERS && prev is Tok.Punct && prev.text == ",") {
                out.removeAt(out.size - 1)
            }
            out.add(tok)
        }
        while (out.firstOrNull().let { it is Tok.Punct && (it.text == "," || it.text in SENTENCE_ENDERS) }) {
            out.removeAt(0)
        }
        return out
    }

    private fun ensureTerminalPeriod(tokens: MutableList<Tok>): MutableList<Tok> {
        val lastMeaningful = tokens.lastOrNull { it !is Tok.Break } ?: return tokens
        val wordCount = tokens.count { it is Tok.Word }
        if (lastMeaningful is Tok.Word && wordCount >= MIN_WORDS_FOR_TERMINAL_PERIOD &&
            !lastMeaningful.text.endsWith("...")
        ) {
            val insertAt = tokens.indexOfLast { it !is Tok.Break } + 1
            tokens.add(insertAt, Tok.Punct("."))
        }
        return tokens
    }

    private fun sentenceStartsAt(precedingText: String, fieldKind: FieldKind): Boolean {
        if (!fieldKind.allowsAutoCapitalize) return false
        val trimmed = precedingText.trimEnd()
        if (trimmed.isEmpty()) return true
        val last = trimmed.last()
        return last == '.' || last == '!' || last == '?' || last == '\n'
    }

    private fun capitalize(tokens: MutableList<Tok>, capitalizeFirst: Boolean) {
        var sentenceStart = capitalizeFirst
        for (i in tokens.indices) {
            when (val tok = tokens[i]) {
                is Tok.Word -> {
                    var text = tok.text
                    val lower = text.lowercase()
                    if (lower == "i" || lower in I_CONTRACTIONS) {
                        text = "I" + text.substring(1)
                    } else if (sentenceStart && text.isNotEmpty() && text[0].isLetter()) {
                        text = text[0].uppercaseChar() + text.substring(1)
                    }
                    if (text != tok.text) tokens[i] = Tok.Word(text)
                    sentenceStart = false
                }
                is Tok.Punct -> if (tok.text in SENTENCE_ENDERS) sentenceStart = true
                is Tok.Break -> sentenceStart = true
            }
        }
    }

    private fun capitalizeStandaloneI(tokens: MutableList<Tok>) {
        for (i in tokens.indices) {
            val tok = tokens[i] as? Tok.Word ?: continue
            val lower = tok.text.lowercase()
            if (tok.text == "i" || (tok.text.first() == 'i' && lower in I_CONTRACTIONS)) {
                tokens[i] = Tok.Word("I" + tok.text.substring(1))
            }
        }
    }

    private fun String.isNumberLike(): Boolean =
        isNotEmpty() && (all { it.isDigit() } || lowercase() in NUMBER_WORDS)

    companion object {
        private val ARTIFACT_REGEX = Regex("""<[a-z_]+>|\[[a-z_ ]+]|\((?:noise|music|laughter)\)""", RegexOption.IGNORE_CASE)

        private val HESITATIONS = setOf(
            "um", "uh", "uhm", "umm", "uhh", "erm", "er", "mmm", "mm", "hmm", "mhm",
        )

        private val DETERMINERS = setOf(
            "a", "an", "the", "this", "that", "my", "your", "his", "her", "its",
            "our", "their", "each", "every", "one", "another", "no",
        )

        private val INTENTIONAL_REPEATS = setOf(
            "very", "really", "no", "so", "ha", "bye", "yeah", "okay", "please",
        )

        private val SENTENCE_ENDERS = setOf(".", "!", "?")

        private val I_CONTRACTIONS = setOf("i'm", "i'll", "i've", "i'd")

        private val NUMBER_WORDS = setOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
            "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred",
            "thousand", "million", "billion", "noon", "midnight",
        )

        private val WEEKDAYS = setOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "tomorrow", "today", "tonight", "yesterday",
        )

        private val MONTHS = setOf(
            "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december",
        )

        private const val ALIGN_WINDOW = 8
        private const val MIN_WORDS_FOR_TERMINAL_PERIOD = 3
    }
}
