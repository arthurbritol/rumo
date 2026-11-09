package app.hunter.maintenance.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MaintenanceDao {
    // --- abastecimentos
    @Query("SELECT * FROM fuelups ORDER BY odometerKm DESC, dateMs DESC")
    fun observeFuelups(): Flow<List<FuelupEntity>>

    @Insert
    suspend fun insertFuelup(f: FuelupEntity): Long

    @Query("DELETE FROM fuelups WHERE id = :id")
    suspend fun deleteFuelup(id: Long)

    // --- manutenção
    @Query("SELECT * FROM maintenance_items ORDER BY name")
    fun observeItems(): Flow<List<MaintenanceItemEntity>>

    @Query("SELECT COUNT(*) FROM maintenance_items")
    suspend fun itemCount(): Int

    @Insert
    suspend fun insertItem(i: MaintenanceItemEntity): Long

    @Update
    suspend fun updateItem(i: MaintenanceItemEntity)

    @Query("DELETE FROM maintenance_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    // --- hodômetro
    @Query("SELECT * FROM odometer WHERE id = 1")
    fun observeOdometer(): Flow<OdometerEntity?>

    @Query("SELECT * FROM odometer WHERE id = 1")
    suspend fun getOdometer(): OdometerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOdometer(o: OdometerEntity)
}
