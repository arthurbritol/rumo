package app.hunter.app.account

import app.hunter.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private val flag: PendingDeletionFlag,
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
     * outros); se falhar, nada é tocado. 2) liga a marca LOCAL de exclusão pendente (síncrona, não
     * depende de rede), apaga os dados da nuvem e então o usuário; a marca local só é desligada
     * quando o usuário é apagado. Se algo falhar, a marca fica e [resumeDeletionIfNeeded] conclui no
     * próximo início. Os dados locais ficam, como de visitante. Depois da reautenticação a sequência
     * não é cancelável, mas cada chamada de rede tem limite de [NET_TIMEOUT_MS].
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
            flag.set()
            try {
                withTimeout(NET_TIMEOUT_MS) { engine.deleteAccountData() }
            } catch (e: Exception) {
                // Não dá para saber o que já foi apagado: a marca fica e a retomada refaz (idempotente).
                return@withContext incomplete(AuthError.NETWORK)
            }
            val r = deleteUserWithTimeout()
            if (r is AuthResult.Error) {
                // Melhor esforço: a marca na nuvem já foi apagada por deleteAllUserData; a local é a que vale.
                try {
                    withTimeout(NET_TIMEOUT_MS) { engine.markDeletionPending() }
                } catch (e: Exception) {
                    // ignorado de propósito
                }
                return@withContext incomplete(r.kind)
            }
            flag.clear()
            AuthResult.Ok
        }
    }

    /**
     * Conclui uma exclusão interrompida (marca local ou da nuvem): apaga os dados de novo
     * (idempotente) e o usuário. Sem usuário logado não faz nada (roda após o próximo login, antes
     * de qualquer sincronização). Retorna true quando terminou, ou quando a sessão local foi
     * encerrada para o usuário terminá-la entrando de novo (login recente exigido); em qualquer
     * outra falha mantém a marca e a sessão e retorna false. Sem laço.
     */
    suspend fun resumeDeletionIfNeeded(): Boolean = withContext(NonCancellable) {
        if (gateway.state.value is AuthState.Guest) return@withContext false
        val pending = flag.isSet() || try {
            withTimeout(NET_TIMEOUT_MS) { engine.isDeletionPending() }
        } catch (e: Exception) {
            false
        }
        if (!pending) return@withContext false
        flag.set()
        try {
            withTimeout(NET_TIMEOUT_MS) { engine.deleteAccountData() }
        } catch (e: Exception) {
            return@withContext false
        }
        val r = deleteUserWithTimeout()
        if (r is AuthResult.Ok) {
            flag.clear()
            return@withContext true
        }
        if (r is AuthResult.Error && r.kind == AuthError.NEEDS_RECENT_LOGIN) {
            gateway.signOut()
            return@withContext true
        }
        false
    }

    private suspend fun deleteUserWithTimeout(): AuthResult =
        try {
            withTimeout(NET_TIMEOUT_MS) { gateway.deleteAuthUser() }
        } catch (e: Exception) {
            AuthResult.Error(AuthError.NETWORK, "Sem resposta da rede.")
        }

    private fun incomplete(kind: AuthError) = AuthResult.Error(
        kind,
        "Não foi possível concluir a exclusão agora. Ela será concluída automaticamente; " +
            "mantenha o app aberto com internet e tente de novo mais tarde.",
    )

    private suspend fun AuthResult.thenSync(): AuthResult {
        if (this !is AuthResult.Ok) return this
        if (flag.isSet()) {
            // Exclusão pendente neste aparelho: conclui antes de qualquer sincronização, nada sobe.
            resumeDeletionIfNeeded()
            return AuthResult.Error(
                AuthError.OTHER,
                "Havia uma exclusão de conta pendente neste aparelho. Ela foi concluída ou será concluída " +
                    "automaticamente; entre de novo depois.",
            )
        }
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
        const val NET_TIMEOUT_MS = 30_000L
    }
}
