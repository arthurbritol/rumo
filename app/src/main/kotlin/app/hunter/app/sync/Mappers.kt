package app.hunter.app.sync

import app.hunter.app.data.PlaceEntity
import app.hunter.app.data.TombstoneEntity
import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.maintenance.data.VeiculoEntity
import app.hunter.sync.VEICULO_LEGADO_ID
import app.hunter.sync.Kind
import app.hunter.sync.OdometerDoc
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile
import app.hunter.trips.data.TripEntity

// Mapeamento entidade <-> SyncDoc em Kotlin puro (testável na JVM). Os nomes dos campos são os que
// app.hunter.sync.dedupKey espera: abastecimento (dateMs, odometerKm, liters, totalCost), manutenção
// (name, intervalKm, intervalDays), local (lat, lon, name), viagem (startedAtMs, distanceM).

private fun Map<String, Any?>.num(k: String): Number? = this[k] as? Number
private fun Map<String, Any?>.dbl(k: String): Double = num(k)?.toDouble() ?: 0.0
private fun Map<String, Any?>.lng(k: String): Long = num(k)?.toLong() ?: 0L
private fun Map<String, Any?>.int(k: String): Int = num(k)?.toInt() ?: 0

// ---------- regras de sincronização (puras) ----------

/** [LocalStore.apply]: não sobrescreve linha/lápide local MAIS NOVA que o doc (edição feita durante a sincronização). */
fun shouldApply(localUpdatedAtMs: Long?, docUpdatedAtMs: Long): Boolean =
    localUpdatedAtMs == null || localUpdatedAtMs <= docUpdatedAtMs

/** [LocalStore.markClean]: só limpa o sujo se a versão local ainda é a que foi enviada. */
fun shouldMarkClean(localUpdatedAtMs: Long?, docUpdatedAtMs: Long): Boolean =
    localUpdatedAtMs != null && localUpdatedAtMs == docUpdatedAtMs

/** [LocalStore.clearAll]: motivo para recusar (qualquer coisa suja), ou null se pode apagar. */
fun clearAllBlocker(dirtyRows: Int, odometerDirty: Boolean, profileDirty: Boolean): String? = when {
    dirtyRows > 0 -> "Há $dirtyRows alterações ainda não enviadas."
    odometerDirty -> "O hodômetro tem alteração ainda não enviada."
    profileDirty -> "O perfil tem alteração ainda não enviada."
    else -> null
}

/** Linha que um local vindo da nuvem deve atualizar: o mesmo gid, ou um RECENTE próximo; nunca outro favorito. */
fun placeMergeTarget(byGid: PlaceEntity?, near: PlaceEntity?): PlaceEntity? =
    byGid ?: near?.takeIf { !it.favorite }

enum class ProfileApply { WRITE, CLEAN_ONLY, SKIP }

/**
 * [LocalStore.applyProfile]: [incoming] é o perfil que o motor leu/enviou. Sujo e igual ao guardado = o que foi
 * enviado, só limpa. Sujo e diferente = edição feita durante a sincronização: não sobrescreve nem limpa.
 */
fun profileApplyDecision(stored: UserProfile?, dirty: Boolean, incoming: UserProfile): ProfileApply = when {
    stored == null || !dirty -> ProfileApply.WRITE
    stored == incoming -> ProfileApply.CLEAN_ONLY
    else -> ProfileApply.SKIP
}

// ---------- abastecimento ----------

fun FuelupEntity.toDoc() = SyncDoc(
    Kind.FUELUP, gid, updatedAtMs, false,
    mapOf("dateMs" to dateMs, "odometerKm" to odometerKm, "liters" to liters, "totalCost" to totalCost, "fullTank" to fullTank, "veiculoId" to veiculoId),
)

fun fuelupFromDoc(d: SyncDoc, existing: FuelupEntity?) = FuelupEntity(
    id = existing?.id ?: 0,
    dateMs = d.fields.lng("dateMs"),
    odometerKm = d.fields.dbl("odometerKm"),
    liters = d.fields.dbl("liters"),
    totalCost = d.fields.dbl("totalCost"),
    fullTank = d.fields["fullTank"] as? Boolean ?: false,
    gid = d.gid, updatedAtMs = d.updatedAtMs, dirty = false,
    veiculoId = d.veiculoIdOuLegado(),
)

// ---------- manutenção ----------

fun MaintenanceItemEntity.toDoc() = SyncDoc(
    Kind.MAINTENANCE, gid, updatedAtMs, false,
    mapOf(
        "name" to name, "intervalKm" to intervalKm, "intervalDays" to intervalDays,
        "lastDoneKm" to lastDoneKm, "lastDoneAtMs" to lastDoneAtMs, "veiculoId" to veiculoId,
    ),
)

fun itemFromDoc(d: SyncDoc, existing: MaintenanceItemEntity?) = MaintenanceItemEntity(
    id = existing?.id ?: 0,
    name = d.fields["name"] as? String ?: "",
    intervalKm = d.fields.num("intervalKm")?.toInt(),
    intervalDays = d.fields.num("intervalDays")?.toInt(),
    lastDoneKm = d.fields.num("lastDoneKm")?.toDouble(),
    lastDoneAtMs = d.fields.num("lastDoneAtMs")?.toLong(),
    gid = d.gid, updatedAtMs = d.updatedAtMs, dirty = false,
    veiculoId = d.veiculoIdOuLegado(),
)

// ---------- locais (só favoritos sincronizam; recentes ficam no aparelho) ----------

