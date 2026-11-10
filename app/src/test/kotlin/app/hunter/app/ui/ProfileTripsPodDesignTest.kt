package app.hunter.app.ui

import app.hunter.app.R
import app.hunter.app.UpdateCheck
import app.hunter.sync.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTripsPodDesignTest {

    @Test
    fun testUpdateStatusTextCoversAllVariants() {
        assertNull(updateStatusText(UpdateCheck.IDLE))
        assertEquals("Buscando…", updateStatusText(UpdateCheck.CHECKING))
        assertEquals("Você está na versão mais recente.", updateStatusText(UpdateCheck.UP_TO_DATE))
        assertEquals("Não foi possível buscar agora. Verifique a internet.", updateStatusText(UpdateCheck.FAILED))
        assertEquals("Há uma versão nova: veja o aviso no topo do mapa.", updateStatusText(UpdateCheck.FOUND))
    }

    @Test
    fun testDeleteConfirmedValidation() {
        assertTrue(deleteConfirmed("APAGAR"))
        assertTrue(deleteConfirmed("  APAGAR  "))
        assertFalse(deleteConfirmed("apagar"))
        assertFalse(deleteConfirmed(""))
    }

    @Test
    fun testTabIconsForTripsAndPod() {
        assertEquals(R.drawable.fa_route, Tab.Trips.iconResId)
        assertEquals(R.drawable.fa_bluetooth, Tab.Pod.iconResId)
    }

    @Test
    fun testProfileEditFormToProfile() {
        val form = ProfileEditForm("Arthur", "Royal Enfield", "Hunter 350", "2024")
        val profile = form.toProfile(voice = true)
        assertEquals("Arthur", profile.displayName)
        assertEquals("Royal Enfield", profile.brand)
        assertEquals("Hunter 350", profile.model)
        assertEquals(2024, profile.year)
        assertTrue(profile.voice)
    }
}
