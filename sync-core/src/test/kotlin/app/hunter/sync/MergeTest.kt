package app.hunter.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeTest {
    private val now = 1_000_000_000L

    private fun fuel(gid: String, at: Long, liters: Double = 10.0, deleted: Boolean = false, date: Long = 5_000L) =
        SyncDoc(
            Kind.FUELUP, gid, at, deleted,
            mapOf("dateMs" to date, "odometerKm" to 1000.0, "liters" to liters, "totalCost" to 60.0),
        )

    @Test
    fun `mesmo gid vence o updatedAtMs mais novo`() {
        val plan = merge(listOf(fuel("a", 100, liters = 11.0)), listOf(fuel("a", 200, liters = 12.0)), now)
        assertEquals(listOf(fuel("a", 200, liters = 12.0)), plan.applyLocal)
        assertTrue(plan.pushRemote.isEmpty())
        val plan2 = merge(listOf(fuel("a", 300, liters = 11.0)), listOf(fuel("a", 200, liters = 12.0)), now)
        assertTrue(plan2.applyLocal.isEmpty())
        assertEquals(listOf(fuel("a", 300, liters = 11.0)), plan2.pushRemote)
    }

    @Test
    fun `empate vence o remoto`() {
        val plan = merge(listOf(fuel("a", 100, liters = 11.0)), listOf(fuel("a", 100, liters = 12.0)), now)
        assertEquals(listOf(fuel("a", 100, liters = 12.0)), plan.applyLocal)
        assertTrue(plan.pushRemote.isEmpty())
    }

    @Test
    fun `mesmo conteúdo com gids diferentes vira um só (fica o menor gid) e o outro é marcado apagado dos dois lados`() {
        val plan = merge(listOf(fuel("b", 100)), listOf(fuel("a", 100)), now)
        val apagado = fuel("b", now).copy(deleted = true)
        assertEquals(listOf(apagado), plan.pushRemote)
        assertEquals(listOf(fuel("a", 100), apagado).sortedBy { it.gid }, plan.applyLocal.sortedBy { it.gid })
        // idempotente: rodando de novo com o resultado, nada muda
        val again = merge(listOf(fuel("a", 100), apagado), listOf(fuel("a", 100), apagado), now + 1)
        assertTrue(again.applyLocal.isEmpty() && again.pushRemote.isEmpty())
    }

    @Test
    fun `registro só local sobe`() {
        val plan = merge(listOf(fuel("a", 100)), emptyList(), now)
        assertTrue(plan.applyLocal.isEmpty())
        assertEquals(listOf(fuel("a", 100)), plan.pushRemote)
    }

    @Test
    fun `registro só remoto desce`() {
        val plan = merge(emptyList(), listOf(fuel("a", 100)), now)
        assertEquals(listOf(fuel("a", 100)), plan.applyLocal)
        assertTrue(plan.pushRemote.isEmpty())
    }

    @Test
    fun `apagado mais novo vence edição mais antiga`() {
        val plan = merge(listOf(fuel("a", 100)), listOf(fuel("a", 200, deleted = true)), now)
        assertEquals(listOf(fuel("a", 200, deleted = true)), plan.applyLocal)
        assertTrue(plan.pushRemote.isEmpty())
    }

    @Test
    fun `edição mais nova revive o apagado`() {
        val plan = merge(listOf(fuel("a", 300, liters = 9.0)), listOf(fuel("a", 200, deleted = true)), now)
        assertTrue(plan.applyLocal.isEmpty())
        assertEquals(listOf(fuel("a", 300, liters = 9.0)), plan.pushRemote)
    }

    @Test
    fun `updatedAtMs mais de um dia no futuro é limitado a agora`() {
        val futuro = now + 2 * 86_400_000L
        val plan = merge(listOf(fuel("a", futuro, liters = 11.0)), listOf(fuel("a", now + 10, liters = 12.0)), now)
        // local limitado a "agora" (< remoto), então o remoto vence
        assertEquals(listOf(fuel("a", now + 10, liters = 12.0)), plan.applyLocal)
        assertTrue(plan.pushRemote.isEmpty())
    }

    @Test
    fun `dois abastecimentos legítimos de mesma data mas litros diferentes não são duplicata`() {
        val plan = merge(listOf(fuel("b", 100, liters = 10.0)), listOf(fuel("a", 100, liters = 20.0)), now)
        assertEquals(listOf(fuel("a", 100, liters = 20.0)), plan.applyLocal)
        assertEquals(listOf(fuel("b", 100, liters = 10.0)), plan.pushRemote)
    }

    @Test
    fun `dedupKey por tipo`() {
        assertNull(dedupKey(fuel("a", 1, deleted = true)))
        assertEquals("5000|1000|10|60", dedupKey(fuel("a", 1)))
        val m = SyncDoc(Kind.MAINTENANCE, "m", 1, false, mapOf("name" to "Óleo", "intervalKm" to 3000, "intervalDays" to 180L))
        assertEquals("Óleo|3000|180", dedupKey(m))
        val p = SyncDoc(Kind.PLACE, "p", 1, false, mapOf("lat" to -8.0476123, "lon" to -34.877, "name" to "Casa"))
        assertEquals("-8.0476|-34.8770|Casa", dedupKey(p))
        val t = SyncDoc(Kind.TRIP, "t", 1, false, mapOf("startedAtMs" to 77L, "distanceM" to 1234.6))
        assertEquals("77|1235", dedupKey(t))
    }

    @Test
    fun `hodômetro vence a última escrita, nulo de um lado devolve o outro`() {
        val a = OdometerDoc(10.0, 5.0, 100)
        val b = OdometerDoc(20.0, 6.0, 200)
        assertEquals(b, mergeOdometer(a, b, now))
        assertEquals(b, mergeOdometer(b, a, now))
        assertEquals(a, mergeOdometer(a, null, now))
        assertEquals(b, mergeOdometer(null, b, now))
        assertNull(mergeOdometer(null, null, now))
        assertEquals(b, mergeOdometer(a.copy(updatedAtMs = 200), b, now))
    }
}
