package com.lyra.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta monocroma: negro, grises y blanco. */
object LyraColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF121212)
    val SurfaceHigh = Color(0xFF1C1C1C)
    val SurfaceHigher = Color(0xFF282828)
    val Card = Color(0xFF181818)
    val Divider = Color(0xFF2A2A2A)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF7A7A7A)
    val Accent = Color(0xFFFFFFFF)
    val OnAccent = Color(0xFF000000)
    val Error = Color(0xFFE6E6E6)
}

private val scheme = darkColorScheme(
    primary = LyraColors.Accent,
    onPrimary = LyraColors.OnAccent,
    primaryContainer = LyraColors.SurfaceHigher,
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
    outline = LyraColors.Divider,
    outlineVariant = LyraColors.Divider,
    error = LyraColors.Error,
    onError = LyraColors.OnAccent,
    inverseSurface = LyraColors.TextPrimary,
    inverseOnSurface = LyraColors.OnAccent,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun LyraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
