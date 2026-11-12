package app.hunter.app.account

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    object Guest : AuthState
    data class SignedIn(val uid: String, val name: String, val email: String?, val verified: Boolean) : AuthState
}

sealed interface AuthResult {
    object Ok : AuthResult

    /** [email] vem preenchido em [AuthError.EMAIL_IN_USE] do Google: a tela pede a senha dele uma vez e chama linkGoogleToEmail. */
    data class Error(val kind: AuthError, val message: String, val email: String? = null) : AuthResult
}

enum class AuthError {
    NETWORK, WRONG_CREDENTIALS, EMAIL_IN_USE, WEAK_PASSWORD, NEEDS_RECENT_LOGIN, UNAVAILABLE, CANCELLED, OTHER,
    SIGN_OUT_BLOCKED,

    /** O usuário de auth já não existe no servidor (conta apagada em outro aparelho). */
    USER_GONE,
}

/** Fronteira com o Firebase Auth; o repositório só conhece esta interface (testável sem Android). */
interface AuthGateway {
    suspend fun signInGoogle(): AuthResult
    suspend fun signUpEmail(name: String, email: String, password: String): AuthResult
    suspend fun signInEmail(email: String, password: String): AuthResult

    /** Entra com o e-mail/senha existente e vincula a credencial Google pendente (após EMAIL_IN_USE do Google). */
    suspend fun linkGoogleToEmail(email: String, password: String): AuthResult
    suspend fun resetPassword(email: String): AuthResult
    suspend fun sendVerification(): AuthResult
    /**
     * Reautentica o usuário JÁ logado (nunca troca de conta): com [password] por e-mail/senha; sem ele pela
     * folha de contas Google. Uma conta Google diferente da logada é recusada com erro.
     */
    suspend fun reauthenticate(password: String?): AuthResult

    /**
     * Apaga o usuário de auth logado, só se o uid dele é [expectedUid]; senão devolve erro sem apagar.
     * [AuthError.USER_GONE] quando o usuário já não existe no servidor (apagado em outro aparelho).
     */
    suspend fun deleteAuthUser(expectedUid: String): AuthResult
    fun signOut()

    /** ID token do Firebase do usuário logado (vale 1 h); null se não há usuário ou a obtenção falhou. */
    suspend fun idToken(forceRefresh: Boolean = false): String?

    /** Verdadeiro quando o usuário atual entra com e-mail/senha (reautenticação pede a senha, não o Google). */
    val providerIsPassword: Boolean
    val state: StateFlow<AuthState>
}
