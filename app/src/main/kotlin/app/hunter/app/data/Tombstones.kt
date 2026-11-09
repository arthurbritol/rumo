package app.hunter.app.data

import androidx.room.Entity

/**
 * Lápide: registro de que um item sincronizável foi apagado neste aparelho, para a exclusão chegar à nuvem.
 * [kind] é o `name` de `app.hunter.sync.Kind` (os DAOs dos módulos gravam o literal em SQL).
 */
@Entity(tableName = "tombstones", primaryKeys = ["kind", "gid"])
data class TombstoneEntity(
    val kind: String,
    val gid: String,
    val deletedAtMs: Long,
    val dirty: Boolean,
)
