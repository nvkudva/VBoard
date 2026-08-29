package com.vboard.core.keyboard

/**
 * The optional digit row that sits above QWERTY, and the row-height arithmetic
 * that keeps it from pushing the keyboard off the screen.
 *
 * This is data and arithmetic only — no Android types — so the app's layout
 * builder and view can be asserted on from a plain JVM test.
 */
object NumberRow {

    /** One digit key: the character it types and its long-press alternates. */
    data class DigitKey(val digit: String, val alternates: List<String>)

    /** Left to right, matching Gboard's number row and its long-press sets. */
    val KEYS: List<DigitKey> = listOf(
        DigitKey("1", listOf("¹", "½")),
        DigitKey("2", listOf("²")),
        DigitKey("3", listOf("³", "¾")),
        DigitKey("4", listOf("⁴")),
        DigitKey("5", listOf("⁵", "⅝")),
        DigitKey("6", listOf("⁶")),
        DigitKey("7", listOf("⁷")),
        DigitKey("8", listOf("⁸")),
        DigitKey("9", listOf("⁹")),
        DigitKey("0", listOf("⁰", "ⁿ", "∅")),
    )
}

/**
 * Row-height arithmetic shared by the keyboard view.
 *
 * With the number row on there are five rows instead of four, and at the top of
 * the portrait row-height clamp that would add ~50dp of keyboard. Rows are
 * scaled by [COMPACT_ROW_FACTOR] so total growth stays inside
 * [MAX_NUMBER_ROW_GROWTH_DP] at every point of the clamp, in both orientations.
 */
object KeyboardHeights {

    /**
     * 0.91, not the 0.92 the spec suggests: at the 58dp top of the portrait
     * clamp, 0.92 grows the keyboard by 42.8dp, over the 40dp budget
     * (5 x 58 x 0.92 - 4 x 58 + one extra 8dp row gap). 0.91 lands at 39.9dp.
     */
    const val COMPACT_ROW_FACTOR = 0.91f

    /** Row count at which the compact factor kicks in. */
    const val COMPACT_ROW_THRESHOLD = 5

    /** The budget the compact factor exists to respect. */
    const val MAX_NUMBER_ROW_GROWTH_DP = 40f

    /** Per-row height for a layout of [rowCount] rows, given the base height. */
    fun rowHeightDp(baseRowHeightDp: Float, rowCount: Int): Float =
        if (rowCount >= COMPACT_ROW_THRESHOLD) baseRowHeightDp * COMPACT_ROW_FACTOR else baseRowHeightDp

    /** Total drawn height of the key area, excluding the suggestion strip. */
    fun totalHeightDp(
        rowCount: Int,
        baseRowHeightDp: Float,
        rowGapDp: Float,
        topPaddingDp: Float,
        bottomPaddingDp: Float,
    ): Float {
        if (rowCount <= 0) return topPaddingDp + bottomPaddingDp
        val rowH = rowHeightDp(baseRowHeightDp, rowCount)
        return topPaddingDp + rowCount * rowH + (rowCount - 1) * rowGapDp + bottomPaddingDp
    }
}
