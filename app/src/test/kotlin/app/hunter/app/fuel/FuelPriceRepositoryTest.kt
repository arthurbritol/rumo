package app.hunter.app.fuel

import app.hunter.app.data.anp.AnpCityStatsEntity
import app.hunter.app.data.anp.AnpFuelDao
import app.hunter.app.data.anp.AnpStateStatsEntity
import app.hunter.app.data.anp.AnpStationEntity
import app.hunter.model.GeoBox
import app.hunter.nav.AnpCityDto
import app.hunter.nav.AnpFuelPayload
import app.hunter.nav.AnpStateDto
import app.hunter.nav.AnpStationDto
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelPriceRepositoryTest {

    private class FakeAnpFuelDao : AnpFuelDao {
        val stations = mutableMapOf<String, AnpStationEntity>()
        val cityStats = mutableMapOf<String, AnpCityStatsEntity>()
        val stateStats = mutableMapOf<String, AnpStateStatsEntity>()

        override suspend fun getStationsInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<AnpStationEntity> =
            stations.values.filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }

        override suspend fun getStationsByCity(cityKey: String): List<AnpStationEntity> =
            stations.values.filter { it.cityKey == cityKey }

        override suspend fun getCityStats(cityKey: String): AnpCityStatsEntity? =
            cityStats[cityKey]

        override suspend fun getAllCityStats(): List<AnpCityStatsEntity> =
            cityStats.values.toList()

        override suspend fun getStateStats(uf: String): AnpStateStatsEntity? =
            stateStats[uf]

        override suspend fun getAllStateStats(): List<AnpStateStatsEntity> =
            stateStats.values.toList()

        override suspend fun insertAllStations(stations: List<AnpStationEntity>) {
            stations.forEach { this.stations[it.id] = it }
        }

        override suspend fun insertAllCityStats(cities: List<AnpCityStatsEntity>) {
            cities.forEach { this.cityStats[it.cityKey] = it }
        }

        override suspend fun insertAllStateStats(states: List<AnpStateStatsEntity>) {
            states.forEach { this.stateStats[it.uf] = it }
        }

        override suspend fun clearStations() { stations.clear() }
        override suspend fun clearCityStats() { cityStats.clear() }
        override suspend fun clearStateStats() { stateStats.clear() }
    }

    private fun compressGzip(content: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    @Test
    fun `ensureSeedLoaded carrega seed comprimido quando banco esta vazio`() = runBlocking {
        val dao = FakeAnpFuelDao()
        val payload = AnpFuelPayload(
            v = "1.0",
            geradoEm = "2026-11-01",
            estados = mapOf(
                "PE" to AnpStateDto(uf = "PE", lat = -8.05, lon = -34.90, gcAvg = 5.99, etAvg = 4.19, dsAvg = 5.89, ref = "2026-11-01"),
            ),
            cidades = mapOf(
                "pe_recife" to AnpCityDto(uf = "PE", mun = "Recife", lat = -8.05, lon = -34.88, gcAvg = 5.89, etAvg = 4.09, ref = "2026-11-01"),
            ),
            postos = listOf(
                AnpStationDto(
                    id = "st-1",
                    nome = "Posto Shell Boa Viagem",
                    bandeira = "SHELL",
                    cityKey = "pe_recife",
                    uf = "PE",
                    endereco = "Av Boa Viagem 100",
                    bairro = "Boa Viagem",
                    cep = "51020-000",
                    lat = -8.12,
                    lon = -34.90,
                    gc = 5.85,
                    ga = 6.05,
                    et = 4.05,
                    ds = 5.75,
                    data = "2026-11-01",
                ),
            ),
        )

        val gzipped = compressGzip(AnpFuelPayload.toJson(payload))
        val repository = FuelPriceRepository(
            dao = dao,
            assetLoader = { path ->
                if (path == "anp_fuel_seed.json.gz") ByteArrayInputStream(gzipped) else null
            },
        )

        repository.ensureSeedLoaded()

        assertEquals(1, dao.getAllStateStats().size)
        assertEquals("PE", dao.getAllStateStats().first().uf)
        assertEquals(1, dao.getAllCityStats().size)
        assertEquals("pe_recife", dao.getAllCityStats().first().cityKey)
        assertEquals(1, dao.stations.size)
        assertEquals("st-1", dao.stations["st-1"]?.id)

        // Se chamado novamente, não recarrega (mantém o que já estava)
        val emptyLoaderRepo = FuelPriceRepository(dao = dao, assetLoader = { null })
        emptyLoaderRepo.ensureSeedLoaded()
        assertEquals(1, dao.getAllStateStats().size)
    }

    @Test
    fun `resolucao em 3 niveis retorna preco exato do posto quando disponivel`() = runBlocking {
        val dao = FakeAnpFuelDao()
        dao.insertAllStations(
            listOf(
                AnpStationEntity(
                    id = "st-exact",
                    nome = "Posto BR Centro",
                    bandeira = "VIBRA",
                    cityKey = "pe_recife",
                    uf = "PE",
                    endereco = "Rua do Sol 10",
                    bairro = "Santo Antônio",
                    cep = "50010-000",
                    lat = -8.06,
                    lon = -34.88,
                    precoGasolina = 5.79,
                    precoGasolinaAdit = 5.99,
                    precoEtanol = 3.99,
                    precoDiesel = 5.69,
                    dataColeta = "2026-11-05",
                ),
            ),
        )

        val repository = FuelPriceRepository(dao = dao)
        val box = GeoBox.around(-8.06, -34.88, 5.0)
        val stations = repository.getStationsInBox(box)

        assertEquals(1, stations.size)
        val resolved = stations.first()
        assertEquals("st-exact", resolved.id)
        assertEquals(5.79, resolved.precoGasolina)
        assertEquals(3.99, resolved.precoEtanol)
        assertEquals(PriceSource.POSTO_ANP, resolved.fonte)
        assertEquals("2026-11-05", resolved.dataRef)
    }

    @Test
    fun `resolucao em 3 niveis retorna media municipal para posto sem preco com cidade mapeada`() = runBlocking {
        val dao = FakeAnpFuelDao()
        dao.insertAllCityStats(
            listOf(
                AnpCityStatsEntity(
                    cityKey = "pe_recife",
                    uf = "PE",
                    municipio = "Recife",
                    lat = -8.05,
                    lon = -34.88,
                    gasolinaAvg = 5.82,
                    gasolinaAditAvg = 6.02,
                    etanolAvg = 4.02,
                    dieselAvg = 5.72,
                    dataReferencia = "2026-11-02",
                ),
            ),
        )
        dao.insertAllStations(
            listOf(
                AnpStationEntity(
                    id = "st-nocoleta",
                    nome = "Posto Ipiranga Bongi",
                    bandeira = "IPIRANGA",
                    cityKey = "pe_recife",
                    uf = "PE",
                    endereco = "Estrada do Bongi 200",
                    bairro = "Bongi",
                    cep = "50751-000",
                    lat = -8.07,
                    lon = -34.91,
                    precoGasolina = null,
                    precoGasolinaAdit = null,
                    precoEtanol = null,
                    precoDiesel = null,
                    dataColeta = "",
                ),
            ),
        )

        val repository = FuelPriceRepository(dao = dao)
        val box = GeoBox.around(-8.07, -34.91, 5.0)
        val stations = repository.getStationsInBox(box)

        assertEquals(1, stations.size)
        val resolved = stations.first()
        assertEquals("st-nocoleta", resolved.id)
        assertEquals(5.82, resolved.precoGasolina)
        assertEquals(6.02, resolved.precoGasolinaAdit)
        assertEquals(4.02, resolved.precoEtanol)
        assertEquals(5.72, resolved.precoDiesel)
        assertEquals(PriceSource.MEDIA_MUNICIPAL, resolved.fonte)
        assertEquals("2026-11-02", resolved.dataRef)
    }

    @Test
    fun `resolucao em 3 niveis retorna media estadual para posto sem cidade mapeada (Review Focus 2)`() = runBlocking {
        val dao = FakeAnpFuelDao()
        dao.insertAllStateStats(
            listOf(
                AnpStateStatsEntity(
                    uf = "PE",
                    lat = -8.05,
                    lon = -34.90,
                    gasolinaAvg = 5.95,
                    etanolAvg = 4.15,
                    dieselAvg = 5.85,
                    dataReferencia = "2026-11-03",
                ),
            ),
        )
        dao.insertAllStations(
            listOf(
                AnpStationEntity(
                    id = "st-rodovia",
                    nome = "Auto Posto Rodovia BR-232",
                    bandeira = "BRANCA",
                    cityKey = "pe_cidade_sem_anp",
                    uf = "PE",
                    endereco = "Rodovia BR-232 Km 80",
                    bairro = "Zona Rural",
                    cep = "55600-000",
                    lat = -8.25,
                    lon = -35.40,
                    precoGasolina = null,
                    precoEtanol = null,
                ),
            ),
        )

        val repository = FuelPriceRepository(dao = dao)
        val box = GeoBox.around(-8.25, -35.40, 10.0)
        val stations = repository.getStationsInBox(box)

        assertEquals(1, stations.size)
        val resolved = stations.first()
        assertEquals("st-rodovia", resolved.id)
        assertEquals(5.95, resolved.precoGasolina)
        assertNull(resolved.precoGasolinaAdit)
        assertEquals(4.15, resolved.precoEtanol)
        assertEquals(5.85, resolved.precoDiesel)
        assertEquals(PriceSource.MEDIA_ESTADUAL, resolved.fonte)
        assertEquals("2026-11-03", resolved.dataRef)
    }

    @Test
    fun `getNearestReferencePrice retorna media municipal quando cidade esta dentro de 50km`() = runBlocking {
        val dao = FakeAnpFuelDao()
        dao.insertAllCityStats(
            listOf(
                AnpCityStatsEntity(
                    cityKey = "pe_recife",
                    uf = "PE",
                    municipio = "Recife",
                    lat = -8.05,
                    lon = -34.88,
                    gasolinaAvg = 5.80,
                    gasolinaAditAvg = 6.00,
                    etanolAvg = 4.00,
                    dieselAvg = 5.70,
                    dataReferencia = "2026-11-01",
                ),
            ),
        )

        val repository = FuelPriceRepository(dao = dao)
        // Coordenada a ~2 km de Recife
        val ref = repository.getNearestReferencePrice(-8.06, -34.89)

        assertNotNull(ref)
        assertEquals("Recife/PE", ref.regiaoNome)
        assertEquals(5.80, ref.precoGasolina)
        assertEquals(4.00, ref.precoEtanol)
        assertEquals(PriceSource.MEDIA_MUNICIPAL, ref.fonte)
    }

    @Test
    fun `getNearestReferencePrice recorre a media estadual quando nenhuma cidade estiver a menos de 50km (Review Focus 2)`() = runBlocking {
        val dao = FakeAnpFuelDao()
        dao.insertAllCityStats(
            listOf(
                AnpCityStatsEntity(
                    cityKey = "pe_recife",
                    uf = "PE",
                    municipio = "Recife",
                    lat = -8.05,
                    lon = -34.88,
                    gasolinaAvg = 5.80,
                ),
            ),
        )
        dao.insertAllStateStats(
            listOf(
                AnpStateStatsEntity(
                    uf = "PE",
                    lat = -8.20,
                    lon = -35.50,
                    gasolinaAvg = 5.92,
                    etanolAvg = 4.12,
                    dieselAvg = 5.82,
                    dataReferencia = "2026-11-01",
                ),
            ),
        )

        val repository = FuelPriceRepository(dao = dao)
        // Coordenada no sertão pernambucano (> 150 km de Recife)
        val ref = repository.getNearestReferencePrice(-8.30, -36.50)

        assertNotNull(ref)
        assertEquals("PE", ref.regiaoNome)
        assertEquals(5.92, ref.precoGasolina)
        assertEquals(4.12, ref.precoEtanol)
        assertEquals(PriceSource.MEDIA_ESTADUAL, ref.fonte)
    }

    @Test
    fun `getNearestReferencePrice retorna null quando banco estiver vazio`() = runBlocking {
        val dao = FakeAnpFuelDao()
        val repository = FuelPriceRepository(dao = dao)
        val ref = repository.getNearestReferencePrice(-8.0, -35.0)
        assertNull(ref)
    }

    @Test
    fun `payload corrompido lanca excecao e nao altera banco de dados existente (Review Focus 4)`() = runBlocking {
        val dao = FakeAnpFuelDao()
        dao.insertAllStateStats(
            listOf(AnpStateStatsEntity(uf = "PE", lat = -8.0, lon = -35.0, gasolinaAvg = 5.90)),
        )
        dao.insertAllStations(
            listOf(
                AnpStationEntity(
                    id = "st-existing",
                    nome = "Posto Existente",
                    bandeira = "BR",
                    cityKey = "pe_recife",
                    uf = "PE",
                    endereco = "Rua Existente 1",
                    bairro = "Centro",
                    cep = "50000-000",
                    lat = -8.0,
                    lon = -35.0,
                    precoGasolina = 5.80,
                ),
            ),
        )

        val repository = FuelPriceRepository(dao = dao)

        // 1. Bytes aleatórios que não são gzip
        val corruptedGzip = "este nao e um gzip valido".toByteArray()
        assertFailsWith<Exception> {
            repository.ingestGzipStream(ByteArrayInputStream(corruptedGzip))
        }

        // Verifica que dados existentes continuam intactos
        assertEquals(1, dao.getAllStateStats().size)
        assertEquals(1, dao.stations.size)
        assertEquals(5.80, dao.stations["st-existing"]?.precoGasolina)

        // 2. Gzip válido contendo JSON corrompido / truncado
        val corruptedJsonGzip = compressGzip("{\"v\": \"1.0\", \"cidades\": {invalido")
        assertFailsWith<Exception> {
            repository.ingestGzipStream(ByteArrayInputStream(corruptedJsonGzip))
        }

        // Dados continuam 100% intactos
        assertEquals(1, dao.getAllStateStats().size)
        assertEquals(1, dao.stations.size)
        assertEquals(5.80, dao.stations["st-existing"]?.precoGasolina)
    }

    @Test
    fun `FuelPriceSyncWorker parseCompressedStream descompacta e valida payload`() {
        val payload = AnpFuelPayload(
            v = "1.0",
            geradoEm = "2026-11-01",
            estados = mapOf("PE" to AnpStateDto(uf = "PE", lat = -8.0, lon = -35.0, gcAvg = 5.90)),
        )
        val validGzip = compressGzip(AnpFuelPayload.toJson(payload))
        val parsed = FuelPriceSyncWorker.parseCompressedStream(ByteArrayInputStream(validGzip))

        assertEquals("1.0", parsed.v)
        assertEquals(1, parsed.estados.size)
        assertEquals("PE", parsed.estados["PE"]?.uf)

        val corruptedGzip = "dados corrompidos".toByteArray()
        assertFailsWith<Exception> {
            FuelPriceSyncWorker.parseCompressedStream(ByteArrayInputStream(corruptedGzip))
        }
    }

    @Test
    fun `detectBrand reconhece marcas principais e fallback para Branca`() {
        assertEquals("Shell", FuelPriceRepository.detectBrand("Auto Posto Shell Portal"))
        assertEquals("Ipiranga", FuelPriceRepository.detectBrand("Posto Ipiranga Centro"))
        assertEquals("Petrobras / Vibra", FuelPriceRepository.detectBrand("Posto BR Mania"))
        assertEquals("Petrobras / Vibra", FuelPriceRepository.detectBrand("Petrobras Distribuidora"))
        assertEquals("Ale", FuelPriceRepository.detectBrand("Posto Ale Rodovia"))
        assertEquals("Branca", FuelPriceRepository.detectBrand("Auto Posto Modelo Independente"))
    }

    @Test
    fun `enrichAndSavePlaces enriquece postos do OSM com precos da ANP e persiste no DAO`() = runBlocking {
        val dao = FakeAnpFuelDao()
        dao.insertAllStateStats(listOf(
            AnpStateStatsEntity("SP", -23.55, -46.63, gasolinaAvg = 5.79, etanolAvg = 3.85, dieselAvg = 5.69, dataReferencia = "2026-11-10")
        ))
        dao.insertAllCityStats(listOf(
            AnpCityStatsEntity(
                cityKey = "sp_campinas",
                uf = "SP",
                municipio = "Campinas",
                lat = -22.90,
                lon = -47.06,
                gasolinaAvg = 5.82,
                gasolinaMin = 5.60,
                gasolinaMax = 6.00,
                gasolinaAditAvg = 6.05,
                gasolinaAditMin = null,
                gasolinaAditMax = null,
                etanolAvg = 3.89,
                etanolMin = 3.70,
                etanolMax = 4.10,
                dieselAvg = 5.72,
                dieselMin = 5.50,
                dieselMax = 5.90,
                dataReferencia = "2026-11-10",
            )
        ))

        val repo = FuelPriceRepository(dao = dao)
        val osmPlaces = listOf(
            app.hunter.nav.Place(name = "Auto Posto Shell Guanabara", subtitle = "Av Brasil 100", lat = -22.89, lon = -47.07),
            app.hunter.nav.Place(name = "Posto Rodovia Interior", subtitle = "BR 369", lat = -21.00, lon = -50.00),
        )

        val enriched = repo.enrichAndSavePlaces(osmPlaces)

        assertEquals(2, enriched.size)
        // 1. Posto perto de Campinas (<= 50km) recebe média municipal
        val st1 = enriched[0]
        assertEquals("Auto Posto Shell Guanabara", st1.nome)
        assertEquals("Shell", st1.bandeira)
        assertEquals(5.82, st1.precoGasolina)
        assertEquals(6.05, st1.precoGasolinaAdit)
        assertEquals(PriceSource.MEDIA_MUNICIPAL, st1.fonte)

        // 2. Posto longe (> 50km) recebe média estadual
        val st2 = enriched[1]
        assertEquals("Branca", st2.bandeira)
        assertEquals(5.79, st2.precoGasolina)
        assertEquals(PriceSource.MEDIA_ESTADUAL, st2.fonte)

        // Confere que foram salvos no banco
        assertEquals(2, dao.stations.size)
    }
}
