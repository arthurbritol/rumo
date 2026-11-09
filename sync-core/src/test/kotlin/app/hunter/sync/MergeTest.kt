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

    @Test
    fun `número Int de um lado e Double do outro não conta como diferença`() {
        val remoto = fuel("a", 100).copy(fields = fuel("a", 100).fields + ("liters" to 10))
        val plan = merge(listOf(fuel("a", 100, liters = 10.0)), listOf(remoto), now)
        assertTrue(plan.applyLocal.isEmpty() && plan.pushRemote.isEmpty())
    }

    @Test
    fun `dedupKey com NaN ou infinito não lança`() {
        val nan = fuel("a", 1, liters = Double.NaN)
        val inf = fuel("b", 1, liters = Double.POSITIVE_INFINITY)
        assertEquals("5000|1000|NaN|60", dedupKey(nan))
        assertEquals("5000|1000|Infinity|60", dedupKey(inf))
        merge(listOf(nan), listOf(inf), now)
    }

    @Test
    fun `o updatedAtMs limitado é o que é aplicado e enviado`() {
        val futuro = now + 2 * 86_400_000L
        val soLocal = merge(listOf(fuel("a", futuro)), emptyList(), now)
        assertEquals(listOf(fuel("a", now)), soLocal.pushRemote)
        val soRemoto = merge(emptyList(), listOf(fuel("a", futuro)), now)
        assertEquals(listOf(fuel("a", now)), soRemoto.applyLocal)
    }

    @Test
    fun `timestamp dentro da janela de um dia não é limitado`() {
        val dentro = now + 86_400_000L
        val plan = merge(listOf(fuel("a", dentro)), emptyList(), now)
        assertEquals(listOf(fuel("a", dentro)), plan.pushRemote)
    }

    private fun trip(gid: String, ended: Long, km: Double, at: Long = 10, deleted: Boolean = false) = SyncDoc(
        Kind.TRIP, gid, at, deleted,
        mapOf("startedAtMs" to ended - 1_000, "endedAtMs" to ended, "distanceM" to km * 1000),
    )

    @Test
    fun `km das viagens trazidas ao lado vencedor - por gid ou conteúdo, só as terminadas até a leitura`() {
        val lado = listOf(trip("a", 100, 5.0), trip("dup-b", 200, 7.0), trip("x", 300, 1.0, deleted = true))
        val mescladas = listOf(
            trip("a", 100, 5.0), // já estava no lado vencedor
            trip("dup-a", 200, 7.0), // a mesma viagem, outro gid (dedup): já estava
            trip("r", 250, 11.0), // trazida e terminada antes da leitura: conta
            trip("x", 300, 1.0), // estava só como apagada: conta
            trip("depois", 2_000, 13.0), // terminou depois da leitura: não conta
            trip("apagada", 260, 17.0, deleted = true), // apagada não conta
        )
        assertEquals(12.0, tripsAddedKm(lado, mescladas, readingAtMs = 1_000), 1e-9)
        assertEquals(0.0, tripsAddedKm(mescladas, mescladas, readingAtMs = 1_000), 1e-9)
    }

    private fun item(gid: String, at: Long = 100, doneAt: Long? = null, doneKm: Double? = null) = SyncDoc(
        Kind.MAINTENANCE, gid, at, false,
        mapOf("name" to "Óleo", "intervalKm" to 5000, "intervalDays" to 365, "lastDoneKm" to doneKm, "lastDoneAtMs" to doneAt),
    )

    private fun alive(plan: MergePlan) = plan.merged.filter { !it.deleted }.map { it.gid }

    @Test
    fun `item semente sem histórico nunca vence o item com histórico, qualquer que seja o gid ou o lado`() {
        val semente = { gid: String -> item(gid, at = 900) } // semeado agora há pouco: mais novo
        val comHistorico = { gid: String -> item(gid, at = 100, doneAt = 50_000, doneKm = 12_000.0) }
        for ((s, h) in listOf("a" to "b", "b" to "a")) {
            assertEquals(listOf(h), alive(merge(listOf(semente(s)), listOf(comHistorico(h)), now)))
            assertEquals(listOf(h), alive(merge(listOf(comHistorico(h)), listOf(semente(s)), now)))
        }
    }

    @Test
    fun `entre duplicatas de manutenção fica a feita mais recentemente, depois a de mais km, depois o menor gid`() {
        assertEquals(listOf("b"), alive(merge(listOf(item("a", doneAt = 10)), listOf(item("b", doneAt = 20)), now)))
        assertEquals(listOf("b"), alive(merge(listOf(item("a", doneAt = 10, doneKm = 1.0)), listOf(item("b", doneAt = 10, doneKm = 2.0)), now)))
        assertEquals(listOf("a"), alive(merge(listOf(item("b", doneAt = 10, doneKm = 2.0)), listOf(item("a", doneAt = 10, doneKm = 2.0)), now)))
    }

    @Test
    fun `entre viagens duplicadas fica a cópia local (que pode ter o trajeto)`() {
        val plan = merge(listOf(trip("z", 100, 5.0)), listOf(trip("a", 100, 5.0)), now)
        assertEquals(listOf("z"), alive(plan))
        assertEquals(listOf(trip("a", 100, 5.0).copy(deleted = true, updatedAtMs = now)), plan.pushRemote.filter { it.deleted })
    }

    @Test
    fun `dedup mais rico é idempotente e converge nos dois aparelhos`() {
        val a = listOf(item("a", at = 900)) // aparelho A: semente
        val nuvem = listOf(item("b", at = 100, doneAt = 50_000, doneKm = 12_000.0))
        val p1 = merge(a, nuvem, now)
        // nuvem depois do aparelho A
        val nuvem2 = (nuvem.associateBy { it.gid } + p1.pushRemote.associateBy { it.gid }).values.toList()
        val localA = p1.merged
        val again = merge(localA, nuvem2, now + 1)
        assertTrue(again.applyLocal.isEmpty() && again.pushRemote.isEmpty())
        // aparelho B tinha só o item com histórico: recebe a lápide da semente e continua com "b"
        val pB = merge(nuvem, nuvem2, now + 2)
        assertEquals(listOf("b"), alive(pB))
        assertTrue(pB.pushRemote.isEmpty())
    }

    @Test
    fun `grupo de três duplicatas fica com o menor gid e duas lápides`() {
        val plan = merge(listOf(fuel("c", 100), fuel("a", 100)), listOf(fuel("b", 100)), now)
        val lapides = listOf(fuel("b", now).copy(deleted = true), fuel("c", now).copy(deleted = true))
        assertEquals(lapides, plan.pushRemote.filter { it.deleted }.sortedBy { it.gid })
        assertEquals(listOf("a"), plan.pushRemote.filter { !it.deleted }.map { it.gid }) // "a" só existia local
        assertEquals(lapides, plan.applyLocal.filter { it.deleted }.sortedBy { it.gid })
    }
}
