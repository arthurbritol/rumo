package app.hunter.sync

/** Armazenamento local (Room, na camada do app). */
interface LocalStore {
    /** Todos os registros, inclusive apagados (lápides). */
    suspend fun all(): List<SyncDoc>

    /** Registros com alterações ainda não enviadas. */
    suspend fun dirty(): List<SyncDoc>

    /**
     * Cria/atualiza/apaga por gid e marca como sincronizado. Deve ser uma única transação.
     */
    suspend fun apply(docs: List<SyncDoc>)

    /**
     * Limpa o indicador de sujo por versão: só para a linha cujo updatedAtMs ainda é igual ao do
     * doc passado. Uma edição feita durante a sincronização (updatedAtMs diferente) continua suja.
     */
    suspend fun markClean(docs: List<SyncDoc>)
    suspend fun odometer(): OdometerDoc?

    /**
     * Grava o hodômetro (se a linha local não for mais nova que [o]). [dirty] falso: marca como sincronizado;
     * verdadeiro: fica pendente de envio (usado quando o motor ajustou o hodômetro mas não conseguiu enviá-lo).
     */
    suspend fun applyOdometer(o: OdometerDoc, dirty: Boolean = false)
    suspend fun profile(): UserProfile?

    /** Grava o perfil e o marca como sincronizado. */
    suspend fun applyProfile(p: UserProfile)
    suspend fun hasDirtyOdometerOrProfile(): Boolean

    /**
     * Apaga tudo do aparelho (sair da conta). A implementação deve ser uma única transação que
     * lança [IllegalStateException], sem apagar nada, se existir qualquer linha suja (registro,
     * hodômetro ou perfil) naquele instante; o motor trata essa exceção como "não pode sair".
     */
    suspend fun clearAll()
}

/** Armazenamento remoto (Firestore, na camada do app). */
interface RemoteStore {
    suspend fun fetchAll(): RemoteSnapshot
    suspend fun push(docs: List<SyncDoc>)
    suspend fun pushOdometer(o: OdometerDoc)
    suspend fun pushProfile(p: UserProfile)
    /** Grava a marca de exclusão (`pending:true`, nenhum dado pessoal). */
    suspend fun markDeletionPending()

    /** A marca de exclusão existe. Como ela é permanente, depois de true é sempre true para o mesmo uid. */
    suspend fun deletionPending(): Boolean

    /**
     * Apaga subcoleções, hodômetro e perfil; idempotente. NÃO apaga a marca de exclusão: ela é
     * permanente, para que um segundo aparelho ainda logado na conta nunca volte a enviar dados.
     */
    suspend fun deleteAllUserData()
}

data class RemoteSnapshot(val docs: List<SyncDoc>, val odometer: OdometerDoc?, val profile: UserProfile?)
