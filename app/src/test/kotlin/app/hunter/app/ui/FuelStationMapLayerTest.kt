package app.hunter.app.ui

import app.hunter.app.fuel.PriceSource
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.model.GeoBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

@OptIn(ExperimentalCoroutinesApi::class)
class FuelStationMapLayerTest {

    private fun sampleStation(
        id: String = "st-1",
        nome: String = "Posto Rumo",
        bandeira: String = "BR",
        lat: Double = -8.05,
        lon: Double = -34.90,
        precoGasolina: Double? = 5.79,
        fonte: PriceSource = PriceSource.POSTO_ANP,
    ) = ResolvedFuelStation(
        id = id,
        nome = nome,
        bandeira = bandeira,
        endereco = "Av. Boa Viagem, 100",
        bairro = "Boa Viagem",
        cep = "51020-000",
        lat = lat,
        lon = lon,
        precoGasolina = precoGasolina,
        precoGasolinaAdit = 5.99,
        precoEtanol = 3.89,
        precoDiesel = 6.09,
        fonte = fonte,
        dataRef = "08/11/2026",
    )

    @Test
    fun `toggleFuelLayer altera estado de habilitado e limpa posto selecionado ao desligar`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = { emptyList() },
            ioDispatcher = dispatcher,
            debounceMs = 300L,
        )

        assertFalse(coordinator.fuelLayerEnabled.value)

        coordinator.toggleFuelLayer()
        assertTrue(coordinator.fuelLayerEnabled.value)

        val station = sampleStation()
        coordinator.selectFuelStation(station)
        assertEquals(station, coordinator.selectedFuelStation.value)

        // Ao desativar a camada, o posto selecionado deve ser limpo
        coordinator.toggleFuelLayer()
        assertFalse(coordinator.fuelLayerEnabled.value)
        assertNull(coordinator.selectedFuelStation.value)
    }

    @Test
    fun `selecao de posto permite selecionar e desmarcar`() = runTest {
        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = { emptyList() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertNull(coordinator.selectedFuelStation.value)

        val station = sampleStation("st-42")
        coordinator.selectFuelStation(station)
        assertEquals("st-42", coordinator.selectedFuelStation.value?.id)

        coordinator.selectFuelStation(null)
        assertNull(coordinator.selectedFuelStation.value)
    }

    @Test
    fun `bounding box com debounce 300ms descarta buscas intermediarias em arrasto rapido (Review Focus 5)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queries = mutableListOf<GeoBox>()

        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = { box ->
                queries.add(box)
                listOf(sampleStation(id = "box-${queries.size}"))
            },
            ioDispatcher = dispatcher,
            debounceMs = 300L,
        )

        coordinator.toggleFuelLayer()
        runCurrent()

        val box1 = GeoBox(-8.10, -34.95, -8.00, -34.85)
        val box2 = GeoBox(-8.12, -34.96, -8.02, -34.86)
        val box3 = GeoBox(-8.15, -34.98, -8.05, -34.88)

        // 1. Simula arrasto contínuo a 60 FPS (chamadas a cada 50ms)
        coordinator.updateMapBoundingBox(box1)
        advanceTimeBy(50)
        coordinator.updateMapBoundingBox(box2)
        advanceTimeBy(50)
        coordinator.updateMapBoundingBox(box3)

        // Passaram 100ms no total, o debounce é 300ms -> nenhuma consulta executada ainda
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, queries.size)
        assertTrue(coordinator.visibleFuelStations.value.isEmpty())

        // Avança mais 200ms (totalizando > 300ms após o box3) -> consulta deve disparar apenas para box3
        advanceTimeBy(200)
        runCurrent()

        assertEquals(1, queries.size)
        assertEquals(box3, queries.first())
        assertEquals(1, coordinator.visibleFuelStations.value.size)
        assertEquals("box-1", coordinator.visibleFuelStations.value.first().id)
    }

    @Test
    fun `com camada desabilitada visibleFuelStations permanece vazia mesmo com bounding box`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var queryCalls = 0

        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = {
                queryCalls++
                listOf(sampleStation())
            },
            ioDispatcher = dispatcher,
            debounceMs = 300L,
        )

        assertFalse(coordinator.fuelLayerEnabled.value)

        coordinator.updateMapBoundingBox(GeoBox(-8.10, -34.95, -8.00, -34.85))
        advanceTimeBy(500)
        runCurrent()

        assertEquals(0, queryCalls)
        assertTrue(coordinator.visibleFuelStations.value.isEmpty())
    }

    @Test
    fun `formatStationMapPrice formata preco segundo procedencia oficial ANP e medias`() {
        // Nivel 1: Posto pesquisado diretamente
        val stAnp = sampleStation(precoGasolina = 5.79, fonte = PriceSource.POSTO_ANP)
        assertEquals("R$ 5,79", formatStationMapPrice(stAnp))

        // Nivel 2: Media municipal estimada
        val stMun = sampleStation(precoGasolina = 5.82, fonte = PriceSource.MEDIA_MUNICIPAL)
        assertEquals("~R$ 5,82", formatStationMapPrice(stMun))

        // Nivel 3: Media estadual estimada
        val stEst = sampleStation(precoGasolina = 5.85, fonte = PriceSource.MEDIA_ESTADUAL)
        assertEquals("~R$ 5,85", formatStationMapPrice(stEst))

        // Sem preco disponivel
        val stSemPreco = sampleStation(precoGasolina = null, fonte = PriceSource.POSTO_ANP).copy(
            precoGasolinaAdit = null,
            precoEtanol = null,
            precoDiesel = null,
        )
        assertEquals("-", formatStationMapPrice(stSemPreco))
    }

    @Test
    fun `fuelStationsFeatures gera FeatureCollection valida para MapLibre GeoJson`() {
        val stations = listOf(
            sampleStation("st-1", "Posto Shell Boa Viagem", precoGasolina = 5.79, fonte = PriceSource.POSTO_ANP, lat = -8.11, lon = -34.91),
            sampleStation("st-2", "Posto Ipiranga Derby", precoGasolina = 5.84, fonte = PriceSource.MEDIA_MUNICIPAL, lat = -8.06, lon = -34.89),
        )

        val fc = fuelStationsFeatures(stations)
        assertNotNull(fc)
        val features = fc.features()
        assertNotNull(features)
        assertEquals(2, features!!.size)

        val f1 = features[0]
        assertEquals("st-1", f1.getStringProperty("id"))
        assertEquals("R$ 5,79", f1.getStringProperty("price"))
        assertEquals("Posto Shell Boa Viagem", f1.getStringProperty("name"))
        assertEquals("POSTO_ANP", f1.getStringProperty("source"))
        val pt1 = f1.geometry() as Point
        assertEquals(-34.91, pt1.longitude(), 0.0001)
        assertEquals(-8.11, pt1.latitude(), 0.0001)

        val f2 = features[1]
        assertEquals("st-2", f2.getStringProperty("id"))
        assertEquals("~R$ 5,84", f2.getStringProperty("price"))
        assertEquals("Posto Ipiranga Derby", f2.getStringProperty("name"))
        assertEquals("MEDIA_MUNICIPAL", f2.getStringProperty("source"))

        val emptyFc = fuelStationsFeatures(emptyList<ResolvedFuelStation>())
        assertTrue(emptyFc.features().isNullOrEmpty())
    }

    @Test
    fun `quando cache local esta vazio, busca postos reais online sob demanda e atualiza lista`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var onlineCalls = 0
        var localReturned = false

        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = {
                if (localReturned) listOf(sampleStation("st-fetched", "Posto Shell Real"))
                else emptyList()
            },
            searchNearbyStations = { center ->
                onlineCalls++
                localReturned = true
                listOf(sampleStation("st-fetched", "Posto Shell Real", lat = center.lat, lon = center.lon))
            },
            ioDispatcher = dispatcher,
            debounceMs = 100L,
        )

        coordinator.toggleFuelLayer()
        coordinator.updateMapBoundingBox(GeoBox(-8.10, -34.95, -8.00, -34.85))

        advanceTimeBy(150)
        runCurrent()

        assertEquals(1, onlineCalls)
        assertEquals(1, coordinator.visibleFuelStations.value.size)
        assertEquals("st-fetched", coordinator.visibleFuelStations.value[0].id)
        assertFalse(coordinator.isSearchingOnline.value)
    }

    @Test
    fun `quando postos ja estao salvos no banco local, aparecem imediatamente sem chamar busca online`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var onlineCalls = 0

        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = { listOf(sampleStation("st-local", "Posto Local Ipiranga")) },
            searchNearbyStations = {
                onlineCalls++
                emptyList()
            },
            ioDispatcher = dispatcher,
            debounceMs = 100L,
        )

        coordinator.toggleFuelLayer()
        coordinator.updateMapBoundingBox(GeoBox(-8.10, -34.95, -8.00, -34.85))

        advanceTimeBy(150)
        runCurrent()

        assertEquals(0, onlineCalls)
        assertEquals(1, coordinator.visibleFuelStations.value.size)
        assertEquals("st-local", coordinator.visibleFuelStations.value[0].id)
        assertFalse(coordinator.isSearchingOnline.value)
    }

    @Test
    fun `quando camera se move durante busca online, a busca nao eh cancelada e completa`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var onlineCalls = 0
        var localReturned = false

        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = {
                if (localReturned) listOf(sampleStation("st-fetched-2", "Posto Petrobras"))
                else emptyList()
            },
            searchNearbyStations = { center ->
                onlineCalls++
                localReturned = true
                listOf(sampleStation("st-fetched-2", "Posto Petrobras", lat = center.lat, lon = center.lon))
            },
            ioDispatcher = dispatcher,
            debounceMs = 100L,
        )

        coordinator.toggleFuelLayer()
        coordinator.updateMapBoundingBox(GeoBox(-8.10, -34.95, -8.00, -34.85))
        advanceTimeBy(150)

        // Movimento de câmera durante a execução
        coordinator.updateMapBoundingBox(GeoBox(-8.11, -34.96, -8.01, -34.86))
        advanceTimeBy(150)
        runCurrent()

        assertEquals(1, onlineCalls)
        assertEquals(1, coordinator.visibleFuelStations.value.size)
        assertEquals("st-fetched-2", coordinator.visibleFuelStations.value[0].id)
    }

    @Test
    fun `updateStationPriceInVisible atualiza precos no estado visivel e no selecionado`() = runTest {
        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = { emptyList() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val station = sampleStation(id = "st-update", precoGasolina = 6.00)
        coordinator.selectFuelStation(station)
        coordinator.refreshVisibleStations(listOf(station))

        assertEquals(6.00, coordinator.selectedFuelStation.value?.precoGasolina)
        assertEquals(6.00, coordinator.visibleFuelStations.value[0].precoGasolina)

        coordinator.updateStationPriceInVisible(
            stationId = "st-update",
            gc = 5.59,
            ga = 5.89,
            et = 3.69,
        )

        assertEquals(5.59, coordinator.selectedFuelStation.value?.precoGasolina)
        assertEquals(3.69, coordinator.selectedFuelStation.value?.precoEtanol)
        assertEquals(5.59, coordinator.visibleFuelStations.value[0].precoGasolina)
        assertEquals(PriceSource.POSTO_ANP, coordinator.visibleFuelStations.value[0].fonte)
    }

    @Test
    fun `searchCurrentArea forca nova busca mesmo se centro ja foi consultado anteriormente`() = runTest {
        var callCount = 0
        val coordinator = FuelMapCoordinator(
            scope = backgroundScope,
            fetchStations = { emptyList() },
            searchNearbyStations = {
                callCount++
                listOf(sampleStation(id = "st-$callCount"))
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            debounceMs = 50L,
        )
        coordinator.toggleFuelLayer()
        coordinator.updateMapBoundingBox(GeoBox(-8.10, -34.95, -8.00, -34.85))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, callCount)

        coordinator.searchCurrentArea()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, callCount)
    }
}
