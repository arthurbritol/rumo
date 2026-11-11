package app.hunter.nav

import app.hunter.model.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/** Postos de combustível (OpenStreetMap, `amenity=fuel`) perto de um ponto, via Komoot Photon (rápido) com fallback Overpass. */
class FuelStationFinder(
    private val http: Http = UrlConnectionHttp(connectTimeoutMs = 4_000, readTimeoutMs = 6_000, attempts = 2),
    private val baseUrl: String = "https://overpass.kumi.systems/api/interpreter",
    private val photonUrl: String = "https://photon.komoot.io",
) {
    /**
     * Os [limit] postos mais próximos de [p] dentro de [radiusM] metros.
     *
     * O Photon só acha pelo NOME, e a maioria dos postos se chama "Shell", "Ipiranga", "BR"… e não "Posto". Por isso
     * fazemos uma consulta por termo/bandeira em paralelo e juntamos tudo (sem repetir o mesmo ponto). O Overpass
     * (nós, vias e áreas) entra em paralelo como reforço, pois os servidores públicos oscilam.
     */
    suspend fun near(p: LatLon, radiusM: Int = 8_000, limit: Int = 30): List<Place> = coroutineScope {
        val photon = if (photonUrl.isBlank()) emptyList() else SEARCH_TERMS.map { term ->
            async { runCatching { photonSearch(term, p) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList()) }
        }
        val overpass: Deferred<List<Place>>? = if (baseUrl.isBlank()) null else async {
            runCatching { overpassSearch(p, radiusM) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
        }
        val all = photon.awaitAll().flatten() + (overpass?.let { withTimeoutOrNull(OVERPASS_WAIT_MS) { it.await() } } ?: emptyList())
        all.asSequence()
            .map { it to Geo.distanceM(p.lat, p.lon, it.lat, it.lon) }
            .filter { it.second <= radiusM.toDouble() }
            .sortedBy { it.second }
            .distinctBy { "%.4f,%.4f".format(java.util.Locale.ROOT, it.first.lat, it.first.lon) }
            .map { it.first }
            .take(limit)
            .toList()
    }

    private suspend fun photonSearch(term: String, p: LatLon): List<Place> {
        val url = "$photonUrl/api/?q=${URLEncoder.encode(term, "UTF-8")}&lat=${p.lat}&lon=${p.lon}&limit=40&osm_tag=amenity:fuel"
        return PhotonGeocoder.parse(http.get(url)).map { place ->
            if (place.name.isBlank() || place.name.equals("posto", ignoreCase = true)) place.copy(name = "Posto de Combustível") else place
        }
    }

    private suspend fun overpassSearch(p: LatLon, radiusM: Int): List<Place> {
        val query = "[out:json][timeout:8];nwr(around:$radiusM,${p.lat},${p.lon})[amenity=fuel];out center 40;"
        return parse(http.get("$baseUrl?data=${URLEncoder.encode(query, "UTF-8")}"), p)
    }

    companion object {
        private val SEARCH_TERMS = listOf("posto", "shell", "ipiranga", "br", "petrobras", "ale")
        private const val OVERPASS_WAIT_MS = 7_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** Ordena do mais perto ao mais longe de [origin]; o subtítulo informa a distância. */
        fun parse(text: String, origin: LatLon): List<Place> {
            val elements = json.parseToJsonElement(text).jsonObject["elements"] as? JsonArray ?: return emptyList()
            return elements.mapNotNull { e ->
                val o = e.jsonObject
                // Nós trazem lat/lon; vias e áreas (muito comum em posto) trazem o ponto central em "center".
                val center = o["center"] as? JsonObject
                val lat = (o["lat"] ?: center?.get("lat"))?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = (o["lon"] ?: center?.get("lon"))?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val tags = o["tags"] as? JsonObject
                fun tag(k: String) = tags?.get(k)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val name = tag("name") ?: tag("brand") ?: tag("operator") ?: "Posto de combustível"
                val d = Geo.distanceM(origin.lat, origin.lon, lat, lon)
                Triple(d, name, Place(
                    name,
                    listOfNotNull(tag("addr:street"), "a ${(d / 100).toInt() / 10.0} km do ponto".replace('.', ',')).joinToString(" · "),
                    lat, lon,
                    street = tag("addr:street"), houseNumber = tag("addr:housenumber"), postcode = tag("addr:postcode"),
                ))
            }.sortedBy { it.first }.map { it.third }
        }
    }
}
