package app.hunter.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearbyStateTest {
    private val pref = PreferenciaVista(Ritmo.MODERADO, Distancia.DE50A150, Parada.CAFE)

    private fun viz(sessao: String, lat: Double = -8.0, combina: Boolean = false) =
        Vizinho(sessao, lat, -34.9, null, pref, combina)

    @Test
    fun procurarProduzJsonExatoDoProtocolo() {
        val json = ClientMessage.Procurar(
            Preferencia(Ritmo.MODERADO, Distancia.DE50A150, Parada.SO_ESTRADA, 5),
        ).toJson()
        assertEquals(
            """{"t":"procurar","ritmo":"moderado","distancia":"50a150","parada":"so_estrada","raioKm":5}""",
            json,
        )
    }

    @Test
    fun pararProduzJsonExato() {
        assertEquals("""{"t":"parar"}""", ClientMessage.Parar.toJson())
    }

    @Test
    fun posicaoEnviaNulosExplicitos() {
        val json = ClientMessage.Posicao(-8.05, -34.9, null, null, null, null).toJson()
        assertEquals(
            """{"t":"posicao","lat":-8.05,"lon":-34.9,"rumo":null,"vel":null,"distKm":null,"etaMin":null}""",
            json,
        )
    }

    @Test
    fun parseListaComDoisVizinhos() {
        val json = """{"t":"vizinhos","lista":[
            {"sessao":"a1","lat":-8.1,"lon":-34.9,"rumo":90.0,"ritmo":"animado","distancia":"ate50","parada":"cafe","combina":true},
            {"sessao":"b2","lat":-8.2,"lon":-34.8,"rumo":null,"ritmo":"tranquilo","distancia":"mais150","parada":"sem_parada","combina":false}
        ]}"""
        val msg = parseServerMessage(json) as ServerMessage.Vizinhos
        assertEquals(2, msg.lista.size)
        assertEquals("a1", msg.lista[0].sessao)
        assertEquals(90.0, msg.lista[0].rumo)
        assertEquals(PreferenciaVista(Ritmo.ANIMADO, Distancia.ATE50, Parada.CAFE), msg.lista[0].preferencia)
        assertTrue(msg.lista[0].combina)
        assertNull(msg.lista[1].rumo)
        assertEquals(Parada.SEM_PARADA, msg.lista[1].preferencia.parada)
    }

    @Test
    fun parseErro() {
        assertEquals(
            ServerMessage.Erro("posicao_implausivel"),
            parseServerMessage("""{"t":"erro","codigo":"posicao_implausivel"}"""),
        )
    }

    @Test
    fun jsonInvalidoOuTipoDesconhecidoDaNulo() {
        assertNull(parseServerMessage("não é json"))
        assertNull(parseServerMessage("""{"t":"outra"}"""))
        assertNull(parseServerMessage("""{"sem":"tipo"}"""))
        assertNull(parseServerMessage("""[1,2]"""))
        assertNull(parseServerMessage("""{"t":"vizinhos","lista":[{"sessao":"x"}]}"""))
    }

    @Test
    fun reduceOrdenaQuemCombinaPrimeiro() {
        val s = NearbyState.reduce(
            NearbyState(),
            ServerMessage.Vizinhos(listOf(viz("a"), viz("b", combina = true), viz("c"), viz("d", combina = true))),
        )
        assertEquals(listOf("b", "d", "a", "c"), s.vizinhos.map { it.sessao })
    }

    @Test
    fun vizinhoRepetidoAparecePorUmaVezComPosicaoMaisNova() {
        val dentroDaLista = NearbyState.reduce(
            NearbyState(),
            ServerMessage.Vizinhos(listOf(viz("a", lat = -8.0), viz("a", lat = -8.5))),
        )
        assertEquals(listOf(-8.5), dentroDaLista.vizinhos.map { it.lat })

        // Mesmo vizinho vindo de dois quadrados.
        var s = NearbyState.reduce(NearbyState(), ServerMessage.Vizinhos(listOf(viz("a", lat = -8.0))), celula = "-9:-35")
        s = NearbyState.reduce(s, ServerMessage.Vizinhos(listOf(viz("a", lat = -8.9), viz("z"))), celula = "-8:-35")
        assertEquals(listOf("a", "z"), s.vizinhos.map { it.sessao })
        assertEquals(-8.9, s.vizinhos.first { it.sessao == "a" }.lat)
    }

    @Test
    fun novaListaDaMesmaCelulaSubstitui() {
        var s = NearbyState.reduce(NearbyState(), ServerMessage.Vizinhos(listOf(viz("a"), viz("b"))))
        s = NearbyState.reduce(s, ServerMessage.Vizinhos(listOf(viz("b"))))
        assertEquals(listOf("b"), s.vizinhos.map { it.sessao })
    }

    @Test
    fun erroFicaNoEstadoSemApagarVizinhos() {
        var s = NearbyState.reduce(NearbyState(), ServerMessage.Vizinhos(listOf(viz("a"))))
        s = NearbyState.reduce(s, ServerMessage.Erro("limite"))
        assertEquals("limite", s.ultimoErro)
        assertEquals(1, s.vizinhos.size)
    }

    @Test
    fun raioForaDe5a20Lanca() {
        assertFailsWith<IllegalArgumentException> {
            Preferencia(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, raioKm = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            Preferencia(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, raioKm = 21)
        }
    }
}
