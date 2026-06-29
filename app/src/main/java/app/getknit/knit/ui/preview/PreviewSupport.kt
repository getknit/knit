package app.getknit.knit.ui.preview

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.getknit.knit.ui.theme.KnitTheme

/**
 * A fixed "now" for previews so relative-timestamp rendering (e.g. "5m", "1h") is deterministic
 * instead of drifting with the wall clock. Sample message times are expressed relative to this.
 */
const val PREVIEW_NOW: Long = 1_700_000_000_000L

/**
 * Shared wrapper for `@Preview` composables. Wraps [content] in [KnitTheme] (so previews pick up the
 * coral brand palette and typography instead of bare Material defaults) and a [Surface] (so the
 * preview gets a themed background rather than a transparent one).
 *
 * `KnitTheme` uses `dynamicColor = false`, so a preview needs no `Context` — the static
 * light/dark color schemes apply directly. Preview functions across the UI package are declared
 * top-level and non-private (never `private`) so detekt's default `UnusedPrivateMember` rule doesn't
 * flag them as unused — the preview renderer invokes them reflectively, never from code.
 */
@Composable
fun KnitPreview(content: @Composable () -> Unit) {
    KnitTheme {
        Surface {
            content()
        }
    }
}
