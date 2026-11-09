package app.hunter.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.hunter.nav.Place
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Destino já usado (recentes) e/ou marcado como favorito. */
@Entity(tableName = "places", indices = [Index("gid", unique = true)])
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val subtitle: String?,
    val lat: Double,
    val lon: Double,
    val favorite: Boolean,
    val lastUsedMs: Long,
    val useCount: Int,
    /** Id global (igual em todos os aparelhos); o [id] acima é só local. Só favoritos sincronizam. */
    @ColumnInfo(defaultValue = "''") val gid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
) {
    fun toPlace() = Place(name, subtitle, lat, lon)

    fun touch() = copy(updatedAtMs = System.currentTimeMillis(), dirty = true)
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

    /** Escrita sem marcar para sincronizar (campos só locais, como último uso). */
    @Update
    suspend fun updateLocalOnly(p: PlaceEntity)

    /** Alteração feita pelo usuário: marca como suja, com data de agora. */
    @Transaction
    suspend fun update(p: PlaceEntity) = updateLocalOnly(p.touch())

    @Query("INSERT OR REPLACE INTO tombstones (kind, gid, deletedAtMs, dirty) SELECT 'PLACE', gid, :now, 1 FROM places WHERE id = :id AND favorite = 1")
    suspend fun tombstoneFavorite(id: Long, now: Long)

    /**
     * Desfavoritar = apagar da lista sincronizada: deixa lápide do gid antigo e a linha volta a ser só
     * "recente" (local) com gid novo, para um novo favorito não brigar com a lápide.
     */
    @Transaction
    suspend fun unfavorite(p: PlaceEntity) {
        tombstoneFavorite(p.id, System.currentTimeMillis())
        updateLocalOnly(p.copy(favorite = false, gid = UUID.randomUUID().toString(), dirty = false))
    }

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        tombstoneFavorite(id, System.currentTimeMillis())
        deleteRow(id)
    }
}
