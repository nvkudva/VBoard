package com.vboard.core.suggest

import com.vboard.core.text.FieldKind
import kotlin.math.ln

enum class AutocorrectMode { OFF, CONSERVATIVE, AGGRESSIVE }

data class SuggestionRequest(
    val composing: String,
    val previousWord: String? = null,
    val fieldKind: FieldKind = FieldKind.TEXT,
    val mode: AutocorrectMode = AutocorrectMode.CONSERVATIVE,
)

data class Suggestion(val text: String, val score: Double, val source: Source) {
    enum class Source { COMPLETION, CORRECTION, PREDICTION, LITERAL }
}

data class SuggestionResult(
    /** Up to 3, best first; empty when the field disallows suggestions. */
    val suggestions: List<Suggestion>,
    /** Non-null => the IME should replace the composing text with this on space/punctuation. */
    val autocorrect: Suggestion?,
) {
    companion object {
        val EMPTY = SuggestionResult(emptyList(), null)
    }
}

/**
 * Suggestion + autocorrect engine.
 *
 * With empty composing text it predicts the next word from the user's learned bigrams,
 * a small built-in bigram table, and top lexicon unigrams. With composing text it ranks
 * the literal input, prefix completions, and fuzzy corrections (bounded weighted
 * Damerau-Levenshtein over the lexicon trie, QWERTY-adjacency-aware), and separately
 * decides whether the top candidate is strong enough to autocorrect.
 */
