package app.hunter.sync

enum class Kind(val path: String) {
    FUELUP("abastecimentos"),
    MAINTENANCE("manutencao"),
    PLACE("locais"),
    TRIP("viagens"),
}

data class SyncDoc(
    val kind: Kind,
    val gid: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val fields: Map<String, Any?>,
)

data class OdometerDoc(val offsetKm: Double, val tankLiters: Double, val updatedAtMs: Long)

data class UserProfile(
    val displayName: String,
    val brand: String?,
    val model: String?,
    val year: Int?,
    val voice: Boolean = true,
)
