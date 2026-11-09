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
 */
class AndroidLocationSource(
    context: Context,
    private val intervalMs: Long = 1_000L,
) : LocationSource {

    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // a permissão é verificada por quem inicia o serviço; SecurityException sobe pelo fluxo
    override fun updates(): Flow<TrackPoint> = callbackFlow {
        val listener = LocationListener { location -> trySend(location.toTrackPoint()) }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
        awaitClose { manager.removeUpdates(listener) }
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
