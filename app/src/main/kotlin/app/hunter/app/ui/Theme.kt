package app.hunter.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ok = Color(0xFF34C77B)
val Warn = Color(0xFFFFB020)
val Bad = Color(0xFFFF5A5F)
val Accent = Color(0xFFFF7A1A)
val Info = Color(0xFF4DA3FF)

/** true quando o tema está escuro (o mapa usa o estilo escuro junto). */
val LocalDark = staticCompositionLocalOf { true }

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A0B00),
    primaryContainer = Color(0xFF3A1D08),
    onPrimaryContainer = Color(0xFFFFD9BF),
    secondary = Info,
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFF1F3F5),
    surface = Color(0xFF14171B),
    onSurface = Color(0xFFF1F3F5),
    surfaceVariant = Color(0xFF1D2227),
    onSurfaceVariant = Color(0xFF9AA4AE),
    outline = Color(0xFF2B3138),
    outlineVariant = Color(0xFF242A30),
    error = Bad,
)

private val Light = lightColorScheme(
    primary = Color(0xFFE85D00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D0),
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Color(0xFF1F6FD1),
    background = Color(0xFFF3F1EE),
    onBackground = Color(0xFF14171B),
    surface = Color.White,
    onSurface = Color(0xFF14171B),
    surfaceVariant = Color(0xFFEAE6E1),
    onSurfaceVariant = Color(0xFF5E666E),
    outline = Color(0xFFD5D0C9),
    outlineVariant = Color(0xFFE3DFD9),
    error = Bad,
)

private val Sans = FontFamily.SansSerif

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 88.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 52.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 36.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun HunterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
