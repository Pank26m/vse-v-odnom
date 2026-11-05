package com.pank.vsevodnom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.R

/** Палитра: светлая, пастельная. Каждая группа разделов — свой оттенок. */
object Pal {
    val Bg = Color(0xFFF6F4F0)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF2B2A33)
    val Muted = Color(0xFF6B6A75)
    val Line = Color(0xFFF0EEEA)

    val Sky = Color(0xFFDCEBFA);      val SkyInk = Color(0xFF3E6FA8)
    val Mint = Color(0xFFDDF2E6);     val MintInk = Color(0xFF2F7D57)
    val Peach = Color(0xFFFCE5D6);    val PeachInk = Color(0xFFB65E2E)
    val Lilac = Color(0xFFE7E1F7);    val LilacInk = Color(0xFF6A55A8)
    val Butter = Color(0xFFFBF1CC);   val ButterInk = Color(0xFF8A6B12)
    val Rose = Color(0xFFF8DDE6);     val RoseInk = Color(0xFFA8456A)
}

data class Tint(val bg: Color, val ink: Color)

object Tints {
    val Sky = Tint(Pal.Sky, Pal.SkyInk)
    val Mint = Tint(Pal.Mint, Pal.MintInk)
    val Peach = Tint(Pal.Peach, Pal.PeachInk)
    val Lilac = Tint(Pal.Lilac, Pal.LilacInk)
    val Butter = Tint(Pal.Butter, Pal.ButterInk)
    val Rose = Tint(Pal.Rose, Pal.RoseInk)
    val all = listOf(Sky, Mint, Peach, Lilac, Butter, Rose)
}

@OptIn(ExperimentalTextApi::class)
private fun manrope(w: Int) = Font(
    R.font.manrope,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

private val base = TextStyle(fontFamily = Manrope, color = Pal.Ink)

private val AppTypography = Typography(
    displayLarge = base.copy(fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    displayMedium = base.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    headlineLarge = base.copy(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = base.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    titleSmall = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    bodyLarge = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodySmall = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Pal.Muted),
    labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Pal.Muted),
)

private val Colors = lightColorScheme(
    primary = Pal.Ink,
    onPrimary = Color.White,
    primaryContainer = Pal.Lilac,
    onPrimaryContainer = Pal.Ink,
    secondary = Pal.LilacInk,
    secondaryContainer = Pal.Lilac,
    onSecondaryContainer = Pal.Ink,
    background = Pal.Bg,
    onBackground = Pal.Ink,
    surface = Pal.Card,
    onSurface = Pal.Ink,
    surfaceVariant = Pal.Line,
    onSurfaceVariant = Pal.Muted,
    surfaceContainer = Pal.Card,
    surfaceContainerHigh = Pal.Card,
    surfaceContainerHighest = Pal.Line,
    outline = Color(0xFFD8D5CF),
    outlineVariant = Pal.Line,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
