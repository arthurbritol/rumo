package app.hunter.app.nearby

import app.hunter.model.Geo
import app.hunter.nav.LatLon
import app.hunter.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Distância (km) e tempo (min) até o ponto, arredondados (1 casa e minuto inteiro). */
data class Eta(val distKm: Double, val etaMin: Double)

/**
 * Rota e tempo até o ponto do encontro, calculados aqui (o servidor não confia nem usa o tempo, só mostra aos outros).
 * [router] é o roteador do app (Valhalla); se falhar ou não achar rota, vale a linha reta a [VELOCIDADE_RETA_KMH].
 */
class EtaCalculator(
    private val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMs: Long = 5_000L,
    private val router: suspend (from: LatLon, to: LatLon) -> Route?,
) {
    /** Rota do roteador, ou a linha reta se ele falhar, não achar rota ou passar de [timeoutMs]. */
    suspend fun eta(from: LatLon, to: LatLon): Eta = etaRota(from, to) ?: linhaReta(from, to)

    /**
     * Só a rota: `null` se o roteador falhou, demorou mais que [timeoutMs] ou não achou. O roteador roda numa thread
     * de E/S que é interrompida no tempo limite (uma chamada de rede bloqueante não segura o chamador).
     */
    suspend fun etaRota(from: LatLon, to: LatLon): Eta? {
        val rota = try {
            // A chamada roda na própria coroutine de E/S: ao estourar o tempo o chamador segue na hora (o slot "em voo"
            // do ViewModel libera) e o resultado tardio, se vier, é descartado. Cliente bloqueante que ignora a
            // interrupção só termina quando a rede responder, mas já sem prender ninguém.
            val chamada = io.async { runInterruptible { runBlocking { router(from, to) } } }
            val r = withTimeoutOrNull(timeoutMs) { chamada.await() }
            if (r == null) chamada.cancel()
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (rota != null && rota.distanceM.isFinite() && rota.durationS.isFinite()) {
            return arredondar(rota.distanceM / 1000.0, rota.durationS / 60.0)
        }
        return null
    }

    /** Sem rede: distância em linha reta e tempo a 40 km/h. */
    fun linhaReta(from: LatLon, to: LatLon): Eta {
        val km = Geo.distanceM(from.lat, from.lon, to.lat, to.lon) / 1000.0
        return arredondar(km, km / VELOCIDADE_RETA_KMH * 60.0)
    }

    private fun arredondar(km: Double, min: Double) =
        Eta((km * 10).roundToInt() / 10.0, min.roundToInt().toDouble())

    companion object {
        const val VELOCIDADE_RETA_KMH = 40.0
    }
}
