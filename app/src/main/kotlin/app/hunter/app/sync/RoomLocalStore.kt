package app.hunter.app.sync

import android.content.SharedPreferences
import androidx.room.withTransaction
import app.hunter.app.data.AppDatabase
import app.hunter.app.data.SyncDao
import app.hunter.app.data.TombstoneEntity
import app.hunter.sync.Kind
import app.hunter.sync.LocalStore
import app.hunter.sync.OdometerDoc
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile
import java.util.UUID

/**
 * Adaptador Room/SharedPreferences para o motor de sincronização.
 *
 * Só locais favoritos e viagens concluídas entram; viagem recebida da nuvem vira [app.hunter.trips.data.TripEntity]
 * sem pontos de trajeto. As escritas daqui NÃO marcam sujo (vêm da nuvem); as da interface passam pelos DAOs
 * dos módulos (que marcam sujo e deixam lápide). O mapeamento e as regras de versão estão em Mappers.kt.
 */
class RoomLocalStore(private val db: AppDatabase, private val prefs: SharedPreferences) : LocalStore {
    private val dao: SyncDao get() = db.syncDao()
    private val maintenance get() = db.maintenanceDao()

    override suspend fun all(): List<SyncDoc> = db.withTransaction {
        dao.fuelups().map { it.toDoc() } +
            dao.items().map { it.toDoc() } +
            dao.favoritePlaces().mapNotNull { it.toDocOrNull() } +
            dao.finishedTrips().mapNotNull { it.toDocOrNull() } +
            dao.tombstones().map { it.toDoc() }
    }

    override suspend fun dirty(): List<SyncDoc> = db.withTransaction {
        dao.dirtyFuelups().map { it.toDoc() } +
            dao.dirtyItems().map { it.toDoc() } +
            dao.dirtyPlaces().mapNotNull { it.toDocOrNull() } +
            dao.dirtyTrips().mapNotNull { it.toDocOrNull() } +
            dao.dirtyTombstones().map { it.toDoc() }
    }

    override suspend fun apply(docs: List<SyncDoc>) = db.withTransaction {
        val now = System.currentTimeMillis()
        for (d in docs) {
            when (d.kind) {
                Kind.FUELUP -> applyOne(d, dao.fuelupByGid(d.gid)?.updatedAtMs,
                    onDelete = { dao.deleteFuelup(d.gid) },
                    onUpsert = {
                        val cur = dao.fuelupByGid(d.gid)
                        if (cur == null) dao.insertFuelup(fuelupFromDoc(d, null)) else dao.updateFuelup(fuelupFromDoc(d, cur))
                    })
                Kind.MAINTENANCE -> applyOne(d, dao.itemByGid(d.gid)?.updatedAtMs,
                    onDelete = { dao.deleteItem(d.gid) },
                    onUpsert = {
                        val cur = dao.itemByGid(d.gid)
                        if (cur == null) dao.insertItem(itemFromDoc(d, null)) else dao.updateItem(itemFromDoc(d, cur))
                    })
                Kind.PLACE -> applyOne(d, dao.placeByGid(d.gid)?.updatedAtMs,
                    onDelete = {
                        // Apagado em outro aparelho: deixa de ser favorito aqui, mas continua nos recentes (local).
                        dao.placeByGid(d.gid)?.let {
                            dao.updatePlace(it.copy(favorite = false, gid = UUID.randomUUID().toString(), dirty = false))
                        }
                    },
                    onUpsert = {
                        val byGid = dao.placeByGid(d.gid)
                        // Sem o mesmo gid: se já há um recente nas mesmas coordenadas, ele vira o favorito (sem duplicar).
                        val cur = byGid ?: dao.placeNear(
                            (d.fields["lat"] as? Number)?.toDouble() ?: 0.0, (d.fields["lon"] as? Number)?.toDouble() ?: 0.0,
                        )
                        val e = placeFromDoc(d, cur, now)
                        if (cur == null) dao.insertPlace(e) else dao.updatePlace(e)
                    })
                Kind.TRIP -> applyOne(d, dao.tripByGid(d.gid)?.updatedAtMs,
                    onDelete = { dao.deleteTrip(d.gid) },
                    onUpsert = {
                        val cur = dao.tripByGid(d.gid)
                        if (cur == null) dao.insertTrip(tripFromDoc(d, null)) else dao.updateTrip(tripFromDoc(d, cur))
                    })
            }
        }
    }

    /**
     * Regra comum: linha ou lápide local MAIS NOVA que o doc não é sobrescrita (edição feita durante a
     * sincronização continua e continua suja). Caso contrário aplica e deixa tudo limpo.
     */
    private suspend fun applyOne(d: SyncDoc, localRowUpdatedAtMs: Long?, onDelete: suspend () -> Unit, onUpsert: suspend () -> Unit) {
        val tomb = dao.tombstone(d.kind.name, d.gid)
        if (!shouldApply(localRowUpdatedAtMs, d.updatedAtMs) || !shouldApply(tomb?.deletedAtMs, d.updatedAtMs)) return
        if (d.deleted) {
            onDelete()
            dao.putTombstone(TombstoneEntity(d.kind.name, d.gid, d.updatedAtMs, dirty = false))
        } else {
            dao.removeTombstone(d.kind.name, d.gid)
            onUpsert()
        }
    }

    override suspend fun markClean(docs: List<SyncDoc>) = db.withTransaction {
        for (d in docs) {
            if (d.deleted) {
                dao.cleanTombstone(d.kind.name, d.gid, d.updatedAtMs)
                continue
            }
            // Os UPDATE comparam updatedAtMs no próprio SQL: só limpa se a versão enviada ainda é a atual.
            when (d.kind) {
                Kind.FUELUP -> dao.cleanFuelup(d.gid, d.updatedAtMs)
                Kind.MAINTENANCE -> dao.cleanItem(d.gid, d.updatedAtMs)
                Kind.PLACE -> dao.cleanPlace(d.gid, d.updatedAtMs)
                Kind.TRIP -> dao.cleanTrip(d.gid, d.updatedAtMs)
            }
        }
    }

    override suspend fun odometer(): OdometerDoc? = maintenance.getOdometer()?.toDoc()

    override suspend fun applyOdometer(o: OdometerDoc) = db.withTransaction {
        val cur = maintenance.getOdometer()
        if (cur == null || shouldApply(cur.updatedAtMs, o.updatedAtMs)) maintenance.putOdometerRaw(odometerFromDoc(o))
    }

    override suspend fun profile(): UserProfile? = ProfilePrefs.read(prefs)

    override suspend fun applyProfile(p: UserProfile) = ProfilePrefs.write(prefs, p)

    override suspend fun hasDirtyOdometerOrProfile(): Boolean =
        maintenance.getOdometer()?.dirty == true || ProfilePrefs.isDirty(prefs)

    override suspend fun clearAll() {
        db.withTransaction {
            val blocker = clearAllBlocker(
                dirtyRows = dao.dirtyCount(),
                odometerDirty = maintenance.getOdometer()?.dirty == true,
                profileDirty = ProfilePrefs.isDirty(prefs),
            )
            if (blocker != null) throw IllegalStateException(blocker)
            dao.wipeTrips() // track_points saem por cascata
            dao.wipeFuelups()
            dao.wipeItems()
            dao.wipePlaces()
            dao.wipeTombstones()
            dao.wipeOdometer()
        }
        ProfilePrefs.clear(prefs)
    }
}
