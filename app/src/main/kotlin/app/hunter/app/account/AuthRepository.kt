package app.hunter.app.account

import app.hunter.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Regras de conta sem Android: combina o [AuthGateway] (Firebase) com o [SyncEngine].
 * Falha de rede na primeira sincronização nunca derruba o login: a conta fica logada e o
 * agendador sincroniza depois.
 */
class AuthRepository(
    private val gateway: AuthGateway,
    private val engine: SyncEngine,
    private val firstSyncTimeoutMs: Long = FIRST_SYNC_TIMEOUT_MS,
) {
    val state: StateFlow<AuthState> get() = gateway.state

    suspend fun signInGoogle(): AuthResult = gateway.signInGoogle().thenSync()

    suspend fun signInEmail(email: String, password: String): AuthResult =
        gateway.signInEmail(email, password).thenSync()

    suspend fun linkGoogleToEmail(email: String, password: String): AuthResult =
        gateway.linkGoogleToEmail(email, password).thenSync()

    suspend fun signUpEmail(name: String, email: String, password: String): AuthResult {
        val r = gateway.signUpEmail(name, email, password)
        if (r is AuthResult.Ok) gateway.sendVerification() // falha no e-mail de verificação não falha o cadastro
        return r.thenSync()
    }

    suspend fun resetPassword(email: String): AuthResult = gateway.resetPassword(email)

    suspend fun sendVerification(): AuthResult = gateway.sendVerification()

    /** Só sai se não houver nada pendente de envio; limpa o aparelho e então encerra a sessão. */
    suspend fun signOut(): AuthResult = withContext(NonCancellable) {
        if (!engine.canSignOut()) return@withContext blocked()
        try {
            engine.signOutWipe()
        } catch (e: IllegalStateException) {
            return@withContext blocked() // uma edição chegou entre a checagem e a limpeza
        }
        gateway.signOut()
        AuthResult.Ok
    }

    /**
     * Ordem: 1) reautentica com a nuvem intacta (senha para quem entra com e-mail, Google para os
     * outros); se falhar, nada é tocado. 2) marca exclusão pendente e apaga os dados da nuvem.
     * 3) apaga o usuário; se falhar, marca pendente de novo para a próxima abertura concluir.
     * Os dados locais ficam, como de visitante. Depois da reautenticação a sequência não é cancelável.
     */
    suspend fun deleteAccount(password: String?): AuthResult {
        val reauth = if (gateway.providerIsPassword) {
            if (password.isNullOrEmpty()) {
                AuthResult.Error(AuthError.WRONG_CREDENTIALS, "Informe sua senha para apagar a conta.")
            } else {
                gateway.reauthenticate(password)
            }
        } else {
            gateway.reauthenticate(null)
        }
        if (reauth is AuthResult.Error) return reauth
        return withContext(NonCancellable) {
            try {
                engine.deleteAccountData()
            } catch (e: Exception) {
                return@withContext AuthResult.Error(
                    AuthError.NETWORK,
                    "Não foi possível apagar os dados da conta. Verifique a internet e tente de novo.",
                )
            }
            val r = gateway.deleteAuthUser()
            if (r is AuthResult.Error) {
                remarkPending()
                return@withContext AuthResult.Error(
                    r.kind,
                    "Seus dados foram apagados, mas não foi possível encerrar a conta agora. " +
                        "A exclusão será concluída automaticamente; tente de novo mais tarde.",
                )
            }
            AuthResult.Ok
        }
    }

    /**
     * Conclui uma exclusão interrompida (dados da nuvem de novo, que é idempotente, e o usuário).
     * Retorna true quando a exclusão terminou ou a sessão local foi encerrada para o usuário
     * terminá-la entrando de novo (login recente exigido). Qualquer outra falha mantém a marca e a
     * sessão e retorna false. Sem laço.
     */
    suspend fun resumeDeletionIfNeeded(): Boolean = withContext(NonCancellable) {
        try {
            if (!engine.isDeletionPending()) return@withContext false
            engine.deleteAccountData()
        } catch (e: Exception) {
            return@withContext false
        }
        val r = gateway.deleteAuthUser()
        if (r is AuthResult.Ok) return@withContext true
        remarkPending()
        if (r is AuthResult.Error && r.kind == AuthError.NEEDS_RECENT_LOGIN) {
            gateway.signOut()
            return@withContext true
        }
        false
    }

    private suspend fun remarkPending() {
        try {
            engine.markDeletionPending()
        } catch (e: Exception) {
            // sem rede: a marca já foi gravada por deleteAccountData e só é limpa ao final dele
        }
    }

    private suspend fun AuthResult.thenSync(): AuthResult {
        if (this !is AuthResult.Ok) return this
        try {
            withTimeoutOrNull(firstSyncTimeoutMs) { engine.firstSync() } // nulo = estourou: sincroniza depois
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // sem rede ou falha do servidor: continua logado, o agendador tenta de novo
        }
        return AuthResult.Ok
    }

    private fun blocked() = AuthResult.Error(
        AuthError.SIGN_OUT_BLOCKED,
        "Há alterações ainda não enviadas. Conecte-se à internet e sincronize antes de sair.",
    )

    companion object {
        const val FIRST_SYNC_TIMEOUT_MS = 15_000L
    }
}
