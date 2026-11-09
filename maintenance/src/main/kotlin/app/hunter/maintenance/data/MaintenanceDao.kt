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
    @Query("SELECT * FROM fuelups ORDER BY odometerKm DESC, dateMs DESC")
    fun observeFuelups(): Flow<List<FuelupEntity>>

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
    @Query("SELECT * FROM maintenance_items ORDER BY name")
    fun observeItems(): Flow<List<MaintenanceItemEntity>>

    @Query("SELECT COUNT(*) FROM maintenance_items")
    suspend fun itemCount(): Int

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

    // --- hodômetro
    @Query("SELECT * FROM odometer WHERE id = 1")
    fun observeOdometer(): Flow<OdometerEntity?>

    @Query("SELECT * FROM odometer WHERE id = 1")
    suspend fun getOdometer(): OdometerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOdometerRaw(o: OdometerEntity)

    /** Alteração feita pelo usuário: marca como suja, com data de agora. */
    @Transaction
    suspend fun putOdometer(o: OdometerEntity) = putOdometerRaw(o.touch())
}
