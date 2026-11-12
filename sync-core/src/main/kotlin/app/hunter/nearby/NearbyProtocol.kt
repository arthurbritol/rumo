package app.hunter.nearby

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Textos de rede iguais aos de `workers/nearby/src/match.js`. */
enum class Ritmo(val wire: String) {
    TRANQUILO("tranquilo"), MODERADO("moderado"), ANIMADO("animado");

    companion object {
        fun fromWire(s: String): Ritmo? = entries.firstOrNull { it.wire == s }
    }
}

enum class Distancia(val wire: String) {
    ATE50("ate50"), DE50A150("50a150"), MAIS150("mais150");

    companion object {
        fun fromWire(s: String): Distancia? = entries.firstOrNull { it.wire == s }
    }
}

enum class Parada(val wire: String) {
    CAFE("cafe"), MIRANTE("mirante"), SO_ESTRADA("so_estrada"), SEM_PARADA("sem_parada");

    companion object {
        fun fromWire(s: String): Parada? = entries.firstOrNull { it.wire == s }
    }
}

data class Preferencia(val ritmo: Ritmo, val distancia: Distancia, val parada: Parada, val raioKm: Int) {
    init {
        require(raioKm in 5..20) { "raioKm deve estar entre 5 e 20: $raioKm" }
    }
}

/** O que os outros veem de uma pessoa (sem o raio, que é só de quem procura). */
data class PreferenciaVista(val ritmo: Ritmo, val distancia: Distancia, val parada: Parada)

data class Vizinho(
    val sessao: String,
    val lat: Double,
    val lon: Double,
    val rumo: Double?,
    val preferencia: PreferenciaVista,
    val combina: Boolean,
)

sealed interface ClientMessage {
    data class Procurar(val preferencia: Preferencia) : ClientMessage
    data object Parar : ClientMessage
    data class Posicao(
        val lat: Double,
        val lon: Double,
        val rumo: Double?,
        val vel: Double?,
        val distKm: Double?,
        val etaMin: Double?,
    ) : ClientMessage
}

sealed interface ServerMessage {
    data class Vizinhos(val lista: List<Vizinho>) : ServerMessage
    data class Erro(val codigo: String) : ServerMessage
}

/** Campos anuláveis saem como `null` explícito: o servidor recusa campo ausente. */
fun ClientMessage.toJson(): String = buildJsonObject {
    when (val m = this@toJson) {
        is ClientMessage.Procurar -> {
            put("t", "procurar")
            put("ritmo", m.preferencia.ritmo.wire)
            put("distancia", m.preferencia.distancia.wire)
            put("parada", m.preferencia.parada.wire)
            put("raioKm", m.preferencia.raioKm)
        }
        ClientMessage.Parar -> put("t", "parar")
        is ClientMessage.Posicao -> {
            put("t", "posicao")
            put("lat", m.lat)
            put("lon", m.lon)
            put("rumo", m.rumo)
            put("vel", m.vel)
            put("distKm", m.distKm)
            put("etaMin", m.etaMin)
        }
    }
}.toString()

@Serializable
private data class VizinhoWire(
    val sessao: String,
    val lat: Double,
    val lon: Double,
    val rumo: Double? = null,
    val ritmo: String,
    val distancia: String,
    val parada: String,
    val combina: Boolean,
)

@Serializable
private data class VizinhosWire(val lista: List<VizinhoWire>)

private val parser = Json { ignoreUnknownKeys = true }

private fun VizinhoWire.toVizinho(): Vizinho? {
    val r = Ritmo.fromWire(ritmo) ?: return null
    val d = Distancia.fromWire(distancia) ?: return null
    val p = Parada.fromWire(parada) ?: return null
    return Vizinho(sessao, lat, lon, rumo, PreferenciaVista(r, d, p), combina)
}

/** `null` para JSON inválido, tipo desconhecido ou conteúdo fora do protocolo. */
fun parseServerMessage(json: String): ServerMessage? = try {
    val obj: JsonObject = parser.parseToJsonElement(json).jsonObject
    when (obj["t"]?.jsonPrimitive?.contentOrNull) {
        "vizinhos" -> {
            val wire = parser.decodeFromJsonElement(VizinhosWire.serializer(), obj)
            val lista = wire.lista.map { it.toVizinho() ?: return null }
            ServerMessage.Vizinhos(lista)
        }
        "erro" -> ServerMessage.Erro(obj["codigo"]!!.jsonPrimitive.content)
        else -> null
    }
} catch (e: Exception) {
    null
}
