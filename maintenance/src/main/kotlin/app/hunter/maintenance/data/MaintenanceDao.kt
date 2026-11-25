package app.hunter.maintenance.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MaintenanceDao {
    // --- abastecimentos
    @Query("SELECT * FROM fuelups WHERE veiculoId = :veiculoId ORDER BY odometerKm DESC, dateMs DESC")
    fun observeFuelups(veiculoId: String): Flow<List<FuelupEntity>>

    @Insert
    suspend fun insertFuelup(f: FuelupEntity): Long

    @Query("INSERT OR REPLACE INTO tombstones (kind, gid, deletedAtMs, dirty) SELECT 'FUELUP', gid, :now, 1 FROM fuelups WHERE id = :id")
    suspend fun tombstoneFuelup(id: Long, now: Long)

    @Query("DELETE FROM fuelups WHERE id = :id")
    suspend fun deleteFuelupRow(id: Long)

    /** Apaga e deixa lápide (para a exclusão também chegar à nuvem), na mesma transação. */
    @Transaction
    suspend fun deleteFuelup(id: Long) {
        tombstoneFuelup(id, System.currentTimeMillis())
        deleteFuelupRow(id)
    }

    // --- manutenção
    @Query("SELECT * FROM maintenance_items WHERE veiculoId = :veiculoId ORDER BY name")
    fun observeItems(veiculoId: String): Flow<List<MaintenanceItemEntity>>

    @Query("SELECT COUNT(*) FROM maintenance_items WHERE veiculoId = :veiculoId")
    suspend fun itemCount(veiculoId: String): Int

    @Insert
    suspend fun insertItem(i: MaintenanceItemEntity): Long

    @Query("SELECT * FROM maintenance_items WHERE id = :id")
    suspend fun getItem(id: Long): MaintenanceItemEntity?

    @Update
    suspend fun updateItemRaw(i: MaintenanceItemEntity)

    /** Alteração feita pelo usuário: marca como suja, com data de agora. */
    @Transaction
    suspend fun updateItem(i: MaintenanceItemEntity) = updateItemRaw(i.touch())

    @Query("INSERT OR REPLACE INTO tombstones (kind, gid, deletedAtMs, dirty) SELECT 'MAINTENANCE', gid, :now, 1 FROM maintenance_items WHERE id = :id")
    suspend fun tombstoneItem(id: Long, now: Long)

    @Query("DELETE FROM maintenance_items WHERE id = :id")
    suspend fun deleteItemRow(id: Long)

    @Transaction
    suspend fun deleteItem(id: Long) {
        tombstoneItem(id, System.currentTimeMillis())
        deleteItemRow(id)
    }

    // --- hodômetro (uma linha por veículo)
    @Query("SELECT * FROM odometer WHERE veiculoId = :veiculoId")
    fun observeOdometer(veiculoId: String): Flow<OdometerEntity?>

    @Query("SELECT * FROM odometer WHERE veiculoId = :veiculoId")
    suspend fun getOdometer(veiculoId: String): OdometerEntity?

    @Query("SELECT * FROM odometer")
    suspend fun getOdometers(): List<OdometerEntity>

    @Query("SELECT COUNT(*) FROM odometer WHERE dirty = 1")
    suspend fun dirtyOdometerCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOdometerRaw(o: OdometerEntity)

    /** Alteração feita pelo usuário: marca como suja, com data de agora. */
    @Transaction
    suspend fun putOdometer(o: OdometerEntity) = putOdometerRaw(o.touch())

    // --- veículos
    /** Veículos não removidos, do mais antigo ao mais novo. */
    @Query("SELECT * FROM veiculos WHERE removidoEm IS NULL ORDER BY criadoEm, id")
    fun observeVeiculos(): Flow<List<VeiculoEntity>>

    @Query("SELECT * FROM veiculos WHERE removidoEm IS NULL ORDER BY criadoEm, id")
    suspend fun getVeiculos(): List<VeiculoEntity>

    @Query("SELECT * FROM veiculos WHERE id = :id")
    suspend fun getVeiculo(id: String): VeiculoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVeiculoRaw(v: VeiculoEntity)

    /** Alteração feita pelo usuário: marca como suja, com data de agora. */
    @Transaction
    suspend fun putVeiculo(v: VeiculoEntity) = putVeiculoRaw(v.touch())

    /** Ids de veículo usados por linhas de dados que não têm a linha em `veiculos` (restauração da nuvem). */
    @Query(
        "SELECT veiculoId FROM (SELECT veiculoId FROM fuelups UNION SELECT veiculoId FROM maintenance_items " +
            "UNION SELECT veiculoId FROM trips WHERE veiculoId IS NOT NULL UNION SELECT veiculoId FROM odometer) " +
            "WHERE veiculoId <> '' AND veiculoId NOT IN (SELECT id FROM veiculos)",
    )
    suspend fun veiculoIdsSemLinha(): List<String>
}
