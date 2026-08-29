package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.CleanupResult
import com.vboard.core.text.FieldKind
import com.vboard.core.text.Tok
import com.vboard.core.text.Tokenizer
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants that must hold for **any** input, checked by generation rather than
 * by enumeration.
 *
 * `CleanupPropertyTest` already fuzzes idempotency and totality over ~30 curated
 * adversarial strings. This file goes after the properties that a curated list
 * cannot reach: it builds utterances from a grammar of the tokens the pipeline
 * actually branches on (markers, punctuation words, hesitations, numbers,
 * breaks, quotes) and from raw Unicode, then asserts structural guarantees about
 * the output.
 *
 * The distinction that matters: a golden corpus tells you the pipeline is right
 * about 44 sentences. A property tells you it cannot be catastrophically wrong
 * about any sentence. The cleanup engine is a pure function of a string, so the
 * second kind of assurance is available cheaply and we were not buying it.
 */
class CleanupInvariantQaTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(
        transcript: String,
        preceding: String = "",
        options: CleanupOptions = CleanupOptions(),
        fieldKind: FieldKind = FieldKind.TEXT,
        terminal: Boolean = true,
    ): CleanupResult = cleaner.clean(CleanupRequest(transcript, preceding, fieldKind, options, terminal))

    // ---------------------------------------------------------------- generators

    /** The vocabulary the pipeline branches on, plus ordinary filler words. */
    private val vocabulary = listOf(
        // ordinary words
        "hello", "world", "meeting", "tomorrow", "please", "the", "a", "my", "that",
        "tell", "him", "her", "about", "it", "and", "but", "so", "very", "really",
        // hesitations and discourse fillers
        "um", "uh", "erm", "hmm", "mm", "you", "know", "basically", "literally",
        // correction markers and their parts
        "no", "wait", "actually", "scratch", "strike", "make", "sorry", "rather",
        "i", "mean", "meant",
        // spoken punctuation words
        "period", "comma", "colon", "semicolon", "hyphen", "dash", "ellipsis",
        "ampersand", "hashtag", "new", "line", "paragraph", "next", "full", "stop",
        "question", "mark", "exclamation", "point", "open", "close", "quote",
        "quotes", "paren", "parenthesis", "at", "sign", "percent", "dot",
        // numbers, weekdays, months (the alignment categories)
        "one", "two", "five", "six", "twelve", "noon", "monday", "tuesday",
        "june", "july", "today", "tonight",
        // literal punctuation and breaks the ASR may emit
        ".", ",", "?", "!", ";", ":", "\"", "(", ")", "-", "&", "@", "#", "%",
        "...", "\n", "\n\n",
        // things the tokenizer does not recognize
        "$", "€", "+", "=", "_", "/", "👋", "é", "́", "​",
        // ASR artifacts
        "<unk>", "[music]", "(noise)",
    )

    private fun utterance(random: Random, maxTokens: Int = 14): String =
        (0 until random.nextInt(0, maxTokens)).joinToString(" ") { vocabulary.random(random) }

    /**
     * The same generator minus the two vocabularies that are *supposed* to change
     * the text without any option asking them to: spoken punctuation words and
     * ASR artifact tags. Properties about "what cleanup adds" have to exclude
     * them or they measure stage 3 rather than the property.
     */
    private val punctuationWords = setOf(
        "period", "comma", "colon", "semicolon", "hyphen", "dash", "ellipsis",
        "ampersand", "hashtag", "new", "line", "paragraph", "next", "full", "stop",
        "question", "mark", "exclamation", "point", "open", "close", "quote",
        "quotes", "paren", "parenthesis", "at", "sign", "percent", "dot",
    )
    private val artifactTokens = setOf("<unk>", "[music]", "(noise)")

    private val proseVocabulary = vocabulary.filterNot { it in punctuationWords || it in artifactTokens }

    private fun proseUtterance(random: Random, maxTokens: Int = 14): String =
        (0 until random.nextInt(0, maxTokens)).joinToString(" ") { proseVocabulary.random(random) }

    /** Prose with no literal punctuation either: for properties about what cleanup *adds*. */
    private val unpunctuatedVocabulary =
        proseVocabulary.filterNot { token -> token.any { it in ".,!?;:\"()-&@#%\n" } }

    private fun unpunctuatedUtterance(random: Random, maxTokens: Int = 14): String =
        (0 until random.nextInt(0, maxTokens)).joinToString(" ") { unpunctuatedVocabulary.random(random) }

    private val optionSpace: List<CleanupOptions> = buildList {
        add(CleanupOptions())
        add(CleanupOptions.RAW)
        add(CleanupOptions(aggressiveFillers = true))
        add(CleanupOptions(removeFillers = false))
        add(CleanupOptions(resolveSelfCorrections = false))
        add(CleanupOptions(collapseRepetitions = false))
        add(CleanupOptions(autoPunctuate = false))
        add(CleanupOptions(autoCapitalize = false))
        add(CleanupOptions(spokenCommands = false))
    }

    // ---------------------------------------------------------- totality & hygiene

    @Test
    fun `cleanup never throws, over the full option and field space`() {
        val random = Random(20260829)
        repeat(6_000) {
            val input = utterance(random)
            val options = optionSpace.random(random)
            val kind = FieldKind.entries.random(random)
            val preceding = listOf("", " ", "Hello", "Hello.", "Hello. ", "\n", "?", "👋").random(random)
            assertDoesNotThrow("threw for <$input> options=$options kind=$kind preceding=<$preceding>") {
                clean(input, preceding, options, kind, random.nextBoolean())
            }
        }
    }

    @Test
    fun `output is never padded and never contains a double space`() {
        val random = Random(1234567)
        repeat(6_000) {
            val input = utterance(random)
            val out = clean(input, options = optionSpace.random(random)).text
            assertTrue("  " !in out, "double space in <$out> from <$input>")
            assertTrue(out == out.trim(' '), "padded output <$out> from <$input>")
            assertTrue(" \n" !in out && "\n " !in out, "space around a break in <$out> from <$input>")
        }
    }

    @Test
    fun `output never contains a space before closing punctuation`() {
        val random = Random(987654)
        repeat(6_000) {
            val out = clean(utterance(random), options = optionSpace.random(random)).text
            for (p in listOf(" .", " ,", " ?", " !", " ;", " :", " %", " )", " ...")) {
                assertTrue(p !in out, "found <$p> in <$out>")
            }
        }
    }

    @Test
    fun `output never contains a doubled sentence terminator`() {
        val random = Random(555_000)
        repeat(6_000) {
            val out = clean(utterance(random), options = optionSpace.random(random)).text
            for (p in listOf("!!", "??", ",,", ";;", "::")) {
                assertTrue(p !in out, "found <$p> in <$out>")
            }
            // ",." is legal only as ",..." — see the pinned ellipsis case below.
            assertTrue(
                Regex(""",\.(?!\.\.)""").find(out) == null,
                "found a period directly after a comma in <$out>",
            )
            // ".," is legal only as "...," — see the pinned ellipsis case below.
            assertTrue(
                Regex("""(?<!\.\.)\.,""").find(out) == null,
                "found a comma directly after a period in <$out>",
            )
            // A run of dots is a period, an ellipsis, or (see the pinned case
            // below) an ellipsis with a period stacked onto it. Never more.
            for (run in Regex("""\.+""").findAll(out)) {
                assertTrue(run.value.length in 1..4, "dot run <${run.value}> in <$out>")
            }
        }
    }

    // ------------------------------------------------------------ content safety

    @Test
    fun `every surviving word is a word the user actually said`() {
        // Cleanup may delete, but it must never *invent* a word. The only strings
        // it is allowed to add are punctuation, and the only word it may change is
        // by capitalization.
        val random = Random(31415)
        repeat(6_000) {
            val input = utterance(random)
            val options = optionSpace.random(random)
            val spoken = Tokenizer.tokenize(input)
                .filterIsInstance<Tok.Word>().map { it.text.lowercase() }.toSet()
            val produced = Tokenizer.tokenize(clean(input, options = options).text)
                .filterIsInstance<Tok.Word>().map { it.text.lowercase() }
            for (word in produced) {
                assertTrue(word in spoken, "cleanup invented the word <$word> from <$input>")
            }
        }
    }

    @Test
    fun `surviving words keep the order they were spoken in`() {
        val random = Random(27182)
        repeat(6_000) {
            val input = utterance(random)
            val options = optionSpace.random(random)
            val spoken = Tokenizer.tokenize(input)
                .filterIsInstance<Tok.Word>().map { it.text.lowercase() }
            val produced = Tokenizer.tokenize(clean(input, options = options).text)
                .filterIsInstance<Tok.Word>().map { it.text.lowercase() }
            var i = 0
            for (word in spoken) if (i < produced.size && produced[i] == word) i++
            assertEquals(produced.size, i, "words reordered: <$input> -> <${produced.joinToString(" ")}>")
        }
    }

    @Test
    fun `a filler-free, marker-free, command-free utterance keeps every word`() {
        // The complement of the destructive stages: if none of the triggers are
        // present, cleanup is guaranteed non-lossy. This is the property the
        // product actually promises, and it holds.
        val safeWords = listOf(
            "hello", "world", "meeting", "tomorrow", "please", "the", "a", "my",
            "tell", "him", "her", "about", "and", "but", "so", "very", "really",
            "monday", "june", "today",
        )
        val random = Random(161803)
        val noCollapse = CleanupOptions(collapseRepetitions = false)
        repeat(4_000) {
            val words = (0 until random.nextInt(1, 12)).map { safeWords.random(random) }
            val input = words.joinToString(" ")

            // With repetition collapse off, every word survives, in order, verbatim.
            val exact = Tokenizer.tokenize(clean(input, options = noCollapse).text.lowercase())
                .filterIsInstance<Tok.Word>().map { it.text }
            assertEquals(words, exact, "a word was lost with collapse off, from <$input>")

            // With it on, the only permitted change is deletion of a repeated word
            // or a repeated bigram — never a substitution or a reordering.
            val collapsed = Tokenizer.tokenize(clean(input).text.lowercase())
                .filterIsInstance<Tok.Word>().map { it.text }
            var i = 0
            for (w in words) if (i < collapsed.size && collapsed[i] == w) i++
            assertEquals(collapsed.size, i, "collapse reordered or invented words in <$input>")
            assertTrue(collapsed.isNotEmpty(), "collapse emptied <$input>")
        }
    }

    @Test
    fun `disabling every stage makes cleanup lose nothing but unrecognized characters`() {
        val random = Random(112358)
        repeat(4_000) {
            val input = proseUtterance(random)
            val allOff = CleanupOptions(
                removeFillers = false, aggressiveFillers = false, resolveSelfCorrections = false,
                collapseRepetitions = false, autoPunctuate = false, autoCapitalize = false,
                spokenCommands = false,
            )
            val result = clean(input, options = allOff)
            if (result.command != UtteranceCommand.NONE) return@repeat
            val spoken = Tokenizer.tokenize(input).filterIsInstance<Tok.Word>().map { it.text.lowercase() }
            val produced = Tokenizer.tokenize(result.text).filterIsInstance<Tok.Word>().map { it.text.lowercase() }
            assertEquals(spoken, produced, "a word was lost with every stage off, from <$input>")
            assertEquals(0, result.fillersRemoved)
            assertEquals(0, result.correctionsResolved)
            assertEquals(0, result.repetitionsCollapsed)
            assertEquals(0, result.spokenSubstitutions)
        }
    }

    // ----------------------------------------------------------- counters honesty

    @Test
    fun `the transformation counters are non-negative and bounded by the word count`() {
        val random = Random(654321)
        repeat(6_000) {
            val input = utterance(random)
            val result = clean(input, options = optionSpace.random(random))
            val words = Tokenizer.tokenize(input).count { it is Tok.Word }
            for ((name, n) in listOf(
                "fillersRemoved" to result.fillersRemoved,
                "correctionsResolved" to result.correctionsResolved,
                "repetitionsCollapsed" to result.repetitionsCollapsed,
                "spokenSubstitutions" to result.spokenSubstitutions,
            )) {
                assertTrue(n >= 0, "$name negative for <$input>")
                assertTrue(n <= words, "$name=$n exceeds the $words words in <$input>")
            }
        }
    }

    @Test
    fun `a command result carries no text and a text result carries no command`() {
        val random = Random(24680)
        repeat(6_000) {
            val result = clean(utterance(random), options = optionSpace.random(random))
            if (result.command != UtteranceCommand.NONE) {
                assertEquals("", result.text, "command ${result.command} came with text")
            }
        }
    }

    // --------------------------------------------------------- field-kind contracts

    @Test
    fun `no field kind other than TEXT ever receives a terminal period`() {
        val random = Random(13579)
        val noCommands = CleanupOptions(spokenCommands = false)
        repeat(4_000) {
            val input = unpunctuatedUtterance(random)
            for (kind in FieldKind.entries.filter { it != FieldKind.TEXT }) {
                val out = clean(input, options = noCommands, fieldKind = kind, terminal = true).text
                assertTrue(
                    out.none { it in ".?!" },
                    "$kind got terminal punctuation: <$input> -> <$out>",
                )
            }
        }
    }

    // ------------------- VB-QA-29: allowsAutoCapitalize gates only the first word

    @Test
    fun `fields that disallow auto-capitalization are left alone at every position (pinned)`() {
        // FieldKind.allowsAutoCapitalize used to be consulted only by
        // sentenceStartsAt, which decides the FIRST word; capitalize() then re-armed
        // at every "." "!" "?" and every break regardless of field kind — including
        // in a PASSWORD field, which the spec says must be left alone entirely. The
        // field kind now gates the whole pass.
        for (kind in listOf(FieldKind.EMAIL, FieldKind.URI, FieldKind.PASSWORD, FieldKind.NUMBER)) {
            assertEquals("hello. world here", clean("hello. world here", fieldKind = kind).text, "for $kind")
            assertEquals("hello\nworld here", clean("hello new line world here", fieldKind = kind).text, "for $kind")
            assertEquals("hello world here", clean("hello world here", fieldKind = kind).text, "for $kind")
        }
    }

    @Test
    fun `fields that disallow auto-capitalization are never capitalized by cleanup`() {
        for (kind in listOf(FieldKind.EMAIL, FieldKind.URI, FieldKind.PASSWORD, FieldKind.NUMBER)) {
            assertEquals("hello. world here", clean("hello. world here", fieldKind = kind).text, "for $kind")
            assertEquals("hello\nworld here", clean("hello new line world here", fieldKind = kind).text, "for $kind")
        }
        val random = Random(97531)
        repeat(2_000) {
            val input = proseUtterance(random)
            for (kind in FieldKind.entries.filterNot { it.allowsAutoCapitalize }) {
                val source = Tokenizer.tokenize(input).filterIsInstance<Tok.Word>().map { it.text }
                for (word in Tokenizer.tokenize(clean(input, fieldKind = kind).text).filterIsInstance<Tok.Word>()) {
                    if (word.text.lowercase() == "i" || word.text.startsWith("I'")) continue
                    assertTrue(word.text in source, "$kind changed the case of <${word.text}> in <$input>")
                }
            }
        }
    }

    // -------------------------------- VB-QA-30: adjacent breaks are never merged

    @Test
    fun `consecutive breaks are merged before rendering (pinned)`() {
        // Tokenizer.render writes each Break verbatim, so N spoken line breaks used
        // to produce N newlines; re-tokenizing that output merged them back to two,
        // which was the mechanism behind the VB-QA-05 idempotency failure for "\n\n\n".
        // normalizePunctuationSequence now merges the run, so the output is what
        // re-tokenizing it would produce.
        val once = clean("hello new paragraph new line world").text
        assertEquals("Hello\n\nWorld", once)
        assertEquals(once, clean(once).text)
        assertEquals("Hello\n\nWorld", clean("hello new line new line new line world").text)
        // Literal breaks in the transcript are capped at two by the tokenizer, and
        // the spoken path now agrees with it.
        assertEquals("A\n\nB", clean("a\n\n\n\nb").text)
    }

    @Test
    fun `consecutive breaks should be normalized to at most a paragraph`() {
        assertEquals("Hello\n\nWorld", clean("hello new paragraph new line world").text)
        assertEquals("Hello\n\nWorld", clean("hello new line new line new line world").text)
    }

    // ------------------- VB-QA-31: "..." is not treated as a sentence terminator

    @Test
    fun `an ellipsis absorbs an adjacent period or comma (pinned)`() {
        // normalizePunctuationSequence collapses two *identical* adjacent Punct
        // tokens and drops a comma after a member of SENTENCE_ENDERS. "..." is
        // neither, so both used to stack visibly; it now absorbs them explicitly.
        // "..." stays out of SENTENCE_ENDERS: that set also drives capitalization,
        // and "and" below must stay lowercase.
        assertEquals("Tell me... and go.", clean("tell me comma ellipsis and go").text)
        assertEquals("Tell me... and go.", clean("tell me ellipsis period and go").text)
        // A trailing ellipsis is correctly left alone (ensureTerminalPeriod only
        // fires when the last meaningful token is a Word), so the defect is
        // confined to an ellipsis with punctuation after it.
        assertEquals("Tell me and go...", clean("tell me and go ellipsis").text)
    }

    @Test
    fun `an ellipsis should terminate a sentence like any other terminator`() {
        assertEquals("Tell me... and go.", clean("tell me comma ellipsis and go").text)
        assertEquals("Tell me... and go.", clean("tell me ellipsis period and go").text)
    }

    // ------------------ artifact scrubbing is not gated by any option

    @Test
    fun `ASR artifact scrubbing runs in every mode including raw (pinned)`() {
        // scrubArtifacts is applied to the transcript before rawMode or any
        // CleanupOptions flag is consulted (TranscriptCleaner.kt:23), so there is
        // no setting a user can turn on to stop it deleting a bracketed aside.
        // See VB-QA-21 for what it deletes.
        assertEquals("Hello there", clean("[music] hello there").text)
        assertEquals("hello there", clean("[music] hello there", options = CleanupOptions.RAW).text)
        assertEquals(
            "hello there",
            clean("[music] hello there", options = CleanupOptions(
                removeFillers = false, resolveSelfCorrections = false, collapseRepetitions = false,
                autoPunctuate = false, autoCapitalize = false, spokenCommands = false,
            )).text,
        )
    }

    @Test
    fun `ensureTerminalPunctuation=false never adds a terminator`() {
        val random = Random(86420)
        val noCommands = CleanupOptions(spokenCommands = false)
        repeat(4_000) {
            val input = unpunctuatedUtterance(random)
            val withOut = clean(input, options = noCommands, terminal = false).text
            assertTrue(
                withOut.none { it in ".!?" },
                "terminal punctuation added with terminal=false: <$input> -> <$withOut>",
            )
        }
    }

    // ------------------------------------------------------------------ raw Unicode

    @Test
    fun `cleanup survives arbitrary Unicode noise`() {
        // Not a grammar: random code points from every plane, including lone
        // surrogates, in strings up to 200 characters. The pipeline must not
        // throw and must not produce a string with an unpaired surrogate that a
        // later InputConnection write would reject.
        val random = Random(1000003)
        repeat(3_000) {
            val length = random.nextInt(0, 200)
            val sb = StringBuilder(length)
            repeat(length) {
                when (random.nextInt(6)) {
                    0 -> sb.append(random.nextInt(0x20, 0x7F).toChar())
                    1 -> sb.append(random.nextInt(0x80, 0x2000).toChar())
                    2 -> sb.append(random.nextInt(0x2000, 0xD800).toChar())
                    3 -> sb.append(random.nextInt(0xE000, 0x10000).toChar())
                    4 -> sb.appendCodePoint(random.nextInt(0x10000, 0x110000))
                    else -> sb.append(random.nextInt(0xD800, 0xE000).toChar()) // lone surrogate
                }
            }
            val input = sb.toString()
            val out = assertDoesNotThrow("threw for a ${input.length}-char random string") {
                clean(input, options = optionSpace.random(random)).text
            }
            var i = 0
            while (i < out.length) {
                val c = out[i]
                assertTrue(
                    !c.isHighSurrogate() || (i + 1 < out.length && out[i + 1].isLowSurrogate()),
                    "unpaired high surrogate at $i in cleanup output",
                )
                assertTrue(
                    !c.isLowSurrogate() || (i > 0 && out[i - 1].isHighSurrogate()),
                    "unpaired low surrogate at $i in cleanup output",
                )
                i++
            }
        }
    }

    @Test
    fun `cleanup is linear enough to be safe on a very long utterance`() {
        // A dictation session with no endpointing can hand the cleaner a very long
        // string. 40k characters must complete without a stack overflow or a
        // quadratic blow-up (the marker loop and the alignment scan are both
        // bounded, so this should be fast).
        val long = (0 until 8_000).joinToString(" ") { listOf("hello", "um", "the", "no", "wait").random(Random(it)) }
        assertTrue(long.length > 30_000)
        val out = assertDoesNotThrow { clean(long).text }
        assertTrue(out.isNotEmpty())
        // And on a single unbroken token.
        assertDoesNotThrow { clean("x".repeat(100_000)) }
        assertDoesNotThrow { clean("\n".repeat(10_000)) }
        assertDoesNotThrow { clean(", ".repeat(10_000)) }
    }
}
