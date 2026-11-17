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

/** Ponto no mapa (o do encontro). */
data class Ponto(val lat: Double, val lon: Double)

/** Proposta aberta de um quadrado vizinho. Nunca traz quem propôs; [combina] é o sinal do servidor. */
data class Proposta(
    val id: String,
    val lat: Double,
    val lon: Double,
    val vagasLivres: Int,
    val vagasTotal: Int,
    val ritmo: Ritmo,
    val distancia: Distancia,
    val parada: Parada,
    val combina: Boolean,
)

/**
 * Quem está no encontro, só com a [sessao] opaca. [lat]/[lon] são nulos até a pessoa mandar a posição; [real] só
 * vale a menos de 1 km do ponto (antes disso a posição vem aproximada). [distKm]/[etaMin] são os dela.
 */
data class Participante(
    val sessao: String,
    val lat: Double?,
    val lon: Double?,
    val real: Boolean,
    val distKm: Double?,
    val etaMin: Double?,
    val chegou: Boolean,
)

enum class MotivoFim(val wire: String) {
    CANCELADO("cancelado"), EXPIROU("expirou"), NINGUEM_CHEGOU("ninguem_chegou"), CHEGARAM("chegaram"), SAIU("saiu"),

    /** Motivo que este app ainda não conhece: o encontro acabou do mesmo jeito. */
    OUTRO("outro");

    companion object {
        fun fromWire(s: String?): MotivoFim = entries.firstOrNull { it.wire == s } ?: OUTRO
    }
}

/** Motivos de denúncia aceitos pelo servidor (`motivo` na rede). */
enum class MotivoDenuncia(val wire: String) {
    INCOMODO("incomodo"), COMPORTAMENTO("comportamento"), FALSO("falso"), OUTRO("outro"),
}

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

    /** Propõe um encontro no ponto (vagas de 2 a 8, com quem propõe). Vai ao quadrado do ponto. */
    data class Proposta(val lat: Double, val lon: Double, val vagas: Int) : ClientMessage
    data object Cancelar : ClientMessage
    data class Entrar(val id: String) : ClientMessage
    data object Sair : ClientMessage

    /** Bloqueia a pessoa pela sessão opaca (nunca nome nem uid); vale nos dois sentidos. */
    data class Bloquear(val sessao: String) : ClientMessage
    data class Denunciar(val sessao: String, val motivo: MotivoDenuncia) : ClientMessage
}

sealed interface ServerMessage {
    data class Vizinhos(val lista: List<Vizinho>) : ServerMessage

    /** Propostas abertas do quadrado dentro do raio; a lista vem inteira a cada mudança. */
    data class Propostas(val lista: List<app.hunter.nearby.Proposta>) : ServerMessage

    /** O encontro em que esta conexão está. [eu] é a sessão da própria pessoa (id opaco, sem uid). */
    data class Encontro(
        val id: String,
        val ponto: Ponto,
        val vagasLivres: Int,
        val eu: String,
        val participantes: List<Participante>,
    ) : ServerMessage

    data class Fim(val id: String, val motivo: MotivoFim) : ServerMessage

    /** Confirma `bloquear` (`ref` = "bloqueio") ou `denunciar` (`ref` = "denuncia"). */
    data class Ok(val ref: String) : ServerMessage

    /** [restanteMs] só vem em `espera`. */
    data class Erro(val codigo: String, val restanteMs: Long? = null) : ServerMessage
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
        is ClientMessage.Proposta -> {
            put("t", "proposta")
            put("lat", m.lat)
            put("lon", m.lon)
            put("vagas", m.vagas)
        }
        ClientMessage.Cancelar -> put("t", "cancelar")
        is ClientMessage.Entrar -> {
            put("t", "entrar")
            put("id", m.id)
        }
        ClientMessage.Sair -> put("t", "sair")
        is ClientMessage.Bloquear -> {
            put("t", "bloquear")
            put("sessao", m.sessao)
        }
        is ClientMessage.Denunciar -> {
            put("t", "denunciar")
            put("sessao", m.sessao)
            put("motivo", m.motivo.wire)
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

@Serializable
private data class PropostaWire(
    val id: String,
    val lat: Double,
    val lon: Double,
    val vagasLivres: Int,
    val vagasTotal: Int,
    val ritmo: String,
    val distancia: String,
    val parada: String,
    val combina: Boolean,
)

@Serializable
private data class PropostasWire(val lista: List<PropostaWire>)

@Serializable
private data class PontoWire(val lat: Double, val lon: Double)

@Serializable
private data class ParticipanteWire(
    val sessao: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val real: Boolean = false,
    val distKm: Double? = null,
    val etaMin: Double? = null,
    val chegou: Boolean = false,
)

@Serializable
private data class EncontroWire(
    val id: String,
    val ponto: PontoWire,
    val vagasLivres: Int,
    val eu: String,
    val participantes: List<ParticipanteWire>,
)

private val parser = Json { ignoreUnknownKeys = true }

private fun PropostaWire.toProposta(): Proposta? {
    val r = Ritmo.fromWire(ritmo) ?: return null
    val d = Distancia.fromWire(distancia) ?: return null
    val p = Parada.fromWire(parada) ?: return null
    return Proposta(id, lat, lon, vagasLivres, vagasTotal, r, d, p, combina)
}

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
        "propostas" -> {
            val wire = parser.decodeFromJsonElement(PropostasWire.serializer(), obj)
            ServerMessage.Propostas(wire.lista.map { it.toProposta() ?: return null })
        }
        "encontro" -> {
            val w = parser.decodeFromJsonElement(EncontroWire.serializer(), obj)
            ServerMessage.Encontro(
                id = w.id,
                ponto = Ponto(w.ponto.lat, w.ponto.lon),
                vagasLivres = w.vagasLivres,
                eu = w.eu,
                participantes = w.participantes.map { Participante(it.sessao, it.lat, it.lon, it.real, it.distKm, it.etaMin, it.chegou) },
            )
        }
        "fim" -> ServerMessage.Fim(obj["id"]!!.jsonPrimitive.content, MotivoFim.fromWire(obj["motivo"]?.jsonPrimitive?.contentOrNull))
        "ok" -> ServerMessage.Ok(obj["ref"]!!.jsonPrimitive.content)
        "erro" -> ServerMessage.Erro(
            obj["codigo"]!!.jsonPrimitive.content,
            obj["restanteMs"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong(),
        )
        else -> null
    }
} catch (e: Exception) {
    null
}
