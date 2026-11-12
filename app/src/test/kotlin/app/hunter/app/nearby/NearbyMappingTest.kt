package app.hunter.app.nearby

import app.hunter.nearby.Distancia
import app.hunter.nearby.Parada
import app.hunter.nearby.PreferenciaVista
import app.hunter.nearby.Ritmo
import app.hunter.nearby.Vizinho
import kotlin.test.Test
import kotlin.test.assertEquals

class NearbyMappingTest {
    private fun viz(sessao: String = "sessao-1", rumo: Double? = 90.0, combina: Boolean = false, lat: Double = -20.0, lon: Double = -40.0) =
        Vizinho(sessao, lat, lon, rumo, PreferenciaVista(Ritmo.ANIMADO, Distancia.DE50A150, Parada.MIRANTE), combina)

    @Test
    fun `propriedades do mapa usam a sessao como id e o rumo`() {
        val f = viz(rumo = 135.5, combina = true, lat = -20.1, lon = -40.2).toRiderFeature()
        assertEquals(RiderFeature("sessao-1", -20.1, -40.2, 135.5, true, RIDER_ICON_COMBINA), f)
    }

    @Test
    fun `sem rumo a moto aponta para o norte e sem combinar usa o icone cinza`() {
        val f = viz(rumo = null, combina = false).toRiderFeature()
        assertEquals(0.0, f.rumo)
        assertEquals(false, f.destaque)
        assertEquals(RIDER_ICON, f.icon)
    }

    @Test
    fun `contagem de pessoas por perto`() {
        assertEquals("ninguém por perto ainda", pessoasPorPerto(0))
        assertEquals("1 pessoa por perto", pessoasPorPerto(1))
        assertEquals("2 pessoas por perto", pessoasPorPerto(2))
        assertEquals("Procurando rolê · ninguém por perto ainda", faixaProcurando(0))
        assertEquals("Procurando rolê · 3 pessoas por perto", faixaProcurando(3))
    }

    @Test
    fun `distancia aproximada arredonda e usa virgula`() {
        // 0,011° de latitude ~ 1,22 km
        assertEquals("a 1,2 km", distanciaAproximada(-20.0, -40.0, viz(lat = -20.011)))
        assertEquals("a menos de 1 km", distanciaAproximada(-20.0, -40.0, viz(lat = -20.004)))
        // 0,2° ~ 22 km
        assertEquals("a 22 km", distanciaAproximada(-20.0, -40.0, viz(lat = -20.2)))
    }

    @Test
    fun `rotulos em portugues`() {
        assertEquals(listOf("Tranquilo", "Moderado", "Animado"), Ritmo.entries.map { it.rotulo })
        assertEquals(listOf("Até 50 km", "50 a 150 km", "Mais de 150 km"), Distancia.entries.map { it.rotulo })
        assertEquals(listOf("Café ou lanche", "Mirante", "Só estrada", "Sem parada"), Parada.entries.map { it.rotulo })
    }
}
