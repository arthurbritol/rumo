package app.hunter.app.social

import app.hunter.model.FuelMath
import app.hunter.model.Fuelup
import app.hunter.social.Activity
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.Role
import app.hunter.social.TripSlice
import app.hunter.sync.UserProfile
import app.hunter.trips.data.TripEntity
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialPublisherTest {
    private val zone = ZoneId.of("America/Recife")
    private val store = FakeSocialStore()

    // Quarta-feira 2026-11-11 12:00; a semana começa na segunda 2026-11-09.
    private val now = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val monday = ZonedDateTime.of(2026, 11, 9, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val profile = UserProfile("Ana Souza", "Honda", "CG 160", 2022)

    private fun publisher(
        profile: UserProfile? = this.profile,
        trips: List<TripSlice> = emptyList(),
        cost: Double? = null,
        searchable: Boolean = true,
    ) = SocialPublisher(store, "me", { profile }, { trips }, { cost }, { searchable }, zone, { now })

    private fun fuelup(id: Long, odo: Double, liters: Double, cost: Double, full: Boolean = true) =
        Fuelup(id, now, odo, liters, cost, full)

    @Test
    fun `publica km da semana só de viagens terminadas e pega o custo por km do resumo de abastecimentos`() = runTest {
        val entities = listOf(
            TripEntity(startedAtMs = monday + 3_600_000, endedAtMs = monday + 7_200_000, distanceM = 10_000.0),
            TripEntity(startedAtMs = monday + 86_400_000, endedAtMs = monday + 90_000_000, distanceM = 5_500.0),
            TripEntity(startedAtMs = now - 1_000, endedAtMs = null, distanceM = 99_000.0), // ainda gravando
            TripEntity(startedAtMs = monday - 86_400_000, endedAtMs = monday - 80_000_000, distanceM = 40_000.0), // semana passada
        )
        val fuelups = listOf(fuelup(1, 1000.0, 10.0, 60.0), fuelup(2, 1300.0, 10.0, 60.0))
        val summary = FuelMath.summarize(fuelups)
        assertEquals(0.2, summary.avgCostPerKm!!, 1e-9)
        store.membersByRole["r1"] = mutableListOf(Member("me", "Ana", MemberState.ACCEPTED, 150.0, null))
        store.rolesById["r1"] = Role("r1", "Praia", "me", listOf("me"), now + 1000)

        publisher(trips = finishedSlices(entities), cost = summary.avgCostPerKm).publishAll()

        assertEquals(Activity(monday, 15.5), store.activities["me"])
        assertEquals(listOf(FigureCall("r1", "me", 150.0, summary.avgCostPerKm)), store.figureCalls)
    }

    @Test
    fun `finishedSlices descarta viagem em andamento`() {
        val slices = finishedSlices(
            listOf(
                TripEntity(startedAtMs = 5, endedAtMs = 9, distanceM = 100.0),
                TripEntity(startedAtMs = 6, endedAtMs = null, distanceM = 200.0),
            ),
        )
        assertEquals(listOf(TripSlice(5, 100.0)), slices)
    }

    @Test
    fun `publica o perfil com a opção de busca`() = runTest {
        publisher(searchable = true).publishAll()
        val p = store.profiles.getValue("me")
        assertEquals("Ana Souza", p.displayName)
        assertEquals("CG 160", p.model)
        assertEquals(2022, p.year)
        assertTrue(p.searchable)
        assertTrue("me" in store.searchIndex)
        assertTrue(store.removedFromSearch.isEmpty())
    }

    @Test
    fun `busca desligada não escreve em busca e apaga o documento existente`() = runTest {
        store.searchIndex += "me"
        publisher(searchable = false).publishAll()
        assertFalse("me" in store.searchIndex)
        assertEquals(listOf("me"), store.removedFromSearch)
        // O perfil público continua publicado para os amigos, marcado como não pesquisável.
        assertFalse(store.profiles.getValue("me").searchable)
        assertTrue("me" in store.activities)
    }

    @Test
    fun `no máximo uma publicação por minuto`() {
        val p = publisher()
        assertTrue(p.shouldPublish(lastPublishedAtMs = 0, nowMs = now))
        assertFalse(p.shouldPublish(lastPublishedAtMs = now, nowMs = now + 59_999))
        assertTrue(p.shouldPublish(lastPublishedAtMs = now, nowMs = now + 60_000))
        // Relógio que voltou para trás não trava a publicação.
        assertTrue(p.shouldPublish(lastPublishedAtMs = now, nowMs = now - 1))
    }

    @Test
    fun `sem custo por km publica nulo sem apagar o orçamento do membro`() = runTest {
        store.rolesById["r1"] = Role("r1", "Praia", "me", listOf("me", "ana"), now + 1000)
        store.membersByRole["r1"] = mutableListOf(Member("me", "Ana", MemberState.ACCEPTED, 150.0, 0.5))
        publisher(cost = null).publishAll()
        assertEquals(listOf(FigureCall("r1", "me", 150.0, null)), store.figureCalls)
    }

    @Test
    fun `não regrava o custo quando já é o publicado`() = runTest {
        store.rolesById["r1"] = Role("r1", "Praia", "me", listOf("me"), now + 1000)
        store.membersByRole["r1"] = mutableListOf(Member("me", "Ana", MemberState.ACCEPTED, 150.0, 0.2))
        publisher(cost = 0.2).publishAll()
        assertTrue(store.figureCalls.isEmpty())
    }

    @Test
    fun `convite pendente e rolê vencido ficam de fora`() = runTest {
        store.rolesById["pend"] = Role("pend", "A", "ana", listOf("ana", "me"), now + 1000)
        store.membersByRole["pend"] = mutableListOf(Member("me", "Ana", MemberState.INVITED, null, null))
        store.rolesById["velho"] = Role("velho", "B", "me", listOf("me"), now)
        store.membersByRole["velho"] = mutableListOf(Member("me", "Ana", MemberState.ACCEPTED, 1.0, null))
        publisher(cost = 0.3).publishAll()
        assertTrue(store.figureCalls.isEmpty())
    }

    @Test
    fun `perfil nulo não publica nada`() = runTest {
        store.rolesById["r1"] = Role("r1", "Praia", "me", listOf("me"), now + 1000)
        store.membersByRole["r1"] = mutableListOf(Member("me", "Ana", MemberState.ACCEPTED, 1.0, null))
        publisher(profile = null, cost = 0.3).publishAll()
        assertTrue(store.profiles.isEmpty())
        assertTrue(store.activities.isEmpty())
        assertTrue(store.figureCalls.isEmpty())
        assertTrue(store.removedFromSearch.isEmpty())
    }

    @Test
    fun `falha do store propaga para quem chamou`() = runTest {
        store.failure = IOException("sem rede")
        assertFailsWith<IOException> { publisher().publishAll() }
    }
}
