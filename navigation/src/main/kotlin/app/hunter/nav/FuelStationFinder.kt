package app.hunter.nav

import app.hunter.model.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Postos de combustível (OpenStreetMap, `amenity=fuel`) perto de um ponto, via Komoot Photon (rápido) com fallback Overpass. */
class FuelStationFinder(
    private val http: Http = UrlConnectionHttp(connectTimeoutMs = 4_000, readTimeoutMs = 6_000, attempts = 2),
    private val baseUrl: String = "https://overpass.kumi.systems/api/interpreter",
    private val photonUrl: String = "https://photon.komoot.io",
) {
    /** Os [limit] postos mais próximos de [p] dentro de [radiusM] metros. */
    suspend fun near(p: LatLon, radiusM: Int = 8_000, limit: Int = 30): List<Place> {
        // 1. Tenta Komoot Photon primeiro: resposta ultra-rápida (típica < 1s)
        if (photonUrl.isNotBlank()) {
            try {
                val queryLimit = maxOf(limit * 2, 35)
                val queryUrl = "$photonUrl/api/?q=posto&lat=${p.lat}&lon=${p.lon}&limit=$queryLimit&osm_tag=amenity:fuel"
                val text = http.get(queryUrl)
                val rawPlaces = PhotonGeocoder.parse(text)
                val filtered = rawPlaces
                    .map { place ->
                        val cleanName = if (place.name.equals("posto", ignoreCase = true) || place.name.isBlank()) {
                            "Posto de Combustível"
                        } else {
                            place.name
                        }
                        place.copy(name = cleanName)
                    }
                    .map { it to Geo.distanceM(p.lat, p.lon, it.lat, it.lon) }
                    .filter { it.second <= radiusM.toDouble() }
                    .sortedBy { it.second }
                    .map { it.first }

                if (filtered.isNotEmpty()) return filtered.take(limit)
            } catch (_: Exception) {
                // Continua para fallback Overpass
            }
        }

        // 2. Fallback para Overpass API caso Photon esteja indisponível
        if (baseUrl.isNotBlank()) {
            try {
                val query = "[out:json][timeout:6];node(around:$radiusM,${p.lat},${p.lon})[amenity=fuel];out 30;"
                val text = http.get("$baseUrl?data=${URLEncoder.encode(query, "UTF-8")}")
                val places = parse(text, p)
                if (places.isNotEmpty()) return places.take(limit)
            } catch (_: Exception) {
                // ignora
            }
        }

        return emptyList()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Ordena do mais perto ao mais longe de [origin]; o subtítulo informa a distância. */
        fun parse(text: String, origin: LatLon): List<Place> {
            val elements = json.parseToJsonElement(text).jsonObject["elements"] as? JsonArray ?: return emptyList()
            return elements.mapNotNull { e ->
                val o = e.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val tags = o["tags"] as? JsonObject
                fun tag(k: String) = tags?.get(k)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val name = tag("name") ?: tag("brand") ?: tag("operator") ?: "Posto de combustível"
                val d = Geo.distanceM(origin.lat, origin.lon, lat, lon)
                Triple(d, name, Place(name, listOfNotNull(tag("addr:street"), "a ${(d / 100).toInt() / 10.0} km do ponto".replace('.', ',')).joinToString(" · "), lat, lon))
            }.sortedBy { it.first }.map { it.third }
        }
    }
}
