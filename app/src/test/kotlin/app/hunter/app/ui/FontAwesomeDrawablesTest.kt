package app.hunter.app.ui

import app.hunter.app.R
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FontAwesomeDrawablesTest {
    @Test
    fun allFontAwesomeDrawablesExist() {
        val drawables = listOf(
            R.drawable.fa_motorcycle,
            R.drawable.fa_map_location_dot,
            R.drawable.fa_route,
            R.drawable.fa_users,
            R.drawable.fa_gas_pump,
            R.drawable.fa_wrench,
            R.drawable.fa_gauge_high,
            R.drawable.fa_bell,
            R.drawable.fa_user,
            R.drawable.fa_plus,
            R.drawable.fa_bluetooth,
            R.drawable.fa_magnifying_glass,
        )
        drawables.forEach { id ->
            assertNotEquals(0, id)
        }
    }
}
