package app.hunter.app.nearby

import app.hunter.model.Geo
import app.hunter.nav.LatLon
import app.hunter.nav.Route
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Distância (km) e tempo (min) até o ponto, arredondados (1 casa e minuto inteiro). */
data class Eta(val distKm: Double, val etaMin: Double)

/**
 * Rota e tempo até o ponto do encontro, calculados aqui (o servidor não confia nem usa o tempo, só mostra aos outros).
 * [router] é o roteador do app (Valhalla); se falhar ou não achar rota, vale a linha reta a [VELOCIDADE_RETA_KMH].
 */
class EtaCalculator(private val router: suspend (from: LatLon, to: LatLon) -> Route?) {

    suspend fun eta(from: LatLon, to: LatLon): Eta {
        val rota = try {
            router(from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (rota != null && rota.distanceM.isFinite() && rota.durationS.isFinite()) {
            return arredondar(rota.distanceM / 1000.0, rota.durationS / 60.0)
        }
        return linhaReta(from, to)
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
