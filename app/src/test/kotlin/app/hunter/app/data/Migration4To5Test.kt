package app.hunter.app.data

import app.hunter.sync.VEICULO_LEGADO_ID
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
 * Valida a migração Room v4 -> v5 (vários veículos): tudo que existia vira do veículo migrado "Minha moto",
 * o hodômetro passa a ter uma linha por veículo, a base vazia não ganha veículo e o esquema final é o exportado.
 */
class Migration4To5Test {

    private fun v4Connection(): Connection = schemaConnection(4)

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

    private fun count(c: Connection, table: String) = c.query("SELECT COUNT(*) FROM `$table`") { it.getInt(1) }.single()

    private fun seedV4Data(c: Connection) {
        c.exec("PRAGMA foreign_keys = ON")
        c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank, gid, updatedAtMs, dirty) VALUES (1700000000000, 12000.5, 11.2, 68.9, 1, 'gid-fuel-1', 1700000000000, 0)")
        c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank, gid, updatedAtMs, dirty) VALUES (1701000000000, 12250.0, 10.0, 61.0, 0, 'gid-fuel-2', 1701000000000, 1)")
        c.exec("INSERT INTO maintenance_items (name, intervalKm, intervalDays, lastDoneKm, lastDoneAtMs, gid, updatedAtMs, dirty) VALUES ('Óleo do motor', 5000, 365, 11000.0, 1690000000000, 'gid-maint-1', 1690000000000, 0)")
        c.exec("INSERT INTO places (name, subtitle, lat, lon, favorite, lastUsedMs, useCount, gid, updatedAtMs, dirty) VALUES ('Casa', 'Rua A, 10', -8.0476, -34.877, 1, 1700000000000, 4, 'gid-place-1', 1700000000000, 0)")
        c.exec("INSERT INTO trips (startedAtMs, endedAtMs, distanceM, elapsedMs, movingTimeMs, stoppedTimeMs, stopCount, maxSpeedMps, elevationGainM, elevationLossM, title, gid, updatedAtMs, dirty) VALUES (1700000000000, 1700003600000, 42000.0, 3600000, 3000000, 600000, 3, 30.5, 120.0, 118.0, 'Viagem v4', 'gid-trip-1', 1700000000000, 0)")
        // viagem ainda gravando (sem fim): também deve ser ligada ao veículo migrado
        c.exec("INSERT INTO trips (startedAtMs, distanceM, elapsedMs, movingTimeMs, stoppedTimeMs, stopCount, maxSpeedMps, elevationGainM, elevationLossM, gid, updatedAtMs, dirty) VALUES (1710000000000, 100.0, 1000, 1000, 0, 0, 5.0, 0.0, 0.0, 'gid-trip-2', 1710000000000, 1)")
        c.exec("INSERT INTO track_points (tripId, idx, timeMs, latitude, longitude, altitudeM, speedMps, accuracyM, bearingDeg) VALUES (1, 0, 1700000000000, -8.00, -34.80, 10.5, 12.0, 5.0, 90.0)")
        c.exec("INSERT INTO odometer (id, offsetKm, tankLiters, updatedAtMs, dirty) VALUES (1, 450.0, 14.0, 1700000000000, 0)")
        c.exec("INSERT INTO tombstones (kind, gid, deletedAtMs, dirty) VALUES ('FUELUP', 'deleted-gid-1', 1700500000000, 1)")
    }

    private fun migrate(c: Connection) = Migration4To5.statements.forEach { c.exec(it) }

    @Test
    fun `base com dados vira tudo ligado ao veiculo migrado Minha moto`() {
        v4Connection().use { c ->
            seedV4Data(c)
            migrate(c)

            val v = c.query("SELECT id, tipo, apelido, marca, modelo, ano, tanqueLitros, consumoPadraoKmL, removidoEm, dirty FROM veiculos") {
                listOf(it.getString("id"), it.getString("tipo"), it.getString("apelido"), it.getString("marca"), it.getString("modelo"),
                    it.getObject("ano"), it.getDouble("tanqueLitros"), it.getObject("consumoPadraoKmL"), it.getObject("removidoEm"), it.getInt("dirty"))
            }.single()
            assertEquals(listOf(VEICULO_LEGADO_ID, "MOTO", "Minha moto", "", "", null, 14.0, null, null, 1), v)

            assertEquals(listOf(VEICULO_LEGADO_ID, VEICULO_LEGADO_ID), c.query("SELECT veiculoId FROM fuelups ORDER BY id") { it.getString(1) })
            assertEquals(listOf(VEICULO_LEGADO_ID), c.query("SELECT veiculoId FROM maintenance_items") { it.getString(1) })
            assertEquals(listOf(VEICULO_LEGADO_ID, VEICULO_LEGADO_ID), c.query("SELECT veiculoId FROM trips ORDER BY id") { it.getString(1) })
            // hodômetro: uma linha, do veículo migrado, com os valores antigos
            assertEquals(
                listOf(listOf(VEICULO_LEGADO_ID, 450.0, 14.0, 1700000000000L, 0)),
                c.query("SELECT veiculoId, offsetKm, tankLiters, updatedAtMs, dirty FROM odometer") {
                    listOf(it.getString(1), it.getDouble(2), it.getDouble(3), it.getLong(4), it.getInt(5))
                },
            )
        }
    }

    @Test
    fun `nada do que existia e perdido`() {
        v4Connection().use { c ->
            seedV4Data(c)
            migrate(c)
            assertEquals(2, count(c, "fuelups"))
            assertEquals(1, count(c, "maintenance_items"))
            assertEquals(1, count(c, "places"))
            assertEquals(2, count(c, "trips"))
            assertEquals(1, count(c, "track_points"))
            assertEquals(1, count(c, "tombstones"))
            assertEquals(listOf(12000.5, 12250.0), c.query("SELECT odometerKm FROM fuelups ORDER BY id") { it.getDouble(1) })
            assertEquals(listOf("gid-fuel-1", "gid-fuel-2"), c.query("SELECT gid FROM fuelups ORDER BY id") { it.getString(1) })
            assertEquals(listOf("Óleo do motor"), c.query("SELECT name FROM maintenance_items") { it.getString(1) })
            assertEquals(listOf("Viagem v4"), c.query("SELECT title FROM trips WHERE title IS NOT NULL") { it.getString(1) })
        }
    }

    @Test
    fun `base vazia fica sem veiculo e sem hodometro`() {
        v4Connection().use { c ->
            migrate(c)
            assertEquals(0, count(c, "veiculos"))
            assertEquals(0, count(c, "odometer"))
            assertEquals(0, count(c, "fuelups"))
        }
    }

    @Test
    fun `so com locais favoritos nao cria veiculo`() {
        v4Connection().use { c ->
            c.exec("INSERT INTO places (name, subtitle, lat, lon, favorite, lastUsedMs, useCount, gid, updatedAtMs, dirty) VALUES ('Casa', NULL, -8.0, -34.8, 1, 1, 1, 'g', 1, 0)")
            migrate(c)
            assertEquals(0, count(c, "veiculos"))
        }
    }

    @Test
    fun `sem linha de hodometro o tanque do veiculo migrado e 13 L`() {
        v4Connection().use { c ->
            c.exec("INSERT INTO fuelups (dateMs, odometerKm, liters, totalCost, fullTank, gid, updatedAtMs, dirty) VALUES (1, 100.0, 5.0, 30.0, 1, 'g', 1, 1)")
            migrate(c)
            assertEquals(listOf(13.0), c.query("SELECT tanqueLitros FROM veiculos") { it.getDouble(1) })
            assertEquals(0, count(c, "odometer"))
        }
    }

    @Test
    fun `so com hodometro tambem cria o veiculo`() {
        v4Connection().use { c ->
            c.exec("INSERT INTO odometer (id, offsetKm, tankLiters, updatedAtMs, dirty) VALUES (1, 10.0, 12.0, 5, 1)")
            migrate(c)
            assertEquals(listOf(12.0), c.query("SELECT tanqueLitros FROM veiculos") { it.getDouble(1) })
        }
    }

    @Test
    fun `repetir a parte de dados nao duplica o veiculo nem muda as ligacoes`() {
        v4Connection().use { c ->
            seedV4Data(c)
            migrate(c)
            val antes = c.query("SELECT id, criadoEm, updatedAtMs FROM veiculos") { Triple(it.getString(1), it.getLong(2), it.getLong(3)) }
            Migration4To5.dados.forEach { c.exec(it) }
            Migration4To5.dados.forEach { c.exec(it) }
            assertEquals(1, count(c, "veiculos"))
            assertEquals(antes, c.query("SELECT id, criadoEm, updatedAtMs FROM veiculos") { Triple(it.getString(1), it.getLong(2), it.getLong(3)) })
            assertEquals(2, c.query("SELECT COUNT(*) FROM fuelups WHERE veiculoId = '$VEICULO_LEGADO_ID'") { it.getInt(1) }.single())
        }
    }

    @Test
    fun `depois da migracao o hodometro aceita uma linha por veiculo`() {
        v4Connection().use { c ->
            seedV4Data(c)
            migrate(c)
            c.exec("INSERT INTO odometer (veiculoId, offsetKm, tankLiters, updatedAtMs, dirty) VALUES ('carro-1', 5000.0, 45.0, 1, 1)")
            assertEquals(2, count(c, "odometer"))
            assertTrue(runCatching { c.exec("INSERT INTO odometer (veiculoId, offsetKm, tankLiters, updatedAtMs, dirty) VALUES ('carro-1', 1.0, 1.0, 1, 1)") }.isFailure)
        }
    }

    /**
     * Rede de segurança do que o Room valida ao abrir o banco migrado: por tabela, colunas (nome, tipo, NOT NULL,
     * DEFAULT, posição na chave primária), índices e chaves estrangeiras do banco v4 migrado devem ser iguais às
     * de um banco v5 novo criado pelo 5.json.
     */
    @Test
    fun `esquema migrado e igual ao esquema v5 exportado`() {
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
        val migrated = v4Connection().use { c -> seedV4Data(c); migrate(c); describe(c) }
        val fresh = schemaConnection(5).use { describe(it) }
        assertEquals(fresh.keys, migrated.keys)
        for (t in fresh.keys) assertEquals(fresh[t], migrated[t], "tabela $t")
    }
}
