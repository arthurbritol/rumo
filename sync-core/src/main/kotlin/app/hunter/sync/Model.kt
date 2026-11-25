package app.hunter.sync

enum class Kind(val path: String) {
    FUELUP("abastecimentos"),
    MAINTENANCE("manutencao"),
    PLACE("locais"),
    TRIP("viagens"),
    /** Veículos da conta (moto/carro). Remoção é lógica (campo `removidoEm`), então não usa lápide. */
    VEICULO("veiculos"),
}

data class SyncDoc(
    val kind: Kind,
    val gid: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val fields: Map<String, Any?>,
)

/**
 * Hodômetro/tanque de UM veículo. [veiculoId] nulo = documento antigo (anterior aos vários veículos): quem baixa o
 * atribui ao veículo migrado (ver [atribuirVeiculoLegado]).
 */
data class OdometerDoc(val offsetKm: Double, val tankLiters: Double, val updatedAtMs: Long, val veiculoId: String? = null)

data class UserProfile(
    val displayName: String,
    val brand: String?,
    val model: String?,
    val year: Int?,
    val voice: Boolean = true,
    /** Veículo ativo da conta (para o outro aparelho abrir no mesmo veículo); opcional. */
    val veiculoAtivoId: String? = null,
)
