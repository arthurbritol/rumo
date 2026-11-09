package app.hunter.sync

import java.math.BigDecimal
import java.util.Locale

private const val DAY_MS = 86_400_000L

/** [merged]: o estado final de todos os registros (vencedores, inclusive lápides) depois da mesclagem. */
data class MergePlan(val applyLocal: List<SyncDoc>, val pushRemote: List<SyncDoc>, val merged: List<SyncDoc> = emptyList())

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

private fun Map<String, Any?>.long(k: String): Long? = (this[k] as? Number)?.toLong()
private fun Map<String, Any?>.double(k: String): Double? = (this[k] as? Number)?.toDouble()

/**
 * Ordem de preferência dentro de um grupo de duplicatas (o primeiro fica). Manutenção: a feita mais
 * recentemente (maior `lastDoneAtMs`), depois a de maior `lastDoneKm` — assim um item padrão recém-semeado,
 * sem histórico, nunca apaga o item com histórico da conta. Viagem: a cópia que existe viva neste aparelho
 * ([isLocal]), que pode ter os pontos do trajeto (a da nuvem nunca tem). Abastecimento e local: indiferente.
 * Desempate final: o menor gid (determinístico, converge entre aparelhos).
 */
private fun richerFirst(isLocal: (SyncDoc) -> Boolean): Comparator<SyncDoc> = Comparator { a, b ->
    val byKind = when (a.kind) {
        Kind.MAINTENANCE -> compareValuesBy(b, a, { it.fields.long("lastDoneAtMs") }, { it.fields.double("lastDoneKm") })
        Kind.TRIP -> compareValuesBy(b, a) { isLocal(it) }
        else -> 0
    }
    if (byKind != 0) byKind else a.gid.compareTo(b.gid)
}

fun merge(local: List<SyncDoc>, remote: List<SyncDoc>, nowMs: Long): MergePlan {
    val localBy = local.associateBy { it.kind to it.gid }
    val remoteBy = remote.associateBy { it.kind to it.gid }
    val winners = LinkedHashMap<Pair<Kind, String>, SyncDoc>()
    for (key in (localBy.keys + remoteBy.keys)) {
        winners[key] = pick(localBy[key], remoteBy[key], nowMs)!!
    }

    // Mesmo conteúdo com gids diferentes: fica o documento mais rico (ver [richerFirst]), os demais viram apagados.
    val groups = winners.values.filter { !it.deleted }.groupBy { it.kind to dedupKey(it) }
    for (group in groups.values) {
        if (group.size < 2) continue
        val keep = group.minWith(richerFirst { localBy[it.kind to it.gid]?.deleted == false }).gid
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
    return MergePlan(applyLocal, pushRemote, winners.values.toList())
}

fun mergeOdometer(local: OdometerDoc?, remote: OdometerDoc?, nowMs: Long): OdometerDoc? {
    val l = local?.copy(updatedAtMs = clamp(local.updatedAtMs, nowMs))
    val r = remote?.copy(updatedAtMs = clamp(remote.updatedAtMs, nowMs))
    if (l == null) return r
    if (r == null) return l
    return if (l.updatedAtMs > r.updatedAtMs) l else r
}

/**
 * Km das viagens que a mesclagem trouxe ao lado vencedor do hodômetro e que já estavam rodadas quando o painel
 * foi lido: viagens vivas de [merged] que não estavam vivas em [winnerSide] (nem pelo gid, nem pelo conteúdo —
 * [dedupKey], para uma duplicata não contar) e que terminaram até [readingAtMs]. Campo da viagem: `distanceM`
 * (metros), `endedAtMs` (ou `startedAtMs`).
 */
fun tripsAddedKm(winnerSide: List<SyncDoc>, merged: List<SyncDoc>, readingAtMs: Long): Double {
    val live = winnerSide.filter { it.kind == Kind.TRIP && !it.deleted }
    val gids = live.mapTo(HashSet()) { it.gid }
    val keys = live.mapTo(HashSet()) { dedupKey(it) }
    return merged.asSequence()
        .filter { it.kind == Kind.TRIP && !it.deleted && it.gid !in gids && dedupKey(it) !in keys }
        .filter { tripEndMs(it) <= readingAtMs }
        .sumOf { ((it.fields["distanceM"] as? Number)?.toDouble() ?: 0.0) / 1000.0 }
}

private fun tripEndMs(d: SyncDoc): Long =
    ((d.fields["endedAtMs"] ?: d.fields["startedAtMs"]) as? Number)?.toLong() ?: 0L

/**
 * Hodômetro vencedor (último a escrever, [mergeOdometer]) corrigido pelas viagens da mesclagem.
 *
 * O km mostrado é `offsetKm + soma das viagens do aparelho`, e o deslocamento é relativo às viagens que o
 * aparelho que leu o painel tinha naquele momento. Se a mesclagem traz ao lado vencedor viagens que ele não
 * tinha e que já tinham sido rodadas na leitura (o painel já as inclui), elas seriam somadas duas vezes — caso
 * típico: reinstalar, cadastrar o painel como visitante sem viagens e depois entrar na conta com o histórico.
 * Então `offset' = offset − km dessas viagens` ([tripsAddedKm]), com `updatedAtMs` novo (agora) para o valor
 * corrigido vencer em todo aparelho. Pressupostos: o relógio do aparelho é razoável (viagem terminada depois da
 * leitura não é descontada) e uma viagem com o mesmo [dedupKey] é a mesma viagem. Sem ajuste, devolve o vencedor
 * como está (idempotente: na segunda vez os dois lados já têm as mesmas viagens).
 */
fun mergeOdometerWithTrips(
    local: OdometerDoc?,
    remote: OdometerDoc?,
    localDocs: List<SyncDoc>,
    remoteDocs: List<SyncDoc>,
    merged: List<SyncDoc>,
    nowMs: Long,
): OdometerDoc? {
    val winner = mergeOdometer(local, remote, nowMs) ?: return null
    val localWins = local != null && (remote == null || clamp(local.updatedAtMs, nowMs) > clamp(remote.updatedAtMs, nowMs))
    val km = tripsAddedKm(if (localWins) localDocs else remoteDocs, merged, winner.updatedAtMs)
    if (km == 0.0) return winner
    return winner.copy(offsetKm = winner.offsetKm - km, updatedAtMs = maxOf(nowMs, winner.updatedAtMs + 1))
}
