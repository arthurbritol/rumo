package app.hunter.sync

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
     * Baixa o remoto, mescla e aplica.
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
        val localOdo = local.odometer()
        val localProfile = local.profile()

        val plan = merge(local.all(), snap.docs, now)
        val odo = mergeOdometer(localOdo, snap.odometer, now)
        val profile: UserProfile? = when {
            localProfile == null -> snap.profile
            snap.profile == null || localDirtyMeta -> localProfile
            else -> snap.profile
        }

        if (plan.pushRemote.isNotEmpty()) remote.push(plan.pushRemote)
        if (odo != null && odo != snap.odometer) remote.pushOdometer(odo)
        if (profile != null && profile != snap.profile) remote.pushProfile(profile)

        if (plan.applyLocal.isNotEmpty()) local.apply(plan.applyLocal)
        local.markClean(dirtyBefore)
        if (odo != null && (odo != localOdo || localDirtyMeta)) local.applyOdometer(odo)
        if (profile != null && (profile != localProfile || localDirtyMeta)) local.applyProfile(profile)
    }
}
