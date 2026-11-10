package app.hunter.social

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekMathTest {
    private val recife = ZoneId.of("America/Recife")
    private val utc = ZoneId.of("UTC")

    private fun ms(z: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, z).toInstant().toEpochMilli()

    // 2026-11-09 é segunda-feira
    @Test
    fun `segunda 00-00 do fuso é o início da semana`() {
        val monday = ms(recife, 2026, 11, 9, 0, 0)
        assertEquals(monday, WeekMath.weekStartMs(monday, recife))
        assertEquals(monday, WeekMath.weekStartMs(ms(recife, 2026, 11, 11, 15, 30), recife))
    }

    @Test
    fun `domingo 23-59 ainda é a mesma semana`() {
        val monday = ms(recife, 2026, 11, 9, 0, 0)
        assertEquals(monday, WeekMath.weekStartMs(ms(recife, 2026, 11, 15, 23, 59, 59), recife))
        assertEquals(ms(recife, 2026, 11, 16, 0, 0), WeekMath.weekStartMs(ms(recife, 2026, 11, 16, 0, 0), recife))
    }

    @Test
    fun `viagem que começa no domingo 23-50 e termina na segunda conta na semana do domingo`() {
        val monday = ms(recife, 2026, 11, 9, 0, 0)
        val trip = TripSlice(ms(recife, 2026, 11, 15, 23, 50), 10_000.0)
        assertEquals(10.0, WeekMath.weekKm(listOf(trip), monday, recife), 1e-9)
        val nextMonday = ms(recife, 2026, 11, 16, 0, 0)
        assertEquals(0.0, WeekMath.weekKm(listOf(trip), nextMonday, recife), 1e-9)
    }

    @Test
    fun `virada de semana - weekKm publicado na semana passada aparece como 0`() {
        val lastWeek = ms(recife, 2026, 11, 2, 0, 0)
        val a = Activity(lastWeek, 120.0)
        assertEquals(120.0, WeekMath.visibleWeekKm(a, ms(recife, 2026, 11, 8, 20, 0), recife), 1e-9)
        assertEquals(0.0, WeekMath.visibleWeekKm(a, ms(recife, 2026, 11, 9, 0, 0), recife), 1e-9)
        assertEquals(0.0, WeekMath.visibleWeekKm(null, ms(recife, 2026, 11, 9, 0, 0), recife), 1e-9)
    }

    @Test
    fun `fuso diferente muda a semana (America-Recife x UTC)`() {
        // domingo 22:00 em Recife (UTC-3) = segunda 01:00 em UTC
        val now = ms(recife, 2026, 11, 15, 22, 0)
        assertEquals(ms(recife, 2026, 11, 9, 0, 0), WeekMath.weekStartMs(now, recife))
        assertEquals(ms(utc, 2026, 11, 16, 0, 0), WeekMath.weekStartMs(now, utc))
    }

    @Test
    fun `soma só as viagens da semana e converte metros em km`() {
        val monday = ms(recife, 2026, 11, 9, 0, 0)
        val trips = listOf(
            TripSlice(ms(recife, 2026, 11, 8, 23, 59), 50_000.0), // semana anterior
            TripSlice(ms(recife, 2026, 11, 9, 0, 0), 12_500.0),
            TripSlice(ms(recife, 2026, 11, 13, 8, 0), 7_500.0),
            TripSlice(ms(recife, 2026, 11, 16, 0, 0), 99_000.0), // próxima semana
        )
        assertEquals(20.0, WeekMath.weekKm(trips, monday, recife), 1e-9)
    }

    @Test
    fun `semana com mudança de horário (America-New_York)`() {
        val ny = ZoneId.of("America/New_York")
        val monday = ms(ny, 2026, 3, 2, 0, 0) // EST
        assertEquals(monday, WeekMath.weekStartMs(ms(ny, 2026, 3, 8, 23, 0), ny))
        assertEquals(ms(ny, 2026, 3, 9, 0, 0), WeekMath.weekStartMs(ms(ny, 2026, 3, 11, 12, 0), ny))
        assertEquals(ms(ny, 2026, 3, 9, 0, 0), WeekMath.weekStartMs(ms(ny, 2026, 3, 9, 0, 0), ny))
        assertEquals(5.0, WeekMath.weekKm(listOf(TripSlice(ms(ny, 2026, 3, 8, 23, 30), 5_000.0)), monday, ny), 1e-9)
    }
}
