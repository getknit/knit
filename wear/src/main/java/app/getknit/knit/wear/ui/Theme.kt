package app.getknit.knit.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import app.getknit.knit.wear.Tone

/**
 * Knit's coral on the watch, from :app's dark scheme (CoralPrimaryDark and friends), with :app's positive
 * green as the tertiary. Used only when the watch has no dynamic colours on (Wear OS 5+ follows the face).
 */
@Suppress("MagicNumber") // ARGB literals, copied from :app's ui/theme/Color.kt
private val KnitWearColors =
    ColorScheme(
        primary = Color(0xFFFFB5A0),
        primaryDim = Color(0xFFE89C87),
        primaryContainer = Color(0xFF7E2D17),
        onPrimary = Color(0xFF5F1500),
        onPrimaryContainer = Color(0xFFFFDBD1),
        secondary = Color(0xFFC5C4D2),
        secondaryDim = Color(0xFFAAA9B6),
        secondaryContainer = Color(0xFF444450),
        onSecondary = Color(0xFF2D2E39),
        onSecondaryContainer = Color(0xFFE0E0EC),
        tertiary = Color(0xFF72DAA0),
        tertiaryDim = Color(0xFF5ABF88),
        tertiaryContainer = Color(0xFF005230),
        onTertiary = Color(0xFF00391F),
        onTertiaryContainer = Color(0xFF8FF7BD),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

@Composable
fun KnitWearTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(colorScheme = dynamicColorScheme(context) ?: KnitWearColors, content = content)
}

/**
 * A [Tone] on screen: the semantic three (up, weak, down) stay green, amber and red whatever the theme — they
 * mean the same on every face — while "calm" and "muted" follow the scheme's primary and outline.
 */
@Composable
fun Tone.color(): Color =
    when (this) {
        Tone.Good, Tone.Warn -> Color(argb)
        Tone.Bad -> MaterialTheme.colorScheme.error
        Tone.Calm -> MaterialTheme.colorScheme.primary
        Tone.Muted -> MaterialTheme.colorScheme.outline
    }
