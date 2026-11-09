package app.hunter.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import app.hunter.nav.Place
import kotlinx.coroutines.flow.Flow

/** Destino já usado (recentes) e/ou marcado como favorito. */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val subtitle: String?,
    val lat: Double,
    val lon: Double,
    val favorite: Boolean,
    val lastUsedMs: Long,
    val useCount: Int,
) {
    fun toPlace() = Place(name, subtitle, lat, lon)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE favorite = 1 ORDER BY name")
    fun observeFavorites(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY lastUsedMs DESC LIMIT 10")
    fun observeRecents(): Flow<List<PlaceEntity>>

    /** Mesmo lugar = coordenadas iguais até ~10 m. */
    @Query("SELECT * FROM places WHERE ROUND(lat, 4) = ROUND(:lat, 4) AND ROUND(lon, 4) = ROUND(:lon, 4) LIMIT 1")
    suspend fun find(lat: Double, lon: Double): PlaceEntity?

    @Insert
    suspend fun insert(p: PlaceEntity): Long

    @Update
    suspend fun update(p: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)
}
