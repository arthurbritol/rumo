package app.hunter.model

/** Escolhe o que merece uma notificação de manutenção, sem repetir o que já foi avisado. */
object MaintenanceReminders {

    data class Due(val item: MaintenanceItem, val status: DueStatus)

    /**
     * Itens perto de vencer ou vencidos cujo estado mudou desde o último aviso ([notified]): avisa uma vez ao
     * chegar perto e outra ao vencer.
     */
    fun due(items: List<MaintenanceItem>, currentKm: Double, nowMs: Long, notified: Map<Long, DueState>): List<Due> =
        items.mapNotNull { item ->
            val st = MaintenanceMath.status(item, currentKm, nowMs)
            if ((st.state == DueState.SOON || st.state == DueState.OVERDUE) && notified[item.id] != st.state) Due(item, st) else null
        }.sortedBy { if (it.status.state == DueState.OVERDUE) 0 else 1 }

    /** Registro a guardar depois do aviso: só o que ainda está perto/vencido (o resto "zera" e pode avisar de novo). */
    fun record(items: List<MaintenanceItem>, currentKm: Double, nowMs: Long): Map<Long, DueState> =
        items.mapNotNull { item ->
            val s = MaintenanceMath.status(item, currentKm, nowMs).state
            if (s == DueState.SOON || s == DueState.OVERDUE) item.id to s else null
        }.toMap()

    fun encode(record: Map<Long, DueState>): String = record.entries.joinToString(",") { "${it.key}:${it.value.name}" }

    fun decode(text: String?): Map<Long, DueState> =
        text.orEmpty().split(',').mapNotNull { part ->
            val (id, st) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val l = id.toLongOrNull() ?: return@mapNotNull null
            val s = runCatching { DueState.valueOf(st) }.getOrNull() ?: return@mapNotNull null
            l to s
        }.toMap()

    /** Texto curto para a notificação. */
    fun describe(d: Due): String {
        val s = d.status
        val km = s.remainingKm
        val days = s.remainingDays
        val parts = buildList {
            if (km != null) add(if (km >= 0) "faltam ${km.toInt()} km" else "passou ${(-km).toInt()} km")
            if (days != null) add(if (days >= 0) "faltam $days dias" else "passou ${-days} dias")
        }
        return "${d.item.name}: ${parts.joinToString(" / ")}"
    }
}
