package app.hunter.app.sync

import app.hunter.app.data.PlaceEntity
import app.hunter.app.data.TombstoneEntity
import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.sync.Kind
import app.hunter.sync.OdometerDoc
import app.hunter.sync.SyncDoc
import app.hunter.sync.dedupKey
import app.hunter.trips.data.TripEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomLocalStoreMappingTest {

    private val fuelup = FuelupEntity(id = 7, dateMs = 1_700_000_000_000, odometerKm = 12000.5, liters = 11.2, totalCost = 68.9, fullTank = true, gid = "g-f", updatedAtMs = 50, dirty = true)
    private val item = MaintenanceItemEntity(id = 3, name = "Óleo", intervalKm = 5000, intervalDays = null, lastDoneKm = 100.0, lastDoneAtMs = 9, gid = "g-m", updatedAtMs = 60)
    private val place = PlaceEntity(id = 2, name = "Casa", subtitle = "Rua A", lat = -8.0476, lon = -34.877, favorite = true, lastUsedMs = 5, useCount = 2, gid = "g-p", updatedAtMs = 70)
    private val trip = TripEntity(id = 4, startedAtMs = 1_700_000_000_000, endedAtMs = 1_700_003_600_000, distanceM = 42000.4, elapsedMs = 3_600_000, movingTimeMs = 3_000_000, stoppedTimeMs = 600_000, stopCount = 3, maxSpeedMps = 30.5, elevationGainM = 120.0, elevationLossM = 118.0, title = "Praia", gid = "g-t", updatedAtMs = 80)

    @Test fun `abastecimento usa as chaves de dedupKey e ida e volta preserva tudo`() {
        val d = fuelup.toDoc()
        assertEquals(Kind.FUELUP, d.kind); assertEquals("g-f", d.gid); assertEquals(50, d.updatedAtMs); assertFalse(d.deleted)
        assertEquals(1_700_000_000_000, d.fields["dateMs"]); assertEquals(12000.5, d.fields["odometerKm"])
        assertEquals(11.2, d.fields["liters"]); assertEquals(68.9, d.fields["totalCost"]); assertEquals(true, d.fields["fullTank"])
        assertTrue(dedupKey(d)!!.startsWith("1700000000000|12000.5|11.19"), dedupKey(d)) // os 4 campos da chave existem e entram nela
        // vindo do Firestore os números chegam como Long/Double indistintamente
        val back = fuelupFromDoc(d.copy(fields = d.fields + ("dateMs" to 1_700_000_000_000L)), existing = fuelup)
        assertEquals(fuelup.copy(dirty = false), back)
    }

    @Test fun `manutencao usa name intervalKm intervalDays e aceita nulos`() {
        val d = item.toDoc()
        assertEquals("Óleo|5000|", dedupKey(d))
        assertNull(d.fields["intervalDays"])
        val back = itemFromDoc(d, existing = null)
        assertEquals(item.copy(id = 0, dirty = false), back)
        // Int vindo como Long do Firestore
        val fromCloud = itemFromDoc(d.copy(fields = d.fields + ("intervalKm" to 5000L)), existing = null)
        assertEquals(5000, fromCloud.intervalKm)
    }

    @Test fun `apenas locais favoritos sincronizam e usam lat lon name`() {
        val d = place.toDocOrNull()!!
        assertEquals(Kind.PLACE, d.kind)
        assertEquals(-8.0476, d.fields["lat"]); assertEquals(-34.877, d.fields["lon"]); assertEquals("Casa", d.fields["name"])
        assertEquals("-8.0476|-34.8770|Casa", dedupKey(d))
        assertNull(place.copy(favorite = false).toDocOrNull())
    }

    @Test fun `local da nuvem vira favorito sem contagem de uso`() {
        val e = placeFromDoc(place.toDocOrNull()!!, existing = null, nowMs = 999)
        assertTrue(e.favorite); assertEquals(0, e.useCount); assertEquals(999, e.lastUsedMs); assertEquals("g-p", e.gid); assertFalse(e.dirty)
        // se já existe como recente, mantém o uso local
        val kept = placeFromDoc(place.toDocOrNull()!!, existing = place.copy(favorite = false, useCount = 9, lastUsedMs = 123), nowMs = 999)
        assertEquals(9, kept.useCount); assertEquals(123, kept.lastUsedMs); assertTrue(kept.favorite)
    }

    @Test fun `viagem concluida usa startedAtMs e distanceM`() {
        val d = trip.toDocOrNull()!!
        assertEquals(Kind.TRIP, d.kind)
        assertEquals("1700000000000|42000", dedupKey(d))
        assertEquals("Praia", d.fields["title"]); assertEquals(1_700_003_600_000, d.fields["endedAtMs"]); assertEquals(3, d.fields["stopCount"])
    }

    @Test fun `viagem em andamento nao sincroniza`() {
        assertNull(trip.copy(endedAtMs = null).toDocOrNull())
    }

    @Test fun `viagem da nuvem vira TripEntity sem pontos, preservando o id local se ja existe`() {
        val base = trip.toDocOrNull()!!
        val d = base.copy(fields = base.fields + ("stopCount" to 3L) + ("elapsedMs" to 3_600_000L))
        val fresh = tripFromDoc(d, existing = null)
        assertEquals(0L, fresh.id) // novo: Room gera o id; o mapeamento nao cria linhas de track_points
        assertEquals(trip.copy(id = 0, dirty = false), fresh)
        val upd = tripFromDoc(d, existing = trip.copy(title = "velho"))
        assertEquals(4L, upd.id); assertEquals("Praia", upd.title)
    }

    @Test fun `lapide vira SyncDoc apagado`() {
        val d = TombstoneEntity(kind = "FUELUP", gid = "g-x", deletedAtMs = 123, dirty = true).toDoc()
        assertEquals(SyncDoc(Kind.FUELUP, "g-x", 123, true, emptyMap()), d)
        assertNull(dedupKey(d))
    }

    @Test fun `nomes de Kind batem com os literais gravados nas lapides pelos DAOs`() {
        assertEquals(setOf("FUELUP", "MAINTENANCE", "PLACE", "TRIP"), Kind.values().map { it.name }.toSet())
    }

    @Test fun `hodometro ida e volta`() {
        val o = OdometerEntity(offsetKm = 350.25, tankLiters = 13.0, updatedAtMs = 5, dirty = true)
        assertEquals(OdometerDoc(350.25, 13.0, 5), o.toDoc())
        assertEquals(OdometerEntity(1, 350.25, 13.0, 5, false), odometerFromDoc(OdometerDoc(350.25, 13.0, 5)))
    }

    @Test fun `regra de apply nao sobrescreve linha local mais nova`() {
        assertTrue(shouldApply(localUpdatedAtMs = null, docUpdatedAtMs = 10))
        assertTrue(shouldApply(10, 10)); assertTrue(shouldApply(9, 10))
        assertFalse(shouldApply(11, 10))
    }

    @Test fun `regra de markClean so limpa se a versao ainda e a enviada`() {
        assertTrue(shouldMarkClean(localUpdatedAtMs = 10, docUpdatedAtMs = 10))
        assertFalse(shouldMarkClean(11, 10)) // editada durante a sincronizacao
        assertFalse(shouldMarkClean(null, 10))
    }

    @Test fun `regra de clearAll recusa se houver qualquer sujo`() {
        assertNull(clearAllBlocker(dirtyRows = 0, odometerDirty = false, profileDirty = false))
        assertNotNull(clearAllBlocker(1, false, false))
        assertNotNull(clearAllBlocker(0, true, false))
        assertNotNull(clearAllBlocker(0, false, true))
    }

    @Test fun `somente voice viaja no perfil`() {
        val keys = ProfilePrefs.SYNCED_KEYS
        assertTrue("voice" in keys)
        for (k in listOf("bottomMode", "mac", "update_checked_at", "update_skip_build", "seeded")) assertFalse(k in keys, k)
    }
}
