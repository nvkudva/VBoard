package com.vboard.core.correct

import com.vboard.core.text.FieldKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attribution and per-change revert.
 *
 * A fix is two different kinds of change wearing one button. Fixing "teh" and
 * closing up a double space is housekeeping nobody needs to be consulted about;
 * swapping a word the user chose is an opinion, and opinions get attributed and
 * get their own undo.
 */
class FixEditsTest {

    private val fixer = TextFixer()

    private fun editsFor(original: String, rules: String, final: String) =
        FixAttribution.attribute(original, rules, final)

    // ------------------------------------------------------------- attribution

    @Test
    fun `rules-only changes are all mechanical`() = runTest {
        val result = fixer.fix("i saw the the dog yesterday", FieldKind.TEXT, refiner = null)
        assertTrue(result.edits.isNotEmpty())
        assertEquals(0, result.editorialCount, "edits: ${result.edits}")
        assertTrue(result.mechanicalCount > 0)
    }

    @Test
    fun `a word the model swapped is editorial`() = runTest {
        val result = fixer.fix(
            "i went too the stor yesterday",
            FieldKind.TEXT,
            SmartRefiner { SmartOutput.of("I went to the store yesterday.") },
        )
        assertEquals("I went to the store yesterday.", result.correctedText())
        assertTrue(result.editorialCount > 0, "edits: ${result.edits}")
        val editorial = result.edits.filter { it.kind == EditKind.EDITORIAL }
        assertTrue(
            editorial.any { it.beforeText() == "stor" && it.afterText() == "store" },
            "expected the stor->store swap to be attributed to the model: $editorial",
        )
    }

    @Test
    fun `casing the rules already applied stays mechanical even when the model agrees`() = runTest {
        val result = fixer.fix(
            "the meeting is at noon",
            FieldKind.TEXT,
            SmartRefiner { SmartOutput.of("The meeting is at noon.") },
        )
        // The rules produced exactly this, so nothing here is the model's doing.
        assertEquals(0, result.editorialCount, "edits: ${result.edits}")
    }

    @Test
    fun `edits address the corrected text`() {
        val original = "i saw teh dog"
        val rules = "I saw teh dog."
        val final = "I saw the dog."
        val edits = editsFor(original, rules, final)
        for (edit in edits) {
            assertEquals(
                edit.afterText(),
                final.substring(edit.start, edit.end),
                "edit span does not match the corrected text: $edit",
            )
        }
    }

    @Test
    fun `identical text produces no edits`() {
        assertTrue(editsFor("all fine.", "all fine.", "all fine.").isEmpty())
    }

    @Test
    fun `a very long change is reported coarsely rather than exhaustively`() {
        val original = (1..600).joinToString(" ") { "alpha$it" }
        val final = (1..600).joinToString(" ") { "beta$it" }
        val edits = editsFor(original, original, final)
        assertEquals(1, edits.size, "expected one coarse edit, got ${edits.size}")
    }

    // ---------------------------------------------------------- per-edit revert

    @Test
    fun `reverting one edit restores just that span`() {
        val final = "I went to the store yesterday."
        val edit = FixEdit(EditKind.EDITORIAL, 14, 19, "stor", "store")
        assertEquals("I went to the stor yesterday.", FixEdits.revert(final, edit))
    }

    @Test
    fun `reverting an edit whose span has moved is refused`() {
        val edit = FixEdit(EditKind.EDITORIAL, 14, 19, "stor", "store")
        assertNull(FixEdits.revert("I went to the shop yesterday.", edit))
        assertNull(FixEdits.revert("short", edit))
    }

    @Test
    fun `reverting every edit in reverse gets the original back`() {
        val original = "i went too the stor yesterday"
        val final = "I went to the store yesterday."
        var edits = editsFor(original, "I went too the stor yesterday.", final)
        assertTrue(edits.isNotEmpty())
        var text = final
        // Reverting from the end backwards needs no rebasing at all.
        for (edit in edits.sortedByDescending { it.start }) {
            text = assertNotNull(FixEdits.revert(text, edit), "could not revert $edit")
        }
        assertEquals(original, text)
        // ...and rebasing gives the same answer front to back.
        text = final
        while (edits.isNotEmpty()) {
            val next = edits.first()
            text = assertNotNull(FixEdits.revert(text, next))
            edits = FixEdits.rebase(edits, next)
        }
        assertEquals(original, text)
    }

    @Test
    fun `rebase drops edits that overlap the reverted span`() {
        val reverted = FixEdit(EditKind.EDITORIAL, 4, 9, "abc", "abcde")
        val overlapping = FixEdit(EditKind.MECHANICAL, 6, 8, "x", "yz")
        val after = FixEdit(EditKind.MECHANICAL, 12, 14, "p", "qr")
        val rebased = FixEdits.rebase(listOf(reverted, overlapping, after), reverted)
        assertEquals(1, rebased.size)
        assertEquals(10, rebased.single().start)
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `edit toString carries no content and no offsets`() {
        val edit = FixEdit(EditKind.EDITORIAL, 3, 8, "acme", "Acme Corp")
        val printed = edit.toString()
        assertFalse("acme" in printed.lowercase(), printed)
        assertFalse("3" in printed, printed)
        assertFalse("8" in printed, printed)
    }
}
