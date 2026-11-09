package app.hunter.trips.recording

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.hunter.model.TrackPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Fonte de pontos de GPS; abstraída para trocar (ex.: Fused Location) sem mexer no recorder/serviço. */
fun interface LocationSource {
    fun updates(): Flow<TrackPoint>
}

/**
 * GPS puro via [LocationManager] (sem Google Play Services). 1 Hz, sem distância mínima.
 * Lança [SecurityException] no fluxo se a permissão de localização não estiver concedida.
 *
 * Com [includeNetwork] (usado pelo mapa) também aceita a posição aproximada por Wi-Fi/antenas e emite a última
 * posição conhecida na hora: o mapa e a rota funcionam em ambiente fechado, antes de o GPS "fixar". A posição
 * de rede só entra enquanto o GPS está mudo (>8 s sem fix). A gravação de viagens usa só GPS.
 */
class AndroidLocationSource(
    context: Context,
    private val intervalMs: Long = 1_000L,
    private val includeNetwork: Boolean = false,
) : LocationSource {

    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // a permissão é verificada por quem inicia o serviço; SecurityException sobe pelo fluxo
    override fun updates(): Flow<TrackPoint> = callbackFlow {
        var lastGpsAt = 0L
        val gps = LocationListener { location ->
            lastGpsAt = System.currentTimeMillis()
            trySend(location.toTrackPoint())
        }
        val net = LocationListener { location ->
            if (System.currentTimeMillis() - lastGpsAt > GPS_SILENCE_MS) trySend(location.toTrackPoint())
        }

        if (includeNetwork) {
            runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()?.let { trySend(it.toTrackPoint()) }
                ?: runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()?.let { trySend(it.toTrackPoint()) }
        }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, gps, Looper.getMainLooper())
        if (includeNetwork && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            runCatching { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3_000L, 0f, net, Looper.getMainLooper()) }
        }
        awaitClose {
            manager.removeUpdates(gps)
            manager.removeUpdates(net)
        }
    }

    private companion object {
        const val GPS_SILENCE_MS = 8_000L
    }
}

internal fun Location.toTrackPoint() = TrackPoint(
    timeMs = time,
    latitude = latitude,
    longitude = longitude,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed else null,
    accuracyM = if (hasAccuracy()) accuracy else null,
    bearingDeg = if (hasBearing()) bearing else null,
)
