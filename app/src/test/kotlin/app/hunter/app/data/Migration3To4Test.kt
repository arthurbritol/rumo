package app.hunter.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valida a migração Room v3 -> v4:
 * Adiciona tabelas de preços de combustíveis da ANP (anp_stations, anp_city_stats, anp_state_stats)
 * e garante que todos os dados preexistentes do usuário nas tabelas v3 permaneçam intactos (Review Focus 3).
 */
class Migration3To4Test {

    private fun v3Connection(): Connection = schemaConnection(3)

    /** Banco em memória criado exatamente pelo esquema exportado do Room ([version].json). */
    private fun schemaConnection(version: Int): Connection {
        val json = Json.parseToJsonElement(File("schemas/app.hunter.app.data.AppDatabase/$version.json").readText()).jsonObject
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        val db = json["database"]!!.jsonObject
        c.createStatement().use { st ->
            for (e in db["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                st.execute(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (i in (o["indices"] as? JsonArray).orEmpty()) {
                    st.execute(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
        }
        return c
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun <T> Connection.query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }

    private fun seedV3Data(c: Connection) {
        c.exec("PRAGMA foreign_keys = ON")
        c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank, gid, updatedAtMs, dirty) VALUES (1700000000000, 12000.5, 11.2, 68.9, 1, 'gid-fuel-1', 1700000000000, 0)")
        c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank, gid, updatedAtMs, dirty) VALUES (1701000000000, 12250.0, 10.0, 61.0, 0, 'gid-fuel-2', 1701000000000, 1)")
        c.exec("INSERT INTO maintenance_items (name, intervalKm, intervalDays, lastDoneKm, lastDoneAtMs, gid, updatedAtMs, dirty) VALUES ('Óleo do motor', 5000, 365, 11000.0, 1690000000000, 'gid-maint-1', 1690000000000, 0)")
        c.exec("INSERT INTO places (name, subtitle, lat, lon, favorite, lastUsedMs, useCount, gid, updatedAtMs, dirty) VALUES ('Casa', 'Rua A, 10', -8.0476, -34.877, 1, 1700000000000, 4, 'gid-place-1', 1700000000000, 0)")
        c.exec("INSERT INTO trips (startedAtMs, endedAtMs, distanceM, elapsedMs, movingTimeMs, stoppedTimeMs, stopCount, maxSpeedMps, elevationGainM, elevationLossM, title, gid, updatedAtMs, dirty) VALUES (1700000000000, 1700003600000, 42000.0, 3600000, 3000000, 600000, 3, 30.5, 120.0, 118.0, 'Viagem v3', 'gid-trip-1', 1700000000000, 0)")
        c.exec("INSERT INTO track_points (tripId, idx, timeMs, latitude, longitude, altitudeM, speedMps, accuracyM, bearingDeg) VALUES (1, 0, 1700000000000, -8.00, -34.80, 10.5, 12.0, 5.0, 90.0)")
        c.exec("INSERT INTO track_points (tripId, idx, timeMs, latitude, longitude, altitudeM, speedMps, accuracyM, bearingDeg) VALUES (1, 1, 1700000001000, -8.01, -34.81, 11.0, 13.0, 4.0, 92.0)")
        c.exec("INSERT INTO odometer (id, offsetKm, tankLiters, updatedAtMs, dirty) VALUES (1, 450.0, 14.0, 1700000000000, 0)")
        c.exec("INSERT INTO tombstones (kind, gid, deletedAtMs, dirty) VALUES ('FUELUP', 'deleted-gid-1', 1700500000000, 1)")
    }

    private fun migrate(c: Connection) = Migration3To4.statements.forEach { c.exec(it) }

    private fun count(c: Connection, table: String) = c.query("SELECT COUNT(*) FROM `$table`") { it.getInt(1) }.single()

    @Test
    fun `dados preexistentes do usuario no v3 sao preservados intactos apos migracao`() {
        v3Connection().use { c ->
            seedV3Data(c)
            migrate(c)

            // Review Focus 3: Contagens e integridade
            assertEquals(2, count(c, "fuelups"))
            assertEquals(1, count(c, "maintenance_items"))
            assertEquals(1, count(c, "places"))
            assertEquals(1, count(c, "trips"))
            assertEquals(2, count(c, "track_points"))
            assertEquals(1, count(c, "odometer"))
            assertEquals(1, count(c, "tombstones"))

            // Conteúdo específico
            assertEquals(listOf(12000.5, 12250.0), c.query("SELECT odometerKm FROM fuelups ORDER BY id") { it.getDouble(1) })
            assertEquals(listOf("gid-fuel-1", "gid-fuel-2"), c.query("SELECT gid FROM fuelups ORDER BY id") { it.getString(1) })
            assertEquals(listOf("Óleo do motor"), c.query("SELECT name FROM maintenance_items") { it.getString(1) })
            assertEquals(listOf("Casa"), c.query("SELECT name FROM places") { it.getString(1) })
            assertEquals(listOf("Viagem v3"), c.query("SELECT title FROM trips") { it.getString(1) })
            assertEquals(listOf(-8.00, -8.01), c.query("SELECT latitude FROM track_points ORDER BY idx") { it.getDouble(1) })
            assertEquals(listOf(450.0), c.query("SELECT offsetKm FROM odometer WHERE id = 1") { it.getDouble(1) })
            assertEquals(listOf("deleted-gid-1"), c.query("SELECT gid FROM tombstones WHERE kind = 'FUELUP'") { it.getString(1) })
        }
    }

    @Test
    fun `novas tabelas anp sao criadas e suportam operacoes de insercao e consulta`() {
        v3Connection().use { c ->
            migrate(c)

            // Inserção em anp_stations
            c.exec(
                "INSERT INTO anp_stations (id, nome, bandeira, cityKey, uf, endereco, bairro, cep, lat, lon, precoGasolina, precoGasolinaAdit, precoEtanol, precoDiesel, dataColeta) " +
                    "VALUES ('station-1', 'Posto Shell Central', 'SHELL', 'sp-sao-paulo', 'SP', 'Av Paulista 1000', 'Bela Vista', '01310-100', -23.56, -46.65, 5.89, 6.09, 3.89, 5.99, '2026-11-01')",
            )
            val station = c.query("SELECT id, nome, precoGasolina, lat, lon FROM anp_stations WHERE id = 'station-1'") {
                Triple(it.getString("id"), it.getString("nome"), it.getDouble("precoGasolina"))
            }.single()
            assertEquals("station-1", station.first)
            assertEquals("Posto Shell Central", station.second)
            assertEquals(5.89, station.third)

            // Inserção em anp_city_stats
            c.exec(
                "INSERT INTO anp_city_stats (cityKey, uf, municipio, lat, lon, gasolinaAvg, gasolinaMin, gasolinaMax, gasolinaAditAvg, gasolinaAditMin, gasolinaAditMax, etanolAvg, etanolMin, etanolMax, dieselAvg, dieselMin, dieselMax, dataReferencia, updatedAtMs) " +
                    "VALUES ('sp-sao-paulo', 'SP', 'São Paulo', -23.55, -46.63, 5.79, 5.49, 6.19, 5.99, 5.69, 6.39, 3.79, 3.49, 4.09, 5.89, 5.59, 6.19, '2026-11-01', 1700000000000)",
            )
            val cityStats = c.query("SELECT cityKey, municipio, gasolinaAvg FROM anp_city_stats WHERE cityKey = 'sp-sao-paulo'") {
                Pair(it.getString("cityKey"), it.getDouble("gasolinaAvg"))
            }.single()
            assertEquals("sp-sao-paulo", cityStats.first)
            assertEquals(5.79, cityStats.second)

            // Inserção em anp_state_stats
            c.exec(
                "INSERT INTO anp_state_stats (uf, lat, lon, gasolinaAvg, etanolAvg, dieselAvg, dataReferencia, updatedAtMs) " +
                    "VALUES ('SP', -23.55, -46.63, 5.85, 3.82, 5.92, '2026-11-01', 1700000000000)",
            )
            val stateStats = c.query("SELECT uf, gasolinaAvg FROM anp_state_stats WHERE uf = 'SP'") {
                Pair(it.getString("uf"), it.getDouble("gasolinaAvg"))
            }.single()
            assertEquals("SP", stateStats.first)
            assertEquals(5.85, stateStats.second)
        }
    }

    @Test
    fun `indices das novas tabelas anp sao criados corretamente`() {
        v3Connection().use { c ->
            migrate(c)

            val stationIndices = c.query("PRAGMA index_list('anp_stations')") { it.getString("name") }
            assertTrue(stationIndices.contains("index_anp_stations_lat_lon"), "Índice (lat, lon) em anp_stations não encontrado")
            assertTrue(stationIndices.contains("index_anp_stations_cityKey"), "Índice (cityKey) em anp_stations não encontrado")

            val cityIndices = c.query("PRAGMA index_list('anp_city_stats')") { it.getString("name") }
            assertTrue(cityIndices.contains("index_anp_city_stats_lat_lon"), "Índice (lat, lon) em anp_city_stats não encontrado")
        }
    }

    @Test
    fun `migrar um banco v3 vazio funciona sem erros`() {
        v3Connection().use { c ->
            migrate(c)
            assertEquals(0, count(c, "fuelups"))
            assertEquals(0, count(c, "anp_stations"))
            assertEquals(0, count(c, "anp_city_stats"))
            assertEquals(0, count(c, "anp_state_stats"))
        }
    }

    /**
     * Rede de segurança do que o Room valida ao abrir o banco migrado: por tabela, colunas (nome, tipo, NOT NULL,
     * DEFAULT, posição na chave primária), índices (nome, único, colunas) e chaves estrangeiras do banco v3
     * migrado devem ser iguais às de um banco v4 novo criado pelo 4.json.
     */
    @Test
    fun `esquema migrado e igual ao esquema v4 exportado`() {
        fun describe(c: Connection): Map<String, List<String>> {
            val tables = c.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { it.getString(1) }
            return tables.associateWith { t ->
                val cols = c.query("PRAGMA table_info(`$t`)") {
                    "col ${it.getString("name")} ${it.getString("type")} nn=${it.getInt("notnull")} def=${it.getString("dflt_value")} pk=${it.getInt("pk")}"
                }.sorted()
                val idx = c.query("PRAGMA index_list(`$t`)") { it.getString("name") to it.getInt("unique") }
                    .filter { !it.first.startsWith("sqlite_autoindex") }
                    .map { (name, unique) ->
                        val on = c.query("PRAGMA index_info(`$name`)") { it.getString("name") }.joinToString(",")
                        "idx $name unique=$unique ($on)"
                    }.sorted()
                val fks = c.query("PRAGMA foreign_key_list(`$t`)") {
                    "fk ${it.getString("from")}->${it.getString("table")}.${it.getString("to")} del=${it.getString("on_delete")}"
                }.sorted()
                cols + idx + fks
            }
        }
        val migrated = v3Connection().use { c -> seedV3Data(c); migrate(c); describe(c) }
        val fresh = schemaConnection(4).use { describe(it) }
        assertEquals(fresh.keys, migrated.keys)
        for (t in fresh.keys) assertEquals(fresh[t], migrated[t], "tabela $t")
    }
}
