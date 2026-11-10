package app.hunter.app.ui

import app.hunter.app.fuel.PriceSource
import app.hunter.app.fuel.ResolvedFuelStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelStationCardTest {

    @Test
    fun `formatFuelPrice formata valores validos como moeda brasileira`() {
        assertEquals("R$ 5,79", formatFuelPrice(5.79))
        assertEquals("R$ 4,12", formatFuelPrice(4.12))
        assertEquals("R$ 5,50", formatFuelPrice(5.5))
        assertEquals("R$ 6,00", formatFuelPrice(6.0))
    }

    @Test
    fun `formatFuelPrice retorna traco para valores nulos, zerados ou invalidos`() {
        assertEquals("-", formatFuelPrice(null))
        assertEquals("-", formatFuelPrice(0.0))
        assertEquals("-", formatFuelPrice(-1.5))
        assertEquals("-", formatFuelPrice(Double.NaN))
        assertEquals("-", formatFuelPrice(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `formatPriceSourceNotice formata corretamente cada procedencia ANP`() {
        assertEquals(
            "Pesquisa oficial ANP · Coletado em 08/11/2026",
            formatPriceSourceNotice(PriceSource.POSTO_ANP, "08/11/2026"),
        )
        assertEquals(
            "Média estimada da cidade (11/2026)",
            formatPriceSourceNotice(PriceSource.MEDIA_MUNICIPAL, "11/2026"),
        )
        assertEquals(
            "Média estadual estimada (11/2026)",
            formatPriceSourceNotice(PriceSource.MEDIA_ESTADUAL, "11/2026"),
        )
    }

    @Test
    fun `resolveFuelParity identifica etanol vantajoso quando razao menor ou igual a 70 por cento`() {
        // Etanol 3.99 / Gasolina 5.99 = 0.666 -> 67%
        val parity = resolveFuelParity(precoEtanol = 3.99, precoGasolina = 5.99)
        assertNotNull(parity)
        assertTrue(parity!!.isEtanolAdvantageous)
        assertEquals("Etanol a 67% da gasolina · Etanol compensa", parity.description)

        // Limiar exato de 70% (3.50 / 5.00 = 0.70)
        val thresholdParity = resolveFuelParity(precoEtanol = 3.50, precoGasolina = 5.00)
        assertNotNull(thresholdParity)
        assertTrue(thresholdParity!!.isEtanolAdvantageous)
        assertEquals("Etanol a 70% da gasolina · Etanol compensa", thresholdParity.description)
    }

    @Test
    fun `resolveFuelParity identifica gasolina vantajosa quando razao maior que 70 por cento`() {
        // Etanol 4.32 / Gasolina 6.00 = 0.72 -> 72%
        val parity = resolveFuelParity(precoEtanol = 4.32, precoGasolina = 6.00)
        assertNotNull(parity)
        assertFalse(parity!!.isEtanolAdvantageous)
        assertEquals("Etanol a 72% da gasolina · Gasolina compensa", parity.description)
    }

    @Test
    fun `resolveFuelParity retorna null quando algum preco for nulo ou invalido`() {
        assertNull(resolveFuelParity(precoEtanol = null, precoGasolina = 5.79))
        assertNull(resolveFuelParity(precoEtanol = 4.12, precoGasolina = null))
        assertNull(resolveFuelParity(precoEtanol = 0.0, precoGasolina = 5.79))
        assertNull(resolveFuelParity(precoEtanol = 4.12, precoGasolina = 0.0))
        assertNull(resolveFuelParity(precoEtanol = -1.0, precoGasolina = 5.79))
    }

    @Test
    fun `formatFuelStationDistance formata metros e kilometros corretamente`() {
        assertEquals("0 m", formatFuelStationDistance(0.0))
        assertEquals("50 m", formatFuelStationDistance(50.0))
        assertEquals("850 m", formatFuelStationDistance(850.0))
        assertEquals("999 m", formatFuelStationDistance(999.0))
        assertEquals("1,0 km", formatFuelStationDistance(1000.0))
        assertEquals("1,4 km", formatFuelStationDistance(1400.0))
        assertEquals("12,5 km", formatFuelStationDistance(12500.0))

        assertNull(formatFuelStationDistance(null))
        assertNull(formatFuelStationDistance(-10.0))
        assertNull(formatFuelStationDistance(Double.NaN))
    }

    @Test
    fun `formatStationSubtitle combina endereco bairro e distancia de forma elegante`() {
        assertEquals(
            "Av. Caxangá, 1200 · Derby · a 1,4 km de você",
            formatStationSubtitle(distanceM = 1400.0, endereco = "Av. Caxangá, 1200", bairro = "Derby"),
        )
        assertEquals(
            "Rod. BR-101, Km 45 · a 850 m de você",
            formatStationSubtitle(distanceM = 850.0, endereco = "Rod. BR-101, Km 45", bairro = ""),
        )
        assertEquals(
            "Rua das Flores, 10 · Centro",
            formatStationSubtitle(distanceM = null, endereco = "Rua das Flores, 10", bairro = "Centro"),
        )
        assertEquals(
            "a 500 m de você",
            formatStationSubtitle(distanceM = 500.0, endereco = "", bairro = ""),
        )
        assertEquals(
            "",
            formatStationSubtitle(distanceM = null, endereco = "", bairro = ""),
        )
    }

    @Test
    fun `ResolvedFuelStation com precos ANP consolida informacoes para o card`() {
        val station = ResolvedFuelStation(
            id = "posto-1",
            nome = "Auto Posto Shell Portal",
            bandeira = "Shell",
            endereco = "Av. Caxangá, 1200",
            bairro = "Derby",
            cep = "50000-000",
            lat = -8.05,
            lon = -34.90,
            precoGasolina = 5.79,
            precoGasolinaAdit = 5.99,
            precoEtanol = 4.12,
            precoDiesel = 5.65,
            fonte = PriceSource.POSTO_ANP,
            dataRef = "08/11/2026",
        )

        assertEquals("R$ 5,79", formatFuelPrice(station.precoGasolina))
        assertEquals("R$ 5,99", formatFuelPrice(station.precoGasolinaAdit))
        assertEquals("R$ 4,12", formatFuelPrice(station.precoEtanol))
        assertEquals("R$ 5,65", formatFuelPrice(station.precoDiesel))

        val parity = resolveFuelParity(station.precoEtanol, station.precoGasolina)
        assertNotNull(parity)
        assertFalse(parity!!.isEtanolAdvantageous)
        assertEquals("Etanol a 71% da gasolina · Gasolina compensa", parity.description)

        val notice = formatPriceSourceNotice(station.fonte, station.dataRef)
        assertEquals("Pesquisa oficial ANP · Coletado em 08/11/2026", notice)
    }
}
