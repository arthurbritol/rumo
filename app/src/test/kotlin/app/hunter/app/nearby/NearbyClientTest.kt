package app.hunter.app.nearby

import app.hunter.nearby.ClientMessage
import app.hunter.nearby.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyClientTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val received = CopyOnWriteArrayList<String>()
    private val fastBackoff = Backoff(baseMs = 20, maxMs = 80)

    private val vizinhosJson =
        """{"t":"vizinhos","lista":[{"sessao":"s1","lat":-21.0,"lon":-41.0,"rumo":90.0,"ritmo":"moderado","distancia":"ate50","parada":"cafe","combina":true}]}"""

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        sockets.forEach { try { it.close(1000, null) } catch (_: Exception) {} }
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun client(
        tokens: List<String?> = listOf("tok"),
        renewAfterMs: Long = 55 * 60_000L,
        tokenCalls: MutableList<Boolean> = CopyOnWriteArrayList(),
    ): NearbyClient {
        var i = 0
        return NearbyClient(
            url = server.url("/").toString().trimEnd('/'),
            tokenProvider = { force ->
                tokenCalls += force
                tokens[minOf(i++, tokens.size - 1)]
            },
            scope = scope,
            backoff = fastBackoff,
            renewAfterMs = renewAfterMs,
        )
    }

    private fun upgrade() = MockResponse().withWebSocketUpgrade(serverListener)

    private suspend fun until(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(10) }
    }

    @Test
    fun conectaComTokenNaQueryERecebeVizinhos() = runBlocking {
        server.enqueue(upgrade())
        val c = client()
        val ev = scope.async { c.events.first() }
        yield()
        c.connect(setOf("-21:-41"))
        until { sockets.isNotEmpty() }
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/ws", req.requestUrl!!.encodedPath)
        assertEquals("-21:-41", req.requestUrl!!.queryParameter("cell"))
        assertEquals("tok", req.requestUrl!!.queryParameter("t"))
        until { c.status.value == NearbyStatus.CONECTADO }
        sockets[0].send("lixo nao json")
        sockets[0].send(vizinhosJson)
        val e = withTimeout(3_000) { ev.await() }
        assertEquals("-21:-41", e.celula)
        val msg = e.msg as ServerMessage.Vizinhos
        assertEquals("s1", msg.lista[0].sessao)
    }

    @Test
    fun enviaMensagemDoClienteComoTexto() = runBlocking {
        server.enqueue(upgrade())
        val c = client()
        c.connect(setOf("-21:-41"))
        until { c.status.value == NearbyStatus.CONECTADO }
        c.send(ClientMessage.Parar)
        until { received.isNotEmpty() }
        assertEquals("""{"t":"parar"}""", received[0])
    }

    @Test
    fun reconectaDepoisDeQueda() = runBlocking {
        server.enqueue(upgrade())
        server.enqueue(upgrade())
        val c = client()
        c.connect(setOf("-21:-41"))
        until { sockets.size == 1 && c.status.value == NearbyStatus.CONECTADO }
        sockets[0].close(1011, "queda")
        until { sockets.size == 2 }
        until { c.status.value == NearbyStatus.CONECTADO }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun renovaTokenEmCasoDe401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(upgrade())
        val calls = CopyOnWriteArrayList<Boolean>()
        val c = client(tokens = listOf("velho", "novo"), tokenCalls = calls)
        c.connect(setOf("-21:-41"))
        until { sockets.isNotEmpty() }
        assertEquals(listOf(false, true), calls.toList())
        server.takeRequest(1, TimeUnit.SECONDS)
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("novo", second.requestUrl!!.queryParameter("t"))
    }

    @Test
    fun respostaCincoXXViraIndisponivelETentaDeNovo() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(upgrade())
        val c = client()
        c.connect(setOf("-21:-41"))
        until { sockets.isNotEmpty() }
        until { c.status.value == NearbyStatus.CONECTADO }
        assertTrue(server.requestCount >= 2)
    }

    @Test
    fun cincoXXSemNovaTentativaFicaIndisponivel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        val c = client()
        c.connect(setOf("-21:-41"))
        until { c.status.value == NearbyStatus.INDISPONIVEL }
    }

    @Test
    fun semRedeFicaIndisponivel() = runBlocking {
        val c = client()
        server.shutdown()
        c.connect(setOf("-21:-41"))
        until { c.status.value == NearbyStatus.INDISPONIVEL }
    }

    @Test
    fun renovaProativamenteAntesDoLimite() = runBlocking {
        server.enqueue(upgrade())
        server.enqueue(upgrade())
        val calls = CopyOnWriteArrayList<Boolean>()
        val c = client(tokens = listOf("a", "b"), renewAfterMs = 300, tokenCalls = calls)
        c.connect(setOf("-21:-41"))
        until { sockets.size >= 2 }
        assertEquals(false, calls[0])
        assertEquals(true, calls[1])
        server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("b", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("t"))
    }

    @Test
    fun umaConexaoPorQuadradoEDisconnectCellFechaSoAquela() = runBlocking {
        server.enqueue(upgrade())
        server.enqueue(upgrade())
        val c = client()
        c.connect(setOf("-21:-41", "-21:-42"))
        until { sockets.size == 2 }
        val cells = (0 until 2).map { server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("cell") }.toSet()
        assertEquals(setOf("-21:-41", "-21:-42"), cells)
        c.disconnectCell("-21:-41")
        delay(300)
        assertEquals(2, server.requestCount)
        assertEquals(NearbyStatus.CONECTADO, c.status.value)
        c.disconnect()
        assertEquals(NearbyStatus.DESCONECTADO, c.status.value)
    }

    @Test
    fun conexaoDeEncontroUsaEncNaUrlEPublicaComOQuadrado() = runBlocking {
        server.enqueue(upgrade())
        val c = client()
        val ev = scope.async { c.events.first() }
        yield()
        c.connectEncounter("-21:-41", "enc1")
        until { sockets.isNotEmpty() }
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("-21:-41", req.requestUrl!!.queryParameter("cell"))
        assertEquals("enc1", req.requestUrl!!.queryParameter("enc"))
        sockets[0].send(vizinhosJson)
        assertEquals("-21:-41", withTimeout(3_000) { ev.await() }.celula)
    }

    @Test
    fun convertePrefixoDeEsquema() {
        assertTrue(NearbyClient.wsUrl("https://x.workers.dev/", "1:2", null, "k").startsWith("wss://x.workers.dev/ws?"))
        assertTrue(NearbyClient.wsUrl("http://localhost:8787", "1:2", "e", "k").startsWith("ws://localhost:8787/ws?"))
        assertTrue(NearbyClient.wsUrl("wss://a.b", "1:2", null, "k").startsWith("wss://a.b/ws?"))
    }
}
