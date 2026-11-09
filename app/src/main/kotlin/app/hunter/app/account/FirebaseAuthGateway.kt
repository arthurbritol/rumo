package app.hunter.app.account

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * [AuthGateway] sobre o Firebase Auth e o Credential Manager.
 *
 * Sem google-services.json o Firebase não é inicializado: o estado fica Guest e toda operação
 * devolve [AuthError.UNAVAILABLE], sem exceção. O login Google precisa de uma Activity para a
 * folha de contas: a tela define [activity] ao ficar visível e a limpa ao sair.
 */
class FirebaseAuthGateway(private val appContext: Context) : AuthGateway {
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    /** Referência fraca: a tela define ao ficar visível; não segura a Activity depois de destruída. */
    var activity: Activity?
        get() = activityRef?.get()
        set(value) { activityRef = value?.let { WeakReference(it) } }

    override val providerIsPassword: Boolean
        get() = auth?.currentUser?.providerData?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true

    private val auth: FirebaseAuth? = try {
        FirebaseAuth.getInstance()
    } catch (e: IllegalStateException) {
        null
    }

    private val _state = MutableStateFlow(auth?.currentUser.toState())
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Credencial Google que colidiu com uma conta de e-mail/senha, à espera de [linkGoogleToEmail]. */
    @Volatile
    private var pendingGoogle: AuthCredential? = null

    init {
        auth?.addAuthStateListener { _state.value = it.currentUser.toState() }
    }

