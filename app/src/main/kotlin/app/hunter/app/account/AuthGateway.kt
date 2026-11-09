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
    suspend fun reauthenticate(password: String?): AuthResult
    suspend fun deleteAuthUser(): AuthResult
    fun signOut()
    val state: StateFlow<AuthState>
}
