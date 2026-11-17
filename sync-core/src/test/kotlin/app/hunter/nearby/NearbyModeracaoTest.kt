package app.hunter.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NearbyModeracaoTest {
    @Test
    fun `bloquear e denunciar levam so a sessao e o motivo`() {
        assertEquals("""{"t":"bloquear","sessao":"0123456789abcdef"}""", ClientMessage.Bloquear("0123456789abcdef").toJson())
        assertEquals(
            """{"t":"denunciar","sessao":"0123456789abcdef","motivo":"falso"}""",
            ClientMessage.Denunciar("0123456789abcdef", MotivoDenuncia.FALSO).toJson(),
        )
        assertEquals(listOf("incomodo", "comportamento", "falso", "outro"), MotivoDenuncia.entries.map { it.wire })
    }

    @Test
    fun `resposta ok traz a referencia`() {
        assertEquals(ServerMessage.Ok("bloqueio"), parseServerMessage("""{"t":"ok","ref":"bloqueio"}"""))
        assertEquals(ServerMessage.Ok("denuncia"), parseServerMessage("""{"t":"ok","ref":"denuncia"}"""))
        assertNull(parseServerMessage("""{"t":"ok"}"""))
    }

    @Test
    fun `o quadrado do vizinho e o da lista mais recente que o traz`() {
        fun v(s: String) = Vizinho(s, 0.0, 0.0, null, PreferenciaVista(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE), false)
        var st = NearbyState()
        st = NearbyState.reduce(st, ServerMessage.Vizinhos(listOf(v("a"), v("b"))), "1:1")
        st = NearbyState.reduce(st, ServerMessage.Vizinhos(listOf(v("b"))), "2:2")
        assertEquals("1:1", st.celulaDoVizinho("a"))
        assertEquals("2:2", st.celulaDoVizinho("b"))
        assertNull(st.celulaDoVizinho("x"))
    }
}
