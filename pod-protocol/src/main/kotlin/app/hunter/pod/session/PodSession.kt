package app.hunter.pod.session

import app.hunter.pod.PodConstants
import app.hunter.pod.PodPackets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime

/** Escreve um pacote de 20 bytes e só retorna quando o anterior foi confirmado; lança em caso de falha. */
fun interface PodTransport {
    suspend fun write(packet: ByteArray)
}

enum class SessionState { IDLE, HANDSHAKING, AWAITING_PIN, CONNECTED, CLOSED }

/**
 * Lógica do enlace com o Pod, independente de Bluetooth (ver docs/PROTOCOL.md §1 e §3 do WazeTripper):
 *  - handshake: dispositivo conhecido (`21 00` → hora → 2×PING, com 200/150/300 ms) ou novo (`21 01` + PIN);
 *  - escritas serializadas (um mutex; o transporte espera a confirmação de cada uma);
 *  - watchdog do firmware: PING após [PodConstants.PING_INTERVAL_MS] sem escrita e a tela atual reenviada a
 *    cada [PodConstants.SCREEN_REFRESH_MS], conferindo a cada 1 s.
 * Qualquer falha de escrita fecha a sessão e chama [onFailure].
 */
class PodSession(
    private val transport: PodTransport,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val localTime: () -> LocalTime = LocalTime::now,
    private val is12h: Boolean = false,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state

    private val mutex = Mutex()
    private var lastWriteAt = Long.MIN_VALUE / 2
    private var lastScreenAt = Long.MIN_VALUE / 2
    private var screen: ByteArray? = null
    private var keepAlive: Job? = null

    /** Dispositivo já pareado: retoma sem PIN. Retorna com a sessão em CONNECTED (ou CLOSED se falhou). */
    suspend fun resume() {
        _state.value = SessionState.HANDSHAKING
        if (!send(PodPackets.handshake(newDevice = false))) return
        startKeepAlive()
        delay(PodConstants.RESUME_TO_SET_TIME_DELAY_MS)
        val t = localTime()
        if (!send(PodPackets.setTime(t.hour, t.minute, is12h))) return
        delay(PodConstants.SET_TIME_TO_PING_DELAY_MS)
        if (!send(PodPackets.ping()) || !send(PodPackets.ping())) return
        delay(PodConstants.PINGS_TO_CONNECTED_DELAY_MS)
        if (_state.value == SessionState.HANDSHAKING) _state.value = SessionState.CONNECTED
    }

    /** Dispositivo novo: o Pod mostra um PIN; a sessão espera [submitPin]. */
    suspend fun beginPairing() {
        _state.value = SessionState.HANDSHAKING
        if (!send(PodPackets.handshake(newDevice = true))) return
        startKeepAlive()
        if (_state.value == SessionState.HANDSHAKING) _state.value = SessionState.AWAITING_PIN
    }

    /** Envia o PIN digitado pelo usuário; o WazeTripper considera conectado logo em seguida. */
    suspend fun submitPin(pin: String): Boolean {
        if (_state.value != SessionState.AWAITING_PIN || pin.isBlank()) return false
        if (!send(PodPackets.pin(pin.trim()))) return false
        _state.value = SessionState.CONNECTED
        return true
    }

    /**
     * Define a tela que o Pod deve mostrar (bússola/navegação): é enviada já e reenviada a cada ~2 s.
     * `null` para de reenviar (o Pod só volta ao relógio se ele mesmo derrubar o link; ver PROTOCOL.md §4.1).
     */
    fun setScreen(packet: ByteArray?) {
        screen = packet
        if (packet != null && _state.value == SessionState.CONNECTED) {
            scope.launch { if (send(packet)) lastScreenAt = now() }
        }
    }

    fun close() {
        keepAlive?.cancel()
        keepAlive = null
        _state.value = SessionState.CLOSED
    }

    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = scope.launch {
            while (isActive && _state.value != SessionState.CLOSED) {
                delay(1_000)
                val t = now()
                val s = screen
                if (s != null && _state.value == SessionState.CONNECTED && t - lastScreenAt >= PodConstants.SCREEN_REFRESH_MS) {
                    if (send(s)) lastScreenAt = t
                } else if (t - lastWriteAt >= PodConstants.PING_INTERVAL_MS) {
                    send(PodPackets.ping())
                }
            }
        }
    }

    /** @return false se a escrita falhou (a sessão foi fechada). */
    private suspend fun send(packet: ByteArray): Boolean {
        if (_state.value == SessionState.CLOSED) return false
        return mutex.withLock {
            try {
                transport.write(packet)
                lastWriteAt = now()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                close()
                onFailure(e)
                false
            }
        }
    }
}
