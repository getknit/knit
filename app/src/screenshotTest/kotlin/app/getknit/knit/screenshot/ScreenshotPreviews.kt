package app.getknit.knit.screenshot

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

// Multipreview annotations for the Compose Preview Screenshot Testing plugin. Each `@PreviewTest` function
// renders once per `@Preview` here, so every subject is checked in both themes; `KnitPreview`'s `KnitTheme`
// follows `uiMode` through `isSystemInDarkTheme()`. A reference image is keyed by the function's name and the
// preview's name, so renaming either means regenerating with `updateDebugScreenshotTest`.
//
// Every subject is also rendered, light only, under the two pseudolocales the debug build generates
// (`isPseudoLocalesEnabled` in `app/build.gradle.kts`): `LongText` is en-XA, every string accented and padded to
// about 40 % longer than the English, and `Rtl` is ar-XB, the English mirrored and laid out right to left. So a
// layout meets long and right-to-left text the day it is written, before any translation exists. Only text from a
// string resource changes; a preview's sample data (names, message bodies) stays as written.
//
// Every preview names its locale. Layoutlib resolves one that names none to whichever locale's resources it finds,
// not to the default `values/`: with the pseudolocales generated, an unnamed Light rendered as en-XA, and with a
// translation in `values-<locale>/` it would render as that.

/** A component on its own, sized to its content: light, dark, long text and right to left. */
@Preview(name = "Light", showBackground = true, locale = ENGLISH)
@Preview(name = "Dark", showBackground = true, locale = ENGLISH, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "LongText", showBackground = true, locale = LONG_TEXT)
@Preview(name = "Rtl", showBackground = true, locale = RIGHT_TO_LEFT)
annotation class ComponentShots

/**
 * A whole screen on a fixed phone-sized canvas: light, dark, long text and right to left. A `Scaffold` fills
 * whatever it is given, so without a fixed size the image would depend on the renderer's default.
 */
@Preview(name = "Light", showBackground = true, device = SCREEN_SPEC, locale = ENGLISH)
@Preview(
    name = "Dark",
    showBackground = true,
    device = SCREEN_SPEC,
    locale = ENGLISH,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "LongText", showBackground = true, device = SCREEN_SPEC, locale = LONG_TEXT)
@Preview(name = "Rtl", showBackground = true, device = SCREEN_SPEC, locale = RIGHT_TO_LEFT)
annotation class ScreenShots

private const val SCREEN_SPEC = "spec:width=411dp,height=891dp"

/** The source language, `values/`: en-US matches no `values-en-r…` directory, so it can only fall back to it. */
const val ENGLISH = "en-rUS"

/** en-XA: the accented, lengthened pseudolocale. */
const val LONG_TEXT = "en-rXA"

/** ar-XB: the mirrored, right-to-left pseudolocale. */
const val RIGHT_TO_LEFT = "ar-rXB"
