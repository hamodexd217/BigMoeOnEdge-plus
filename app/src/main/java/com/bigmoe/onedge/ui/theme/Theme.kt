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
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.data.local.datastore.AppVisualStyle
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

val LocalAppVisualStyle = staticCompositionLocalOf { AppVisualStyle.SOLID }

val LocalAppColors = staticCompositionLocalOf {
    val p = PalettePresets.byId(PalettePresets.DEFAULT_LIGHT_ID)!!
    appColorsFrom(p)
}

/** Shortcut: `AppTheme.colors.userMessage`. */
object AppTheme {
    val visualStyle: AppVisualStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalAppVisualStyle.current

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
    visualStyle: AppVisualStyle = AppVisualStyle.SOLID,
    applySystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    // enableEdgeToEdge() picks system-bar icon colours from the SYSTEM dark mode; the palette is chosen
    // independently, so keep the icons readable against the palette's own background.
    val view = LocalView.current
    if (applySystemBars && !view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !palette.dark
                controller.isAppearanceLightNavigationBars = !palette.dark
            }
        }
    }
    CompositionLocalProvider(
        LocalAppColors provides appColorsFrom(palette),
        LocalAppVisualStyle provides visualStyle
    ) {
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
    visualStyle: AppVisualStyle = AppVisualStyle.SOLID,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val palette = PaletteStore.resolve(themeId, PaletteStore.parse(customPalettesJson), systemDark)
    BigMoeTheme(palette = palette, visualStyle = visualStyle, content = content)
}

// ---------------------------------------------------------------------------------------------------------
// Visual styles
//
//  SOLID        flat palette colours (the original look).
//  GRADIENT     opaque, softly tinted gradients. Every colour is pre-blended (no alpha stacking), so there is no
//               banding / muddy overlap and text contrast stays that of the base surface.
//  LIQUID_GLASS translucent "frost" over a backdrop that carries soft colour glows. No drop shadows: an Android
//               shadow under a translucent fill shows through it and draws the dark inner rectangles/halos that
//               made the old glass look broken. Depth comes from a top sheen and a light-catching edge instead.
// ---------------------------------------------------------------------------------------------------------

/** Paints the app canvas behind screens. Draw it once (AppNavigation does); screens above stay transparent. */
@Composable
fun appBackgroundModifier(): Modifier {
    val cs = MaterialTheme.colorScheme
    return when (AppTheme.visualStyle) {
        AppVisualStyle.SOLID -> Modifier.background(cs.background)
        AppVisualStyle.GRADIENT -> Modifier.background(
            Brush.linearGradient(
                listOf(
                    cs.background,
                    lerp(cs.background, cs.primary, if (cs.background.luminance() < 0.5f) 0.20f else 0.13f),
                    lerp(cs.background, cs.tertiary, if (cs.background.luminance() < 0.5f) 0.18f else 0.12f)
                )
            )
        )
        AppVisualStyle.LIQUID_GLASS -> {
            val primary = cs.primary
            val tertiary = cs.tertiary
            val secondary = cs.secondary
            val dark = cs.background.luminance() < 0.5f
            Modifier
                .background(cs.background)
                .drawBehind {
                    val reach = maxOf(size.width, size.height)
                    // Soft colour glows give the frosted panels something to sit on.
                    drawRect(
                        Brush.radialGradient(
                            listOf(primary.copy(alpha = if (dark) 0.34f else 0.26f), Color.Transparent),
                            center = Offset(size.width * 0.12f, size.height * 0.10f), radius = reach * 0.62f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(tertiary.copy(alpha = if (dark) 0.30f else 0.22f), Color.Transparent),
                            center = Offset(size.width * 0.92f, size.height * 0.88f), radius = reach * 0.58f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(secondary.copy(alpha = if (dark) 0.20f else 0.14f), Color.Transparent),
                            center = Offset(size.width * 0.82f, size.height * 0.16f), radius = reach * 0.36f
                        )
                    )
                }
        }
    }
}

/**
 * Reusable treatment for touchable surfaces (cards, bubbles, rows, the nav pill, the input bar).
 * Pair it with a Surface/Card whose own colour is Transparent, or use [AppCard].
 *
 * [strong] keeps the fill nearly opaque: used for chat bubbles, whose text colour was chosen against the solid colour.
 */
@Composable
fun appSurfaceModifier(
    shape: Shape = RoundedCornerShape(20.dp),
    base: Color = MaterialTheme.colorScheme.surface,
    strong: Boolean = false
): Modifier {
    val cs = MaterialTheme.colorScheme
    return when (AppTheme.visualStyle) {
        AppVisualStyle.SOLID -> Modifier.background(base, shape)
        AppVisualStyle.GRADIENT -> {
            val tint = if (strong) 0.08f else 0.11f
            val m = Modifier.background(
                Brush.linearGradient(listOf(lerp(base, cs.primary, tint), lerp(base, cs.tertiary, tint))), shape
            )
            if (strong) m else m.border(1.dp, cs.outlineVariant.copy(alpha = 0.65f), shape)
        }
        AppVisualStyle.LIQUID_GLASS -> {
            val dark = base.luminance() < 0.5f
            val fill = if (strong) 0.88f else if (dark) 0.52f else 0.60f
            val sheenTop = if (dark) 0.10f else 0.30f
            val edgeLit = if (dark) 0.30f else 0.75f
            val edgeDim = if (dark) 0.06f else 0.20f
            Modifier
                .background(
                    Brush.verticalGradient(listOf(base.copy(alpha = (fill + 0.06f).coerceAtMost(1f)), base.copy(alpha = fill - 0.06f))),
                    shape
                )
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = sheenTop), 0.45f to Color.Transparent), shape)
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = edgeLit), Color.White.copy(alpha = edgeDim), Color.White.copy(alpha = edgeLit * 0.55f))),
                    shape
                )
        }
    }
}

/**
 * Full-height side panel (the tablet chat history). Edge to edge with a flat shape and only a hairline on the
 * inner edge: rounded corners and shadows at the screen edge left visible seams under the status/nav bars.
 */
@Composable
fun appSidePanelModifier(base: Color = MaterialTheme.colorScheme.surface): Modifier {
    val cs = MaterialTheme.colorScheme
    return when (AppTheme.visualStyle) {
        AppVisualStyle.SOLID -> Modifier.background(base, RectangleShape)
        AppVisualStyle.GRADIENT -> Modifier
            .background(Brush.verticalGradient(listOf(lerp(base, cs.primary, 0.09f), lerp(base, cs.tertiary, 0.09f))))
            .innerEdgeLine(cs.outlineVariant.copy(alpha = 0.7f))
        AppVisualStyle.LIQUID_GLASS -> {
            val dark = base.luminance() < 0.5f
            Modifier
                .background(base.copy(alpha = if (dark) 0.46f else 0.52f))
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.07f else 0.22f), 0.35f to Color.Transparent))
                .innerEdgeLine(Color.White.copy(alpha = if (dark) 0.22f else 0.70f))
        }
    }
}

private fun Modifier.innerEdgeLine(color: Color): Modifier = drawWithContent {
    drawContent()
    val x = size.width - 0.5f
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
}

/**
 * The card used by every screen. SOLID keeps Material's filled card; the other styles route through
 * [appSurfaceModifier] so cards, rows, bubbles and the nav bar all share one recipe.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    base: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    outline: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val styled = AppTheme.visualStyle != AppVisualStyle.SOLID
    Card(
        modifier = modifier.then(if (styled) appSurfaceModifier(shape, base) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (styled) Color.Transparent else base),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (!styled && outline) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        content = content
    )
}
