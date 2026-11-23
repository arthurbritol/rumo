package app.hunter.nav.perigos

import app.hunter.model.Geo
import app.hunter.nav.Http
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale

/** De onde vêm os perigos de um quadrado. Trocável nos testes. */
interface PerigoSource {
    suspend fun buscar(tile: TileId): List<PerigoPonto>
}

/**
 * Consulta o Overpass (OpenStreetMap) por quadrado. Dados abertos e gratuitos, mas a cobertura no Brasil é incompleta:
 * o que ninguém mapeou não aparece. Tenta cada espelho de [espelhos] em ordem até um responder.
 * Não manda nada além do retângulo arredondado do quadrado (sem identificação de pessoa ou posição exata).
 */
class OverpassPerigoSource(
    private val http: Http,
    private val espelhos: List<String> = ESPELHOS,
) : PerigoSource {

    override suspend fun buscar(tile: TileId): List<PerigoPonto> {
        val query = consulta(tile)
        var ultimo: Exception? = null
        for (base in espelhos) {
            try {
                return parse(http.get("$base?data=${URLEncoder.encode(query, "UTF-8")}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ultimo = e
            }
        }
        throw ultimo ?: IllegalStateException("sem espelho do Overpass")
    }

    companion object {
        val ESPELHOS = listOf(
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass-api.de/api/interpreter",
        )
        private val json = Json { ignoreUnknownKeys = true }

        /** Consulta OverpassQL de um quadrado (sul,oeste,norte,leste, com 4 casas = ~11 m). */
        fun consulta(t: TileId): String {
            val bbox = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", t.sul, t.oeste, t.norte, t.leste)
            return "[out:json][timeout:25];(" +
                "nwr[\"highway\"=\"speed_camera\"]($bbox);" +
                "node[\"enforcement\"~\"maxspeed|speed_camera|traffic_signals|red_light\"]($bbox);" +
                "nwr[\"traffic_calming\"~\"^(bump|hump|table|cushion|speed_table|rumble_strip)\$\"]($bbox);" +
                "node[\"railway\"=\"level_crossing\"]($bbox);" +
                ");out center tags;"
        }

        /** Resposta do Overpass -> pontos. Ignora elementos sem coordenada ou sem tipo conhecido; junta repetidos a < 25 m. */
        fun parse(texto: String): List<PerigoPonto> {
            val elementos = json.parseToJsonElement(texto).jsonObject["elements"] as? JsonArray ?: return emptyList()
            val out = ArrayList<PerigoPonto>()
            // Grade de ~33 m para achar repetidos sem comparar tudo com tudo.
            val grade = HashMap<Long, MutableList<PerigoPonto>>()
            fun chave(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
            for (e in elementos) {
                val o = e as? JsonObject ?: continue
                val center = o["center"] as? JsonObject
                val lat = (o["lat"] ?: center?.get("lat"))?.jsonPrimitive?.doubleOrNull ?: continue
                val lon = (o["lon"] ?: center?.get("lon"))?.jsonPrimitive?.doubleOrNull ?: continue
                val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: continue
                val tipo = PerigoTags.tipoDeTags(tags) ?: continue
                val prefixo = when (o["type"]?.jsonPrimitive?.contentOrNull) { "node" -> "n"; "way" -> "w"; "relation" -> "r"; else -> "?" }
                val id = prefixo + (o["id"]?.jsonPrimitive?.contentOrNull ?: continue)
                val limite = if (tipo == Perigo.RADAR) PerigoTags.limiteKmh(tags["maxspeed"]) else null
                val p = PerigoPonto(id, tipo, lat, lon, limite, PerigoTags.direcaoGraus(tags))
                // Vários nós do mesmo tipo quase no mesmo lugar (uma lombada mapeada por faixa) viram um aviso só.
                val cx = Math.floor(lon / CELULA).toInt()
                val cy = Math.floor(lat / CELULA).toInt()
                val repetido = (-1..1).any { dx -> (-1..1).any { dy ->
                    grade[chave(cx + dx, cy + dy)]?.any { it.tipo == tipo && Geo.distanceM(it.lat, it.lon, lat, lon) < JUNTAR_M } == true
                } }
                if (repetido) continue
                grade.getOrPut(chave(cx, cy)) { ArrayList() } += p
                out += p
            }
            return out
        }

        private const val JUNTAR_M = 25.0
        private const val CELULA = 0.0003
    }
}
