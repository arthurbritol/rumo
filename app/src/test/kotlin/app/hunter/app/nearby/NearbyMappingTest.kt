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
    fun `marcador proprio vira a moto laranja so enquanto procura`() {
        assertEquals("rider-self", ownMarkerIcon(true))
        assertEquals("me", ownMarkerIcon(false))
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

class NearbyEncounterMappingTest {
    private val proposta = app.hunter.nearby.Proposta(
        "0123456789abcdef", -20.0, -40.0, 2, 4, Ritmo.MODERADO, Distancia.ATE50, app.hunter.nearby.Parada.CAFE, true,
    )

    @Test
    fun `ponto da proposta mostra ocupadas de total`() {
        val f = proposta.toProposalFeature()
        assertEquals("2 de 4", f.label)
        assertEquals(PROPOSAL_ICON_COMBINA, f.icon)
        assertEquals(PROPOSAL_ICON, proposta.copy(combina = false).toProposalFeature().icon)
        assertEquals("7 de 8", ocupacao(1, 8))
    }

    @Test
    fun `vagas livres no singular e no plural`() {
        assertEquals("1 vaga livre", vagasLivresTexto(1))
        assertEquals("3 vagas livres", vagasLivresTexto(3))
    }

    @Test
    fun `distancia e tempo por participante`() {
        assertEquals("3,2 km · 12 min", distEtaTexto(3.2, 12.0))
        assertEquals("calculando…", distEtaTexto(null, null))
    }

    @Test
    fun `erros do servidor viram texto em portugues`() {
        assertEquals("Esse encontro já está cheio", textoDoErro("cheio", null))
        assertEquals("Espere 5 min para propor de novo", textoDoErro("espera", 300_000))
        assertEquals("Espere 5 min para propor de novo", textoDoErro("espera", 240_001))
        assertEquals("Espere menos de 1 min para propor de novo", textoDoErro("espera", 20_000))
        assertEquals("Você já tem uma proposta aberta", textoDoErro("ja_tem_proposta", null))
        assertEquals("Esse encontro não existe mais", textoDoErro("inexistente", null))
        assertEquals(null, textoDoErro("posicao_implausivel", null))
        assertEquals("Algo deu errado. Tente de novo", textoDoErro("codigo_novo", null))
    }

    @Test
    fun `participante sem posicao nao vira moto`() {
        val sem = app.hunter.nearby.Participante("s", null, null, false, null, null, false)
        assertEquals(null, sem.toRiderFeatureOrNull())
        val com = app.hunter.nearby.Participante("s", -20.0, -40.0, true, 1.0, 3.0, false)
        assertEquals(RiderFeature("s", -20.0, -40.0, 0.0, false, RIDER_ICON), com.toRiderFeatureOrNull())
    }
}
