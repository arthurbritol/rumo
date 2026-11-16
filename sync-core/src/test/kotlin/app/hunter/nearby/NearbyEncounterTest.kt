package app.hunter.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearbyEncounterTest {
    private fun prop(id: String, livres: Int = 2, combina: Boolean = false) =
        Proposta(id, -20.5, -40.5, livres, 4, Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, combina)

    private fun part(sessao: String, distKm: Double? = null, chegou: Boolean = false) =
        Participante(sessao, -20.5, -40.5, false, distKm, distKm?.let { it * 1.5 }, chegou)

    private fun enc(id: String = "aaaaaaaaaaaaaaaa", vararg ps: Participante) =
        ServerMessage.Encontro(id, Ponto(-20.5, -40.5), 1, "s1", ps.toList())

    @Test
    fun mensagensDoClienteProduzemJsonExato() {
        assertEquals("""{"t":"proposta","lat":-20.5,"lon":-40.5,"vagas":4}""", ClientMessage.Proposta(-20.5, -40.5, 4).toJson())
        assertEquals("""{"t":"cancelar"}""", ClientMessage.Cancelar.toJson())
        assertEquals("""{"t":"entrar","id":"0123456789abcdef"}""", ClientMessage.Entrar("0123456789abcdef").toJson())
        assertEquals("""{"t":"sair"}""", ClientMessage.Sair.toJson())
        assertEquals(
            """{"t":"posicao","lat":1.0,"lon":2.0,"rumo":null,"vel":null,"distKm":3.5,"etaMin":12.0}""",
            ClientMessage.Posicao(1.0, 2.0, null, null, 3.5, 12.0).toJson(),
        )
    }

    @Test
    fun parsePropostas() {
        val json = """{"t":"propostas","lista":[{"id":"0123456789abcdef","lat":-20.5,"lon":-40.5,"vagasLivres":2,
            "vagasTotal":4,"ritmo":"moderado","distancia":"ate50","parada":"cafe","combina":true}]}"""
        assertEquals(ServerMessage.Propostas(listOf(prop("0123456789abcdef", combina = true))), parseServerMessage(json))
    }

    @Test
    fun propostaComTextoForaDaListaEhRecusada() {
        val json = """{"t":"propostas","lista":[{"id":"x","lat":1,"lon":2,"vagasLivres":2,"vagasTotal":4,
            "ritmo":"voando","distancia":"ate50","parada":"cafe","combina":true}]}"""
        assertNull(parseServerMessage(json))
    }

    @Test
    fun parseEncontroComCamposExtrasEPosicaoNula() {
        val json = """{"t":"encontro","id":"0123456789abcdef","ponto":{"lat":-20.5,"lon":-40.5},"vagasLivres":1,"eu":"ab12",
            "participantes":[{"sessao":"ab12","lat":null,"lon":null,"real":false,"distKm":null,"etaMin":null,"chegou":false},
            {"sessao":"cd34","lat":-20.4,"lon":-40.4,"real":true,"distKm":0.8,"etaMin":3,"chegou":false}],"novo":1}"""
        val m = parseServerMessage(json) as ServerMessage.Encontro
        assertEquals("ab12", m.eu)
        assertEquals(Ponto(-20.5, -40.5), m.ponto)
        assertEquals(1, m.vagasLivres)
        assertNull(m.participantes[0].lat)
        assertEquals(Participante("cd34", -20.4, -40.4, true, 0.8, 3.0, false), m.participantes[1])
    }

    @Test
    fun parseFimEMotivoDesconhecido() {
        assertEquals(ServerMessage.Fim("x", MotivoFim.NINGUEM_CHEGOU), parseServerMessage("""{"t":"fim","id":"x","motivo":"ninguem_chegou"}"""))
        assertEquals(ServerMessage.Fim("x", MotivoFim.SAIU), parseServerMessage("""{"t":"fim","id":"x","motivo":"saiu"}"""))
        assertEquals(ServerMessage.Fim("x", MotivoFim.OUTRO), parseServerMessage("""{"t":"fim","id":"x","motivo":"novo"}"""))
    }

    @Test
    fun parseErroComEspera() {
        assertEquals(ServerMessage.Erro("espera", 300000), parseServerMessage("""{"t":"erro","codigo":"espera","restanteMs":300000}"""))
        assertEquals(ServerMessage.Erro("cheio"), parseServerMessage("""{"t":"erro","codigo":"cheio"}"""))
    }

    @Test
    fun propostasDeDoisQuadradosNaoSeRepetem() {
        var s = NearbyState.reduce(NearbyState(), ServerMessage.Propostas(listOf(prop("p1"), prop("p2"))), "a")
        s = NearbyState.reduce(s, ServerMessage.Propostas(listOf(prop("p1", livres = 1))), "b")
        assertEquals(listOf("p2", "p1"), s.propostas.map { it.id })
        assertEquals(1, s.propostas.first { it.id == "p1" }.vagasLivres)
        // lista nova do mesmo quadrado substitui
        s = NearbyState.reduce(s, ServerMessage.Propostas(emptyList()), "a")
        assertEquals(listOf("p1"), s.propostas.map { it.id })
    }

    @Test
    fun propostasNaoMexemNosVizinhos() {
        val v = Vizinho("v", -20.0, -40.0, null, PreferenciaVista(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE), false)
        var s = NearbyState.reduce(NearbyState(), ServerMessage.Vizinhos(listOf(v)), "a")
        s = NearbyState.reduce(s, ServerMessage.Propostas(listOf(prop("p1"))), "a")
        assertEquals(listOf("v"), s.vizinhos.map { it.sessao })
        assertEquals(listOf("p1"), s.propostas.map { it.id })
    }

    @Test
    fun encontroAtualizaParticipantesEFimFecha() {
        var s = NearbyState.reduce(NearbyState(), enc(ps = arrayOf(part("s1", 5.0))))
        assertEquals(1, s.encontro!!.participantes.size)
        s = NearbyState.reduce(s, enc(ps = arrayOf(part("s1", 3.0), part("s2", 8.0))))
        assertEquals(listOf(3.0, 8.0), s.encontro!!.participantes.map { it.distKm })
        s = NearbyState.reduce(s, ServerMessage.Fim("aaaaaaaaaaaaaaaa", MotivoFim.CHEGARAM))
        assertNull(s.encontro)
        assertEquals(MotivoFim.CHEGARAM, s.fim!!.motivo)
        assertNull(s.limparFim().fim)
    }

    @Test
    fun fimDeOutroEncontroNaoDerrubaOAtual() {
        var s = NearbyState.reduce(NearbyState(), enc("aaaaaaaaaaaaaaaa"))
        s = NearbyState.reduce(s, ServerMessage.Fim("bbbbbbbbbbbbbbbb", MotivoFim.EXPIROU))
        assertEquals("aaaaaaaaaaaaaaaa", s.encontro!!.id)
        assertNull(s.fim)
    }

    @Test
    fun semListasGuardaOEncontro() {
        var s = NearbyState.reduce(NearbyState(), ServerMessage.Propostas(listOf(prop("p1"))), "a")
        s = NearbyState.reduce(s, enc())
        s = s.semListas()
        assertTrue(s.propostas.isEmpty())
        assertEquals("aaaaaaaaaaaaaaaa", s.encontro!!.id)
    }

    @Test
    fun encerrarLocalmente() {
        var s = NearbyState.reduce(NearbyState(), enc())
        s = s.encerrar("aaaaaaaaaaaaaaaa", MotivoFim.SAIU)
        assertNull(s.encontro)
        assertEquals(ServerMessage.Fim("aaaaaaaaaaaaaaaa", MotivoFim.SAIU), s.fim)
    }

    @Test
    fun erroGuardaAEspera() {
        val s = NearbyState.reduce(NearbyState(), ServerMessage.Erro("espera", 90_000))
        assertEquals("espera", s.ultimoErro)
        assertEquals(90_000L, s.ultimaEsperaMs)
    }
}
