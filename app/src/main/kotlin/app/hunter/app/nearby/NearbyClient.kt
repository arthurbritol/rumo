package app.hunter.app.nearby

import app.hunter.nearby.ClientMessage
import app.hunter.nearby.ServerMessage
import app.hunter.nearby.parseServerMessage
import app.hunter.nearby.toJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

enum class NearbyStatus { DESCONECTADO, CONECTANDO, CONECTADO, INDISPONIVEL }

/** Texto de tela quando o recurso não está acessível (sem internet, limite do servidor, erro 5xx). */
const val NEARBY_INDISPONIVEL_TEXTO = "Rolê por perto indisponível agora"

/** Mensagem do servidor junto com o id do quadrado de onde chegou (a conexão de encontro usa o quadrado dado a ela). */
data class NearbyEvent(val celula: String, val msg: ServerMessage)

/**
 * Cliente WebSocket do Rolê por perto: uma conexão por quadrado, mais (opcionalmente) uma de encontro.
 * Reconecta com [backoff]; em 401 pede token novo; renova a conexão com token novo antes de [renewAfterMs]
 * (o ID token do Firebase vale 1 h). O token nunca é registrado em log.
 */
class NearbyClient(
    url: String,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val renewAfterMs: Long = 55 * 60_000L,
    private val http: OkHttpClient = defaultHttp,
) : NearbyLink {
    private val baseUrl = url
    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<NearbyEvent> = _events

    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val opened: SharedFlow<String> = _opened

    private val _status = MutableStateFlow(NearbyStatus.DESCONECTADO)
    override val status: StateFlow<NearbyStatus> = _status

    private val lock = Any()
    private val closing = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocket>()
    private val conns = LinkedHashMap<String, Conn>() // chave: quadrado, ou "enc|quadrado|id"
    private var encKey: String? = null

    /** Passa a manter exatamente uma conexão por quadrado de [cells]; fecha as que saíram. */
    override fun connect(cells: Set<String>) {
        synchronized(lock) {
            for (k in conns.keys.filter { !it.startsWith(ENC) && it !in cells }) conns.remove(k)?.stop()
            for (cell in cells) if (cell !in conns) conns[cell] = startConn(cell, cell, null)
            recompute()
        }
    }

    /** Conexão extra do encontro: `/ws?cell=<cell>&enc=<encId>&t=<token>`. Substitui a anterior, se houver. */
    fun connectEncounter(cell: String, encId: String) {
        synchronized(lock) {
            val key = "$ENC|$cell|$encId"
            if (encKey == key && conns.containsKey(key)) return
            encKey?.let { conns.remove(it)?.stop() }
            conns[key] = startConn(key, cell, encId)
            encKey = key
            recompute()
        }
    }

    fun disconnectEncounter() {
        synchronized(lock) {
            encKey?.let { conns.remove(it)?.stop() }
            encKey = null
            recompute()
        }
    }

    fun disconnectCell(cell: String) {
        synchronized(lock) {
            conns.remove(cell)?.stop()
            recompute()
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            conns.values.forEach { it.stop() }
            conns.clear()
            encKey = null
            recompute()
        }
    }

    /**
     * Manda a mensagem por todas as conexões de quadrado abertas ([cell] nulo: posicao, procurar, parar)
     * ou só pela do quadrado [cell] (proposta, entrar, cancelar, sair). Quadrado não conectado: não faz nada.
     */
    override fun send(msg: ClientMessage, cell: String?) {
        val text = msg.toJson()
        val alvo = synchronized(lock) {
            if (cell == null) conns.filterKeys { !it.startsWith(ENC) }.values.toList()
            else listOfNotNull(conns[cell])
        }
        alvo.forEach { it.ws?.send(text) }
    }

    /** Manda a mensagem só pela conexão do encontro. */
    fun sendEncounter(msg: ClientMessage) {
        val text = msg.toJson()
        val c = synchronized(lock) { encKey?.let { conns[it] } }
        c?.ws?.send(text)
    }

    // ---- interno ----

    private fun startConn(key: String, cell: String, encId: String?): Conn {
        val c = Conn(key, cell, encId)
        c.job = scope.launch { c.run() }
        return c
    }

    private fun recompute() {
        val all = conns.values.map { it.state }
        _status.value = when {
            all.isEmpty() -> NearbyStatus.DESCONECTADO
            all.any { it == NearbyStatus.CONECTADO } -> NearbyStatus.CONECTADO
            all.any { it == NearbyStatus.INDISPONIVEL } -> NearbyStatus.INDISPONIVEL
            else -> NearbyStatus.CONECTANDO
        }
    }

    /** Fecha com `close(1000)` (entrega antes o que está na fila, como um `Parar`) e cancela se não fechar a tempo. */
    private fun closeGracefully(w: WebSocket) {
        if (!closing.add(w)) return // já fechando (stop() e o cancelamento do job passam os dois por aqui)
        w.close(1000, null)
        CoroutineScope(Dispatchers.Default).launch {
            delay(CLOSE_GRACE_MS)
            w.cancel()
            closing.remove(w)
        }
    }

    private sealed interface Outcome {
        data object Closed : Outcome
        data class Failed(val code: Int?) : Outcome
    }

    private inner class Conn(val key: String, val cell: String, val encId: String?) {
        @Volatile var ws: WebSocket? = null
        @Volatile var state = NearbyStatus.CONECTANDO
        lateinit var job: Job

        fun stop() {
            job.cancel()
            val w = ws
            ws = null
            if (w != null) closeGracefully(w)
        }

        private fun mudar(s: NearbyStatus) {
            synchronized(lock) {
                if (conns[key] !== this) return
                state = s
                recompute()
            }
        }

        suspend fun run() {
            var attempt = 0
            var force = false
            var last401 = false
            while (scope.isActive) {
                mudar(NearbyStatus.CONECTANDO)
                val token = try {
                    tokenProvider(force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                force = false
                if (token == null) {
                    mudar(NearbyStatus.INDISPONIVEL)
                    delay(backoff.nextDelayMs(attempt++))
                    continue
                }
                currentCoroutineContext().ensureActive()
                val done = CompletableDeferred<Outcome>()
                var gotMessage = false
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        last401 = false
                        attempt = 0 // conexão que abriu zera a espera: a próxima queda recomeça do básico
                        mudar(NearbyStatus.CONECTADO)
                        if (encId == null) _opened.tryEmit(cell)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        gotMessage = true
                        val m = parseServerMessage(text) ?: return
                        _events.tryEmit(NearbyEvent(cell, m))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        done.complete(Outcome.Closed)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        done.complete(Outcome.Closed)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        done.complete(Outcome.Failed(response?.code))
                    }
                }
                val socket = try {
                    http.newWebSocket(Request.Builder().url(wsUrl(baseUrl, cell, encId, token)).build(), listener)
                } catch (e: IllegalArgumentException) {
                    mudar(NearbyStatus.INDISPONIVEL)
                    delay(backoff.nextDelayMs(attempt++))
                    continue
                }
                ws = socket
                val outcome = try {
                    withTimeoutOrNull(renewAfterMs) { done.await() }
                } catch (e: CancellationException) {
                    closeGracefully(socket)
                    throw e
                }
                ws = null
                when (outcome) {
                    null -> { // perto do vencimento do token: reconecta com token novo
                        socket.close(1000, "renovando")
                        force = true
                        attempt = 0
                    }
                    is Outcome.Closed -> {
                        if (gotMessage) attempt = 0
                        mudar(NearbyStatus.CONECTANDO)
                        delay(backoff.nextDelayMs(attempt++))
                    }
                    is Outcome.Failed -> {
                        socket.cancel()
                        if (outcome.code == 401 && !last401) {
                            last401 = true
                            force = true
                            attempt = 0
                        } else {
                            mudar(NearbyStatus.INDISPONIVEL)
                            force = outcome.code == 401
                            delay(backoff.nextDelayMs(attempt++))
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ENC = "enc"
        private const val CLOSE_GRACE_MS = 1_000L

        private val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        /** Endereço do WebSocket: `https` vira `wss`, `http` vira `ws`; `ws`/`wss` ficam como estão. */
        fun wsUrl(base: String, cell: String, encId: String?, token: String): String {
            val trimmed = base.trim().trimEnd('/')
            val (scheme, rest) = when {
                trimmed.startsWith("https://") -> "wss" to trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws" to trimmed.removePrefix("http://")
                trimmed.startsWith("wss://") -> "wss" to trimmed.removePrefix("wss://")
                trimmed.startsWith("ws://") -> "ws" to trimmed.removePrefix("ws://")
                else -> "wss" to trimmed
            }
            val http: HttpUrl = ("${if (scheme == "wss") "https" else "http"}://$rest").toHttpUrl()
            val b = http.newBuilder().addPathSegment("ws").addQueryParameter("cell", cell)
            if (encId != null) b.addQueryParameter("enc", encId)
            b.addQueryParameter("t", token)
            return scheme + "://" + b.build().toString().substringAfter("://")
        }
    }
}
