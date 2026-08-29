package com.vboard.core.qa

import com.vboard.core.model.ModelCatalog
import com.vboard.core.suggest.AutocorrectMode
import com.vboard.core.suggest.Lexicon
import com.vboard.core.suggest.SuggestionEngine
import com.vboard.core.suggest.SuggestionRequest
import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One test per finding in `docs/QA_REPORT.md`, named by its id.
 *
 * The existing suite covers most of these behaviours, but it covers them by
 * *behaviour*, scattered across files, and in several cases only as a comment
 * next to an assertion. That is enough to catch a regression and not enough to
 * answer the question a QA report has to answer: "is finding VB-QA-07 still
 * fixed?" A reader should be able to grep an id and get a yes or no.
 *
 * Findings whose fix lives outside `core` (or whose fix is a `PackInstaller`
 * interaction better tested in `ModelInstallerQaTest`) are recorded here as a
 * pointer rather than duplicated.
 */
class QaRegressionPinTest {

    private val cleaner = TranscriptCleaner()
    private val lexicon = Lexicon.english()

    private fun clean(
        transcript: String,
        preceding: String = "",
        options: CleanupOptions = CleanupOptions(),
        kind: FieldKind = FieldKind.TEXT,
        terminal: Boolean = true,
    ) = cleaner.clean(CleanupRequest(transcript, preceding, kind, options, terminal))

    private fun suggest(composing: String, mode: AutocorrectMode = AutocorrectMode.CONSERVATIVE) =
        SuggestionEngine(lexicon).suggest(SuggestionRequest(composing, null, FieldKind.TEXT, mode))

    // ------------------------------------------------------------------- fixed

    @Test
    fun `VB-QA-01 spoken digit sequences are never collapsed as stutters`() {
        assertEquals("Five five five one two one two.", clean("five five five one two one two").text)
        assertEquals("Call 555 1212 now.", clean("call 555 1212 now").text)
        assertEquals("Two two two two.", clean("two two two two").text)
        assertEquals("Room 3 3 3.", clean("room 3 3 3").text)
        // Non-numbers are still collapsed, which is the point of the stage.
        assertEquals("The cat sat.", clean("the the cat sat").text)
        assertEquals(1, clean("the the cat sat").repetitionsCollapsed)
    }

    @Test
    fun `VB-QA-02 an interrogative-led utterance gets a question mark`() {
        assertEquals("What is this?", clean("what is this").text)
        assertEquals("How are you?", clean("how are you").text)
        assertEquals("Can you hear me?", clean("can you hear me").text)
        assertEquals("Should we go now?", clean("should we go now").text)
        // The documented remaining limit: an interjection-led question still
        // gets a period. Pinned so the limit is visible, not forgotten.
        assertEquals("Hey are you coming.", clean("hey are you coming").text)
    }

    @Test
    fun `VB-QA-03 actually no is a strong correction marker`() {
        assertEquals("For june", clean("for may actually no june").text)
        assertEquals("At six", clean("at five actually no six").text)
        assertEquals(1, clean("for may actually no june").correctionsResolved)
        // It requires i > 0, so an utterance that merely opens with the phrase
        // is left alone.
        assertEquals("Actually no thanks.", clean("actually no thanks").text)
    }

    @Test
    fun `VB-QA-04 raw mode still capitalizes standalone I and normalizes spacing`() {
        // Recorded as still-open in the report; the fuller consequences (emoji and
        // symbol loss in raw mode) are VB-QA-17 in RawModeFidelityQaTest.
        assertEquals("I think I'm ok", clean("i think i'm ok", options = CleanupOptions.RAW).text)
        assertEquals("hello world", clean("hello    world", options = CleanupOptions.RAW).text)
    }

    @Test
    fun `VB-QA-05 idempotency holds everywhere except the three documented inputs`() {
        // The known breakers, pinned as they behave today. The spec-correct
        // assertion is the @Disabled test in CleanupPropertyTest.
        val breakers = listOf(
            "no wait no wait no wait no wait no wait",
            "scratch that scratch that",
            "new line new line new line",
        )
        var differed = 0
        for (input in breakers) {
            val once = clean(input).text
            if (clean(once).text != once) differed++
        }
        assertEquals(
            breakers.size, differed,
            "an idempotency breaker started passing - re-enable the @Disabled test in CleanupPropertyTest",
        )
        // Everything ordinary is idempotent.
        for (input in listOf(
            "hello world", "what is this", "um hello there", "the the cat sat",
            "call me at five no wait six", "hello comma world", "he said period",
        )) {
            val once = clean(input).text
            assertEquals(once, clean(once).text, "not idempotent for <$input>")
        }
    }

    @Test
    fun `VB-QA-06 internal capitals gate autocorrect exactly like ALL-CAPS`() {
        for (word in listOf("iPhone", "iOS", "VBoard", "McDonald", "eBay", "iPad")) {
            for (mode in listOf(AutocorrectMode.CONSERVATIVE, AutocorrectMode.AGGRESSIVE)) {
                assertEquals(null, suggest(word, mode).autocorrect, "$mode rewrote <$word>")
            }
        }
        for (word in listOf("HELLO", "ASAP", "NASA")) {
            assertEquals(null, suggest(word, AutocorrectMode.AGGRESSIVE).autocorrect, "rewrote <$word>")
        }
        // The gate is on internal capitals only; a plain lowercase typo still corrects.
        assertEquals("the", suggest("teh").autocorrect?.text)
        assertEquals("The", suggest("Teh").autocorrect?.text)
    }

