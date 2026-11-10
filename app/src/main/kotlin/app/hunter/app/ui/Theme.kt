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

// Tokens de estado e acento inspirados no Novare Finance
val NovareAccent = Color(0xFFFF7A1A)
val NovareSuccess = Color(0xFF10B981)
val NovareWarning = Color(0xFFF59E0B)
val NovareError = Color(0xFFEF4444)
val NovareInfo = Color(0xFF38BDF8)

// Compatibilidade retroativa para código existente
val Ok = NovareSuccess
val Warn = NovareWarning
val Bad = NovareError
val Accent = NovareAccent
val Info = NovareInfo

// Paleta Dark Midnight Navy
val DarkMidnightBackground = Color(0xFF0B111D)
val DarkMidnightSurface = Color(0xFF141C2B)
val DarkMidnightSurfaceVariant = Color(0xFF1E2D44)
val DarkMidnightOutline = Color(0xFF24344D)
val DarkMidnightOnSurface = Color(0xFFF8FAFC)
val DarkMidnightOnSurfaceVariant = Color(0xFF94A3B8)

val DarkBackground = DarkMidnightBackground
val DarkSurface = DarkMidnightSurface
val DarkSurfaceVariant = DarkMidnightSurfaceVariant
val DarkOutline = DarkMidnightOutline
val DarkOnSurface = DarkMidnightOnSurface
val DarkOnSurfaceVariant = DarkMidnightOnSurfaceVariant

// Paleta Light Clean Slate
val LightCleanBackground = Color(0xFFF1F5F9)
val LightCleanSurface = Color(0xFFFFFFFF)
val LightCleanSurfaceVariant = Color(0xFFE2E8F0)
val LightCleanOutline = Color(0xFFCBD5E1)
val LightCleanOnSurface = Color(0xFF0F172A)
val LightCleanOnSurfaceVariant = Color(0xFF64748B)

val LightBackground = LightCleanBackground
val LightSurface = LightCleanSurface
val LightSurfaceVariant = LightCleanSurfaceVariant
val LightOutline = LightCleanOutline
val LightOnSurface = LightCleanOnSurface
val LightOnSurfaceVariant = LightCleanOnSurfaceVariant

/** true quando o tema está escuro (o mapa usa o estilo escuro junto). */
val LocalDark = staticCompositionLocalOf { true }

private val Dark = darkColorScheme(
    primary = NovareAccent,
    onPrimary = Color(0xFF1A0B00),
    primaryContainer = Color(0xFF3A1D08),
    onPrimaryContainer = Color(0xFFFFD9BF),
    secondary = NovareInfo,
    onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFBAE6FD),
    background = DarkMidnightBackground,
    onBackground = DarkMidnightOnSurface,
    surface = DarkMidnightSurface,
    onSurface = DarkMidnightOnSurface,
    surfaceVariant = DarkMidnightSurfaceVariant,
    onSurfaceVariant = DarkMidnightOnSurfaceVariant,
    outline = DarkMidnightOutline,
    outlineVariant = Color(0xFF1A2436),
    error = NovareError,
    onError = Color.White,
)

private val Light = lightColorScheme(
    primary = Color(0xFFEA580C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D0),
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    background = LightCleanBackground,
    onBackground = LightCleanOnSurface,
    surface = LightCleanSurface,
    onSurface = LightCleanOnSurface,
    surfaceVariant = LightCleanSurfaceVariant,
    onSurfaceVariant = LightCleanOnSurfaceVariant,
    outline = LightCleanOutline,
    outlineVariant = Color(0xFFCBD5E1),
    error = NovareError,
    onError = Color.White,
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
