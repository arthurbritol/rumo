package app.hunter.social

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Matemática da semana do ranking: de segunda 00:00:00 a domingo 23:59:59 no fuso de quem lê.
 * Uma viagem conta na semana do seu instante de início.
 */
object WeekMath {
    /** Instante (ms) da segunda-feira 00:00 do fuso [zone] que abre a semana que contém [nowMs]. */
    fun weekStartMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** Soma em km das viagens cujo início cai na semana que começa em [weekStartMs]. */
    fun weekKm(trips: List<TripSlice>, weekStartMs: Long, zone: ZoneId): Double =
        trips.filter { weekStartMs(it.startedAtMs, zone) == weekStartMs }.sumOf { it.distanceM } / 1000.0

    /** Km da atividade publicada, ou 0 se ela não é da semana atual de quem lê (ou é nula). */
    fun visibleWeekKm(a: Activity?, nowMs: Long, zone: ZoneId): Double =
        if (a != null && a.weekStartMs == weekStartMs(nowMs, zone)) a.weekKm else 0.0
}
