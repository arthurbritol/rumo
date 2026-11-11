package app.hunter.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Busca de endereços/lugares com o Photon (OpenStreetMap), gratuito e bom para autocompletar. */
class PhotonGeocoder(
    // Busca precisa responder rápido: falha em 8 s e tenta mais uma vez (as rotas têm prazos maiores).
    private val http: Http = UrlConnectionHttp(readTimeoutMs = 8_000, attempts = 2),
    private val baseUrl: String = "https://photon.komoot.io",
) {
    /** [near] enviesa os resultados para perto de onde o usuário está. */
    suspend fun search(query: String, near: LatLon? = null, limit: Int = 6): List<Place> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        // Cache por texto + região aproximada (~1 km): repetir ou apagar/redigitar é instantâneo.
        val key = "${q.lowercase()}|${near?.let { "%.2f,%.2f".format(it.lat, it.lon) }.orEmpty()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&location_bias_scale=0.4" }.orEmpty()
        val url = "$baseUrl/api/?q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit$bias"
        val result = parse(http.get(url))
        if (result.isNotEmpty()) synchronized(cache) { cache[key] = result }
        return result
    }

    private val cache = object : LinkedHashMap<String, List<Place>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Place>>?) = size > 60
    }

    /** Endereço aproximado de um ponto tocado no mapa. */
    suspend fun reverse(p: LatLon): Place? =
        parse(http.get("$baseUrl/reverse?lat=${p.lat}&lon=${p.lon}&limit=1")).firstOrNull()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): List<Place> {
            val features = json.parseToJsonElement(text).jsonObject["features"] as? JsonArray ?: return emptyList()
            val seen = HashSet<String>()
            return features.mapNotNull { f ->
                val o = f.jsonObject
                val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
                val lon = coords[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val lat = coords[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val p = o["properties"]?.jsonObject ?: return@mapNotNull null
                val place = toPlace(p, lat, lon) ?: return@mapNotNull null
                if (seen.add("${place.name}|${place.subtitle}")) place else null
            }
        }

        private fun JsonObject.str(k: String) = get(k)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        private fun toPlace(p: JsonObject, lat: Double, lon: Double): Place? {
            val street = p.str("street")
            val house = p.str("housenumber")
            val name = p.str("name")
                ?: street?.let { if (house != null) "$it, $house" else it }
                ?: if (p.str("osm_value") == "fuel" || p.str("osm_key") == "amenity") "Posto de Combustível" else return null
            val sub = listOfNotNull(
                street?.takeIf { it != name && p.str("name") != null },
                p.str("district"),
                p.str("city"),
                p.str("state"),
            ).distinct().filter { it != name }.joinToString(", ").ifEmpty { p.str("country") }
            return Place(name, sub, lat, lon, street = street, houseNumber = house, postcode = p.str("postcode"))
        }
    }
}
