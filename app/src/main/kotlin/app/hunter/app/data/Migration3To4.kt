package app.hunter.app.data

/**
 * v3 → v4: Criação das tabelas de cache local da ANP (anp_stations, anp_city_stats, anp_state_stats).
 * Dados preexistentes nas tabelas v3 do usuário não sofrem nenhuma modificação.
 */
object Migration3To4 {
    val statements: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS anp_stations (id TEXT PRIMARY KEY NOT NULL, nome TEXT NOT NULL, bandeira TEXT NOT NULL, cityKey TEXT NOT NULL, uf TEXT NOT NULL, endereco TEXT NOT NULL, bairro TEXT NOT NULL, cep TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, precoGasolina REAL, precoGasolinaAdit REAL, precoEtanol REAL, precoDiesel REAL, dataColeta TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_anp_stations_lat_lon ON anp_stations (lat, lon)",
        "CREATE INDEX IF NOT EXISTS index_anp_stations_cityKey ON anp_stations (cityKey)",
        "CREATE TABLE IF NOT EXISTS anp_city_stats (cityKey TEXT PRIMARY KEY NOT NULL, uf TEXT NOT NULL, municipio TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, gasolinaAvg REAL, gasolinaMin REAL, gasolinaMax REAL, gasolinaAditAvg REAL, gasolinaAditMin REAL, gasolinaAditMax REAL, etanolAvg REAL, etanolMin REAL, etanolMax REAL, dieselAvg REAL, dieselMin REAL, dieselMax REAL, dataReferencia TEXT NOT NULL, updatedAtMs INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_anp_city_stats_lat_lon ON anp_city_stats (lat, lon)",
        "CREATE TABLE IF NOT EXISTS anp_state_stats (uf TEXT PRIMARY KEY NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, gasolinaAvg REAL, etanolAvg REAL, dieselAvg REAL, dataReferencia TEXT NOT NULL, updatedAtMs INTEGER NOT NULL)",
    )
}
