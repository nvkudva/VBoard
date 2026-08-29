package com.vboard.app.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * Design tokens from docs/DESIGN_SPEC.md §1. Two fixed palettes (light/dark);
 * dynamic color only remaps the accent group when enabled.
 */
data class KeyboardTheme(
    val isDark: Boolean,
    val bgKeyboard: Int,
    val keySurface: Int,
    val keySurfaceAlt: Int,
    val keyText: Int,
    val keyTextSecondary: Int,
    val accent: Int,
    val onAccent: Int,
    val suggestionBg: Int,
    val suggestionText: Int,
    val suggestionAutocorrect: Int,
    val keyPressed: Int,
    val keyPressedAlt: Int,
    val error: Int,
    val onError: Int,
    val micPulse: Int,
    val transcriptPartial: Int,
    val transcriptFinal: Int,
    val popupSurface: Int,
) {
    companion object {
        val LIGHT = KeyboardTheme(
            isDark = false,
            bgKeyboard = 0xFFECEEF1.toInt(),
            keySurface = 0xFFFFFFFF.toInt(),
            keySurfaceAlt = 0xFFD9DDE3.toInt(),
            keyText = 0xFF1B1C1E.toInt(),
            keyTextSecondary = 0xFF5F6368.toInt(),
            accent = 0xFF007A70.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            suggestionBg = 0xFFF4F6F8.toInt(),
            suggestionText = 0xFF3C4043.toInt(),
            suggestionAutocorrect = 0xFF007A70.toInt(),
            keyPressed = 0xFFC7CCD4.toInt(),
            keyPressedAlt = 0xFFB4BAC4.toInt(),
            error = 0xFFB3261E.toInt(),
            onError = 0xFFFFFFFF.toInt(),
            micPulse = 0xFF00A894.toInt(),
            transcriptPartial = 0xFF8A9095.toInt(),
            transcriptFinal = 0xFF1B1C1E.toInt(),
            popupSurface = 0xFFFFFFFF.toInt(),
        )

        val DARK = KeyboardTheme(
            isDark = true,
            bgKeyboard = 0xFF131417.toInt(),
            keySurface = 0xFF24262B.toInt(),
            keySurfaceAlt = 0xFF33363D.toInt(),
            keyText = 0xFFE8EAED.toInt(),
            keyTextSecondary = 0xFF9AA0A6.toInt(),
            accent = 0xFF35D0C2.toInt(),
            onAccent = 0xFF00332E.toInt(),
            suggestionBg = 0xFF1B1D21.toInt(),
            suggestionText = 0xFFDADCE0.toInt(),
            suggestionAutocorrect = 0xFF35D0C2.toInt(),
            keyPressed = 0xFF3F434B.toInt(),
            keyPressedAlt = 0xFF4A4F58.toInt(),
            error = 0xFFF2B8B5.toInt(),
            onError = 0xFF601410.toInt(),
            micPulse = 0xFF35D0C2.toInt(),
            transcriptPartial = 0xFF7C828A.toInt(),
            transcriptFinal = 0xFFE8EAED.toInt(),
            popupSurface = 0xFF2E3138.toInt(),
        )

        fun forContext(context: Context, override: ThemeMode): KeyboardTheme {
            val dark = when (override) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> {
                    val mask = context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
                    mask == Configuration.UI_MODE_NIGHT_YES
                }
            }
            return if (dark) DARK else LIGHT
        }
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Static dp metrics from DESIGN_SPEC §2 (converted to px by views). */
object KeyboardMetrics {
    const val ROW_HEIGHT_FRACTION = 0.088f
    const val ROW_HEIGHT_MIN_DP = 48f
    const val ROW_HEIGHT_MAX_DP = 58f
    const val ROW_HEIGHT_LANDSCAPE_DP = 44f
    const val KEY_GAP_H_DP = 5f
    const val KEY_GAP_V_DP = 8f
    const val SIDE_PADDING_DP = 4f
    const val TOP_PADDING_DP = 6f
    const val BOTTOM_PADDING_DP = 8f
    const val STRIP_HEIGHT_DP = 44f
    const val KEY_RADIUS_DP = 8f
    const val SPACE_RADIUS_DP = 12f
    const val VOICE_BAR_HEIGHT_DP = 120f

    /**
     * The "+ bottom inset" DESIGN_SPEC §4.1 puts under the 120dp voice bar. The
     * orb sits in the bottom 56dp control row and its amplitude halo grows to
     * 88dp across, so without this the halo — and the bottom of the orb itself —
     * was clipped by the view's edge.
     */
    const val VOICE_BAR_BOTTOM_INSET_DP = 20f
    const val KEY_LABEL_SP = 22f
    const val KEY_LABEL_SMALL_SP = 16f
    const val HINT_SP = 11f
    const val SPACEBAR_LABEL_SP = 13f
    const val SUGGESTION_SP = 16f
    const val POPUP_CHAR_SP = 26f
}

fun Int.withAlphaFraction(fraction: Float): Int =
    Color.argb((fraction * 255).toInt().coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))
