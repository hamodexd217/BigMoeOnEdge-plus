package com.bigmoe.onedge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

/**
 * Each text's paragraph direction follows its first strong character, so Arabic text in an English interface and English
 * text (setting descriptions that are not translated, model names, code) in an Arabic one both read correctly; a text
 * with no strong character (a bare number) uses the layout direction.
 */
fun Typography.forDirection(layout: LayoutDirection): Typography {
    val d = if (layout == LayoutDirection.Rtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr
    return copy(
        displayLarge = displayLarge.copy(textDirection = d),
        displayMedium = displayMedium.copy(textDirection = d),
        displaySmall = displaySmall.copy(textDirection = d),
        headlineLarge = headlineLarge.copy(textDirection = d),
        headlineMedium = headlineMedium.copy(textDirection = d),
        headlineSmall = headlineSmall.copy(textDirection = d),
        titleLarge = titleLarge.copy(textDirection = d),
        titleMedium = titleMedium.copy(textDirection = d),
        titleSmall = titleSmall.copy(textDirection = d),
        bodyLarge = bodyLarge.copy(textDirection = d),
        bodyMedium = bodyMedium.copy(textDirection = d),
        bodySmall = bodySmall.copy(textDirection = d),
        labelLarge = labelLarge.copy(textDirection = d),
        labelMedium = labelMedium.copy(textDirection = d),
        labelSmall = labelSmall.copy(textDirection = d)
    )
}
