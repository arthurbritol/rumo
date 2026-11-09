package app.hunter.app.sync

import app.hunter.sync.Kind
import app.hunter.sync.OdometerDoc
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile

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

/**
 * Mapa do perfil conforme as regras: displayNameLower derivado, ano omitido quando nulo.
 * As regras exigem que `voice` seja texto, então o booleano vai como "true"/"false".
 */
fun profileToMap(p: UserProfile, nowMs: Long): Map<String, Any?> = buildMap {
    put("displayName", p.displayName)
    put("displayNameLower", p.displayName.lowercase())
    put("brand", p.brand)
    put("model", p.model)
    if (p.year != null) put("year", p.year)
    put("voice", p.voice.toString())
    put(UPDATED_AT, nowMs)
}

/** Inverso de [profileToMap]; `voice` ausente vale verdadeiro. */
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
