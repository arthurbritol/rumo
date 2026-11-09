package app.hunter.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rotas com o Valhalla (OpenStreetMap), custo `motorcycle`. O servidor público
 * (valhalla1.openstreetmap.de) é de uso justo: bom para uso pessoal; para uso intenso, hospedar o próprio.
 */
class ValhallaClient(
    private val http: Http = UrlConnectionHttp(),
    private val baseUrl: String = "https://valhalla1.openstreetmap.de",
) {
    /**
     * Rota principal primeiro; até [alternates] alternativas depois. Com [via] (paradas intermediárias, ex.: um
     * posto) a rota passa por elas na ordem e não há alternativas.
     */
    suspend fun route(from: LatLon, to: LatLon, profile: RouteProfile, alternates: Int = 0, via: List<LatLon> = emptyList()): List<Route> {
        val body = buildRequest(from, to, profile, if (via.isEmpty()) alternates else 0, via)
        val text = http.postJson("$baseUrl/route", body)
        return parse(text, profile)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun buildRequest(from: LatLon, to: LatLon, profile: RouteProfile, alternates: Int = 0, via: List<LatLon> = emptyList()): String {
            val stops = listOf(from) + via + to
            val locations = stops.joinToString(",") { """{"lat":${it.lat},"lon":${it.lon},"type":"break"}""" }
            val opts = when (profile) {
                RouteProfile.FASTEST -> """"use_highways":1.0,"use_tolls":0.5"""
                RouteProfile.CURVES -> """"use_highways":0.0,"use_tolls":0.0,"use_trails":0.0"""
                RouteProfile.NO_TOLLS -> """"use_highways":1.0,"use_tolls":0.0"""
                RouteProfile.NO_DIRT -> """"use_highways":1.0,"use_tolls":0.5,"use_trails":0.0,"exclude_unpaved":true"""
            }
            return """{"locations":[$locations],""" +
                """"costing":"motorcycle","costing_options":{"motorcycle":{$opts}},""" +
                """"directions_options":{"language":"pt-BR","units":"kilometers"},"alternates":$alternates}"""
        }

        fun parse(text: String, profile: RouteProfile): List<Route> {
            val root = json.parseToJsonElement(text).jsonObject
            val routes = mutableListOf<Route>()
            root["trip"]?.let { routes += parseTrip(it.jsonObject, profile) }
            (root["alternates"] as? JsonArray)?.forEach { alt ->
                alt.jsonObject["trip"]?.let { routes += parseTrip(it.jsonObject, profile) }
            }
            return routes
        }

        private fun parseTrip(trip: JsonObject, profile: RouteProfile): Route {
            // Com paradas intermediárias há uma "leg" por trecho: junta tudo numa geometria só (o primeiro ponto de
            // cada trecho repete o último do anterior) e desloca os índices das manobras.
            val shape = ArrayList<LatLon>()
            val maneuvers = ArrayList<Maneuver>()
            trip["legs"]!!.jsonArray.forEachIndexed { i, legEl ->
                val leg = legEl.jsonObject
                val pts = Polyline.decode(leg["shape"]!!.jsonPrimitive.content, 6)
                val offset = if (i == 0) 0 else shape.size - 1
                shape += if (i == 0) pts else pts.drop(1)
                leg["maneuvers"]!!.jsonArray.forEach { m ->
                    val mm = m.jsonObject.toManeuver()
                    maneuvers += mm.copy(beginShapeIndex = mm.beginShapeIndex + offset, endShapeIndex = mm.endShapeIndex + offset)
                }
            }
            val summary = trip["summary"]!!.jsonObject
            return Route(
                shape = shape,
                maneuvers = maneuvers,
                distanceM = summary["length"]!!.jsonPrimitive.double * 1000.0,
                durationS = summary["time"]!!.jsonPrimitive.double,
                hasToll = summary["has_toll"]?.jsonPrimitive?.boolean ?: false,
                hasHighway = summary["has_highway"]?.jsonPrimitive?.boolean ?: false,
                profile = profile,
            )
        }

        private fun JsonObject.toManeuver() = Maneuver(
            type = get("type")!!.jsonPrimitive.int,
            instruction = get("instruction")?.jsonPrimitive?.content.orEmpty(),
            verbalPre = get("verbal_pre_transition_instruction")?.jsonPrimitive?.content,
            streetName = (get("street_names") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content,
            lengthM = get("length")?.jsonPrimitive?.double?.times(1000.0) ?: 0.0,
            timeS = get("time")?.jsonPrimitive?.double ?: 0.0,
            beginShapeIndex = get("begin_shape_index")!!.jsonPrimitive.int,
            endShapeIndex = get("end_shape_index")!!.jsonPrimitive.int,
            roundaboutExit = get("roundabout_exit_count")?.jsonPrimitive?.int,
        )
    }
}
