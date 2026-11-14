package com.manumusicprod.playcam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.manumusicprod.playcam.R

/** Couleurs MANU MUSIC PROD : ébène, ivoire et le vert signature. */
object Mmp {
    val Ebony = Color(0xFF0E0F0D)
    val Ebony2 = Color(0xFF171916)
    val Ebony3 = Color(0xFF262A22)
    val Ivory = Color(0xFFF3EFE4)
    val Ivory2 = Color(0xFFE5DFCF)
    val Mute = Color(0xFFA39E90)
    val Soft = Color(0xFFCFCBC0)
    val Green = Color(0xFF59C35F)
    val GreenDeep = Color(0xFF0A7034)
    val OnGreen = Color(0xFF0B130B)
    val Red = Color(0xFFE5484D)
    val Amber = Color(0xFFE9B44C)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val InterDisplay = FontFamily(
    Font(R.font.inter_display_black_italic, FontWeight.Black, FontStyle.Italic),
    Font(R.font.inter_display_extrabold_italic, FontWeight.ExtraBold, FontStyle.Italic),
)

private val typo = Typography(
    displaySmall = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp),
)

private val scheme = darkColorScheme(
    primary = Mmp.Green, onPrimary = Mmp.OnGreen,
    secondary = Mmp.Ivory, onSecondary = Mmp.Ebony,
    background = Mmp.Ebony, onBackground = Mmp.Ivory,
    surface = Mmp.Ebony2, onSurface = Mmp.Ivory,
    surfaceVariant = Mmp.Ebony3, onSurfaceVariant = Mmp.Soft,
    outline = Mmp.Ebony3, error = Mmp.Red,
)

@Composable
fun MmpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typo, content = content)
}
