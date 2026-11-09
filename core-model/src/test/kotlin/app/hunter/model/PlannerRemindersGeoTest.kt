package app.hunter.model

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerRemindersGeoTest {

    private fun near(e: Double, a: Double, tol: Double, msg: String = "") =
        assertTrue(kotlin.math.abs(e - a) <= tol, "$msg esperado≈$e obtido=$a")

    private fun f(id: Long, odo: Double, l: Double, full: Boolean = true) = Fuelup(id, id, odo, l, l * 6, full)

    // ---------- FuelPlanner ----------

    @Test
    fun `sem tanque cheio registrado nao estima`() {
        assertNull(FuelPlanner.state(13.0, 30.0, 1000.0, listOf(f(1, 900.0, 5.0, full = false))))
        assertNull(FuelPlanner.state(13.0, 30.0, 1000.0, emptyList()))
    }

    @Test
    fun `estima combustivel pelo consumo desde o ultimo tanque cheio`() {
        // cheio em 1000 km; 150 km depois, a 30 km/l → 5 L gastos → 8 L, autonomia 240 km
        val s = FuelPlanner.state(13.0, 30.0, 1150.0, listOf(f(1, 1000.0, 10.0)))!!
        near(8.0, s.litersLeft, 1e-9)
        near(240.0, s.rangeKm, 1e-9)
        assertFalse(s.usedDefaultConsumption)
    }

    @Test
    fun `sem media usa consumo generico e avisa`() {
        val s = FuelPlanner.state(13.0, null, 1056.0, listOf(f(1, 1000.0, 10.0)))!!
        near(11.0, s.litersLeft, 1e-9) // 56 km / 28 = 2 L
        assertTrue(s.usedDefaultConsumption)
    }

    @Test
    fun `parcial depois do cheio soma litros e nunca passa da capacidade nem fica negativo`() {
        val withPartial = FuelPlanner.state(13.0, 30.0, 1300.0, listOf(f(1, 1000.0, 10.0), f(2, 1200.0, 4.0, full = false)))!!
        near(13.0 + 4.0 - 10.0, withPartial.litersLeft, 1e-9) // 300 km = 10 L gastos
        val empty = FuelPlanner.state(13.0, 30.0, 2000.0, listOf(f(1, 1000.0, 10.0)))!!
        assertEquals(0.0, empty.litersLeft)
        val full = FuelPlanner.state(13.0, 30.0, 1000.0, listOf(f(1, 1000.0, 10.0), f(2, 900.0, 50.0, full = false)))!!
        near(13.0, full.litersLeft, 1e-9)
    }

    @Test
    fun `rota dentro da autonomia util nao precisa parar`() {
        val a = FuelPlanner.advise(routeKm = 200.0, rangeKm = 240.0) // útil = 204
        assertFalse(a.needsStop)
        assertNull(a.stopAtKm)
    }

    @Test
    fun `rota maior que a autonomia util manda parar antes do limite`() {
        val a = FuelPlanner.advise(routeKm = 300.0, rangeKm = 240.0) // útil = 204 → parar ~183 km
        assertTrue(a.needsStop)
        near(183.6, a.stopAtKm!!, 0.1)
        assertFalse(a.refuelBeforeLeaving)
    }

    @Test
    fun `tanque quase vazio manda abastecer antes de sair`() {
        val a = FuelPlanner.advise(routeKm = 50.0, rangeKm = 8.0)
        assertTrue(a.needsStop)
        assertTrue(a.refuelBeforeLeaving)
    }

    // ---------- Lembretes ----------

    private val day = TimeUnit.DAYS.toMillis(1)
    private fun item(id: Long, km: Int? = 5000, last: Double? = 1000.0) = MaintenanceItem(id, "Item $id", km, null, last, 0L)

    @Test
    fun `avisa uma vez ao chegar perto e outra ao vencer`() {
        val items = listOf(item(1))
        assertTrue(MaintenanceReminders.due(items, 2000.0, 0, emptyMap()).isEmpty(), "longe: nada")
        val soon = MaintenanceReminders.due(items, 5700.0, 0, emptyMap())
        assertEquals(DueState.SOON, soon.single().status.state)
        // já avisado como SOON: não repete
        assertTrue(MaintenanceReminders.due(items, 5800.0, 0, mapOf(1L to DueState.SOON)).isEmpty())
        // venceu: escala e avisa de novo
        val over = MaintenanceReminders.due(items, 6100.0, 0, mapOf(1L to DueState.SOON))
        assertEquals(DueState.OVERDUE, over.single().status.state)
        assertTrue(MaintenanceReminders.due(items, 6200.0, 0, mapOf(1L to DueState.OVERDUE)).isEmpty())
    }

    @Test
    fun `vencidos vem antes dos perto e item sem registro nao avisa`() {
        val items = listOf(item(1), item(2), item(3, last = null))
        val d = MaintenanceReminders.due(items, 5700.0, 0, emptyMap()) // 1 e 2 perto; 3 sem registro
        assertEquals(setOf(1L, 2L), d.map { it.item.id }.toSet())
        val mixed = MaintenanceReminders.due(listOf(item(1), item(2, last = 100.0)), 5700.0, 0, emptyMap())
        assertEquals(2L, mixed.first().item.id, "2 está vencido (100+5000 < 5700)")
    }

    @Test
    fun `registro guarda so perto e vencido e ao fazer a manutencao zera`() {
        val items = listOf(item(1), item(2, last = 100.0), item(3))
        val rec = MaintenanceReminders.record(items, 5700.0, 0)
        assertEquals(mapOf(1L to DueState.SOON, 2L to DueState.OVERDUE, 3L to DueState.SOON), rec)
        // depois de refazer o item 1 (last = 5700) ele sai do registro e pode avisar de novo no futuro
        val after = MaintenanceReminders.record(listOf(item(1, last = 5700.0), item(2, last = 100.0)), 5800.0, 0)
        assertEquals(mapOf(2L to DueState.OVERDUE), after)
    }

    @Test
    fun `codifica e decodifica o registro, ignorando lixo`() {
        val m = mapOf(1L to DueState.SOON, 22L to DueState.OVERDUE)
        assertEquals(m, MaintenanceReminders.decode(MaintenanceReminders.encode(m)))
        assertEquals(emptyMap(), MaintenanceReminders.decode(null))
        assertEquals(mapOf(5L to DueState.SOON), MaintenanceReminders.decode("x,5:SOON,7:NOPE,a:b"))
    }

    @Test
    fun `texto da notificacao`() {
        val d = MaintenanceReminders.due(listOf(item(1)), 6100.0, 0, emptyMap()).single()
        assertEquals("Item 1: passou 100 km", MaintenanceReminders.describe(d))
        val days = MaintenanceItem(2, "Fluido", null, 730, 0.0, 0L)
        val d2 = MaintenanceReminders.due(listOf(days), 0.0, 720 * day, emptyMap()).single()
        assertEquals("Fluido: faltam 10 dias", MaintenanceReminders.describe(d2))
        assertNotNull(d2)
    }

    // ---------- GeoBox ----------

    @Test
    fun `caixa ao redor de um ponto tem o raio pedido`() {
        val b = GeoBox.around(-8.05, -34.9, 25.0)
        near(50.0, Geo.distanceM(b.minLat, -34.9, b.maxLat, -34.9) / 1000, 0.2, "altura")
        near(50.0, Geo.distanceM(-8.05, b.minLon, -8.05, b.maxLon) / 1000, 0.3, "largura")
    }

    @Test
    fun `caixa que contem pontos com folga`() {
        val b = GeoBox.enclosing(listOf(-8.0 to -34.0, -9.0 to -35.0), padKm = 10.0)
        assertTrue(b.minLat < -9.0 && b.maxLat > -8.0 && b.minLon < -35.0 && b.maxLon > -34.0)
    }

    @Test
    fun `contagem de tiles`() {
        // O mundo todo no zoom 0 é 1 tile; no zoom 1, 4.
        val world = GeoBox(-80.0, -179.9, 80.0, 179.9)
        assertEquals(1L, world.tileCount(0, 0))
        assertEquals(4L, world.tileCount(1, 1))
        // Raio 25 km até o zoom 14: dezenas a poucas centenas de milhares? Deve ficar abaixo de ~6000 tiles.
        val n = GeoBox.around(-8.05, -34.9, 25.0).tileCount(5, 14)
        assertTrue(n in 500..6000, "tiles=$n")
        // Mais zoom, mais tiles
        assertTrue(GeoBox.around(-8.05, -34.9, 25.0).tileCount(5, 15) > n)
    }
}
