package app.hunter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsStructureTest {
    @Test
    fun squircleDimensionsAreStandardized() {
        assertEquals(44, SQUIRCLE_DEFAULT_SIZE_DP)
        assertEquals(12, SQUIRCLE_DEFAULT_CORNER_DP)
    }

    @Test
    fun rumoComponentStandardHeights() {
        assertEquals(54, RUMO_PRIMARY_BUTTON_HEIGHT_DP)
        assertEquals(50, RUMO_SECONDARY_BUTTON_HEIGHT_DP)
        assertEquals(48, RUMO_SEARCH_HEIGHT_DP)
    }
}
