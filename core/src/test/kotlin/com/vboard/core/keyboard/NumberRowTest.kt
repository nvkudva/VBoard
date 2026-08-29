package com.vboard.core.keyboard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Snapshot of the number row's data, and the arithmetic that decides whether it
 * fits. The 40dp growth budget is the reason the compact factor exists, so it is
 * asserted at every point of the row-height clamp rather than at one convenient
 * one — including landscape, where there is the least room to give.
 *
 * The dp values mirror `KeyboardMetrics` in
 * app/src/main/kotlin/com/vboard/app/keyboard/KeyboardTheme.kt; keep them in step.
 */
class NumberRowTest {

    private companion object {
        const val ROW_HEIGHT_MIN_DP = 48f
        const val ROW_HEIGHT_MAX_DP = 58f
        const val ROW_HEIGHT_LANDSCAPE_DP = 44f
        const val KEY_GAP_V_DP = 8f
        const val TOP_PADDING_DP = 6f
        const val BOTTOM_PADDING_DP = 8f
    }

    // ---------------------------------------------------------------- the row

    @Test
    fun `the row is the ten digits in Gboard order`() {
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            NumberRow.KEYS.map { it.digit },
        )
    }

    @Test
    fun `long-press alternates match the Gboard set`() {
        val expected = mapOf(
            "1" to listOf("¹", "½"),
            "2" to listOf("²"),
            "3" to listOf("³", "¾"),
            "4" to listOf("⁴"),
            "5" to listOf("⁵", "⅝"),
            "6" to listOf("⁶"),
            "7" to listOf("⁷"),
            "8" to listOf("⁸"),
            "9" to listOf("⁹"),
            "0" to listOf("⁰", "ⁿ", "∅"),
        )
        assertEquals(expected, NumberRow.KEYS.associate { it.digit to it.alternates })
    }

    @Test
    fun `every alternate is a single visible character and never the digit itself`() {
        for (key in NumberRow.KEYS) {
            for (alternate in key.alternates) {
                assertEquals(1, alternate.length, "\"$alternate\" is not one char")
                assertTrue(alternate != key.digit, "${key.digit} repeats itself as an alternate")
                assertTrue(alternate.first().isDefined(), "\"$alternate\" is not a defined code point")
            }
        }
    }

    @Test
    fun `alternates are unique across the whole row`() {
        val all = NumberRow.KEYS.flatMap { it.alternates }
        assertEquals(all.size, all.toSet().size)
    }

    // ------------------------------------------------------------- the height

    @Test
    fun `four rows keep the full row height`() {
        assertEquals(58f, KeyboardHeights.rowHeightDp(58f, 4))
        assertEquals(44f, KeyboardHeights.rowHeightDp(44f, 4))
    }

    @Test
    fun `five rows shrink by the compact factor`() {
        assertEquals(58f * KeyboardHeights.COMPACT_ROW_FACTOR, KeyboardHeights.rowHeightDp(58f, 5))
    }

    @Test
    fun `adding the number row costs no more than 40dp at any clamp point`() {
        val bases = listOf(
            ROW_HEIGHT_MIN_DP,
            ROW_HEIGHT_MAX_DP,
            ROW_HEIGHT_LANDSCAPE_DP,
            // Every whole dp across the portrait clamp, so a future tweak to the
            // clamp cannot slip past a hand-picked sample.
            *(ROW_HEIGHT_MIN_DP.toInt()..ROW_HEIGHT_MAX_DP.toInt())
                .map { it.toFloat() }
                .toTypedArray(),
        )
        for (base in bases) {
            val four = total(4, base)
            val five = total(5, base)
            val growth = five - four
            assertTrue(
                growth <= KeyboardHeights.MAX_NUMBER_ROW_GROWTH_DP,
                "base ${base}dp: the number row added ${growth}dp",
            )
            assertTrue(growth > 0f, "base ${base}dp: the number row added nothing")
        }
    }

    @Test
    fun `five landscape rows still fit a short landscape window`() {
        // A 360dp-tall landscape window (the shortest phone form factor we target)
        // minus the 44dp suggestion strip has to hold the whole key area.
        val keyArea = total(5, ROW_HEIGHT_LANDSCAPE_DP)
        assertTrue(keyArea + 44f <= 360f, "landscape keyboard is ${keyArea}dp of key area")
    }

    @Test
    fun `a compact five-row key is still a comfortable touch target`() {
        // Material's 48dp minimum applies to the target, which includes the 8dp
        // inter-row gap; the drawn key itself may be a little under.
        val shortest = KeyboardHeights.rowHeightDp(ROW_HEIGHT_LANDSCAPE_DP, 5)
        assertTrue(shortest + KEY_GAP_V_DP >= 48f, "shortest touch target is ${shortest + KEY_GAP_V_DP}dp")
    }

    @Test
    fun `an empty layout is just its padding`() {
        assertEquals(TOP_PADDING_DP + BOTTOM_PADDING_DP, total(0, 58f))
    }

    private fun total(rows: Int, base: Float) = KeyboardHeights.totalHeightDp(
        rowCount = rows,
        baseRowHeightDp = base,
        rowGapDp = KEY_GAP_V_DP,
        topPaddingDp = TOP_PADDING_DP,
        bottomPaddingDp = BOTTOM_PADDING_DP,
    )
}
