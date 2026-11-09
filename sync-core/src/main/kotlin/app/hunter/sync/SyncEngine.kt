package app.hunter.sync

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

    /** Sincronização periódica: sobe o que está sujo e depois baixa/mescla o remoto. */
    suspend fun syncNow() {
        val dirty = local.dirty()
        if (dirty.isNotEmpty()) {
            remote.push(dirty)
            local.markClean(dirty)
        }
        reconcile()
    }

    /** Verdadeiro quando não há registro, hodômetro ou perfil pendente de envio. */
    suspend fun canSignOut(): Boolean = local.dirty().isEmpty() && !local.hasDirtyOdometerOrProfile()

    /** Limpa o aparelho sem tocar na conta. Exige [canSignOut]; senão lança [IllegalStateException]. */
    suspend fun signOutWipe() {
        check(canSignOut()) { "Há alterações não sincronizadas; sincronize antes de sair." }
        local.clearAll()
    }

    /** Marca a exclusão como pendente e só então apaga; se falhar no meio, [resumePendingDeletion] conclui. */
    suspend fun deleteAccountData() {
        remote.markDeletionPending()
        remote.deleteAllUserData()
    }

    /** Conclui uma exclusão interrompida. Retorna true se havia exclusão pendente. */
    suspend fun resumePendingDeletion(): Boolean {
        if (!remote.deletionPending()) return false
        remote.deleteAllUserData()
        return true
    }

    /**
     * Baixa o remoto, mescla e aplica. Todo o envio ao remoto acontece antes de qualquer escrita
     * local, então uma falha de rede deixa o local intacto.
     *
     * Perfil (sem timestamp): só local sobe; só remoto é aplicado; nos dois, o remoto vence, a menos
     * que [LocalStore.hasDirtyOdometerOrProfile] indique alteração local pendente, caso em que o
     * local sobe (aproximação conservadora: perde-se menos dado, ao custo de um hodômetro sujo
     * também proteger o perfil local).
     */
    private suspend fun reconcile() {
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
