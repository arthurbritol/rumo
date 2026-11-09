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

/** Roda o SQL real de [Migration2To3] sobre o esquema v2 exportado, com dados parecidos com os de um usuário. */
class Migration2To3Test {

    private fun v2Connection(): Connection = schemaConnection(2)

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

    private fun seed(c: Connection) {
        c.exec("PRAGMA foreign_keys = ON")
        c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank) VALUES (1700000000000, 12000.5, 11.2, 68.9, 1)")
        c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank) VALUES (1701000000000, 12250.0, 10.0, 61.0, 0)")
        c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank) VALUES (1702000000000, 12480.3, 12.1, 75.5, 1)")
        c.exec("INSERT INTO maintenance_items (name, intervalKm, intervalDays, lastDoneKm, lastDoneAtMs) VALUES ('Óleo do motor', 5000, 365, 11000.0, 1690000000000)")
        c.exec("INSERT INTO maintenance_items (name, intervalKm, intervalDays) VALUES ('Corrente', 3000, NULL)")
        c.exec("INSERT INTO places (name, subtitle, lat, lon, favorite, lastUsedMs, useCount) VALUES ('Casa', 'Rua A, 10', -8.0476, -34.877, 1, 1700000000000, 4)")
        c.exec("INSERT INTO places (name, subtitle, lat, lon, favorite, lastUsedMs, useCount) VALUES ('Posto', NULL, -8.1, -34.9, 0, 1700000000001, 1)")
        c.exec("INSERT INTO trips (startedAtMs, endedAtMs, distanceM, elapsedMs, movingTimeMs, stoppedTimeMs, stopCount, maxSpeedMps, elevationGainM, elevationLossM, title) VALUES (1700000000000, 1700003600000, 42000.0, 3600000, 3000000, 600000, 3, 30.5, 120.0, 118.0, 'Praia')")
        c.exec("INSERT INTO trips (startedAtMs, endedAtMs, distanceM, elapsedMs, movingTimeMs, stoppedTimeMs, stopCount, maxSpeedMps, elevationGainM, elevationLossM, title) VALUES (1701000000000, NULL, 1000.0, 100000, 90000, 10000, 0, 15.0, 1.0, 2.0, NULL)")
        for (trip in 1..2) for (i in 0 until 5) {
            c.exec("INSERT INTO track_points (tripId, idx, timeMs, latitude, longitude, altitudeM, speedMps, accuracyM, bearingDeg) VALUES ($trip, $i, ${1700000000000 + i * 1000}, -8.0$i, -34.8$i, 10.5, 12.0, 5.0, 90.0)")
        }
        c.exec("INSERT INTO odometer (id, offsetKm, tankLiters) VALUES (1, 350.25, 13.0)")
    }

    private fun migrate(c: Connection) = Migration2To3.statements.forEach { c.exec(it) }

    private fun count(c: Connection, table: String) = c.query("SELECT COUNT(*) FROM $table") { it.getInt(1) }.single()

    @Test fun `nenhum registro e perdido e os pontos do trajeto ficam intactos`() {
        v2Connection().use { c ->
            seed(c)
            migrate(c)
            assertEquals(3, count(c, "fuelups"))
            assertEquals(2, count(c, "maintenance_items"))
            assertEquals(2, count(c, "places"))
            assertEquals(2, count(c, "trips"))
            assertEquals(10, count(c, "track_points"))
            assertEquals(1, count(c, "odometer"))
            // conteúdo antigo preservado
            assertEquals(listOf(12000.5, 12250.0, 12480.3), c.query("SELECT odometerKm FROM fuelups ORDER BY id") { it.getDouble(1) })
            assertEquals(listOf("Óleo do motor", "Corrente"), c.query("SELECT name FROM maintenance_items ORDER BY id") { it.getString(1) })
            assertEquals(listOf("Praia", null), c.query("SELECT title FROM trips ORDER BY id") { it.getString(1) })
            assertEquals(listOf(5, 5), c.query("SELECT COUNT(*) FROM track_points GROUP BY tripId ORDER BY tripId") { it.getInt(1) })
            assertEquals(listOf(-8.00, -8.01, -8.02, -8.03, -8.04), c.query("SELECT latitude FROM track_points WHERE tripId = 1 ORDER BY idx") { it.getDouble(1) })
        }
    }

    @Test fun `gid nao vazio e unico por linha, sujo e data preenchidos`() {
        v2Connection().use { c ->
            seed(c)
            val before = System.currentTimeMillis()
            migrate(c)
            for (t in listOf("fuelups", "maintenance_items", "places", "trips")) {
                val rows = c.query("SELECT gid, dirty, updatedAtMs FROM $t") { Triple(it.getString(1), it.getInt(2), it.getLong(3)) }
                assertTrue(rows.isNotEmpty(), t)
                assertTrue(rows.all { it.first.isNotEmpty() }, "$t: gid vazio")
                assertEquals(rows.size, rows.map { it.first }.toSet().size, "$t: gid repetido")
                assertTrue(rows.all { it.second == 1 }, "$t: dirty")
                assertTrue(rows.all { it.third >= before - 5_000 }, "$t: updatedAtMs")
            }
            val dup = runCatching { c.exec("UPDATE fuelups SET gid = (SELECT gid FROM fuelups WHERE id = 1) WHERE id = 2") }
            assertTrue(dup.isFailure, "índice único de gid deveria rejeitar duplicata")
        }
    }

    @Test fun `hodometro e mantido e fica sujo, lapides existem vazias`() {
        v2Connection().use { c ->
            seed(c)
            migrate(c)
            val od = c.query("SELECT offsetKm, tankLiters, dirty, updatedAtMs FROM odometer WHERE id = 1") {
                listOf(it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getDouble(4))
            }.single()
            assertEquals(350.25, od[0])
            assertEquals(13.0, od[1])
            assertEquals(1.0, od[2])
            assertTrue(od[3] > 0)
            assertEquals(0, count(c, "tombstones"))
            c.exec("INSERT INTO tombstones (kind, gid, deletedAtMs, dirty) VALUES ('FUELUP', 'abc', 1, 1)")
        }
    }

    /**
     * Rede de segurança do que o Room valida ao abrir o banco migrado: por tabela, colunas (nome, tipo, NOT NULL,
     * DEFAULT, posição na chave primária), índices (nome, único, colunas) e chaves estrangeiras do banco v2
     * migrado devem ser iguais às de um banco v3 novo criado pelo 3.json.
     */
    @Test fun `esquema migrado e igual ao esquema v3 exportado`() {
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
        val migrated = v2Connection().use { c -> seed(c); migrate(c); describe(c) }
        val fresh = schemaConnection(3).use { describe(it) }
        assertEquals(fresh.keys, migrated.keys)
        for (t in fresh.keys) assertEquals(fresh[t], migrated[t], "tabela $t")
    }

    @Test fun `migrar um banco vazio tambem funciona`() {
        v2Connection().use { c ->
            migrate(c)
            assertEquals(0, count(c, "fuelups"))
            assertEquals(0, count(c, "tombstones"))
        }
    }
}
