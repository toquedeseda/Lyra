package com.lyra.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La misma paleta que el móvil y la web: negro cálido, blanco hueso, grises cálidos y el rojo
 * apagado del corazón. El color de los detalles ([Accent]) se cambia en Ajustes.
 */
object LyraColors {
    private val accent = mutableStateOf(Color(0xFFE8E6DF))

    fun setAccent(color: Color) {
        accent.value = color
    }

    val Background = Color(0xFF0A0A0B)
    val Surface = Color(0xFF141416)
    val SurfaceHigh = Color(0xFF1C1C1F)
    val SurfaceHigher = Color(0xFF26262A)
    val Hover = Color(0x14F3F3F1)
    val Border = Color(0x14F3F3F1)
    val TextPrimary = Color(0xFFF3F3F1)
    val TextSecondary = Color(0xFF9C9C96)
    val TextTertiary = Color(0xFF6E6E68)
    val Accent: Color get() = accent.value
    val OnAccent = Color(0xFF0A0A0B)
    val Like = Color(0xFFC45C5C)
}

private fun outfit(weight: Int) = Font(
    "fonts/outfit.ttf",
    FontWeight(weight),
    FontStyle.Normal,
    FontVariation.Settings(FontVariation.weight(weight)),
)

/** Outfit: la letra de toda la interfaz. */
val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600), outfit(700), outfit(800))

/** Instrument Serif: los títulos grandes, como en el móvil y la web. */
val InstrumentSerif = FontFamily(
    Font("fonts/instrument_serif.ttf", FontWeight.Normal, FontStyle.Normal),
    Font("fonts/instrument_serif_italic.ttf", FontWeight.Normal, FontStyle.Italic),
)

private fun scheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = LyraColors.OnAccent,
    primaryContainer = LyraColors.SurfaceHigh,
    onPrimaryContainer = LyraColors.TextPrimary,
    secondary = LyraColors.TextSecondary,
    onSecondary = LyraColors.OnAccent,
    secondaryContainer = LyraColors.SurfaceHigh,
    onSecondaryContainer = LyraColors.TextPrimary,
    tertiary = LyraColors.TextSecondary,
    background = LyraColors.Background,
    onBackground = LyraColors.TextPrimary,
    surface = LyraColors.Surface,
    onSurface = LyraColors.TextPrimary,
    surfaceVariant = LyraColors.SurfaceHigh,
    onSurfaceVariant = LyraColors.TextSecondary,
    surfaceContainerLowest = LyraColors.Background,
    surfaceContainerLow = LyraColors.Surface,
    surfaceContainer = LyraColors.SurfaceHigh,
    surfaceContainerHigh = LyraColors.SurfaceHigh,
    surfaceContainerHighest = LyraColors.SurfaceHigher,
    outline = LyraColors.Border,
    outlineVariant = LyraColors.Border,
    error = LyraColors.Like,
    onError = LyraColors.TextPrimary,
    inverseSurface = accent,
    inverseOnSurface = LyraColors.OnAccent,
    scrim = Color(0xCC000000),
)

private val serif = TextStyle(fontFamily = InstrumentSerif, fontWeight = FontWeight.Normal)
private val sans = TextStyle(fontFamily = Outfit)

val LyraTypography = Typography(
    displayLarge = serif.copy(fontSize = 72.sp, lineHeight = 72.sp, letterSpacing = (-1.2).sp),
    displayMedium = serif.copy(fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-0.8).sp),
    displaySmall = serif.copy(fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.5).sp),
    headlineLarge = serif.copy(fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineMedium = serif.copy(fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.3).sp),
    headlineSmall = sans.copy(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = sans.copy(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = sans.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = sans.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = sans.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodyMedium = sans.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    bodySmall = sans.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = sans.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = sans.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = sans.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun LyraTheme(content: @Composable () -> Unit) {
    val accent = LyraColors.Accent
    val colorScheme = remember(accent) { scheme(accent) }
    MaterialTheme(colorScheme = colorScheme, typography = LyraTypography, shapes = shapes) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 250,
                unhoverColor = Color(0x33F3F3F1),
                hoverColor = Color(0x66F3F3F1),
            ),
            LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}
