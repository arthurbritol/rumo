package app.hunter.app

import android.content.Context
import app.hunter.app.ui.STYLE_DARK
import app.hunter.app.ui.STYLE_LIGHT
import app.hunter.model.GeoBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** Uma área baixada (ou em download). Representa o par de regiões claro/escuro. */
data class OfflineArea(
    val name: String,
    val completed: Long,
    val required: Long,
    val bytes: Long,
    val complete: Boolean,
    val error: String? = null,
) {
    val fraction: Float get() = if (required <= 0) 0f else (completed.toFloat() / required).coerceIn(0f, 1f)
}

/**
 * Mapas offline (MapLibre OfflineManager). Cada área vira duas regiões — estilo claro e escuro — que
 * compartilham os mesmos tiles no cache. Só o mapa funciona sem internet: rota e busca ainda precisam dela.
 */
object OfflineMaps {
    const val MIN_ZOOM = 6.0
    const val MAX_ZOOM = 14.0
    /** Tamanho médio de um tile vetorial (estimativa para mostrar o tamanho do download). */
    private const val AVG_TILE_KB = 25

    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas.asStateFlow()

    private class Entry(val region: OfflineRegion, val name: String, var completed: Long = 0, var required: Long = 0, var bytes: Long = 0, var complete: Boolean = false, var error: String? = null)

    private val entries = LinkedHashMap<Long, Entry>()

    fun estimateMb(box: GeoBox): Int = (box.tileCount(MIN_ZOOM.toInt(), MAX_ZOOM.toInt()) * AVG_TILE_KB / 1024).toInt().coerceAtLeast(1)

    private fun manager(context: Context): OfflineManager {
        MapLibre.getInstance(context.applicationContext)
        return OfflineManager.getInstance(context.applicationContext)
    }

    /** Lê do disco as áreas já baixadas e retoma downloads incompletos. */
    fun refresh(context: Context) {
        manager(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                synchronized(entries) {
                    entries.clear()
                    offlineRegions?.forEach { r ->
                        val name = runCatching { JSONObject(String(r.metadata, Charsets.UTF_8)).getString("name") }.getOrDefault("Área")
                        val e = Entry(r, name)
                        entries[r.id] = e
                        observe(r, e)
                        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                if (status != null) apply(e, status, resume = true)
                            }
                            override fun onError(error: String?) { e.error = error; publish() }
                        })
                    }
                }
                publish()
            }

            override fun onError(error: String) {
                // sem lista: a UI mostra vazio
            }
        })
    }

    /** Baixa [box] para o estilo claro e o escuro. */
    fun download(context: Context, name: String, box: GeoBox) {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(box.minLat, box.minLon))
            .include(LatLng(box.maxLat, box.maxLon))
            .build()
        val ratio = context.resources.displayMetrics.density
        for ((style, tag) in listOf(STYLE_LIGHT to "light", STYLE_DARK to "dark")) {
            val def = OfflineTilePyramidRegionDefinition(style, bounds, MIN_ZOOM, MAX_ZOOM, ratio)
            val meta = JSONObject().put("name", name).put("style", tag).toString().toByteArray(Charsets.UTF_8)
            manager(context).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    val e = Entry(offlineRegion, name)
                    synchronized(entries) { entries[offlineRegion.id] = e }
                    observe(offlineRegion, e)
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    publish()
                }

                override fun onError(error: String) {
                    _lastError.value = "Não foi possível baixar \"$name\": $error"
                }
            })
        }
    }

    private val _lastError = MutableStateFlow<String?>(null)
    /** Último erro ao criar um download (a UI mostra e limpa com [clearError]). */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    fun clearError() { _lastError.value = null }

    /** Apaga a área (as duas regiões). */
    fun delete(name: String) {
        val victims = synchronized(entries) { entries.values.filter { it.name == name } }
        victims.forEach { e ->
            e.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            e.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    synchronized(entries) { entries.remove(e.region.id) }
                    publish()
                }

                override fun onError(error: String) {
                    e.error = error
                    publish()
                }
            })
        }
    }

    private fun observe(r: OfflineRegion, e: Entry) {
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = apply(e, status, resume = false)
            override fun onError(error: OfflineRegionError) { e.error = error.message; publish() }
            override fun mapboxTileCountLimitExceeded(limit: Long) { e.error = "Limite de tiles excedido"; publish() }
        })
    }

    private fun apply(e: Entry, s: OfflineRegionStatus, resume: Boolean) {
        e.completed = s.completedResourceCount
        e.required = s.requiredResourceCount
        e.bytes = s.completedResourceSize
        e.complete = s.isComplete
        if (resume && !s.isComplete && s.downloadState == OfflineRegion.STATE_INACTIVE) e.region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        if (s.isComplete) e.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        publish()
    }

    private fun publish() {
        val list = synchronized(entries) {
            entries.values.groupBy { it.name }.map { (name, es) ->
                OfflineArea(
                    name = name,
                    completed = es.minOf { it.completed },
                    required = es.maxOf { it.required },
                    bytes = es.maxOf { it.bytes }, // as duas regiões compartilham tiles: não soma
                    complete = es.all { it.complete },
                    error = es.firstNotNullOfOrNull { it.error },
                )
            }
        }
        _areas.value = list
    }
}
