package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * QA golden corpus for the Tier-1 cleanup engine (VB-201..VB-206, VB-706).
 *
 * Every case pins the EXACT output of [TranscriptCleaner] with default
 * [CleanupOptions] so any rule change shows up as a diff. Expectations were
 * derived by auditing the cleaner's stage rules; where current behavior
 * deviates from the product spec, the case carries a `VB-QA-nn` comment and the
 * deviation is documented in docs/QA_REPORT.md (spec-correct expectations live
 * in the @Disabled tests at the bottom).
 *
 * Unless stated otherwise, cases run as a finalized utterance into an empty
 * free-form TEXT field (ensureTerminalPunctuation = true), which is the
 * high-volume path of Journey B.
 */
class CleanupGoldenCorpusTest {

    data class Case(
        val name: String,
        val input: String,
        val expected: String,
        val fieldKind: FieldKind = FieldKind.TEXT,
        val terminal: Boolean = true,
        val preceding: String = "",
    )

    private val cleaner = TranscriptCleaner()

    private fun clean(
        transcript: String,
        preceding: String = "",
        options: CleanupOptions = CleanupOptions(),
        fieldKind: FieldKind = FieldKind.TEXT,
        terminal: Boolean = true,
    ) = cleaner.clean(CleanupRequest(transcript, preceding, fieldKind, options, terminal))

