package app.hunter.app.account

import app.hunter.sync.SyncEngine
import kotlinx.coroutines.CancellationException
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
    suspend fun signOut(): AuthResult {
        if (!engine.canSignOut()) return blocked()
        try {
            engine.signOutWipe()
        } catch (e: IllegalStateException) {
            return blocked() // uma edição chegou entre a checagem e a limpeza
        }
        gateway.signOut()
        return AuthResult.Ok
    }

    /**
     * Ordem: marca exclusão pendente e apaga os dados da nuvem, depois apaga o usuário (pedindo
     * reautenticação uma vez se o Firebase exigir). Os dados locais ficam, como de visitante.
     */
    suspend fun deleteAccount(password: String?): AuthResult {
        try {
            engine.deleteAccountData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AuthResult.Error(AuthError.NETWORK, "Não foi possível apagar os dados da conta. Verifique a internet e tente de novo.")
        }
        var r = gateway.deleteAuthUser()
        if (r is AuthResult.Error && r.kind == AuthError.NEEDS_RECENT_LOGIN) {
            val re = gateway.reauthenticate(password)
            if (re is AuthResult.Error) return re
            r = gateway.deleteAuthUser()
        }
        return r
    }

    /**
     * Conclui uma exclusão interrompida (dados da nuvem e usuário). Retorna true se havia uma.
     * Se o Firebase exigir login recente, encerra a sessão local e segue como visitante, sem laço.
     */
    suspend fun resumeDeletionIfNeeded(): Boolean {
        val resumed = try {
            engine.resumePendingDeletion()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        if (!resumed) return false
        val r = gateway.deleteAuthUser()
        if (r is AuthResult.Error) gateway.signOut()
        return true
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
