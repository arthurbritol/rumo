package app.hunter.app.data

/**
 * v2 → v3: id global (gid), data de alteração e marca de "sujo" nas tabelas sincronizáveis, mais a tabela de
 * lápides. Só ADICIONA colunas/tabelas; nenhuma linha existente é tocada além de receber os novos valores.
 *
 * Os comandos ficam aqui, em Kotlin puro, para a [AppDatabase.MIGRATION_2_3] e o teste na JVM usarem
 * exatamente o mesmo SQL. As colunas novas têm DEFAULT idêntico ao `@ColumnInfo(defaultValue)` das entidades,
 * para o Room validar o esquema migrado igual ao de um banco novo.
 *
 * Linhas existentes ficam com dirty = 1: sobem para a nuvem no primeiro login.
 */
object Migration2To3 {
    private const val NOW_MS = "CAST(strftime('%s','now') AS INTEGER) * 1000"

    private val syncTables = listOf("fuelups", "maintenance_items", "places", "trips")

    val statements: List<String> = buildList {
        for (t in syncTables) {
            add("ALTER TABLE `$t` ADD COLUMN `gid` TEXT NOT NULL DEFAULT ''")
            add("ALTER TABLE `$t` ADD COLUMN `updatedAtMs` INTEGER NOT NULL DEFAULT 0")
            add("ALTER TABLE `$t` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 1")
            // randomblob é reavaliado a cada linha: um gid distinto por linha.
            add("UPDATE `$t` SET `gid` = lower(hex(randomblob(16))), `updatedAtMs` = $NOW_MS, `dirty` = 1")
            add("CREATE UNIQUE INDEX IF NOT EXISTS `index_${t}_gid` ON `$t` (`gid`)")
        }
        add("ALTER TABLE `odometer` ADD COLUMN `updatedAtMs` INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE `odometer` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 1")
        add("UPDATE `odometer` SET `updatedAtMs` = $NOW_MS, `dirty` = 1")
        add(
            "CREATE TABLE IF NOT EXISTS `tombstones` (`kind` TEXT NOT NULL, `gid` TEXT NOT NULL, " +
                "`deletedAtMs` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`kind`, `gid`))",
        )
    }
}
