package app.hunter.nav

import app.hunter.model.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Postos de combustível (OpenStreetMap, `amenity=fuel`) perto de um ponto, via Overpass API. */
class FuelStationFinder(
    private val http: Http = UrlConnectionHttp(),
    private val baseUrl: String = "https://overpass-api.de/api/interpreter",
) {
    /** Os [limit] postos mais próximos de [p] dentro de [radiusM] metros. */
    suspend fun near(p: LatLon, radiusM: Int = 4_000, limit: Int = 6): List<Place> {
        val query = "[out:json][timeout:15];node(around:$radiusM,${p.lat},${p.lon})[amenity=fuel];out 30;"
        val text = http.get("$baseUrl?data=${URLEncoder.encode(query, "UTF-8")}")
        return parse(text, p).take(limit)
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
