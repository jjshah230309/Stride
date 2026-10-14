package com.jashith.stride.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jashith.stride.R

/**
 * The fixed light palette from the iPhone app (Theme.swift): off-white pages, near-black forest-green hero
 * cards and a lime accent for every primary action. The app is light-only on both platforms.
 */
object StrideColors {
    val Lime = Color(0xFFDDF85C)
    val Accent = Lime
    val Ink = Color(0xFF1A2A1D)
    val Green = Color(0xFF1A7A45)
    val DeepGreen = Color(0xFF14401F)
    val PageBackground = Color(0xFFF7F7F4)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceBorder = Color(0xFFECECE6)
    val Track = Color(0xFFE6E7E1)
    val MapLine = Color(0xFF1E4620)
    val PrimaryText = Color(0xFF14181A)
    val SecondaryText = Color(0xFF767C73)
    val TertiaryText = Color(0xFFA2A79E)
    val OnInkSecondary = Color(0xFF9AA596)
    val InkBar = Color(0xFF33422F)
    val Negative = Color(0xFFC2410C)
    val Warning = Color(0xFFB45309)
    val Positive = Green
    val RideColor = Color(0xFF2F6F52)
    val HikeColor = Color(0xFF4A7C2F)
    val RunColor = Green
    val FitnessColor = Green
    val FatigueColor = Negative
    val FormColor = RideColor
    val BehindPR = Color(0xFFFF9A76)
    val Heat = Color(0x4DFF592B)           // rgba(255, 89, 43, 0.30)

    val HeartRateZones = listOf(
        Color(0xFFB9C4B2), Color(0xFF7FA98C), Color(0xFF1A7A45), Color(0xFFB9C22F), Color(0xFFC2410C),
    )
    val PowerZones = listOf(
        Color(0xFFB9C4B2), Color(0xFF8FB79A), Color(0xFF4E9468), Color(0xFF1A7A45),
        Color(0xFFA8BE33), Color(0xFFD98324), Color(0xFFC2410C),
    )
}

/** Numbers are set in a rounded face with tabular digits so a ticking clock never jitters. */
val FigureFamily = FontFamily(
    Font(R.font.nunito, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.nunito, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.nunito, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.nunito, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

object StrideType {
    fun figure(size: TextUnit, weight: FontWeight = FontWeight.Bold, color: Color = StrideColors.PrimaryText) = TextStyle(
        fontFamily = FigureFamily, fontSize = size, fontWeight = weight, color = color,
        fontFeatureSettings = "tnum", letterSpacing = 0.sp,
    )

    fun figure(size: Int, weight: FontWeight = FontWeight.Bold, color: Color = StrideColors.PrimaryText) = figure(size.sp, weight, color)

    val Kicker = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.SecondaryText, letterSpacing = 0.1.em)
    val ScreenTitle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = StrideColors.PrimaryText, lineHeight = 36.sp)
    val RowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.PrimaryText)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = StrideColors.SecondaryText)
    val Body = TextStyle(fontSize = 14.sp, color = StrideColors.PrimaryText)
    val BodySecondary = TextStyle(fontSize = 14.sp, color = StrideColors.SecondaryText)
    val Footnote = TextStyle(fontSize = 12.sp, color = StrideColors.SecondaryText)
    val Button = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = StrideColors.Ink, textAlign = TextAlign.Center)
}

object StrideShapes {
    val Card = 20.dp
    val InkCard = 24.dp
    val MetricCard = 18.dp
    val Map = 22.dp
    val DetailMap = 18.dp
    val Glyph = 13.dp
}

private val scheme = lightColorScheme(
    primary = StrideColors.Green,
    onPrimary = Color.White,
    primaryContainer = StrideColors.Lime,
    onPrimaryContainer = StrideColors.DeepGreen,
    secondary = StrideColors.Ink,
    onSecondary = Color.White,
    background = StrideColors.PageBackground,
    onBackground = StrideColors.PrimaryText,
    surface = StrideColors.Surface,
    onSurface = StrideColors.PrimaryText,
    surfaceVariant = StrideColors.Track,
    onSurfaceVariant = StrideColors.SecondaryText,
    outline = StrideColors.SurfaceBorder,
    outlineVariant = StrideColors.SurfaceBorder,
    error = StrideColors.Negative,
    surfaceContainer = StrideColors.Surface,
    surfaceContainerHigh = StrideColors.Surface,
    surfaceContainerHighest = StrideColors.Surface,
    surfaceContainerLow = StrideColors.PageBackground,
    surfaceContainerLowest = StrideColors.Surface,
)

@Composable
fun StrideTheme(content: @Composable () -> Unit) {
    // Light only, whatever the phone is set to: the colour language of the app depends on it.
    @Suppress("UNUSED_VARIABLE") val ignored = isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
