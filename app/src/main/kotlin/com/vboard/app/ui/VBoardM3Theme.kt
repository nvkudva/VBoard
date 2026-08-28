package com.vboard.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vboard.app.keyboard.ThemeMode

/**
 * Material 3 theme for VBoard's Compose surfaces (onboarding + settings),
 * built from the Resonance Teal brand palette in docs/DESIGN_SPEC.md §1.3.
 * Dynamic color is intentionally OFF: the default identity is VBoard's own.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF007A70),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7F0E8),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF06201C),
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFF8FAF9),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7977),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF35D0C2),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF9FF2E7),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    background = Color(0xFF131417),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF131417),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C6),
    outline = Color(0xFF89938F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun VBoardM3Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** Resolves the user's theme preference to a concrete dark/light choice. */
@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}
