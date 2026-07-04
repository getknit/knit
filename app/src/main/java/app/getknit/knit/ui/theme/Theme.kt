package app.getknit.knit.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme =
    lightColorScheme(
        primary = CoralPrimaryLight,
        onPrimary = CoralOnPrimaryLight,
        primaryContainer = CoralPrimaryContainerLight,
        onPrimaryContainer = CoralOnPrimaryContainerLight,
        secondary = CoralSecondaryLight,
        onSecondary = CoralOnSecondaryLight,
        secondaryContainer = CoralSecondaryContainerLight,
        onSecondaryContainer = CoralOnSecondaryContainerLight,
        tertiary = CoralTertiaryLight,
        onTertiary = CoralOnTertiaryLight,
        tertiaryContainer = CoralTertiaryContainerLight,
        onTertiaryContainer = CoralOnTertiaryContainerLight,
        background = BackgroundLight,
        onBackground = OnBackgroundLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        error = ErrorLight,
        onError = OnErrorLight,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = CoralPrimaryDark,
        onPrimary = CoralOnPrimaryDark,
        primaryContainer = CoralPrimaryContainerDark,
        onPrimaryContainer = CoralOnPrimaryContainerDark,
        secondary = CoralSecondaryDark,
        onSecondary = CoralOnSecondaryDark,
        secondaryContainer = CoralSecondaryContainerDark,
        onSecondaryContainer = CoralOnSecondaryContainerDark,
        tertiary = CoralTertiaryDark,
        onTertiary = CoralOnTertiaryDark,
        tertiaryContainer = CoralTertiaryContainerDark,
        onTertiaryContainer = CoralOnTertiaryContainerDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        error = ErrorDark,
        onError = OnErrorDark,
    )

@Composable
fun KnitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the Knit coral brand identity shows instead of the wallpaper palette.
    // Callers can opt into Material You on Android 12+.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