fun PlaceEntity.toDocOrNull(): SyncDoc? = if (!favorite) null else SyncDoc(
    Kind.PLACE, gid, updatedAtMs, false,
    mapOf("name" to name, "subtitle" to subtitle, "lat" to lat, "lon" to lon),
)

/** Local vindo da nuvem é sempre favorito; contagem/data de uso são locais e só se mantêm se a linha já existia. */
fun placeFromDoc(d: SyncDoc, existing: PlaceEntity?, nowMs: Long) = PlaceEntity(
    id = existing?.id ?: 0,
    name = d.fields["name"] as? String ?: "",
    subtitle = d.fields["subtitle"] as? String,
    lat = d.fields.dbl("lat"),
    lon = d.fields.dbl("lon"),
    favorite = true,
    lastUsedMs = existing?.lastUsedMs ?: nowMs,
    useCount = existing?.useCount ?: 0,
    gid = d.gid, updatedAtMs = d.updatedAtMs, dirty = false,
)

// ---------- viagens (só concluídas; viagem da nuvem não traz pontos de trajeto) ----------

fun TripEntity.toDocOrNull(): SyncDoc? {
    val ended = endedAtMs ?: return null
    return SyncDoc(
        Kind.TRIP, gid, updatedAtMs, false,
        mapOf(
            "startedAtMs" to startedAtMs, "endedAtMs" to ended, "distanceM" to distanceM, "elapsedMs" to elapsedMs,
            "movingTimeMs" to movingTimeMs, "stoppedTimeMs" to stoppedTimeMs, "stopCount" to stopCount,
            "maxSpeedMps" to maxSpeedMps, "elevationGainM" to elevationGainM, "elevationLossM" to elevationLossM,
            "title" to title, "veiculoId" to veiculoId,
        ),
    )
}

fun tripFromDoc(d: SyncDoc, existing: TripEntity?) = TripEntity(
    id = existing?.id ?: 0,
    startedAtMs = d.fields.lng("startedAtMs"),
    endedAtMs = d.fields.num("endedAtMs")?.toLong(),
    distanceM = d.fields.dbl("distanceM"),
    elapsedMs = d.fields.lng("elapsedMs"),
    movingTimeMs = d.fields.lng("movingTimeMs"),
    stoppedTimeMs = d.fields.lng("stoppedTimeMs"),
    stopCount = d.fields.int("stopCount"),
    maxSpeedMps = d.fields.dbl("maxSpeedMps"),
    elevationGainM = d.fields.dbl("elevationGainM"),
    elevationLossM = d.fields.dbl("elevationLossM"),
    title = d.fields["title"] as? String,
    gid = d.gid, updatedAtMs = d.updatedAtMs, dirty = false,
    veiculoId = (d.fields["veiculoId"] as? String)?.takeIf { it.isNotBlank() },
)

// ---------- lápides e hodômetro ----------

fun TombstoneEntity.toDoc() = SyncDoc(Kind.valueOf(kind), gid, deletedAtMs, true, emptyMap())

fun OdometerEntity.toDoc() = OdometerDoc(offsetKm, tankLiters, updatedAtMs, veiculoId)

/** Documento sem veículo (não deveria chegar: o download atribui o legado) vai para o veículo migrado. */
fun odometerFromDoc(o: OdometerDoc) = OdometerEntity(o.veiculoId ?: VEICULO_LEGADO_ID, o.offsetKm, o.tankLiters, o.updatedAtMs, false)

// ---------- veículos ----------

/**
 * Valores sempre aceitos pelas regras do Firestore (um veículo recusado falharia a cada sincronização): textos
 * aparados e limitados a 60, tipo MOTO/CARRO, tanque entre 0 e 200 L, ano de 1900 a 2100 e consumo de 0 (exclusivo) a 100.
 */
fun VeiculoEntity.toDoc() = SyncDoc(
    Kind.VEICULO, id, updatedAtMs, false,
    mapOf(
        "tipo" to app.hunter.model.TipoVeiculo.deNome(tipo).name,
        "apelido" to clampText(apelido), "marca" to clampText(marca), "modelo" to clampText(modelo),
        "ano" to ano?.takeIf { it in 1900..2100 },
        "tanqueLitros" to (tanqueLitros.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 200.0),
        "consumoPadraoKmL" to consumoPadraoKmL?.takeIf { it > 0.0 && it <= 100.0 },
        "criadoEm" to criadoEm, "removidoEm" to removidoEm,
    ),
)

fun veiculoFromDoc(d: SyncDoc) = VeiculoEntity(
    id = d.gid,
    tipo = d.fields["tipo"] as? String ?: "MOTO",
    apelido = d.fields["apelido"] as? String ?: "",
    marca = d.fields["marca"] as? String ?: "",
    modelo = d.fields["modelo"] as? String ?: "",
    ano = d.fields.num("ano")?.toInt(),
    tanqueLitros = d.fields.num("tanqueLitros")?.toDouble() ?: 13.0,
    consumoPadraoKmL = d.fields.num("consumoPadraoKmL")?.toDouble(),
    criadoEm = d.fields.lng("criadoEm"),
    removidoEm = d.fields.num("removidoEm")?.toLong(),
    updatedAtMs = d.updatedAtMs, dirty = false,
)

/** Veículo do documento; sem campo (cliente antigo, não resolvido) vale o veículo migrado. */
private fun SyncDoc.veiculoIdOuLegado(): String = (fields["veiculoId"] as? String)?.takeIf { it.isNotBlank() } ?: VEICULO_LEGADO_ID
