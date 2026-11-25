package app.hunter.sync

/**
 * Id fixo do veículo "Minha moto" criado pela migração do banco (Room 4 para 5) a partir do perfil antigo.
 * É igual em todos os aparelhos da conta (os ids de veículo são por usuário, então não colide), para que dois
 * aparelhos que migraram sozinhos convirjam para o MESMO veículo em vez de gerar duas motos.
 */
const val VEICULO_LEGADO_ID = "00000000-0000-4000-8000-000000000001"

/**
 * Veículo dono dos documentos antigos (sem `veiculoId`): o veículo migrado se existir ([VEICULO_LEGADO_ID]); senão a
 * moto não removida mais antiga (`criadoEm`, depois gid); senão qualquer moto; senão o id do veículo migrado
 * (o app cria o veículo que falta; ver RoomLocalStore.apply). Lápides ([SyncDoc.deleted]) são ignoradas.
 */
fun idDoVeiculoLegado(veiculos: List<SyncDoc>): String {
    val vivos = veiculos.filter { it.kind == Kind.VEICULO && !it.deleted }
    if (vivos.any { it.gid == VEICULO_LEGADO_ID }) return VEICULO_LEGADO_ID
    val motos = vivos.filter { it.fields["tipo"] == "MOTO" }
    val ordem = compareBy<SyncDoc>({ it.fields["removidoEm"] != null }, { (it.fields["criadoEm"] as? Number)?.toLong() ?: Long.MAX_VALUE }, { it.gid })
    return motos.minWithOrNull(ordem)?.gid ?: VEICULO_LEGADO_ID
}

private val KINDS_COM_VEICULO = setOf(Kind.FUELUP, Kind.MAINTENANCE, Kind.TRIP)

/**
 * Atribui ao veículo migrado ([idDoVeiculoLegado]) tudo que veio da nuvem sem `veiculoId` (contas anteriores aos
 * vários veículos, ou documentos reescritos por um app antigo), sem perder nada. Hodômetros sem veículo também;
 * se um veículo acabar com dois hodômetros (o antigo e o novo), vale o mais recente.
 * Chamar ao baixar; o motor, ao mesclar, devolve os documentos já com o campo à nuvem.
 */
fun atribuirVeiculoLegado(snap: RemoteSnapshot, extras: List<SyncDoc> = emptyList()): RemoteSnapshot {
    val legado = idDoVeiculoLegado(snap.docs + extras)
    val docs = snap.docs.map { d ->
        val semVeiculo = (d.fields["veiculoId"] as? String).isNullOrBlank()
        if (d.kind in KINDS_COM_VEICULO && !d.deleted && semVeiculo) d.copy(fields = d.fields + ("veiculoId" to legado)) else d
    }
    val odos = snap.odometers
        .map { if (it.veiculoId.isNullOrBlank()) it.copy(veiculoId = legado) else it }
        .sortedByDescending { it.updatedAtMs }
        .distinctBy { it.veiculoId }
    return snap.copy(docs = docs, odometers = odos)
}
