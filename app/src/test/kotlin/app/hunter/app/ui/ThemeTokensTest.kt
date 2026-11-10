package app.hunter.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeTokensTest {
    @Test
    fun novareTokensHaveExpectedValues() {
        assertEquals(Color(0xFFFF7A1A), NovareAccent)
        assertEquals(Color(0xFF10B981), NovareSuccess)
        assertEquals(Color(0xFFF59E0B), NovareWarning)
        assertEquals(Color(0xFFEF4444), NovareError)
        assertEquals(Color(0xFF38BDF8), NovareInfo)
    }

    @Test
    fun darkAndLightSurfacesAreDistinct() {
        assertNotEquals(DarkMidnightSurface, LightCleanSurface)
        assertNotEquals(DarkMidnightBackground, LightCleanBackground)
    }
}
