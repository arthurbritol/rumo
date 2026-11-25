package app.hunter.sync

import kotlin.coroutines.cancellation.CancellationException

/**
 * A conta tem a marca de exclusão (apagada ou sendo apagada): a sincronização não envia nada. Quem
 * recebe deve concluir a exclusão / encerrar a sessão neste aparelho.
 */
class DeletionPendingException : IllegalStateException("A conta foi apagada; nada é sincronizado.")

/**
 * Motor de sincronização entre o armazenamento local e o remoto. Não conhece Room nem Firestore.
 * A mesclagem de registros e do hodômetro vem de [merge] e [mergeOdometer].
 */
class SyncEngine(
    private val local: LocalStore,
    private val remote: RemoteStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Primeiro login no aparelho: baixa a conta, mescla com o que existe local, sobe o que só
     * existe local e marca tudo como sincronizado. Se o download falhar, o local não é alterado
     * e a exceção propaga.
     */
    suspend fun firstSync() {
        reconcile()
    }

    /**
     * Sincronização periódica: baixa, mescla (último a escrever vence) e sobe só os vencedores
     * locais. Não envia o sujo antes de comparar, para não sobrescrever uma edição remota mais nova.
     */
    suspend fun syncNow() {
        reconcile()
    }

    /** Verdadeiro quando não há registro, hodômetro ou perfil pendente de envio. */
    suspend fun canSignOut(): Boolean = local.dirty().isEmpty() && !local.hasDirtyOdometerOrProfile()

    /**
     * Limpa o aparelho sem tocar na conta. Exige [canSignOut]; senão lança [IllegalStateException].
     * Se uma edição chegar entre a checagem e a limpeza, [LocalStore.clearAll] lança
     * [IllegalStateException], que propaga como "não pode sair" sem apagar nada.
     */
    suspend fun signOutWipe() {
        check(canSignOut()) { "Há alterações não sincronizadas; sincronize antes de sair." }
        local.clearAll()
    }

    /**
     * Grava a marca de exclusão e só então apaga os dados. A marca é PERMANENTE (só `pending:true`, sem
     * dado pessoal): nunca é removida, então nenhum aparelho volta a enviar dados para este uid.
     */
    suspend fun deleteAccountData() {
        remote.markDeletionPending()
        remote.deleteAllUserData()
    }

    /** A conta tem exclusão pedida (marca presente). Uma vez verdadeiro, é verdadeiro para sempre. */
    suspend fun isDeletionPending(): Boolean = remote.deletionPending()

    /** Grava a marca de exclusão sem apagar nada. */
    suspend fun markDeletionPending() = remote.markDeletionPending()

    /**
     * Se a conta tem a marca de exclusão, apaga os dados de novo (idempotente; conclui uma exclusão
     * interrompida) e retorna true. A marca continua: a resposta para o mesmo uid nunca volta a ser false.
     */
    suspend fun resumePendingDeletion(): Boolean {
        if (!remote.deletionPending()) return false
        remote.deleteAllUserData()
        return true
    }

    /**
     * Baixa o remoto, mescla e aplica. Ordem: registros (envia, aplica, marca limpo; uma falha no envio
     * deixa o local intacto e propaga), depois hodômetro e perfil, cada um tentado mesmo que o outro falhe.
     * Um hodômetro/perfil recusado continua sujo localmente e o erro é relançado no fim — nunca impede que
     * os registros remotos sejam aplicados (antes, um perfil inválido travava a sincronização para sempre).
     *
     * Marca de exclusão presente (exclusão pedida aqui ou em outro aparelho): apaga de novo, não envia
     * nada e lança [DeletionPendingException], para a camada do app encerrar a sessão neste aparelho.
     *
     * Perfil (sem timestamp): só local sobe; só remoto é aplicado; nos dois, o remoto vence, a menos
     * que [LocalStore.hasDirtyOdometerOrProfile] indique alteração local pendente, caso em que o
     * local sobe (aproximação conservadora: perde-se menos dado, ao custo de um hodômetro sujo
     * também proteger o perfil local).
     */
    private suspend fun reconcile() {
        if (resumePendingDeletion()) throw DeletionPendingException()
        val now = clock()
        val snap = remote.fetchAll()
        val dirtyBefore = local.dirty()
        val localDirtyMeta = local.hasDirtyOdometerOrProfile()
        val localOdos = local.odometers()
        val localProfile = local.profile()

        val localDocs = local.all()
        val plan = merge(localDocs, snap.docs, now)
        // Hodômetro de cada veículo: último a escrever vence, corrigido pelas viagens (do mesmo veículo) que a
        // mesclagem trouxe ao lado vencedor (ver KDoc de mergeOdometerWithTrips).
        val veiculoIds = (localOdos.map { it.veiculoId } + snap.odometers.map { it.veiculoId }).distinct()
        val odoPlans = veiculoIds.map { id ->
            val localOdo = localOdos.firstOrNull { it.veiculoId == id }
            val remoteOdo = snap.odometers.firstOrNull { it.veiculoId == id }
            val odo = mergeOdometerWithTrips(
                localOdo, remoteOdo, docsDoVeiculo(localDocs, id), docsDoVeiculo(snap.docs, id), docsDoVeiculo(plan.merged, id), now,
            )
            OdoPlan(localOdo, remoteOdo, odo, odoAdjusted = odo != null && odo != mergeOdometer(localOdo, remoteOdo, now))
        }
        val profile: UserProfile? = when {
            localProfile == null -> snap.profile
            snap.profile == null || localDirtyMeta -> localProfile
            else -> snap.profile
        }

        // 1) Registros: envio antes de qualquer escrita local (falha aqui deixa o local intacto e propaga).
        if (plan.pushRemote.isNotEmpty()) remote.push(plan.pushRemote)
        if (plan.applyLocal.isNotEmpty()) local.apply(plan.applyLocal)
        local.markClean(dirtyBefore)

        // 2) Hodômetro e perfil, cada um independente: a falha de um (rede, regra) não impede o outro nem
        // desfaz os registros já sincronizados; o primeiro erro é relançado no fim para o agendador reportar.
        var failure: Exception? = null
        for (p in odoPlans) {
            val odo = p.odo ?: continue
            val err = if (odo == p.remoteOdo) null else attempt { remote.pushOdometer(odo) }
            if (err != null) {
                failure = failure ?: err
                // As viagens trazidas já foram aplicadas: o ajuste precisa ficar no aparelho (sujo) e subir depois,
                // senão a diferença some e o hodômetro fica contado em dobro.
                if (p.odoAdjusted) local.applyOdometer(odo, dirty = true)
            } else if (odo != p.localOdo || localDirtyMeta) {
                local.applyOdometer(odo)
            }
        }
        if (profile != null) {
            val err = if (profile == snap.profile) null else attempt { remote.pushProfile(profile) }
            if (err != null) {
                failure = failure ?: err
            } else if (profile != localProfile || localDirtyMeta) {
                local.applyProfile(profile)
            }
        }
        failure?.let { throw it }
    }

    private class OdoPlan(val localOdo: OdometerDoc?, val remoteOdo: OdometerDoc?, val odo: OdometerDoc?, val odoAdjusted: Boolean)

    /** Viagens contam para o hodômetro do veículo delas; [id] nulo (documento sem veículo) vê todas. */
    private fun docsDoVeiculo(docs: List<SyncDoc>, id: String?): List<SyncDoc> =
        if (id == null) docs else docs.filter { it.kind != Kind.TRIP || it.fields["veiculoId"] == id }

    /** Roda [block] e devolve a exceção (null se deu certo); cancelamento sempre propaga. */
    private suspend fun attempt(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }
}
