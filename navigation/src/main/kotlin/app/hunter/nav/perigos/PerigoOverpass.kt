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
import app.hunter.nav.HttpFailure
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** De onde vêm os perigos de um quadrado. Trocável nos testes. */
interface PerigoSource {
    suspend fun buscar(tile: TileId): List<PerigoPonto>
}

/** O Overpass respondeu 200 mas com `remark` (ex.: "runtime error: Query timed out"): resultado incompleto, não vale. */
class OverpassRemark(message: String) : IOException(message)

/**
 * Consulta o Overpass (OpenStreetMap) por quadrado. Dados abertos e gratuitos, mas a cobertura no Brasil é incompleta:
 * o que ninguém mapeou não aparece. Tenta os [espelhos] em ordem; um espelho que falhou fica de lado por [PAUSA_ESPELHO_MS]
 * (10 min) e depois volta. Se todos estão de lado, tenta todos mesmo assim (o recuo do repositório evita martelar).
 * 429/502/504, timeout e `remark` contam como falha. Não manda nada além do retângulo arredondado do quadrado.
 */
class OverpassPerigoSource(
    private val http: Http,
    private val espelhos: List<String> = ESPELHOS,
    private val agora: () -> Long = System::currentTimeMillis,
) : PerigoSource {
    private val falhouAte = HashMap<String, Long>()

    override suspend fun buscar(tile: TileId): List<PerigoPonto> {
        val query = consulta(tile)
        val t = agora()
        val bons = synchronized(falhouAte) { espelhos.filter { (falhouAte[it] ?: 0L) <= t } }
        val ordem = bons.ifEmpty { espelhos }
        var ultimo: Exception? = null
        var espera: Long? = null
        for (base in ordem) {
            try {
                val lista = parse(http.get("$base?data=${URLEncoder.encode(query, "UTF-8")}"))
                synchronized(falhouAte) { falhouAte.remove(base) }
                return lista
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ultimo = e
                synchronized(falhouAte) { falhouAte[base] = agora() + PAUSA_ESPELHO_MS }
                (e as? HttpFailure)?.retryAfterMs?.let { espera = maxOf(espera ?: 0L, it) }
            }
        }
        val e = ultimo ?: IllegalStateException("sem espelho do Overpass")
        // O servidor pediu para esperar: repassa ao recuo do repositório.
        throw if (espera != null && e !is HttpFailure) HttpFailure(0, e.message.orEmpty(), espera) else e
    }

    companion object {
        /** Na prática (2026-11): overpass-api.de responde rápido mas às vezes 502; o do mail.ru é estável; o kumi costuma segurar até estourar o tempo. */
        val ESPELHOS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
        const val PAUSA_ESPELHO_MS = 10 * 60_000L
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

        /** Resposta do Overpass -> pontos. Lança [OverpassRemark] se veio `remark` (incompleta) e exceção se não for JSON. Ignora elementos sem coordenada ou sem tipo conhecido; junta repetidos a < 25 m. */
        fun parse(texto: String): List<PerigoPonto> {
            val raiz = json.parseToJsonElement(texto).jsonObject
            raiz["remark"]?.let { throw OverpassRemark(it.jsonPrimitive.contentOrNull ?: "remark") }
            val elementos = raiz["elements"] as? JsonArray ?: return emptyList()
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
