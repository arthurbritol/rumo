package app.hunter.app.ui

import app.hunter.nav.perigos.Perigo
import kotlin.test.Test
import kotlin.test.assertEquals

class PerigoIconsTest {
    @Test
    fun `um id de imagem distinto por tipo`() {
        val ids = Perigo.values().map(::perigoIconId)
        assertEquals(Perigo.values().size, ids.toSet().size)
        assertEquals("perigo-radar", perigoIconId(Perigo.RADAR))
        assertEquals("perigo-travessia_ferroviaria", perigoIconId(Perigo.TRAVESSIA_FERROVIARIA))
    }
}