class SuggestionEngine(
    private val lexicon: Lexicon,
    private val userHistory: UserHistory = UserHistory(),
) {

    fun suggest(request: SuggestionRequest): SuggestionResult {
        val kind = request.fieldKind
        if (kind == FieldKind.PASSWORD || kind == FieldKind.NUMBER) return SuggestionResult.EMPTY

        val composing = request.composing.trim()

        if (kind == FieldKind.EMAIL || kind == FieldKind.URI) {
            // Literal echo only: never rewrite addresses/URLs, never predict into them.
            if (composing.isEmpty()) return SuggestionResult.EMPTY
            return SuggestionResult(
                suggestions = listOf(Suggestion(composing, LITERAL_PRIOR, Suggestion.Source.LITERAL)),
                autocorrect = null,
            )
        }

        return if (composing.isEmpty()) {
            predictNextWord(request.previousWord)
        } else {
            suggestForComposing(composing, request)
        }
    }

    /**
     * Learns a committed word (called by the IME on space/punct/enter). The caller is
     * responsible for gating on [FieldKind.allowsLearning].
     */
    fun recordCommittedWord(previousWord: String?, word: String) {
        val w = learnableForm(word) ?: return
        userHistory.recordUnigram(w)
        val p = previousWord?.let { learnableForm(it) }
        if (p != null) userHistory.recordBigram(p, w)
    }

    // ------------------------------------------------------------------ predictions

    private fun predictNextWord(previousWord: String?): SuggestionResult {
        val prev = previousWord?.trim()?.trimEnd('.', ',', '!', '?', ';', ':')?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        // word -> score; higher wins. User bigrams dominate, then the built-in table,
        // then top lexicon unigrams as filler.
        val scores = HashMap<String, Double>()

        if (prev != null) {
            for ((word, count) in userHistory.continuationsOf(prev)) {
                val s = USER_BIGRAM_BASE + 3.0 * ln(1.0 + count)
                scores.merge(word, s, ::maxOf)
            }
            val builtin = BUILTIN_BIGRAMS[prev]
            if (builtin != null) {
                for ((index, word) in builtin.withIndex()) {
                    val s = BUILTIN_BIGRAM_BASE + (builtin.size - index).toDouble()
                    scores.merge(word, s, ::maxOf)
                }
            }
        }
        for (scored in lexicon.wordsWithPrefix("", PREDICTION_FILLER_COUNT)) {
            scores.merge(scored.word, ln(1.0 + scored.score) / 4.0, ::maxOf)
        }
        if (prev != null) scores.remove(prev)

        val top = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(MAX_SUGGESTIONS)
            .map { Suggestion(presentPronouns(it.key), it.value, Suggestion.Source.PREDICTION) }
        return SuggestionResult(top, autocorrect = null)
    }

    // ------------------------------------------------------------------ composing text

    private class Candidate(
        val word: String,
        var score: Double,
        var source: Suggestion.Source,
        /** Discrete edit-operation count between the typed text and this word. */
        var ops: Int,
    )

    private fun suggestForComposing(composing: String, request: SuggestionRequest): SuggestionResult {
        val lower = composing.lowercase()
        val prev = request.previousWord?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val inLexicon = lexicon.contains(lower)

        val literalScore =
            (if (inLexicon) ln(1.0 + lexicon.frequencyOf(lower)) else LITERAL_PRIOR) + historyBoost(lower, prev)

        val candidates = HashMap<String, Candidate>(24)

        // Prefix completions.
        for (scored in lexicon.wordsWithPrefix(lower, COMPLETION_POOL)) {
            val word = scored.word
            if (word == lower) continue
            val extra = word.length - lower.length
            val score = ln(1.0 + scored.score) - COMPLETION_PENALTY * extra + historyBoost(word, prev)
            candidates[word] = Candidate(word, score, Suggestion.Source.COMPLETION, extra)
        }

        // Fuzzy corrections. Skipped for text the trie cannot spell: every match
        // it could return is a mis-measurement (VB-QA-32). This is a fact about
        // this one candidate source, so the skip lives at its call site.
        if (!isOutsideTrieAlphabet(composing)) {
            val maxCost = if (lower.length <= SHORT_WORD_LENGTH) 1.0 else 2.0
            lexicon.fuzzyMatch(lower, maxCost) { word, frequency, cost, ops ->
                val score = ln(1.0 + frequency) - EDIT_PENALTY * cost + historyBoost(word, prev)
                val existing = candidates[word]
                if (existing == null) {
                    candidates[word] = Candidate(word, score, Suggestion.Source.CORRECTION, ops)
                } else if (score > existing.score) {
                    existing.score = score
                    existing.source = Suggestion.Source.CORRECTION
                    existing.ops = ops
                }
            }
        }

        val ranked = candidates.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })

        val suggestions = ArrayList<Suggestion>(MAX_SUGGESTIONS)
        val seen = HashSet<String>(8)
        // The literal always competes; it is what the user actually typed.
        seen.add(composing)
        val literal = Suggestion(composing, literalScore, Suggestion.Source.LITERAL)
        var literalPlaced = false
        for (candidate in ranked) {
            if (!literalPlaced && literalScore >= candidate.score) {
                suggestions.add(literal)
                literalPlaced = true
                if (suggestions.size == MAX_SUGGESTIONS) break
            }
            val text = presentPronouns(matchCase(composing, candidate.word))
            if (!seen.add(text)) continue
            suggestions.add(Suggestion(text, candidate.score, candidate.source))
            if (suggestions.size == MAX_SUGGESTIONS) break
        }
        if (!literalPlaced && suggestions.size < MAX_SUGGESTIONS) suggestions.add(literal)

        val autocorrect = decideAutocorrect(
            composing, lower, inLexicon, literalScore, ranked, request.mode, request.fieldKind,
        )

        // Surface the autocorrect choice in the strip if ranking happened to exclude it.
        if (autocorrect != null && suggestions.none { it.text == autocorrect.text }) {
            suggestions.add(0, autocorrect)
            while (suggestions.size > MAX_SUGGESTIONS) suggestions.removeAt(suggestions.size - 1)
        }

        // VB-306: what the user actually typed must always stay reachable in the
        // strip (left slot = display index 1), even when three higher-scored
        // candidates would otherwise fill it (VB-QA-09).
        if (suggestions.none { it.text.equals(composing, ignoreCase = true) }) {
            val at = minOf(1, suggestions.size)
            suggestions.add(at, literal)
            while (suggestions.size > MAX_SUGGESTIONS) suggestions.removeAt(suggestions.size - 1)
        }

        return SuggestionResult(suggestions, autocorrect)
    }

    private fun historyBoost(word: String, prev: String?): Double {
        var boost = 0.0
        val uni = userHistory.unigramCount(word)
        if (uni > 0) boost += UNIGRAM_BOOST * ln(1.0 + uni)
        if (prev != null) {
            val bi = userHistory.bigramCount(prev, word)
            if (bi > 0) boost += BIGRAM_BOOST * ln(1.0 + bi)
        }
        return boost
    }

    // ------------------------------------------------------------------ autocorrect

    private fun decideAutocorrect(
        composing: String,
        lower: String,
        inLexicon: Boolean,
        literalScore: Double,
        ranked: List<Candidate>,
        mode: AutocorrectMode,
        kind: FieldKind,
    ): Suggestion? {
        if (mode == AutocorrectMode.OFF) return null
        if (!kind.allowsAutocorrect) return null
        if (!isCorrectableToken(composing)) return null
        if (isAllCaps(composing)) return null
        // Internal capitals ("iPhone", "VBoard", "McDonald") signal deliberate
        // input and gate autocorrect exactly like ALL-CAPS does (VB-QA-06).
        if (composing.length > 1 && composing.drop(1).any { it.isUpperCase() }) return null

        // Lone lowercase "i" becomes "I" in free-form text.
        if (composing == "i" && kind == FieldKind.TEXT) {
            return Suggestion("I", ln(1.0 + lexicon.frequencyOf("i")), Suggestion.Source.CORRECTION)
        }

        // Contractions win before any general correction (and before the in-lexicon
        // gate: "dont", "im", "id" all appear in corpora as raw tokens).
        val contraction = CONTRACTIONS[lower]
        if (contraction != null) {
            val cased = if (composing[0].isUpperCase() && contraction[0].isLowerCase()) {
                contraction.replaceFirstChar { it.uppercaseChar() }
            } else {
                contraction
            }
            return Suggestion(cased, ln(1.0 + lexicon.frequencyOf(contraction)), Suggestion.Source.CORRECTION)
        }

        if (composing.length < 2) return null
        if (inLexicon) return null

        val (maxOps, margin) = when (mode) {
            AutocorrectMode.CONSERVATIVE -> 1 to CONSERVATIVE_MARGIN
            AutocorrectMode.AGGRESSIVE -> 2 to AGGRESSIVE_MARGIN
            AutocorrectMode.OFF -> return null
        }
        val best = ranked.firstOrNull { it.ops <= maxOps } ?: return null
        if (best.score - literalScore < margin) return null
        return Suggestion(presentPronouns(matchCase(composing, best.word)), best.score, best.source)
    }

    /**
     * True when [composing] contains a letter the bundled trie cannot spell.
     *
     * The trie holds a-z only, and the weighted edit distance charges "è" -> "i"
     * as one ordinary substitution — so an out-of-lexicon accented word lands one
     * edit from a frequent ASCII one and the margin falls over ("crème" -> "crime",
     * "élan" -> "plan"). No word the *fuzzy walk* can return for such a token is a
     * real spelling of it (VB-QA-32).
     *
     * That is a limit of the trie, not of the engine, so nothing else consults
     * this: autocorrect declines an accented token because the trie handed it no
     * candidate, not because a blanket rule silenced every source. User history —
     * which does learn accented words and does surface them as predictions — must
     * stay reachable. Deliberate ASCII casing ("iPhone", "ASAP") is untouched by
     * this test either way.
     */
    private fun isOutsideTrieAlphabet(composing: String): Boolean {
        var i = 0
        while (i < composing.length) {
            val cp = composing.codePointAt(i)
            if (Character.isLetter(cp) && Character.toLowerCase(cp) !in ASCII_LOWER) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** Letters with optional internal apostrophes; digits/symbols make a token untouchable. */
    private fun isCorrectableToken(composing: String): Boolean {
        if (composing.isEmpty()) return false
        if (!composing.first().isLetter() || !composing.last().isLetter()) return false
        return composing.all { it.isLetter() || it == '\'' }
    }

    private fun isAllCaps(composing: String): Boolean =
        composing.length > 1 && composing.none { it.isLowerCase() } && composing.any { it.isUpperCase() }

    // ------------------------------------------------------------------ casing

    /** Applies the user's typed casing pattern to a lowercase lexicon word. */
    private fun matchCase(pattern: String, word: String): String = when {
        isAllCaps(pattern) -> word.uppercase()
        pattern.first().isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    /** "i", "i'm", "i'll", ... always present with a capital I. */
    private fun presentPronouns(word: String): String =
        if (word == "i" || word.startsWith("i'")) "I" + word.substring(1) else word

    private fun learnableForm(word: String): String? {
        val w = word.trim().trim('.', ',', '!', '?', ';', ':', '"').lowercase()
        if (w.isEmpty() || w.length > MAX_LEARNED_LENGTH) return null
        if (!isCorrectableToken(w)) return null
        return w
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3
        private const val COMPLETION_POOL = 12
        private const val PREDICTION_FILLER_COUNT = 8
        private const val SHORT_WORD_LENGTH = 4
        private const val MAX_LEARNED_LENGTH = 48

        /** Weight of each unit of weighted edit distance, in log-frequency units. */
        private const val EDIT_PENALTY = 5.0

        /** Penalty per character the user has not typed yet, for completions. */
        private const val COMPLETION_PENALTY = 0.9

        /** Base score of out-of-lexicon literal text (the benefit of the doubt). */
        private const val LITERAL_PRIOR = 2.0

        /** The lexicon trie's whole alphabet, in code points. */
        private val ASCII_LOWER = 'a'.code..'z'.code

        private const val CONSERVATIVE_MARGIN = 1.0
        private const val AGGRESSIVE_MARGIN = 0.25

        private const val UNIGRAM_BOOST = 0.8
        private const val BIGRAM_BOOST = 1.6

        private const val USER_BIGRAM_BASE = 100.0
        private const val BUILTIN_BIGRAM_BASE = 40.0

        /**
         * Common apostrophe-dropped contractions, consulted before general correction.
         * Deliberately absent because they are ordinary words that must never be
         * rewritten: "its", "ill", "lets", "well", "were", "wed", "shed", "hell", "shell".
         * ("cant" is technically a rare word too, but the contraction is overwhelmingly
         * more likely and the spec requires correcting it.)
         */
        private val CONTRACTIONS: Map<String, String> = mapOf(
            "dont" to "don't",
            "im" to "I'm",
            "cant" to "can't",
            "wont" to "won't",
            "id" to "I'd",
            "ive" to "I've",
            "isnt" to "isn't",
            "didnt" to "didn't",
            "doesnt" to "doesn't",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "arent" to "aren't",
            "aint" to "ain't",
            "couldnt" to "couldn't",
            "wouldnt" to "wouldn't",
            "shouldnt" to "shouldn't",
            "havent" to "haven't",
            "hasnt" to "hasn't",
            "hadnt" to "hadn't",
            "youre" to "you're",
            "youve" to "you've",
            "youll" to "you'll",
            "youd" to "you'd",
            "theyre" to "they're",
            "theyve" to "they've",
            "theyll" to "they'll",
            "weve" to "we've",
            "thats" to "that's",
            "whats" to "what's",
            "theres" to "there's",
            "heres" to "here's",
            "wheres" to "where's",
            "whos" to "who's",
            "hes" to "he's",
            "shes" to "she's",
            "yall" to "y'all",
            "oclock" to "o'clock",
            "wouldve" to "would've",
            "couldve" to "could've",
            "shouldve" to "should've",
        )

        /**
         * Curated high-value next-word pairs (~150), keyed by lowercase previous word,
         * tails ordered most-likely first.
         */
        private val BUILTIN_BIGRAMS: Map<String, List<String>> = mapOf(
            "i" to listOf("am", "have", "was", "will", "think", "don't"),
            "i'm" to listOf("not", "sorry", "going", "sure", "so"),
            "i'll" to listOf("be", "see", "call"),
            "i've" to listOf("been", "got", "never"),
            "thank" to listOf("you", "god"),
            "thanks" to listOf("for", "to"),
            "a" to listOf("lot", "few", "little", "good", "great", "bit"),
            "the" to listOf("same", "best", "first", "only", "way", "most"),
            "to" to listOf("be", "do", "go", "get", "see", "make"),
            "in" to listOf("the", "a", "my", "this", "fact"),
            "on" to listOf("the", "my", "a", "it"),
            "for" to listOf("the", "a", "you", "me", "your"),
            "of" to listOf("the", "course", "a", "my", "this"),
            "at" to listOf("the", "least", "all", "home", "work"),
            "with" to listOf("the", "a", "you", "me", "my"),
            "how" to listOf("are", "do", "to", "much", "many"),
            "what" to listOf("is", "do", "about", "a", "the"),
            "you" to listOf("are", "can", "know", "have", "want"),
            "your" to listOf("own", "name", "life", "help"),
            "my" to listOf("own", "life", "name", "friend", "god"),
            "it" to listOf("is", "was", "would", "will"),
            "it's" to listOf("a", "not", "just", "okay", "been"),
            "let" to listOf("me", "us", "it"),
            "let's" to listOf("go", "see", "get", "do"),
            "see" to listOf("you", "the", "what", "if"),
            "good" to listOf("morning", "luck", "idea", "night", "job"),
            "have" to listOf("a", "to", "you", "been", "no"),
            "has" to listOf("been", "a", "to", "the"),
            "had" to listOf("a", "to", "been", "no"),
            "will" to listOf("be", "you", "not"),
            "would" to listOf("be", "you", "like", "have"),
            "could" to listOf("be", "you", "have", "not"),
            "should" to listOf("be", "have", "i", "we"),
            "can" to listOf("you", "be", "i", "we", "do"),
            "don't" to listOf("know", "want", "have", "worry", "think"),
            "we" to listOf("are", "can", "have", "need", "should"),
            "we're" to listOf("going", "not", "gonna", "here"),
            "they" to listOf("are", "have", "were", "will"),
            "he" to listOf("is", "was", "has", "said"),
            "she" to listOf("is", "was", "has", "said"),
            "this" to listOf("is", "was", "one", "morning"),
            "that" to listOf("is", "was", "one", "the"),
            "that's" to listOf("a", "not", "what", "why", "right"),
            "not" to listOf("sure", "really", "a", "the"),
            "be" to listOf("a", "the", "able", "there", "careful"),
            "so" to listOf("much", "many", "far", "that", "i"),
            "very" to listOf("much", "good", "well", "nice"),
            "no" to listOf("one", "problem", "way", "matter"),
            "as" to listOf("a", "the", "well", "soon", "if"),
            "just" to listOf("a", "one", "like", "wanted"),
            "all" to listOf("the", "of", "right", "day"),
            "about" to listOf("the", "it", "you", "that"),
            "there" to listOf("is", "are", "was", "were"),
            "here" to listOf("is", "we", "to", "for"),
            "right" to listOf("now", "here", "away"),
            "come" to listOf("on", "back", "here", "in"),
            "go" to listOf("to", "back", "home", "ahead"),
            "get" to listOf("out", "the", "a", "back"),
            "going" to listOf("to", "on", "out"),
            "want" to listOf("to", "you", "a"),
            "need" to listOf("to", "a", "you", "some", "help"),
            "like" to listOf("to", "a", "this", "that", "you"),
            "one" to listOf("of", "day", "more", "thing"),
            "do" to listOf("you", "it", "not", "that"),
            "did" to listOf("you", "not", "it"),
            "are" to listOf("you", "we", "they", "not"),
            "is" to listOf("that", "it", "the", "a", "not"),
            "was" to listOf("a", "the", "not", "just"),
            "if" to listOf("you", "i", "we", "it", "there"),
            "when" to listOf("you", "i", "we", "the", "it"),
            "why" to listOf("are", "do", "not", "would"),
            "where" to listOf("are", "is", "the", "do", "did"),
            "who" to listOf("are", "is", "was", "do"),
            "been" to listOf("a", "there", "here"),
            "out" to listOf("of", "there", "here"),
            "up" to listOf("to", "the", "here"),
            "from" to listOf("the", "a", "you", "my", "here"),
            "by" to listOf("the", "a", "my", "now"),
            "or" to listOf("something", "two", "the", "not"),
            "but" to listOf("i", "it", "the", "you", "not"),
            "and" to listOf("i", "the", "then", "you", "we"),
            "oh" to listOf("my", "no", "god", "yeah", "come"),
            "yes" to listOf("sir", "i", "it"),
            "excuse" to listOf("me"),
            "miss" to listOf("you"),
            "love" to listOf("you", "it", "to"),
            "look" to listOf("at", "like", "forward"),
            "looking" to listOf("for", "at", "forward"),
            "sounds" to listOf("good", "like", "great"),
            "talk" to listOf("to", "about", "soon"),
            "call" to listOf("me", "you", "the"),
            "tell" to listOf("me", "you", "him", "her"),
            "make" to listOf("sure", "it", "a", "sense"),
            "next" to listOf("week", "time", "to", "year"),
            "last" to listOf("night", "week", "year", "time"),
            "every" to listOf("day", "time", "one"),
            "each" to listOf("other"),
            "years" to listOf("ago", "old"),
            "take" to listOf("care", "a", "the", "it"),
            "long" to listOf("time", "ago"),
            "first" to listOf("time", "of", "place"),
            "too" to listOf("much", "many", "late", "bad"),
        )
    }
}