    /** Abre a folha de contas Google; devolve a credencial escolhida ou o erro (cancelado, sem conta...). */
    private suspend fun pickGoogle(): Pair<GoogleIdTokenCredential?, AuthResult.Error?> {
        val act = activity ?: return null to AuthResult.Error(AuthError.UNAVAILABLE, "Abra o login pela tela do app.")
        val id = appContext.resources.getIdentifier("default_web_client_id", "string", appContext.packageName)
        if (id == 0) return null to unavailable()
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(appContext.getString(id))
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val cred = CredentialManager.create(act).getCredential(act, request).credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                null to AuthResult.Error(AuthError.OTHER, "Não foi possível entrar com o Google.")
            } else {
                GoogleIdTokenCredential.createFrom(cred.data) to null
            }
        } catch (e: GetCredentialCancellationException) {
            null to AuthResult.Error(AuthError.CANCELLED, "Login cancelado.")
        } catch (e: NoCredentialException) {
            null to AuthResult.Error(AuthError.OTHER, "Nenhuma conta Google disponível neste aparelho.")
        } catch (e: GetCredentialException) {
            null to AuthResult.Error(AuthError.OTHER, "Não foi possível entrar com o Google.")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null to AuthResult.Error(AuthError.OTHER, "Não foi possível entrar com o Google.")
        }
    }

    override suspend fun signInGoogle(): AuthResult {
        val a = auth ?: return unavailable()
        val (google, error) = pickGoogle()
        if (google == null) return error ?: AuthResult.Error(AuthError.OTHER, "Não foi possível entrar com o Google.")
        val credential = GoogleAuthProvider.getCredential(google.idToken, null)
        return try {
            a.signInWithCredential(credential).await()
            pendingGoogle = null
            _state.value = a.currentUser.toState() // não espera o listener: o repositório lê o uid já
            AuthResult.Ok
        } catch (e: FirebaseAuthUserCollisionException) {
            pendingGoogle = credential
            AuthResult.Error(
                AuthError.EMAIL_IN_USE,
                "Já existe uma conta com este e-mail. Informe a senha dela para vincular o Google.",
                email = e.email ?: google.id,
            )
        } catch (e: Exception) {
            map(e)
        }
    }

    override suspend fun linkGoogleToEmail(email: String, password: String): AuthResult {
        val a = auth ?: return unavailable()
        val google = pendingGoogle ?: return AuthResult.Error(AuthError.OTHER, "Entre com o Google de novo.")
        return guarded {
            val user = a.signInWithEmailAndPassword(email.trim(), password).await().user
                ?: return@guarded AuthResult.Error(AuthError.OTHER, "Não foi possível entrar.")
            user.linkWithCredential(google).await()
            pendingGoogle = null
            _state.value = a.currentUser.toState()
            AuthResult.Ok
        }
    }

    override suspend fun signUpEmail(name: String, email: String, password: String): AuthResult {
        val a = auth ?: return unavailable()
        return guarded {
            val user = a.createUserWithEmailAndPassword(email.trim(), password).await().user
                ?: return@guarded AuthResult.Error(AuthError.OTHER, "Não foi possível criar a conta.")
            // Conta criada: falha ao gravar o nome ou ao enviar a verificação não falha o cadastro.
            try {
                user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // o nome cai no prefixo do e-mail; o usuário pode editar depois
            }
            _state.value = a.currentUser.toState()
            AuthResult.Ok
        }
    }

    override suspend fun signInEmail(email: String, password: String): AuthResult {
        val a = auth ?: return unavailable()
        return guarded {
            a.signInWithEmailAndPassword(email.trim(), password).await()
            _state.value = a.currentUser.toState()
            AuthResult.Ok
        }
    }

    override suspend fun resetPassword(email: String): AuthResult {
        val a = auth ?: return unavailable()
        return guarded { a.sendPasswordResetEmail(email.trim()).await(); AuthResult.Ok }
    }

    override suspend fun sendVerification(): AuthResult {
        val user = auth?.currentUser ?: return unavailable()
        return guarded { user.sendEmailVerification().await(); AuthResult.Ok }
    }

    /**
     * Com [password] reautentica por e-mail/senha; sem ele, pela folha de contas Google. Em ambos os casos é
     * `currentUser.reauthenticate`, que falha (ERROR_USER_MISMATCH) se a credencial for de OUTRA conta: nunca
     * troca a conta logada (um signInWithCredential aqui poderia levar a apagar a conta errada).
     */
    override suspend fun reauthenticate(password: String?): AuthResult {
        val user = auth?.currentUser ?: return unavailable()
        val credential = if (password != null) {
            val email = user.email ?: return AuthResult.Error(AuthError.OTHER, "A conta não tem e-mail.")
            EmailAuthProvider.getCredential(email, password)
        } else {
            val (google, error) = pickGoogle()
            if (google == null) return error ?: AuthResult.Error(AuthError.OTHER, "Não foi possível confirmar com o Google.")
            GoogleAuthProvider.getCredential(google.idToken, null)
        }
        return try {
            user.reauthenticate(credential).await()
            AuthResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthInvalidUserException) {
            if (e.errorCode == "ERROR_USER_MISMATCH") {
                AuthResult.Error(AuthError.WRONG_CREDENTIALS, "Escolha a mesma conta Google com que você entrou. Nada foi apagado.")
            } else {
                map(e)
            }
        } catch (e: Exception) {
            map(e)
        }
    }

    override suspend fun deleteAuthUser(expectedUid: String): AuthResult {
        val user = auth?.currentUser ?: return unavailable()
        if (user.uid != expectedUid) return AuthResult.Error(AuthError.OTHER, "A conta mudou. Nada foi apagado.")
        return try {
            user.delete().await()
            AuthResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthInvalidUserException) {
            // Usuário inexistente/desativado no servidor: a conta já foi apagada (em outro aparelho).
            AuthResult.Error(AuthError.USER_GONE, "A conta já não existe.")
        } catch (e: Exception) {
            map(e)
        }
    }

    override fun signOut() {
        pendingGoogle = null
        auth?.signOut()
    }

    private suspend fun guarded(block: suspend () -> AuthResult): AuthResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            map(e)
        }

    private fun unavailable() = AuthResult.Error(
        AuthError.UNAVAILABLE,
        "A conta ainda não está disponível neste aparelho.",
    )

    private fun map(e: Exception): AuthResult.Error = when (e) {
        is FirebaseNetworkException -> AuthResult.Error(AuthError.NETWORK, "Sem conexão com a internet. Tente de novo.")
        is FirebaseAuthWeakPasswordException -> AuthResult.Error(AuthError.WEAK_PASSWORD, "A senha é fraca. Use ao menos 6 caracteres.")
        is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException ->
            AuthResult.Error(AuthError.WRONG_CREDENTIALS, "E-mail ou senha incorretos.")
        is FirebaseAuthUserCollisionException -> AuthResult.Error(AuthError.EMAIL_IN_USE, "Já existe uma conta com este e-mail.", email = e.email)
        is FirebaseAuthRecentLoginRequiredException ->
            AuthResult.Error(AuthError.NEEDS_RECENT_LOGIN, "Por segurança, confirme sua identidade de novo.")
        else -> AuthResult.Error(AuthError.OTHER, "Não foi possível concluir. Tente de novo.")
    }
}

private fun FirebaseUser?.toState(): AuthState =
    if (this == null) AuthState.Guest
    else AuthState.SignedIn(
        uid = uid,
        name = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: "",
        email = email,
        verified = isEmailVerified,
    )
