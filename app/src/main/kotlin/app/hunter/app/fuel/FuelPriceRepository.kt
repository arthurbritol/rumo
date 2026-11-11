package app.hunter.app.fuel

import android.content.Context
import app.hunter.app.data.anp.AnpCityStatsEntity
import app.hunter.app.data.anp.AnpFuelDao
import app.hunter.app.data.anp.AnpStateStatsEntity
import app.hunter.app.data.anp.AnpStationEntity
import app.hunter.model.Geo
import app.hunter.model.GeoBox
import app.hunter.nav.AnpFuelPayload
import app.hunter.nav.Place
import java.io.InputStream
import java.util.zip.GZIPInputStream

enum class PriceSource {
    POSTO_ANP,
    MEDIA_MUNICIPAL,
    MEDIA_ESTADUAL,
}

data class ResolvedFuelStation(
    val id: String,
    val nome: String,
    val bandeira: String,
    val endereco: String,
    val bairro: String,
    val cep: String,
    val lat: Double,
    val lon: Double,
    val precoGasolina: Double?,
    val precoGasolinaAdit: Double?,
    val precoEtanol: Double?,
    val precoDiesel: Double?,
    val fonte: PriceSource,
    val dataRef: String,
)

/** Média estadual da ANP, mostrada no mapa só quando o zoom enquadra o estado inteiro. */
data class StateAverage(val uf: String, val lat: Double, val lon: Double, val gasolina: Double?, val etanol: Double?)

data class FuelPriceReference(
    val regiaoNome: String,
    val precoGasolina: Double?,
    val precoGasolinaAdit: Double?,
    val precoEtanol: Double?,
    val precoDiesel: Double?,
    val fonte: PriceSource,
    val dataRef: String,
)

/**
 * Repositório central de postos e preços de combustíveis da ANP.
 *
 * Provê resolução espacial em 3 níveis:
 * 1. Preço exato do posto aferido pela ANP
 * 2. Média municipal da cidade cadastrada
 * 3. Média estadual da UF correspondente ou mais próxima
 */
