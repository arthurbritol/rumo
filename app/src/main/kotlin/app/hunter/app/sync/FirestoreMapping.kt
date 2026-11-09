package app.hunter.app.sync

import app.hunter.sync.Kind
import app.hunter.sync.OdometerDoc
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile
import java.util.Locale

/** Documentos por lote de escrita (o limite do Firestore é 500 operações). */
const val BATCH_SIZE = 400

/** Documentos por página ao apagar uma subcoleção. */
const val DELETE_PAGE_SIZE = 300

/** As regras aceitam no máximo 20 chaves; duas são updatedAtMs e deleted. */
const val MAX_DATA_FIELDS = 18

private const val UPDATED_AT = "updatedAtMs"
private const val DELETED = "deleted"

/** Divide em lotes de até [size] itens. */
fun <T> batches(items: List<T>, size: Int = BATCH_SIZE): List<List<T>> = items.chunked(size)

/**
 * Converte o registro no mapa gravado no Firestore.
 * Lança [IllegalArgumentException] se houver mais de [MAX_DATA_FIELDS] campos de dados ou se um
 * campo usar nome reservado (updatedAtMs, deleted), pois as regras rejeitariam o documento.
 */
fun docToMap(doc: SyncDoc): Map<String, Any?> {
    require(doc.fields.size <= MAX_DATA_FIELDS) {
        "Registro ${doc.kind.path}/${doc.gid} tem ${doc.fields.size} campos; o máximo é $MAX_DATA_FIELDS."
    }
    require(UPDATED_AT !in doc.fields && DELETED !in doc.fields) {
        "Registro ${doc.kind.path}/${doc.gid} usa nome de campo reservado."
    }
    return doc.fields + mapOf(UPDATED_AT to doc.updatedAtMs, DELETED to doc.deleted)
}

/** Inverso de [docToMap]; números do Firestore (Long ou Double) são aceitos em updatedAtMs. */
fun mapToDoc(kind: Kind, gid: String, data: Map<String, Any?>): SyncDoc = SyncDoc(
    kind = kind,
    gid = gid,
    updatedAtMs = (data[UPDATED_AT] as? Number)?.toLong() ?: 0L,
    deleted = data[DELETED] as? Boolean ?: false,
    fields = data - UPDATED_AT - DELETED,
)

fun odometerToMap(o: OdometerDoc): Map<String, Any?> =
    mapOf("offsetKm" to o.offsetKm, "tankLiters" to o.tankLiters, UPDATED_AT to o.updatedAtMs)

fun mapToOdometer(data: Map<String, Any?>): OdometerDoc? {
    val offset = data["offsetKm"] as? Number ?: return null
    val tank = data["tankLiters"] as? Number ?: return null
    val at = data[UPDATED_AT] as? Number ?: return null
    return OdometerDoc(offset.toDouble(), tank.toDouble(), at.toLong())
}

/** Limite de texto das regras (displayName, brand, model). */
const val MAX_PROFILE_TEXT = 60
private const val DEFAULT_NAME = "Motociclista"

/** Apara e limita a [max] unidades UTF-16 sem partir um par substituto (emoji) ao meio. */
fun clampText(s: String, max: Int = MAX_PROFILE_TEXT): String {
    val t = s.trim()
    if (t.length <= max) return t
    val cut = if (Character.isHighSurrogate(t[max - 1])) max - 1 else max
    return t.substring(0, cut).trim()
}

/**
 * Mapa do perfil que NUNCA viola as regras (um perfil recusado falharia a cada sincronização): nome aparado e
 * limitado a 1..60 ("Motociclista" se vazio), marca/modelo limitados a 60 (em branco viram nulos), ano omitido
 * se nulo ou fora de 1950..2100. `displayNameLower` é `lowercase(Locale.ROOT)` e só vai quando a minúscula muda
 * apenas letras ASCII: o `lower()` das regras (conferido no emulador) não converte maiúsculas acentuadas ("Á"),
 * então para "Ágata" ou "İzmir" o campo (opcional nas regras) é omitido em vez de o perfil ser recusado.
 * `voice` vai como booleano (as regras aceitam bool ou nulo).
 */
fun profileToMap(p: UserProfile, nowMs: Long): Map<String, Any?> = buildMap {
    val name = clampText(p.displayName).ifEmpty { DEFAULT_NAME }
    put("displayName", name)
    val lower = name.lowercase(Locale.ROOT)
    val asciiLower = String(CharArray(name.length) { i -> name[i].let { c -> if (c in 'A'..'Z') c + 32 else c } })
    if (lower == asciiLower) put("displayNameLower", lower)
    put("brand", p.brand?.let(::clampText)?.ifEmpty { null })
    put("model", p.model?.let(::clampText)?.ifEmpty { null })
    if (p.year != null && p.year in 1950..2100) put("year", p.year)
    put("voice", p.voice)
    put(UPDATED_AT, nowMs)
}

/** Inverso de [profileToMap]; `voice` ausente vale verdadeiro; aceita booleano ou texto legado. */
fun mapToProfile(data: Map<String, Any?>): UserProfile? {
    val name = data["displayName"] as? String ?: return null
    return UserProfile(
        displayName = name,
        brand = data["brand"] as? String,
        model = data["model"] as? String,
        year = (data["year"] as? Number)?.toInt(),
        voice = when (val v = data["voice"]) {
            is Boolean -> v
            is String -> v != "false"
            else -> true
        },
    )
}
