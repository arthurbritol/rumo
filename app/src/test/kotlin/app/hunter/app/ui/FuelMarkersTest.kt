package app.hunter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelMarkersTest {
    @Test
    fun `bandeira de exibicao vem da bandeira da ANP ou do nome do posto`() {
        assertEquals(StationBrand.SHELL, stationBrandOf("RAIZEN", "Posto X"))
        assertEquals(StationBrand.SHELL, stationBrandOf("Branca", "Posto Shell - Arara Azul"))
        assertEquals(StationBrand.IPIRANGA, stationBrandOf("IPIRANGA", ""))
        assertEquals(StationBrand.BR, stationBrandOf("Petrobras / Vibra", ""))
        assertEquals(StationBrand.BR, stationBrandOf("Branca", "Posto BR"))
        assertEquals(StationBrand.ALE, stationBrandOf("ALE", "Posto Z"))
        assertEquals(StationBrand.OTHER, stationBrandOf("BRANCA", "Posto Coliseu"))
        assertEquals(StationBrand.OTHER, stationBrandOf("Branca", "Posto Brasil"))
    }
}
