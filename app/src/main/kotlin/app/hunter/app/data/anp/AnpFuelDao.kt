package app.hunter.app.data.anp

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnpFuelDao {
    @Query("SELECT * FROM anp_stations WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun getStationsInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<AnpStationEntity>

    @Query("SELECT * FROM anp_stations WHERE cityKey = :cityKey")
    suspend fun getStationsByCity(cityKey: String): List<AnpStationEntity>

    @Query("SELECT * FROM anp_city_stats WHERE cityKey = :cityKey LIMIT 1")
    suspend fun getCityStats(cityKey: String): AnpCityStatsEntity?

    @Query("SELECT * FROM anp_city_stats")
    suspend fun getAllCityStats(): List<AnpCityStatsEntity>

    @Query("SELECT * FROM anp_state_stats WHERE uf = :uf LIMIT 1")
    suspend fun getStateStats(uf: String): AnpStateStatsEntity?

    @Query("SELECT * FROM anp_state_stats")
    suspend fun getAllStateStats(): List<AnpStateStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllStations(stations: List<AnpStationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllCityStats(cities: List<AnpCityStatsEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllStateStats(states: List<AnpStateStatsEntity>)

    @Query("DELETE FROM anp_stations")
    suspend fun clearStations()

    @Query("DELETE FROM anp_city_stats")
    suspend fun clearCityStats()

    @Query("DELETE FROM anp_state_stats")
    suspend fun clearStateStats()
}