class FuelPriceRepository(
    private val dao: AnpFuelDao,
    private val assetLoader: (String) -> InputStream? = { null },
    /** Versão do seed já carregado neste aparelho. O padrão (versão atual) desliga a migração. */
    private val seedVersionGet: () -> Int = { SEED_VERSION },
    private val seedVersionSet: (Int) -> Unit = {},
) {

    constructor(context: Context, dao: AnpFuelDao) : this(
        dao = dao,
        assetLoader = { path ->
            try {
                context.assets.open(path)
            } catch (_: Exception) {
                null
            }
        },
    )

    /**
     * Garante que a base inicial de postos e médias seja carregada a partir do asset embarcado
     * caso o banco local de estados esteja vazio.
     */
    suspend fun ensureSeedLoaded() {
        val upToDate = seedVersionGet() >= SEED_VERSION
        if (upToDate && dao.getAllCityStats().size >= 40 && dao.getAllStateStats().isNotEmpty()) return
        val stream = assetLoader("anp_fuel_seed.json.gz") ?: return
        // Seed novo: troca a base antiga (outros ids de posto) e descarta ligações do mapa feitas com regras antigas.
        if (!upToDate) dao.clearStations()
        ingestGzipStream(stream)
        seedVersionSet(SEED_VERSION)
    }

    /**
     * Descomprime e faz o parse de um fluxo GZIP contendo o payload JSON da ANP.
     * Se os bytes forem inválidos ou corrompidos, lança exceção antes de alterar qualquer dado no banco.
     */
    suspend fun ingestGzipStream(stream: InputStream) {
        val jsonText = GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val payload = AnpFuelPayload.fromJson(jsonText)
        ingestPayload(payload)
    }

    /**
     * Converte os DTOs do payload em entidades e persiste no Room.
     */
    suspend fun ingestPayload(payload: AnpFuelPayload) {
        val stateEntities = payload.estados.map { (key, dto) ->
            AnpStateStatsEntity(
                uf = dto.uf.ifBlank { key },
                lat = dto.lat,
                lon = dto.lon,
                gasolinaAvg = dto.gcAvg,
                etanolAvg = dto.etAvg,
                dieselAvg = dto.dsAvg,
                dataReferencia = dto.ref,
            )
        }
        val cityEntities = payload.cidades.map { (key, dto) ->
            AnpCityStatsEntity(
                cityKey = key,
                uf = dto.uf,
                municipio = dto.mun,
                lat = dto.lat,
                lon = dto.lon,
                gasolinaAvg = dto.gcAvg,
                gasolinaMin = dto.gcMin,
                gasolinaMax = dto.gcMax,
                gasolinaAditAvg = dto.gaAvg,
                gasolinaAditMin = dto.gaMin,
                gasolinaAditMax = dto.gaMax,
                etanolAvg = dto.etAvg,
                etanolMin = dto.etMin,
                etanolMax = dto.etMax,
                dieselAvg = dto.dsAvg,
                dieselMin = dto.dsMin,
                dieselMax = dto.dsMax,
                dataReferencia = dto.ref,
            )
        }
        val stationEntities = payload.postos.map { dto ->
            AnpStationEntity(
                id = dto.id,
                nome = dto.nome,
                bandeira = dto.bandeira,
                cityKey = dto.cityKey,
                uf = dto.uf,
                endereco = dto.endereco,
                bairro = dto.bairro,
                cep = dto.cep,
                lat = dto.lat,
                lon = dto.lon,
                precoGasolina = dto.gc,
                precoGasolinaAdit = dto.ga,
                precoEtanol = dto.et,
                precoDiesel = dto.ds,
                dataColeta = dto.data,
            )
        }

        dao.insertAllStateStats(stateEntities)
        dao.insertAllCityStats(cityEntities)
        dao.insertAllStations(stationEntities)
        refreshMatchedStations(payload.postos)
    }

    /**
     * Os postos do mapa ligados à ANP ("anpm_<id da ANP>") são cópias; quando a base muda (atualização semanal) eles
     * recebem o preço novo se a coleta for mais recente que a que guardam (um preço informado hoje pelo usuário
     * continua valendo até a ANP coletar depois), e somem se o posto não existe mais na base (serão ligados de novo).
     */
    private suspend fun refreshMatchedStations(postos: List<app.hunter.nav.AnpStationDto>) {
        val byId = postos.associateBy { it.id }
        for (row in dao.getMatchedStations()) {
            val src = byId[row.id.removePrefix(MATCHED_PREFIX)]
            if (src == null) {
                dao.deleteStation(row.id)
            } else if (dateKey(src.data) >= dateKey(row.dataColeta)) {
                dao.updateStationPrices(row.id, src.gc, src.ga, src.et, src.ds, src.data)
            }
        }
    }

    /**
     * Consulta postos no retângulo geográfico com resolução de preços em 3 níveis:
     * 1. Preço exato do posto coletado pela ANP
     * 2. Média municipal da cidade cadastrada
     * 3. Média estadual da UF correspondente ou estado mais próximo (Review Focus 2)
     */
    suspend fun getStationsInBox(box: GeoBox): List<ResolvedFuelStation> {
        val stations = dao.getStationsInBox(box.minLat, box.minLon, box.maxLat, box.maxLon)
        if (stations.isEmpty()) return emptyList()

        val allStates = dao.getAllStateStats()
        val statesByUf = allStates.associateBy { it.uf.uppercase() }
        val cityStatsCache = mutableMapOf<String, AnpCityStatsEntity?>()

        return stations.map { st ->
            if (st.precoGasolina != null || st.precoEtanol != null) {
                ResolvedFuelStation(
                    id = st.id,
                    nome = st.nome,
                    bandeira = st.bandeira,
                    endereco = st.endereco,
                    bairro = st.bairro,
                    cep = st.cep,
                    lat = st.lat,
                    lon = st.lon,
                    precoGasolina = st.precoGasolina,
                    precoGasolinaAdit = st.precoGasolinaAdit,
                    precoEtanol = st.precoEtanol,
                    precoDiesel = st.precoDiesel,
                    fonte = PriceSource.POSTO_ANP,
                    dataRef = st.dataColeta,
                )
            } else {
                val cityStats = if (st.cityKey.isNotBlank()) {
                    cityStatsCache.getOrPut(st.cityKey) { dao.getCityStats(st.cityKey) }
                } else null

                if (cityStats != null && (cityStats.gasolinaAvg != null || cityStats.etanolAvg != null || cityStats.dieselAvg != null)) {
                    ResolvedFuelStation(
                        id = st.id,
                        nome = st.nome,
                        bandeira = st.bandeira,
                        endereco = st.endereco,
                        bairro = st.bairro,
                        cep = st.cep,
                        lat = st.lat,
                        lon = st.lon,
                        precoGasolina = cityStats.gasolinaAvg,
                        precoGasolinaAdit = cityStats.gasolinaAditAvg,
                        precoEtanol = cityStats.etanolAvg,
                        precoDiesel = cityStats.dieselAvg,
                        fonte = PriceSource.MEDIA_MUNICIPAL,
                        dataRef = cityStats.dataReferencia,
                    )
                } else {
                    val state = (if (st.uf.isNotBlank()) statesByUf[st.uf.uppercase()] else null)
                        ?: allStates.minByOrNull { Geo.distanceM(st.lat, st.lon, it.lat, it.lon) }

                    ResolvedFuelStation(
                        id = st.id,
                        nome = st.nome,
                        bandeira = st.bandeira,
                        endereco = st.endereco,
                        bairro = st.bairro,
                        cep = st.cep,
                        lat = st.lat,
                        lon = st.lon,
                        precoGasolina = state?.gasolinaAvg,
                        precoGasolinaAdit = null,
                        precoEtanol = state?.etanolAvg,
                        precoDiesel = state?.dieselAvg,
                        fonte = PriceSource.MEDIA_ESTADUAL,
                        dataRef = state?.dataReferencia.orEmpty(),
                    )
                }
            }
        }
    }

    /**
     * Retorna a referência de preço mais próxima de uma coordenada geográfica.
     * Busca municípios num raio de até 50 km. Se não houver nenhum, recorre à média estadual
     * do estado mais próximo (Review Focus 2).
     */
    suspend fun getNearestReferencePrice(lat: Double, lon: Double): FuelPriceReference? {
        val allCities = dao.getAllCityStats()
        val nearestCity = allCities
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
            .minByOrNull { it.second }

        if (nearestCity != null && nearestCity.second <= 50_000.0) {
            val city = nearestCity.first
            return FuelPriceReference(
                regiaoNome = "${city.municipio}/${city.uf}",
                precoGasolina = city.gasolinaAvg,
                precoGasolinaAdit = city.gasolinaAditAvg,
                precoEtanol = city.etanolAvg,
                precoDiesel = city.dieselAvg,
                fonte = PriceSource.MEDIA_MUNICIPAL,
                dataRef = city.dataReferencia,
            )
        }

        val allStates = dao.getAllStateStats()
        val nearestState = allStates
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
            .minByOrNull { it.second }
            ?.first

        if (nearestState != null) {
            return FuelPriceReference(
                regiaoNome = nearestState.uf,
                precoGasolina = nearestState.gasolinaAvg,
                precoGasolinaAdit = null,
                precoEtanol = nearestState.etanolAvg,
                precoDiesel = nearestState.dieselAvg,
                fonte = PriceSource.MEDIA_ESTADUAL,
                dataRef = nearestState.dataReferencia,
            )
        }

        return null
    }

    suspend fun updateStationPrices(
        stationId: String,
        precoGasolina: Double?,
        precoGasolinaAdit: Double?,
        precoEtanol: Double?,
        precoDiesel: Double? = null,
        dataColeta: String = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.forLanguageTag("pt-BR")).format(java.util.Date()),
    ) {
        dao.updateStationPrices(
            stationId = stationId,
            precoGasolina = precoGasolina,
            precoGasolinaAdit = precoGasolinaAdit,
            precoEtanol = precoEtanol,
            precoDiesel = precoDiesel,
            dataColeta = dataColeta,
        )
    }

    suspend fun stateAverages(): List<StateAverage> =
        dao.getAllStateStats().map { StateAverage(it.uf, it.lat, it.lon, it.gasolinaAvg, it.etanolAvg) }

    /**
     * Postos para o mapa: só os que têm preço próprio da ANP (nunca a média da cidade ou do estado). A posição vem do
     * mapa; o preço, do posto da ANP de mesmo endereço (ver [AnpMatcher]).
     */
    suspend fun getMapStations(box: GeoBox): List<ResolvedFuelStation> =
        getStationsInBox(box).filter { it.id.startsWith(MATCHED_PREFIX) && it.fonte == PriceSource.POSTO_ANP }

    /**
     * Liga cada posto do mapa ao posto da ANP de mesmo endereço, grava (para aparecer na hora nas próximas vezes) e
     * devolve só os que têm preço da ANP. Quem não casa fica de fora: sem preço próprio, sem marcador.
     */
    suspend fun enrichAndSavePlaces(places: List<Place>): List<ResolvedFuelStation> {
        if (places.isEmpty()) return emptyList()

        if (dao.getAllStateStats().isEmpty() || dao.getAllCityStats().size < 40) {
            ensureSeedLoaded()
        }

        val allCities = dao.getAllCityStats()
        val byCity = mutableMapOf<String, List<AnpStationEntity>>()
        suspend fun anpStationsOf(cityKey: String) =
            byCity.getOrPut(cityKey) { dao.getStationsByCity(cityKey).filter { it.id.startsWith("anp_") } }

        val entities = places.mapNotNull { place ->
            val cityKeys = allCities
                .map { it to Geo.distanceM(place.lat, place.lon, it.lat, it.lon) }
                .filter { it.second <= 30_000.0 }
                .sortedBy { it.second }
                .take(3)
                .map { it.first.cityKey }
            val candidates = cityKeys.flatMap { anpStationsOf(it) }
            val scored = AnpMatcher.matchScored(place, candidates) ?: return@mapNotNull null
            val anp = scored.station
            if (anp.precoGasolina == null && anp.precoEtanol == null && anp.precoDiesel == null) return@mapNotNull null
            val osmBrand = detectBrand(place.name)
            scored.score to AnpStationEntity(
                id = MATCHED_PREFIX + anp.id,
                nome = place.name.ifBlank { anp.nome },
                bandeira = if (osmBrand != "Branca") osmBrand else anp.bandeira,
                cityKey = anp.cityKey,
                uf = anp.uf,
                endereco = anp.endereco,
                bairro = anp.bairro,
                cep = anp.cep,
                lat = place.lat,
                lon = place.lon,
                precoGasolina = anp.precoGasolina,
                precoGasolinaAdit = anp.precoGasolinaAdit,
                precoEtanol = anp.precoEtanol,
                precoDiesel = anp.precoDiesel,
                dataColeta = anp.dataColeta,
            )
        }
            // Dois postos do mapa na mesma ligação: fica o de ligação mais forte (o outro não é esse posto da ANP).
            .groupBy { it.second.id }.values.map { group -> group.maxByOrNull { it.first }!!.second }

        dao.insertNewStations(entities)

        return entities.map { st ->
            ResolvedFuelStation(
                id = st.id,
                nome = st.nome,
                bandeira = st.bandeira,
                endereco = st.endereco,
                bairro = st.bairro,
                cep = st.cep,
                lat = st.lat,
                lon = st.lon,
                precoGasolina = st.precoGasolina,
                precoGasolinaAdit = st.precoGasolinaAdit,
                precoEtanol = st.precoEtanol,
                precoDiesel = st.precoDiesel,
                fonte = PriceSource.POSTO_ANP,
                dataRef = st.dataColeta,
            )
        }
    }

    companion object {
        /** Prefixo do id de um posto do mapa já ligado a um posto da ANP (o resto do id é o id da ANP). */
        const val MATCHED_PREFIX = "anpm_"

        /** Sobe quando o seed embarcado muda de forma incompatível (v2: série histórica de 12 meses, ids por CNPJ). */
        const val SEED_VERSION = 3

        /** "dd/MM/aaaa" -> "aaaaMMdd" (comparável); data inválida vira "". */
        fun dateKey(data: String): String {
            val p = data.trim().split("/")
            return if (p.size == 3 && p[2].length == 4) p[2] + p[1].padStart(2, '0') + p[0].padStart(2, '0') else ""
        }

        fun detectBrand(name: String): String {
            val n = name.lowercase()
            return when {
                "shell" in n -> "Shell"
                "ipiranga" in n -> "Ipiranga"
                "petrobras" in n || "vibra" in n || "br mania" in n || " br " in " $n " -> "Petrobras / Vibra"
                "ale" in " $n ".split(" ", "-", "_", "/") -> "Ale"
                "rodoil" in n -> "Rodoil"
                else -> "Branca"
            }
        }
    }
}
