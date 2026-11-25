package app.hunter.app.data

import app.hunter.sync.VEICULO_LEGADO_ID

/**
 * v4 → v5: vários veículos. Cria `veiculos`, liga abastecimentos, manutenção, viagens e hodômetro a um veículo
 * e passa o hodômetro de "linha única (id = 1)" para "uma linha por veículo" (chave = `veiculoId`).
 *
 * Quem já tinha dados (qualquer abastecimento, item de manutenção, viagem ou hodômetro) ganha o veículo
 * "Minha moto" (tipo MOTO, id fixo [VEICULO_LEGADO_ID]: igual em todos os aparelhos da conta, para a nuvem
 * não gerar duas motos), com o tanque da linha de hodômetro existente (13 L sem ela) e tudo ligado a ele.
 * Instalação sem dados: nenhum veículo (o cadastro é feito pelo app).
 *
 * Marca e modelo ficam VAZIOS aqui: o SQL não alcança o perfil (SharedPreferences). O app completa na primeira
 * abertura depois da migração (ver `completarVeiculoLegado`).
 *
 * Os comandos ficam aqui, em Kotlin puro, para a [AppDatabase.MIGRATION_4_5] e o teste na JVM usarem o mesmo SQL.
 * [estrutura] roda uma vez (ALTER TABLE não tem "se não existir"); [dados] só usa INSERT OR IGNORE e UPDATE
 * condicionados, então repetir não duplica o veículo nem muda nada.
 */
object Migration4To5 {
    private const val NOW_MS = "CAST(strftime('%s','now') AS INTEGER) * 1000"
    private const val ID = "'$VEICULO_LEGADO_ID'"

    val estrutura: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `veiculos` (`id` TEXT NOT NULL, `tipo` TEXT NOT NULL, `apelido` TEXT NOT NULL, " +
            "`marca` TEXT NOT NULL, `modelo` TEXT NOT NULL, `ano` INTEGER, `tanqueLitros` REAL NOT NULL, " +
            "`consumoPadraoKmL` REAL, `criadoEm` INTEGER NOT NULL, `removidoEm` INTEGER, " +
            "`updatedAtMs` INTEGER NOT NULL DEFAULT 0, `dirty` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
        "ALTER TABLE `fuelups` ADD COLUMN `veiculoId` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `maintenance_items` ADD COLUMN `veiculoId` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `trips` ADD COLUMN `veiculoId` TEXT",
        // Hodômetro: a chave primária muda (id fixo 1 -> veiculoId); SQLite exige recriar a tabela.
        "CREATE TABLE IF NOT EXISTS `odometer_v5` (`veiculoId` TEXT NOT NULL, `offsetKm` REAL NOT NULL, " +
            "`tankLiters` REAL NOT NULL, `updatedAtMs` INTEGER NOT NULL DEFAULT 0, `dirty` INTEGER NOT NULL DEFAULT 1, " +
            "PRIMARY KEY(`veiculoId`))",
        "INSERT INTO `odometer_v5` (`veiculoId`, `offsetKm`, `tankLiters`, `updatedAtMs`, `dirty`) " +
            "SELECT $ID, `offsetKm`, `tankLiters`, `updatedAtMs`, `dirty` FROM `odometer` WHERE `id` = 1",
        "DROP TABLE `odometer`",
        "ALTER TABLE `odometer_v5` RENAME TO `odometer`",
    )

    val dados: List<String> = listOf(
        // Só cria o veículo migrado se já havia dados. Sujo (dirty = 1): sobe para a nuvem na próxima sincronização.
        "INSERT OR IGNORE INTO `veiculos` (`id`, `tipo`, `apelido`, `marca`, `modelo`, `ano`, `tanqueLitros`, " +
            "`consumoPadraoKmL`, `criadoEm`, `removidoEm`, `updatedAtMs`, `dirty`) " +
            "SELECT $ID, 'MOTO', 'Minha moto', '', '', NULL, COALESCE((SELECT `tankLiters` FROM `odometer` LIMIT 1), 13.0), " +
            "NULL, $NOW_MS, NULL, $NOW_MS, 1 " +
            "WHERE (SELECT COUNT(*) FROM `fuelups`) + (SELECT COUNT(*) FROM `maintenance_items`) + " +
            "(SELECT COUNT(*) FROM `trips`) + (SELECT COUNT(*) FROM `odometer`) > 0",
        "UPDATE `fuelups` SET `veiculoId` = $ID WHERE `veiculoId` = ''",
        "UPDATE `maintenance_items` SET `veiculoId` = $ID WHERE `veiculoId` = ''",
        "UPDATE `trips` SET `veiculoId` = $ID WHERE `veiculoId` IS NULL",
    )

    val statements: List<String> = estrutura + dados
}
