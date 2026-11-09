package app.hunter.sync

import java.math.BigDecimal
import java.util.Locale

private const val DAY_MS = 86_400_000L

data class MergePlan(val applyLocal: List<SyncDoc>, val pushRemote: List<SyncDoc>)

private fun fmt(v: Any?): String = when (v) {
    null -> ""
    is Number -> v.toDouble().let { if (it.isFinite()) BigDecimal(it).stripTrailingZeros().toPlainString() else it.toString() }
    else -> v.toString()
}

private fun fixed(v: Any?, decimals: Int): String =
    if (v is Number) String.format(Locale.ROOT, "%.${decimals}f", v.toDouble()) else ""

/** Chave de conteúdo para detectar duplicatas com gids diferentes; nula para apagados. */
fun dedupKey(d: SyncDoc): String? {
    if (d.deleted) return null
    val f = d.fields
    return when (d.kind) {
        Kind.FUELUP -> listOf("dateMs", "odometerKm", "liters", "totalCost").joinToString("|") { fmt(f[it]) }
        Kind.MAINTENANCE -> listOf("name", "intervalKm", "intervalDays").joinToString("|") { fmt(f[it]) }
        Kind.PLACE -> "${fixed(f["lat"], 4)}|${fixed(f["lon"], 4)}|${fmt(f["name"])}"
        Kind.TRIP -> {
            val m = (f["distanceM"] as? Number)?.toDouble()?.let { Math.round(it).toString() } ?: ""
            "${fmt(f["startedAtMs"])}|$m"
        }
    }
}

/** Normaliza números (Int/Long/Float -> Double) para que o ciclo de ida e volta no Firestore não conte como mudança. */
private fun normalize(v: Any?): Any? = when (v) {
    is Number -> v.toDouble()
    is Map<*, *> -> v.mapValues { normalize(it.value) }
    is List<*> -> v.map { normalize(it) }
    else -> v
}

private fun sameDoc(a: SyncDoc?, b: SyncDoc): Boolean =
    a != null && a.kind == b.kind && a.gid == b.gid && a.updatedAtMs == b.updatedAtMs &&
        a.deleted == b.deleted && normalize(a.fields) == normalize(b.fields)

private fun clamp(ts: Long, nowMs: Long): Long = if (ts > nowMs + DAY_MS) nowMs else ts

/** Retorna o vencedor entre duas versões: mais novo vence; empate vence o remoto. */
private fun pick(local: SyncDoc?, remote: SyncDoc?, nowMs: Long): SyncDoc? {
    if (local == null) return remote?.let { it.copy(updatedAtMs = clamp(it.updatedAtMs, nowMs)) }
    if (remote == null) return local.copy(updatedAtMs = clamp(local.updatedAtMs, nowMs))
    val l = local.copy(updatedAtMs = clamp(local.updatedAtMs, nowMs))
    val r = remote.copy(updatedAtMs = clamp(remote.updatedAtMs, nowMs))
    return if (l.updatedAtMs > r.updatedAtMs) l else r
}

fun merge(local: List<SyncDoc>, remote: List<SyncDoc>, nowMs: Long): MergePlan {
    val localBy = local.associateBy { it.kind to it.gid }
    val remoteBy = remote.associateBy { it.kind to it.gid }
    val winners = LinkedHashMap<Pair<Kind, String>, SyncDoc>()
    for (key in (localBy.keys + remoteBy.keys)) {
        winners[key] = pick(localBy[key], remoteBy[key], nowMs)!!
    }

    // Mesmo conteúdo com gids diferentes: fica o menor gid, os demais viram apagados.
    val groups = winners.values.filter { !it.deleted }.groupBy { it.kind to dedupKey(it) }
    for (group in groups.values) {
        if (group.size < 2) continue
        val keep = group.minOf { it.gid }
        for (d in group) {
            if (d.gid != keep) winners[d.kind to d.gid] = d.copy(deleted = true, updatedAtMs = nowMs)
        }
    }

    val applyLocal = ArrayList<SyncDoc>()
    val pushRemote = ArrayList<SyncDoc>()
    for ((key, doc) in winners) {
        if (!sameDoc(localBy[key], doc)) applyLocal += doc
        if (!sameDoc(remoteBy[key], doc)) pushRemote += doc
    }
    return MergePlan(applyLocal, pushRemote)
}

fun mergeOdometer(local: OdometerDoc?, remote: OdometerDoc?, nowMs: Long): OdometerDoc? {
    val l = local?.copy(updatedAtMs = clamp(local.updatedAtMs, nowMs))
    val r = remote?.copy(updatedAtMs = clamp(remote.updatedAtMs, nowMs))
    if (l == null) return r
    if (r == null) return l
    return if (l.updatedAtMs > r.updatedAtMs) l else r
}
