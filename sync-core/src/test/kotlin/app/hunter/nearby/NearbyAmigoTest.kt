package app.hunter.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NearbyAmigoTest {
    @Test
    fun `amigo e amigo_ack serializam so a sessao ou o uid`() {
        assertEquals("""{"t":"amigo","sessao":"0123456789abcdef"}""", ClientMessage.Amigo("0123456789abcdef").toJson())
        assertEquals("""{"t":"amigo_ack","uid":"u-123"}""", ClientMessage.AmigoAck("u-123").toJson())
    }

    @Test
    fun `amigo_confirmado traz o uid`() {
        assertEquals(ServerMessage.AmigoConfirmado("u-123"), parseServerMessage("""{"t":"amigo_confirmado","uid":"u-123"}"""))
    }

    @Test
    fun `amigo_confirmado sem uid ou vazio e ignorado`() {
        assertNull(parseServerMessage("""{"t":"amigo_confirmado"}"""))
        assertNull(parseServerMessage("""{"t":"amigo_confirmado","uid":""}"""))
    }

    @Test
    fun `ok amigo traz a referencia e o estado nao muda com amigo_confirmado`() {
        assertEquals(ServerMessage.Ok("amigo"), parseServerMessage("""{"t":"ok","ref":"amigo"}"""))
        val st = NearbyState()
        val depois = NearbyState.reduce(st, ServerMessage.AmigoConfirmado("u-123"))
        assertNull(depois.ultimoErro)
        assertNull(depois.encontro)
    }
}
