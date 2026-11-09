package app.hunter.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.trips.data.TripEntity

/**
 * Acesso do [app.hunter.app.sync.RoomLocalStore]. Escreve SEM marcar sujo (as escritas aqui vêm da nuvem);
 * as escritas da interface passam pelos DAOs de cada módulo, que marcam sujo e deixam lápide.
 * Definição de "sujo" (igual para dirty(), contagem e clearAll): só o que realmente sincroniza, ou seja,
 * locais favoritos e viagens concluídas, além de lápides.
 */
@Dao
interface SyncDao {
    // --- abastecimentos
    @Query("SELECT * FROM fuelups") suspend fun fuelups(): List<FuelupEntity>
    @Query("SELECT * FROM fuelups WHERE dirty = 1") suspend fun dirtyFuelups(): List<FuelupEntity>
    @Query("SELECT * FROM fuelups WHERE gid = :gid") suspend fun fuelupByGid(gid: String): FuelupEntity?
    @Insert suspend fun insertFuelup(e: FuelupEntity): Long
    @Update suspend fun updateFuelup(e: FuelupEntity)
    @Query("DELETE FROM fuelups WHERE gid = :gid") suspend fun deleteFuelup(gid: String)
    @Query("UPDATE fuelups SET dirty = 0 WHERE gid = :gid AND updatedAtMs = :ver") suspend fun cleanFuelup(gid: String, ver: Long)

    // --- manutenção
    @Query("SELECT * FROM maintenance_items") suspend fun items(): List<MaintenanceItemEntity>
    @Query("SELECT * FROM maintenance_items WHERE dirty = 1") suspend fun dirtyItems(): List<MaintenanceItemEntity>
    @Query("SELECT * FROM maintenance_items WHERE gid = :gid") suspend fun itemByGid(gid: String): MaintenanceItemEntity?
    @Insert suspend fun insertItem(e: MaintenanceItemEntity): Long
    @Update suspend fun updateItem(e: MaintenanceItemEntity)
    @Query("DELETE FROM maintenance_items WHERE gid = :gid") suspend fun deleteItem(gid: String)
    @Query("UPDATE maintenance_items SET dirty = 0 WHERE gid = :gid AND updatedAtMs = :ver") suspend fun cleanItem(gid: String, ver: Long)

    // --- locais (só favoritos)
    @Query("SELECT * FROM places WHERE favorite = 1") suspend fun favoritePlaces(): List<PlaceEntity>
    @Query("SELECT * FROM places WHERE favorite = 1 AND dirty = 1") suspend fun dirtyPlaces(): List<PlaceEntity>
    @Query("SELECT * FROM places WHERE gid = :gid") suspend fun placeByGid(gid: String): PlaceEntity?

    /** Recente (não favorito) no mesmo lugar: coordenadas iguais até ~10 m. Favoritos nunca são adotados. */
    @Query("SELECT * FROM places WHERE favorite = 0 AND ROUND(lat, 4) = ROUND(:lat, 4) AND ROUND(lon, 4) = ROUND(:lon, 4) LIMIT 1")
    suspend fun placeNearRecent(lat: Double, lon: Double): PlaceEntity?
    @Insert suspend fun insertPlace(e: PlaceEntity): Long
    @Update suspend fun updatePlace(e: PlaceEntity)
    @Query("UPDATE places SET dirty = 0 WHERE gid = :gid AND updatedAtMs = :ver") suspend fun cleanPlace(gid: String, ver: Long)

    // --- viagens (só concluídas)
    @Query("SELECT * FROM trips WHERE endedAtMs IS NOT NULL") suspend fun finishedTrips(): List<TripEntity>
    @Query("SELECT * FROM trips WHERE endedAtMs IS NOT NULL AND dirty = 1") suspend fun dirtyTrips(): List<TripEntity>
    @Query("SELECT * FROM trips WHERE gid = :gid") suspend fun tripByGid(gid: String): TripEntity?
    @Insert suspend fun insertTrip(e: TripEntity): Long
    @Update suspend fun updateTrip(e: TripEntity)

    /** Os track_points saem junto, por cascata. */
    @Query("DELETE FROM trips WHERE gid = :gid") suspend fun deleteTrip(gid: String)
    @Query("UPDATE trips SET dirty = 0 WHERE gid = :gid AND updatedAtMs = :ver") suspend fun cleanTrip(gid: String, ver: Long)

    // --- lápides
    @Query("SELECT * FROM tombstones") suspend fun tombstones(): List<TombstoneEntity>
    @Query("SELECT * FROM tombstones WHERE dirty = 1") suspend fun dirtyTombstones(): List<TombstoneEntity>
    @Query("SELECT * FROM tombstones WHERE kind = :kind AND gid = :gid") suspend fun tombstone(kind: String, gid: String): TombstoneEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTombstone(t: TombstoneEntity)
    @Query("DELETE FROM tombstones WHERE kind = :kind AND gid = :gid") suspend fun removeTombstone(kind: String, gid: String)
    @Query("UPDATE tombstones SET dirty = 0 WHERE kind = :kind AND gid = :gid AND deletedAtMs = :ver")
    suspend fun cleanTombstone(kind: String, gid: String, ver: Long)

    // --- primeira abertura
    /** Quantas linhas de dados do usuário existem (para reconhecer uma instalação anterior). */
    @Query("SELECT (SELECT COUNT(*) FROM trips) + (SELECT COUNT(*) FROM fuelups) + (SELECT COUNT(*) FROM maintenance_items) + (SELECT COUNT(*) FROM places)")
    suspend fun localRowCount(): Int

    // --- sair da conta
    @Query(
        "SELECT (SELECT COUNT(*) FROM fuelups WHERE dirty = 1) + (SELECT COUNT(*) FROM maintenance_items WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM places WHERE favorite = 1 AND dirty = 1) + " +
            "(SELECT COUNT(*) FROM trips WHERE endedAtMs IS NOT NULL AND dirty = 1) + " +
            "(SELECT COUNT(*) FROM tombstones WHERE dirty = 1)",
    )
    suspend fun dirtyCount(): Int

    @Query("DELETE FROM fuelups") suspend fun wipeFuelups()
    @Query("DELETE FROM maintenance_items") suspend fun wipeItems()
    @Query("DELETE FROM places") suspend fun wipePlaces()
    /** Só viagens concluídas: a que está gravando (e seus pontos) fica, senão a gravação em curso quebra. */
    @Query("DELETE FROM trips WHERE endedAtMs IS NOT NULL") suspend fun wipeFinishedTrips()
    @Query("DELETE FROM tombstones") suspend fun wipeTombstones()
    @Query("DELETE FROM odometer") suspend fun wipeOdometer()
}
