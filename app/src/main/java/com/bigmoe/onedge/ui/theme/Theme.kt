package com.bigmoe.onedge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Colours the Material scheme has no slot for: the chat bubbles, code blocks and the accent. */
data class AppColors(
    val userMessage: Color,
    val onUserMessage: Color,
    val aiMessage: Color,
    val onAiMessage: Color,
    val codeBlock: Color,
    val onCodeBlock: Color,
    val accent: Color
)

val LocalAppColors = staticCompositionLocalOf {
    val p = PalettePresets.byId(PalettePresets.DEFAULT_LIGHT_ID)!!
    appColorsFrom(p)
}

/** Shortcut: `AppTheme.colors.userMessage`. */
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

private fun Int.c(): Color = Color(this)

fun appColorsFrom(p: PaletteSpec) = AppColors(
    userMessage = p.userMessage.c(),
    onUserMessage = ColorMath.onColor(p.userMessage).c(),
    aiMessage = p.aiMessage.c(),
    onAiMessage = ColorMath.onColor(p.aiMessage).c(),
    codeBlock = p.codeBlock.c(),
    onCodeBlock = ColorMath.onColor(p.codeBlock).c(),
    accent = p.accent.c()
)

/**
 * Builds the Material scheme from the palette's nine colours. The remaining Material roles are derived
 * (containers by blending towards the background, "on" colours by contrast), so a custom palette with only
 * nine inputs still produces a complete, readable scheme.
 */
fun colorSchemeFrom(p: PaletteSpec): ColorScheme {
    val onPrimary = ColorMath.onColor(p.primary)
    val onSecondary = ColorMath.onColor(p.secondary)
    val onAccent = ColorMath.onColor(p.accent)
    val surfaceVariant = ColorMath.blend(p.surface, p.text, 0.08f)
    val primaryContainer = ColorMath.blend(p.background, p.primary, if (p.dark) 0.35f else 0.22f)
    val secondaryContainer = ColorMath.blend(p.background, p.secondary, if (p.dark) 0.35f else 0.25f)
    val tertiaryContainer = ColorMath.blend(p.background, p.accent, if (p.dark) 0.35f else 0.25f)
    val errorColor = if (p.dark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
    val errorContainer = if (p.dark) 0xFF93000A.toInt() else 0xFFFFDAD6.toInt()
    val scheme = if (p.dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = p.primary.c(),
        onPrimary = onPrimary.c(),
        primaryContainer = primaryContainer.c(),
        onPrimaryContainer = ColorMath.onColor(primaryContainer).c(),
        secondary = p.secondary.c(),
        onSecondary = onSecondary.c(),
        secondaryContainer = secondaryContainer.c(),
        onSecondaryContainer = ColorMath.onColor(secondaryContainer).c(),
        tertiary = p.accent.c(),
        onTertiary = onAccent.c(),
        tertiaryContainer = tertiaryContainer.c(),
        onTertiaryContainer = ColorMath.onColor(tertiaryContainer).c(),
        error = errorColor.c(),
        onError = ColorMath.onColor(errorColor).c(),
        errorContainer = errorContainer.c(),
        onErrorContainer = ColorMath.onColor(errorContainer).c(),
        background = p.background.c(),
        onBackground = p.text.c(),
        surface = p.surface.c(),
        onSurface = p.text.c(),
        surfaceVariant = surfaceVariant.c(),
        onSurfaceVariant = ColorMath.blend(p.text, p.background, 0.25f).c(),
        outline = ColorMath.blend(p.background, p.text, 0.35f).c(),
        outlineVariant = ColorMath.blend(p.background, p.text, 0.16f).c(),
        surfaceTint = p.primary.c(),
        inverseSurface = p.text.c(),
        inverseOnSurface = p.background.c(),
        inversePrimary = p.secondary.c(),
        scrim = Color.Black,
        surfaceBright = ColorMath.blend(p.surface, p.text, 0.04f).c(),
        surfaceDim = ColorMath.blend(p.surface, p.background, 0.5f).c(),
        surfaceContainerLowest = ColorMath.blend(p.background, p.surface, 0.5f).c(),
        surfaceContainerLow = ColorMath.blend(p.background, p.surface, 0.8f).c(),
        surfaceContainer = p.surface.c(),
        surfaceContainerHigh = ColorMath.blend(p.surface, p.text, 0.05f).c(),
        surfaceContainerHighest = surfaceVariant.c()
    )
}

@Composable
fun BigMoeTheme(
    palette: PaletteSpec,
    content: @Composable () -> Unit
) {
    // enableEdgeToEdge() picks system-bar icon colours from the SYSTEM dark mode; the palette is chosen
    // independently, so keep the icons readable against the palette's own background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !palette.dark
                controller.isAppearanceLightNavigationBars = !palette.dark
            }
        }
    }
    CompositionLocalProvider(LocalAppColors provides appColorsFrom(palette)) {
        MaterialTheme(
            colorScheme = colorSchemeFrom(palette),
            typography = Typography,
            content = content
        )
    }
}

/** Resolves the persisted theme id (custom or preset) and applies it. */
@Composable
fun BigMoeTheme(
    themeId: String,
    customPalettesJson: String,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val palette = PaletteStore.resolve(themeId, PaletteStore.parse(customPalettesJson), systemDark)
    BigMoeTheme(palette = palette, content = content)
}
