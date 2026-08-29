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
        // Normalize to NFC so cleanup does not depend on which normalization form
        // the recognizer happened to emit (VB-QA-15). Raw mode is exempt: it is the
        // verbatim escape hatch, and NFC is still a transformation.
        val source = if (options.rawMode) {
            request.transcript
        } else {
            java.text.Normalizer.normalize(request.transcript, java.text.Normalizer.Form.NFC)
        }
        var tokens = Tokenizer.tokenize(scrubArtifacts(source))
        if (tokens.isEmpty()) return CleanupResult("")

        detectUtteranceCommand(tokens)?.let { return CleanupResult("", command = it) }

        var spokenSubstitutions = 0
        if (options.spokenCommands) {
            val (result, substituted) = applySpokenCommands(tokens)
            tokens = result
            spokenSubstitutions = substituted
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

        // The field kind gates the whole pass, not just its first word: capitalize()
        // re-arms at every sentence ender and every break, so gating only the start
        // still recased everything after a "." or a "\n" in an EMAIL, URI, PASSWORD
        // or NUMBER field (VB-QA-29).
        if (options.autoCapitalize && !options.rawMode && request.fieldKind.allowsAutoCapitalize) {
            capitalize(tokens, sentenceStartsAt(request.precedingText, request.fieldKind))
        } else {
            capitalizeStandaloneI(tokens)
        }

        return CleanupResult(
            text = Tokenizer.render(tokens),
            fillersRemoved = fillersRemoved,
            correctionsResolved = correctionsResolved,
            repetitionsCollapsed = repetitionsCollapsed,
            spokenSubstitutions = spokenSubstitutions,
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

    private fun applySpokenCommands(tokens: MutableList<Tok>): Pair<MutableList<Tok>, Int> {
        val out = mutableListOf<Tok>()
        var substitutions = 0
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok !is Tok.Word) {
                out.add(tok); i++; continue
            }
            val match = matchSpokenPhrase(tokens, i)
            if (match != null && conversionIsIntended(tokens, out, i, match)) {
                out.add(match.replacement)
                substitutions++
                i += match.length
            } else {
                out.add(tok); i++
            }
        }
        return out to substitutions
    }

    /**
     * Stage 3 matches on surface form alone, and every punctuation word is also an
     * ordinary English word — "menstrual period tracking", "full stop the car",
     * "on the next line item". Converting one of those deletes a word the user
     * said, so three cheap signals have to agree first (VB-QA-18).
     */
    private fun conversionIsIntended(
        tokens: List<Tok>,
        out: List<Tok>,
        start: Int,
        match: PhraseMatch,
    ): Boolean {
        // (1) At index 0 a converted mark is the one that normalizePunctuationSequence
        // drops on the way out, so the word that became it vanishes without even
        // leaving a symbol behind. That leading-drop loop removes Tok.Punct only and
        // never a Tok.Break, so the argument does not reach break commands: an
        // utterance that opens with "new paragraph ..." keeps every token either way,
        // and typing the words is simply the wrong answer for a normal way to dictate.
        // Knowingly accepted collateral: "new line of thinking" now breaks the line.
        // It is token-for-token the same shape as "new paragraph here is my text", so
        // no rule can convert one and refuse the other (VB-QA-18).
        if (start == 0 && match.replacement is Tok.Punct) return false

        // (2) A determiner immediately before it makes it a noun phrase: "add a
        // period at the end", "on the next line item". Multi-word phrases need this
        // as much as single words do.
        val prev = out.lastOrNull()
        if (prev is Tok.Word && prev.text.lowercase() in DETERMINERS) return false

        // (3) A sentence-splitting mark ends the clause before it, so it needs a
        // plausible clause after it: the end of the utterance, a break or another
        // spoken mark, or at least two more words. One trailing word is far more
        // likely to be a noun ("period tracking") than a sentence. Inline marks and
        // breaks read as a pause rather than a split, so they stop at signals 1-2.
        val replacement = match.replacement
        if (replacement !is Tok.Punct || replacement.text !in SENTENCE_ENDERS) return true
        val after = start + match.length
        val rest = tokens.subList(after, tokens.size)
        if (rest.none { it is Tok.Word }) return true
        if (rest.first() !is Tok.Word || matchSpokenPhrase(tokens, after) != null) return true
        return rest.count { it is Tok.Word } >= 2
    }

    private data class PhraseMatch(val replacement: Tok, val length: Int)

    private fun matchSpokenPhrase(tokens: List<Tok>, start: Int): PhraseMatch? {
        fun wordAt(offset: Int): String? =
            (tokens.getOrNull(start + offset) as? Tok.Word)?.text?.lowercase()

        val w0 = wordAt(0) ?: return null
        val w1 = wordAt(1)
        val w2 = wordAt(2)

        // Multi-word phrases. Less ambiguous than the single words below, but not
        // unambiguous — conversionIsIntended still has the final say.
        when {
            w0 == "new" && w1 == "paragraph" -> return PhraseMatch(Tok.Break("\n\n"), 2)
            w0 == "new" && w1 == "line" -> return PhraseMatch(Tok.Break("\n"), 2)
            w0 == "next" && w1 == "line" -> return PhraseMatch(Tok.Break("\n"), 2)
            w0 == "full" && w1 == "stop" -> return PhraseMatch(Tok.Punct("."), 2)
            w0 == "question" && w1 == "mark" -> return PhraseMatch(Tok.Punct("?"), 2)
            w0 == "exclamation" && (w1 == "mark" || w1 == "point") ->
                return PhraseMatch(Tok.Punct("!"), 2)
            w0 == "open" && (w1 == "quote" || w1 == "quotes") -> return PhraseMatch(Tok.Punct("\""), 2)
            w0 == "close" && (w1 == "quote" || w1 == "quotes") -> return PhraseMatch(Tok.Punct("\""), 2)
            w0 == "open" && (w1 == "paren" || w1 == "parenthesis") -> return PhraseMatch(Tok.Punct("("), 2)
            w0 == "close" && (w1 == "paren" || w1 == "parenthesis") -> return PhraseMatch(Tok.Punct(")"), 2)
            w0 == "at" && w1 == "sign" -> return PhraseMatch(Tok.Punct("@"), 2)
            w0 == "percent" && w1 == "sign" -> return PhraseMatch(Tok.Punct("%"), 2)
            w0 == "dot" && w1 == "dot" && w2 == "dot" -> return PhraseMatch(Tok.Punct(ELLIPSIS), 3)
        }

        // Single-word punctuation. "hashtag" is a common noun as often as it is a
        // symbol ("a hashtag for it"), but dropping it from the table removed the
        // only way to dictate "#" at all; it carries the same conversionIsIntended
        // corroboration as every other single word here instead (VB-QA-18).
        val single = when (w0) {
            "period" -> "."
            "hashtag" -> "#"
            "comma" -> ","
            "colon" -> ":"
            "semicolon" -> ";"
            "hyphen", "dash" -> "-"
            "ellipsis" -> ELLIPSIS
            "ampersand" -> "&"
            else -> null
        } ?: return null
        return PhraseMatch(Tok.Punct(single), 1)
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
            val (start, end, strong) = marker
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

    private data class Marker(val start: Int, val end: Int, val strong: Boolean)

    private fun findMarker(tokens: List<Tok>): Marker? {
        var i = 0
        while (i < tokens.size) {
            val w0 = (tokens.getOrNull(i) as? Tok.Word)?.text?.lowercase()
            if (w0 == null) { i++; continue }
            val w1 = (nextWordAfter(tokens, i))?.second
            val w1Idx = nextWordAfter(tokens, i)?.first
            when {
                // Every marker requires i > 0: a correction has to have something
                // before it to correct. "no wait" was the one exception, which made
                // an utterance that merely opens with it lose its first two words
                // ("no wait for me" -> "For me", VB-QA-20).
                ((w0 == "no" && w1 == "wait") || (w0 == "wait" && w1 == "no")) && i > 0 ->
                    return Marker(i, w1Idx!!, strong = true)
                (w0 == "actually" && w1 == "no") && i > 0 ->
                    return Marker(i, w1Idx!!, strong = true)
                (w0 == "i" && (w1 == "mean" || w1 == "meant")) && i > 0 ->
                    return Marker(i, w1Idx!!, strong = false)
                // "scratch that" is ordinary English far more often than it is a
                // command ("i need to scratch that itch"), so mid-utterance it only
                // acts when the replacement aligns with something already said
                // (VB-QA-19). The whole-utterance form stays a command, in stage 2.
                (w0 == "scratch" || w0 == "strike") && w1 == "that" && i > 0 ->
                    return Marker(i, w1Idx!!, strong = false)
                w0 == "make" && w1 == "that" && i > 0 ->
                    return Marker(i, w1Idx!!, strong = false)
                (w0 == "sorry" || w0 == "rather") && i > 0 ->
                    return Marker(i, i, strong = false)
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

    // ---------------------------------------------------------------- stage 7

    private fun collapseRepetitions(tokens: List<Tok>): Pair<MutableList<Tok>, Int> {
        // First collapse repeated bigrams ("i want i want to"), then repeated words.
        // Number-like words are NEVER collapsed: "five five five one two one two"
        // is a phone number, not a stutter (VB-203: when uncertain, keep both).
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
                b.text.equals(d.text, ignoreCase = true) &&
                !a.text.isNumberLike() && !b.text.isNumberLike()
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
                tok.text.lowercase() !in INTENTIONAL_REPEATS &&
                !tok.text.isNumberLike()
            ) {
                collapsed++
                continue
            }
            out.add(tok)
        }
        return out to collapsed
    }

    // ---------------------------------------------------------------- stage 8

    /** Collapses doubled punctuation and breaks, and drops leading punctuation after edits. */
    private fun normalizePunctuationSequence(tokens: MutableList<Tok>): MutableList<Tok> {
        val out = mutableListOf<Tok>()
        for (tok in tokens) {
            val prev = out.lastOrNull()
            // Adjacent breaks are a paragraph, not N blank lines. Two spoken breaks
            // used to render as "\n\n\n", which re-tokenizes to "\n\n" — the pipeline
            // producing text it cannot reproduce (VB-QA-30, and one cause of VB-QA-05).
            // Same "count >= 2 means paragraph" rule the tokenizer applies to literals.
            if (tok is Tok.Break && prev is Tok.Break) {
                out[out.size - 1] = Tok.Break("\n\n")
                continue
            }
            if (tok is Tok.Punct && prev is Tok.Punct && prev.text == tok.text && tok.text != "\"") continue
            // "..." already closes the clause, so a comma or period stacked onto it is
            // redundant, in either order (VB-QA-31). It is deliberately not a member of
            // SENTENCE_ENDERS: that set also drives capitalization and alignment, where
            // an ellipsis is mid-sentence ("one more thing... the demo needs music").
            if (tok is Tok.Punct && prev is Tok.Punct && prev.text == ELLIPSIS && tok.text in ELLIPSIS_ABSORBS) continue
            if (tok is Tok.Punct && tok.text == "," && prev is Tok.Punct && prev.text in SENTENCE_ENDERS) continue
            if (tok is Tok.Punct && (tok.text in SENTENCE_ENDERS || tok.text == ELLIPSIS) &&
                prev is Tok.Punct && prev.text == ","
            ) {
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
            val firstWord = (tokens.firstOrNull { it is Tok.Word } as? Tok.Word)
                ?.text?.lowercase()
            val mark = if (firstWord in INTERROGATIVE_STARTERS) "?" else "."
            val insertAt = tokens.indexOfLast { it !is Tok.Break } + 1
            tokens.add(insertAt, Tok.Punct(mark))
        }
        return tokens
    }

    private fun sentenceStartsAt(precedingText: String, fieldKind: FieldKind): Boolean {
        if (!fieldKind.allowsAutoCapitalize) return false
        // Trim only horizontal space: a plain trimEnd() also strips the newline this
        // function is looking for, which made the '\n' branch below unreachable and left
        // the sentence after a spoken "new line" uncapitalized.
        val trimmed = precedingText.trimEnd(' ', '\t')
        if (trimmed.isEmpty()) return true
        // A terminator may be followed by closing punctuation — the "end of a quoted
        // sentence" shape is extremely common — so skip those before looking (VB-QA-27).
        var end = trimmed.length
        while (end > 0) {
            val cp = trimmed.codePointBefore(end)
            if (cp !in SENTENCE_CLOSERS) break
            end -= Character.charCount(cp)
        }
        if (end == 0) return true
        val last = trimmed.codePointBefore(end)
        return last in SENTENCE_TERMINATORS || last == '\n'.code
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
        /**
         * ASR junk tags. Angle brackets are unambiguous — no user dictates "<unk>" —
         * so any tag shape is scrubbed there. Square brackets and parentheses are
         * ordinary prose the user does dictate, so those two forms match a closed
         * vocabulary of recognizer labels only; matching "any lowercase word in
         * brackets" deleted bracketed asides outright (VB-QA-21).
         */
        private val ARTIFACT_LABELS =
            "unk|music|noise|laughter|laugh|applause|silence|inaudible|unintelligible|" +
                "blank_audio|no_speech|non_speech|sound|speaking|background"

        private val ARTIFACT_REGEX = Regex(
            """<[a-z_]+>|\[(?:$ARTIFACT_LABELS)]|\((?:$ARTIFACT_LABELS)\)""",
            RegexOption.IGNORE_CASE,
        )

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

        private const val ELLIPSIS = "..."

        /** Punctuation an ellipsis swallows when the two end up adjacent (VB-QA-31). */
        private val ELLIPSIS_ABSORBS = setOf(".", ",")

        /**
         * Sentence terminators across the writing systems VBoard claims to support.
         * A straight apostrophe is deliberately absent from [SENTENCE_CLOSERS]: it is
         * ambiguous with a word-final apostrophe and is not reliably a closing quote.
         */
        private val SENTENCE_TERMINATORS: Set<Int> =
            ".!?\u2026\u3002\uFF01\uFF1F\u061F\u0964\u0965\u203D\uFF0E".map { it.code }.toSet()

        private val SENTENCE_CLOSERS: Set<Int> =
            "\")]}\u00BB\u2019\u201D\u300D\u300F\uFF09".map { it.code }.toSet()

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

        private val INTERROGATIVE_STARTERS = setOf(
            "who", "what", "when", "where", "why", "how", "which", "whose", "whom",
            "is", "are", "was", "were", "am", "do", "does", "did", "can", "could",
            "will", "would", "should", "shall", "have", "has", "may", "might",
        )

        private const val ALIGN_WINDOW = 8
        private const val MIN_WORDS_FOR_TERMINAL_PERIOD = 3
    }
}