    @TestFactory
    fun `golden corpus`(): List<DynamicTest> = CASES.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val result = clean(
                case.input,
                preceding = case.preceding,
                fieldKind = case.fieldKind,
                terminal = case.terminal,
            )
            assertEquals(case.expected, result.text, "input: <${case.input}>")
            assertEquals(UtteranceCommand.NONE, result.command, "input: <${case.input}>")
        }
    }

    // ------------------------------------------------------------------
    // Known spec deviations, pinned with the SPEC-correct expectation and
    // disabled until the underlying finding is fixed. See docs/QA_REPORT.md.
    // ------------------------------------------------------------------

    @Test
    fun `spoken phone number digits are preserved`() {
        // VB-QA-01 fixed: number-like words are exempt from repetition collapse.
        assertEquals(
            "Call me at five five five one two one two.",
            clean("call me at five five five one two one two").text,
        )
    }

    @Test
    fun `numeric digit repetitions are preserved`() {
        // VB-QA-01 fixed: digit tokens are exempt from repetition collapse.
        assertEquals("5 5 5 1 2 1 2.", clean("5 5 5 1 2 1 2").text)
    }

    @Test
    fun `interrogative utterance gets question mark`() {
        // VB-QA-02 fixed: interrogative-starter utterances get '?'.
        assertEquals("What time does the store close?", clean("what time does the store close").text)
    }

    @Test
    fun `actually no resolves a self-correction`() {
        // VB-QA-03 fixed: "actually no" is a strong correction marker.
        assertEquals("Book the flight for june.", clean("book the flight for may actually no june").text)
    }

    @Test
    fun `a spoken price keeps its currency symbol`() {
        // Nobody had checked whether the recognizer ever emits the glyph rather
        // than the word 'dollars'. If it does — and a price is exactly the kind
        // of thing a user dictates — the symbol disappears with no trace, which
        // is a silent corruption of the user's text rather than a formatting
        // nicety. It did, and Package A closed it (VB-QA-12); the golden case
        // below now asserts the same thing from the corpus side.
        assertEquals("That jacket costs $75.", clean("that jacket costs $75").text)
    }

    companion object {
        val CASES: List<Case> = listOf(
            // ---------------------------------------------------- messaging
            Case(
                // VB-QA-12 fixed: the tokenizer carries through every symbol that
                // is not an ASR artifact, so a dictated price keeps its unit.
                "currency symbol is kept in a dictated price",
                "that jacket costs $75",
                "That jacket costs $75.",
            ),
            Case(
                "hesitation fillers removed, sentence capitalized and terminated",
                "um so I was thinking we should just order pizza tonight",
                "So I was thinking we should just order pizza tonight.",
            ),
            Case(
                // Known limit of the interrogative-starter heuristic: an
                // interjection ("hey") hides the question form, so '.' is kept.
                "interjection-led question keeps terminal period",
                "hey are you coming to the game tonight",
                "Hey are you coming to the game tonight.",
            ),
            Case(
                "make that swaps aligned numbers after filler removal",
                "tell sarah I'll be there at 7 uh make that 8",
                "Tell sarah I'll be there at 8.",
            ),
            Case(
                "spoken comma splices clauses",
                "running late comma be there in ten minutes",
                "Running late, be there in ten minutes.",
            ),
            Case(
                "intentional double no is kept (allowlist)",
                "no no I totally get it",
                "No no I totally get it.",
            ),
            Case(
                "spoken question mark wins over default period",
                "can you grab milk on the way home question mark",
                "Can you grab milk on the way home?",
            ),
            Case(
                "stuttered pronoun collapses",
                "I I think we should leave early",
                "I think we should leave early.",
            ),
            Case(
                "intentional double so is kept (allowlist)",
                "that's so so funny",
                "That's so so funny.",
            ),
            Case(
                "no wait rewrites the aligned prepositional phrase",
                "send the report to john no wait to megan",
                "Send the report to megan.",
            ),
            Case(
                "intentional double okay is kept (allowlist)",
                "okay okay see you soon",
                "Okay okay see you soon.",
            ),
            Case(
                "leading and mid hesitations removed together",
                "um yeah uh let's do friday instead",
                "Yeah let's do friday instead.",
            ),
            Case(
                "spoken exclamation mark",
                "lol that was hilarious exclamation mark",
                "Lol that was hilarious!",
            ),
            // ---------------------------------------------------- email dictation
            Case(
                "new paragraph plus spoken period recapitalizes",
                "hi team comma new paragraph the launch is moving to thursday period please update your plans",
                "Hi team,\n\nThe launch is moving to thursday. Please update your plans.",
            ),
            Case(
                "spoken period splits sentences and I-contraction is fixed",
                "thanks for the quick turnaround period I'll review it tonight",
                "Thanks for the quick turnaround. I'll review it tonight.",
            ),
            Case(
                "plain business sentence untouched except caps and period",
                "please find the attached invoice for march",
                "Please find the attached invoice for march.",
            ),
            Case(
                "sign-off with new line capitalizes the name",
                "best regards comma new line daniel",
                "Best regards,\nDaniel.",
            ),
            Case(
                "colon guarded by non-determiner converts; list commas kept",
                "we need the following colon budget comma timeline comma and owners",
                "We need the following: budget, timeline, and owners.",
            ),
            Case(
                "times and units are untouched",
                "the meeting is at 3 pm tomorrow",
                "The meeting is at 3 pm tomorrow.",
            ),
            Case(
                "alphanumeric tokens like q3 survive, could-question gets question mark",
                "could you send over the q3 numbers before our sync",
                "Could you send over the q3 numbers before our sync?",
            ),
            Case(
                "utterance-initial I mean is content, not a correction marker",
                "I mean the client meeting is on wednesday",
                "I mean the client meeting is on wednesday.",
            ),
            // ---------------------------------------------------- notes
            Case(
                "shopping list without terminal flag gets no period",
                "buy eggs comma bread comma and coffee",
                "Buy eggs, bread, and coffee",
                terminal = false,
            ),
            Case(
                "note headline with colon, no terminal flag",
                "idea colon voice keyboard with offline models",
                "Idea: voice keyboard with offline models",
                terminal = false,
            ),
            Case(
                "reminder sentence",
                "remember to call the dentist tomorrow morning",
                "Remember to call the dentist tomorrow morning.",
            ),
            Case(
                "number words that do not repeat are kept",
                "the wifi password is hunter two",
                "The wifi password is hunter two.",
            ),
            Case(
                "scratch that aligns on the repeated determiner",
                "pick up the dry cleaning scratch that the groceries",
                "Pick up the groceries.",
            ),
            Case(
                "multi-line note capitalizes each line",
                "meeting notes new line first item discuss hiring new line second item budget review",
                "Meeting notes\nFirst item discuss hiring\nSecond item budget review.",
            ),
            // ---------------------------------------------------- numbers, addresses
            Case(
                "spelled-out time survives",
                "my flight lands at seven thirty in the evening",
                "My flight lands at seven thirty in the evening.",
            ),
            Case(
                "money amounts survive",
                "the total comes to forty five dollars and sixty cents",
                "The total comes to forty five dollars and sixty cents.",
            ),
            Case(
                "street address with non-repeating number words survives",
                "she lives at one hundred twenty two elm street",
                "She lives at one hundred twenty two elm street.",
            ),
            Case(
                "digit sequence with no immediate repeats survives",
                "the invoice number is nine eight seven six five",
                "The invoice number is nine eight seven six five.",
            ),
            Case(
                "no wait aligns on repeated preposition around times",
                "let's meet at noon no wait at one",
                "Let's meet at one.",
            ),
            Case(
                // VB-203 tradeoff (VB-QA-01 fix): number words are exempt from
                // stutter collapse so digit sequences survive; a genuine "two two"
                // stutter is kept — when uncertain, keep the user's words.
                "adjacent identical number words are preserved",
                "the recipe needs two two cups of flour",
                "The recipe needs two two cups of flour.",
            ),
            Case(
                "non-adjacent repeated number words survive",
                "his room number is one oh one",
                "His room number is one oh one.",
            ),
            Case(
                "address with unit letters survives",
                "ship it to twelve maple avenue apartment four b",
                "Ship it to twelve maple avenue apartment four b.",
            ),
            // ---------------------------------------------------- questions
            Case(
                "wh-question gets terminal question mark (VB-QA-02 fixed)",
                "what time does the store close",
                "What time does the store close?",
            ),
            Case(
                "explicit spoken question mark always works",
                "do you want the window seat or the aisle question mark",
                "Do you want the window seat or the aisle?",
            ),
            // ---------------------------------------------------- corrections
            Case(
                "scratch that aligns weekdays by category",
                "the demo is on tuesday scratch that wednesday",
                "The demo is on wednesday.",
            ),
            Case(
                "actually no resolves a month swap (VB-QA-03 fixed)",
                "book the flight for may actually no june",
                "Book the flight for june.",
            ),
            Case(
                "weak sorry marker without alignment keeps everything",
                "invite tom sorry tim to the retro",
                "Invite tom sorry tim to the retro.",
            ),
            Case(
                "you know is kept with default (non-aggressive) options",
                "we should hire more engineers you know to move faster",
                "We should hire more engineers you know to move faster.",
            ),
            // ---------------------------------------------------- spoken punctuation
            Case(
                "open and close quote wrap the quoted span, no period after closing quote",
                "she said open quote I'll be late close quote",
                "She said \"I'll be late\"",
            ),
            Case(
                "hashtag guarded by determiner stays a word",
                "use the hashtag launch day",
                "Use the hashtag launch day.",
            ),
            Case(
                "at sign converts (rendering puts a space before it)",
                "email me at john at sign gmail dot com",
                "Email me at john @gmail dot com.",
            ),
            Case(
                "dash after non-determiner converts",
                "wrap up dash we ship on friday",
                "Wrap up - we ship on friday.",
            ),
            Case(
                "ellipsis does not force capitalization after it",
                "one more thing ellipsis the demo needs music",
                "One more thing... the demo needs music.",
            ),
            // ---------------------------------------------------- search fields (VB-706)
            Case(
                "search query gets no terminal period",
                "coffee shops near me open now",
                "Coffee shops near me open now",
                fieldKind = FieldKind.SEARCH,
            ),
            Case(
                "search query with number words gets no terminal period",
                "wireless earbuds under one hundred dollars",
                "Wireless earbuds under one hundred dollars",
                fieldKind = FieldKind.SEARCH,
            ),
            // ---------------------------------------------------- fillers and i-casing
            Case(
                "i contractions capitalized after filler removal",
                "uh i'm not sure i'll make it um maybe",
                "I'm not sure I'll make it maybe.",
            ),
            Case(
                "hmm is a hesitation",
                "hmm let me think about it",
                "Let me think about it.",
            ),
            Case(
                "er removed twice in one utterance",
                "er the er meeting moved",
                "The meeting moved.",
            ),
            Case(
                "mid-conversation utterance is not capitalized after comma-ish context",
                "and then we can review it together",
                "and then we can review it together.",
                preceding = "I'll draft the doc,",
            ),
            Case(
                "utterance after sentence end in preceding text is capitalized",
                "sounds good to me",
                "Sounds good to me.",
                preceding = "We ship friday. ",
            ),
        )
    }
}
