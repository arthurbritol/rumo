package app.hunter.app.ui

import app.hunter.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TabIconsTest {
    @Test
    fun tabsUseFontAwesomeIcons() {
        assertEquals(R.drawable.fa_map_location_dot, Tab.Map.iconResId)
        assertEquals(R.drawable.fa_route, Tab.Trips.iconResId)
        assertEquals(R.drawable.fa_motorcycle, Tab.Garage.iconResId)
        assertEquals(R.drawable.fa_users, Tab.Friends.iconResId)
        assertEquals(R.drawable.fa_bluetooth, Tab.Pod.iconResId)
    }
}
