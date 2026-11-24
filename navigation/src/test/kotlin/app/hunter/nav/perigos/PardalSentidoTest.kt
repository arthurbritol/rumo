package app.hunter.nav.perigos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Radares do Pardal com `direction_deg` (sentido = true): só avisam no sentido fiscalizado (até 60º). */
class PardalSentidoTest {
    private val rota = rotaLeste()

    /** Via de pista dupla: pista para o leste (90º) e pista para o oeste (270º), 10 m de distância entre elas. */
    private fun radarLeste(id: String = "leste", e: Double = 2_000.0, dir: Double? = 90.0, sentido: Boolean = true, tipo: Perigo = Perigo.RADAR) =
        perigo(id, e, 0.0, tipo, 60, dir).copy(sentido = sentido && dir != null)

    private fun radarOeste(id: String = "oeste", e: Double = 2_000.0, dir: Double? = 270.0) =
        perigo(id, e, 10.0, Perigo.RADAR, 60, dir).copy(sentido = dir != null)

    private fun naRota(aqui: Double, ps: List<PerigoPonto>) =
        PerigosAdiante.adiante(ll(aqui), 90.0, 15.0, rota, aqui, ps).map { it.perigo.id }

    private fun livre(aqui: Double, rumo: Double, ps: List<PerigoPonto>) =
        PerigosAdiante.adiante(ll(aqui, 5.0), rumo, 15.0, null, null, ps).map { it.perigo.id }

    @Test fun `na rota para o leste so vale o radar do sentido leste`() {
        assertEquals(listOf("leste"), naRota(1_500.0, listOf(radarLeste(), radarOeste())))
    }

    @Test fun `no modo livre cada sentido ve so o radar da propria pista`() {
        val ps = listOf(radarLeste(), radarOeste())
        assertEquals(listOf("leste"), livre(1_700.0, 90.0, ps))
        assertEquals(listOf("oeste"), livre(2_300.0, 270.0, ps))
    }

    @Test fun `sem direcao avisa nos dois sentidos`() {
        val ps = listOf(radarLeste(dir = null))
        assertEquals(listOf("leste"), livre(1_700.0, 90.0, ps))
        assertEquals(listOf("leste"), livre(2_300.0, 270.0, ps))
    }

    @Test fun `sentido vale ate 60 graus e nao tem o eixo duplo do OSM`() {
        // 150º = 60º do rumo 90º: vale; 155º: não; 270º (oposto): não, mesmo sendo o mesmo eixo.
        assertEquals(listOf("a"), livre(1_700.0, 90.0, listOf(radarLeste("a", dir = 150.0))))
        assertTrue(livre(1_700.0, 90.0, listOf(radarLeste("b", dir = 155.0))).isEmpty())
        assertTrue(livre(1_700.0, 90.0, listOf(radarLeste("c", dir = 270.0))).isEmpty())
        // Mesmo dado vindo do OSM (sentido = false) continua valendo nos dois sentidos do eixo.
        assertEquals(listOf("d"), livre(1_700.0, 90.0, listOf(radarLeste("d", dir = 270.0, sentido = false))))
    }

    @Test fun `avisador fala so no sentido certo da pista dupla`() {
        val ps = listOf(radarLeste(), radarOeste())
        val av = AvisadorDePerigos()
        val r = av.atualizar(ll(1_210.0), 90.0, 15.0, rota, 1_210.0, ps)
        assertEquals(listOf("Radar à frente, 800 metros, limite 60."), r.falas)
        assertEquals("leste", r.alerta?.perigo?.id)
    }

    @Test fun `avisador sem direcao fala para quem vem de qualquer sentido`() {
        val ps = listOf(radarLeste(dir = null))
        val leste = AvisadorDePerigos().atualizar(ll(1_210.0), 90.0, 15.0, rota, 1_210.0, ps)
        assertEquals(1, leste.falas.size)
        val oeste = AvisadorDePerigos().atualizar(ll(2_590.0, 5.0), 270.0, 15.0, null, null, ps)
        assertEquals(1, oeste.falas.size)
    }

    @Test fun `radar de trecho avisa na entrada, como o radar, e respeita o sentido`() {
        val trecho = radarLeste("t", tipo = Perigo.RADAR_DE_TRECHO).copy(fimLat = 0.0, fimLon = 5_000.0 / 111_194.9)
        val av = AvisadorDePerigos()
        val ps = listOf(trecho)
        assertTrue(av.atualizar(ll(1_100.0), 90.0, 15.0, rota, 1_100.0, ps).falas.isEmpty())
        assertEquals(listOf("Radar de trecho à frente, 800 metros."), av.atualizar(ll(1_210.0), 90.0, 15.0, rota, 1_210.0, ps).falas)
        assertEquals(listOf("Radar de trecho à frente, 300 metros."), av.atualizar(ll(1_710.0), 90.0, 15.0, rota, 1_710.0, ps).falas)
        assertTrue(av.atualizar(ll(1_905.0), 90.0, 15.0, rota, 1_905.0, ps).falas.isEmpty()) // sem "adiante" curto
        // Sentido contrário não avisa.
        assertTrue(livre(2_300.0, 270.0, listOf(trecho)).isEmpty())
    }
}