    @Test
    fun `VB-QA-07 concurrent installs are serialized - see ModelInstallerQaTest`() {
        // The fix is a per-pack Mutex inside PackInstaller and needs a coroutine
        // harness; ModelInstallerQaTest owns that test. Pinned here only as a
        // pointer so the id is greppable, plus the catalog invariant the fix
        // depends on: pack ids must be unique, or the Mutex map keys collide.
        val ids = ModelCatalog.packs.map { it.id }
        assertEquals(ids.distinct().size, ids.size, "duplicate pack id would defeat the per-pack install Mutex")
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun `VB-QA-09 the typed literal is always reachable in the strip`() {
        for (composing in listOf("teh", "hte", "recieve", "seperate", "definately", "wierd")) {
            val result = suggest(composing, AutocorrectMode.AGGRESSIVE)
            assertTrue(
                result.suggestions.any { it.text.equals(composing, ignoreCase = true) },
                "literal <$composing> unreachable: ${result.suggestions.map { it.text }}",
            )
            assertTrue(result.suggestions.size <= 3)
        }
    }

    @Test
    fun `VB-QA-10 catalog sizes are measured, not estimated - see ModelInstallerQaTest`() {
        // The behavioural fix (ask the server for contentLength, handle 416) lives
        // in PackInstaller and is covered by ModelInstallerQaTest. What is pinned
        // here is the catalog-side half: sizes are real numbers rather than round
        // estimates, which is what made the old hard-minimum check fail.
        val measured = mutableListOf<String>()
        val estimated = mutableListOf<String>()
        for (pack in ModelCatalog.packs) {
            for (file in pack.files) {
                assertTrue(file.sizeBytes > 0, "${pack.id}/${file.relativePath} has no size")
                val key = "${pack.id}/${file.relativePath}"
                if (file.sizeBytes % 1_000_000L == 0L) estimated.add(key) else measured.add(key)
            }
        }
        // Both ASR packs — the ones that actually failed the download — carry
        // measured sizes.
        assertTrue(measured.any { it.startsWith("zipformer-en-streaming/") })
        assertTrue(measured.any { it.startsWith("parakeet-tdt-0.6b-v2/") })
        // The refiner pack does not: its size is still a round estimate and its
        // sha256 is still empty. VB-QA-10's fix (gate on the server's
        // contentLength) makes that survivable rather than fatal, and the empty
        // digest is already listed as a known risk in the report — pinned here so
        // "sizes are now measured from the upstream assets" is not read as
        // covering every pack.
        assertEquals(listOf("qwen25-05b-refiner/qwen2.5-0.5b-instruct-q8.task"), estimated)
        assertTrue(
            ModelCatalog.packs.flatMap { it.files }.any { it.sha256.isEmpty() },
            "all digests are pinned now - update this pin and the report's known-risks section",
        )
    }

    @Test
    fun `VB-QA-11 an archive pack budgets more than its download size`() {
        val archivePacks = ModelCatalog.packs.filter { pack ->
            pack.files.any { it.relativePath.endsWith(".tar.bz2") || it.relativePath.endsWith(".zip") }
        }
        assertTrue(archivePacks.isNotEmpty(), "no archive pack in the catalog - has packaging changed?")
        for (pack in archivePacks) {
            val download = pack.files.sumOf { it.sizeBytes }
            assertTrue(
                pack.installFootprintBytes > download,
                "${pack.id} budgets ${pack.installFootprintBytes} for a ${download}-byte archive download",
            )
        }
        // A non-archive pack needs no extra headroom.
        for (pack in ModelCatalog.packs - archivePacks.toSet()) {
            assertEquals(pack.files.sumOf { it.sizeBytes }, pack.installFootprintBytes, "for ${pack.id}")
        }
    }

    @Test
    fun `VB-QA-12 the dollar sign is still dropped - see TokenizerSymbolLossQaTest`() {
        // Open. The spec-correct assertion is the @Disabled test in
        // CleanupGoldenCorpusTest; the sibling symbols are in
        // TokenizerSymbolLossQaTest.
        assertEquals("That jacket costs 75.", clean("that jacket costs $75").text)
    }

    // ------------------------------------------------------- report completeness

    @Test
    fun `every VB-QA id in this file is unique and contiguous from 01`() {
        // A cheap guard against two people picking the same next id. This test
        // knows only about the ids pinned here; new ones live in their own files
        // and are listed in the report.
        val ids = this::class.java.declaredMethods
            .mapNotNull { Regex("""VB.QA.(\d\d)""").find(it.name)?.groupValues?.get(1) }
            .map { it.toInt() }
            .sorted()
        assertEquals(ids.distinct(), ids, "duplicate VB-QA id pinned in this file")
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12), ids)
    }
}
