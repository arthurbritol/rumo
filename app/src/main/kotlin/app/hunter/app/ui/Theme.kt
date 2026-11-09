package app.hunter.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ok = Color(0xFF3FA34D)
val Warn = Color(0xFFF2A900)
val Bad = Color(0xFFD64545)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF2B1300),
    secondary = Color(0xFF8FB8D8),
    background = Color(0xFF101214),
    surface = Color(0xFF16191C),
    surfaceVariant = Color(0xFF23282D),
    onSurface = Color(0xFFE8EAEC),
    onSurfaceVariant = Color(0xFFAEB5BC),
    error = Bad,
)

private val Light = lightColorScheme(
    primary = Color(0xFFC4500A),
    onPrimary = Color.White,
    secondary = Color(0xFF35607F),
    background = Color(0xFFF6F4F1),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAE6E1),
    error = Bad,
)

@Composable
fun HunterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
