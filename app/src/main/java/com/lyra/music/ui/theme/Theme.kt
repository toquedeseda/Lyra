package com.lyra.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyra.music.R
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Paleta "Lira'": negro cálido, blanco hueso y grises cálidos, y el rojo apagado del corazón de
 * "Me gusta". El color de los botones y detalles ([Accent]) se puede cambiar en Ajustes → Aspecto.
 */
object LyraColors {
    private val accent = mutableStateOf(Color(0xFFE8E6DF))

    /** Cambia el color de Lyra: todo lo que lo usa se repinta solo. */
    fun setAccent(color: Color) {
        accent.value = color
    }

    val Background = Color(0xFF0A0A0B)
    val Surface = Color(0xFF141416)
    val SurfaceHigh = Color(0xFF1C1C1F)   // "elevated"
    val SurfaceHigher = Color(0xFF26262A)
    val Card = Color(0xFF141416)
    val Divider = Color(0x14F3F3F1)       // borde finísimo (8 %)
    val Border = Divider
    val TextPrimary = Color(0xFFF3F3F1)
    val TextSecondary = Color(0xFF9C9C96) // "muted"
    val TextTertiary = Color(0xFF6E6E68)  // "subtle"
    val Accent: Color get() = accent.value // blanco hueso, o el elegido
    val OnAccent = Color(0xFF0A0A0B)
    val Like = Color(0xFFC45C5C)
    val Error = Color(0xFFE6E4DD)
}

private fun outfit(weight: Int) = Font(
    R.font.outfit,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Outfit: la sans de toda la interfaz. */
val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600), outfit(700), outfit(800))

/** Instrument Serif: los títulos grandes, como en la web. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
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
    surfaceContainer = LyraColors.Surface,
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

private val typography = Typography(
    displayLarge = serif.copy(fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-0.8).sp),
    displayMedium = serif.copy(fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.6).sp),
    displaySmall = serif.copy(fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = serif.copy(fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineMedium = serif.copy(fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.3).sp),
    headlineSmall = sans.copy(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = sans.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = sans.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = sans.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = sans.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = sans.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = sans.copy(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = sans.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = sans.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = sans.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun LyraTheme(content: @Composable () -> Unit) {
    val accent = LyraColors.Accent
    val colorScheme = remember(accent) { scheme(accent) }
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
}
