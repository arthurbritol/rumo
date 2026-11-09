package app.hunter.app.account

import app.hunter.sync.Kind
import app.hunter.sync.LocalStore
import app.hunter.sync.OdometerDoc
import app.hunter.sync.RemoteSnapshot
import app.hunter.sync.RemoteStore
import app.hunter.sync.SyncDoc
import app.hunter.sync.SyncEngine
import app.hunter.sync.UserProfile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class Events { val list = mutableListOf<String>() }

private class FakeLocal : LocalStore {
    var dirtyDocs = emptyList<SyncDoc>()
    var cleared = false
    var onClear: (() -> Unit)? = null
    override suspend fun all() = emptyList<SyncDoc>()
    override suspend fun dirty() = dirtyDocs
    override suspend fun apply(docs: List<SyncDoc>) {}
    override suspend fun markClean(docs: List<SyncDoc>) {}
    override suspend fun odometer(): OdometerDoc? = null
    override suspend fun applyOdometer(o: OdometerDoc) {}
    override suspend fun profile(): UserProfile? = null
    override suspend fun applyProfile(p: UserProfile) {}
    override suspend fun hasDirtyOdometerOrProfile() = false
    override suspend fun clearAll() { onClear?.invoke(); cleared = true }
}

private class FakeRemote(val ev: Events) : RemoteStore {
    var fetches = 0
    var fetchError: Throwable? = null
    var hang = false
    var pending = false
    override suspend fun fetchAll(): RemoteSnapshot {
        fetches++
        if (hang) awaitCancellation()
        fetchError?.let { throw it }
        return RemoteSnapshot(emptyList(), null, null)
    }
    override suspend fun push(docs: List<SyncDoc>) {}
    override suspend fun pushOdometer(o: OdometerDoc) {}
    override suspend fun pushProfile(p: UserProfile) {}
    override suspend fun markDeletionPending() { pending = true; ev.list += "pendente" }
    override suspend fun deletionPending() = pending
    override suspend fun deleteAllUserData() { ev.list += "dados"; pending = false }
}

private class FakeAuthGateway(val ev: Events) : AuthGateway {
    override val state = MutableStateFlow<AuthState>(AuthState.Guest)
    var signInResult: AuthResult = AuthResult.Ok
    var googleResult: AuthResult = AuthResult.Ok
    var linkResult: AuthResult = AuthResult.Ok
    var verificationResult: AuthResult = AuthResult.Ok
    val deleteResults = ArrayDeque<AuthResult>()
    var reauthResult: AuthResult = AuthResult.Ok
    var signedUpName: String? = null
    var verificationSent = 0
    val reauthCalls = mutableListOf<String?>()
    val linked = mutableListOf<Pair<String, String>>()
    var signOuts = 0
    var deleteCalls = 0

    override suspend fun signInGoogle() = googleResult
    override suspend fun signUpEmail(name: String, email: String, password: String): AuthResult {
        signedUpName = name
        return AuthResult.Ok
    }
    override suspend fun signInEmail(email: String, password: String) = signInResult
    override suspend fun linkGoogleToEmail(email: String, password: String): AuthResult {
        linked += email to password
        return linkResult
    }
    override suspend fun resetPassword(email: String): AuthResult = AuthResult.Ok
    override suspend fun sendVerification(): AuthResult { verificationSent++; return verificationResult }
    override suspend fun reauthenticate(password: String?): AuthResult { reauthCalls += password; return reauthResult }
    override suspend fun deleteAuthUser(): AuthResult {
        deleteCalls++
        ev.list += "usuario"
        return deleteResults.removeFirstOrNull() ?: AuthResult.Ok
    }
    override fun signOut() { signOuts++; ev.list += "sair" }
}

class AuthRepositoryTest {
    private val ev = Events()
    private val local = FakeLocal()
    private val remote = FakeRemote(ev)
    private val gateway = FakeAuthGateway(ev)
    private val repo = AuthRepository(gateway, SyncEngine(local, remote) { 1L })
    private val sujo = SyncDoc(Kind.FUELUP, "a", 1, false, emptyMap())

    @Test
    fun `login com sucesso dispara a primeira sincronizacao`() = runTest {
        assertEquals(AuthResult.Ok, repo.signInEmail("a@b.c", "123456"))
        assertEquals(1, remote.fetches)
    }

    @Test
    fun `login com erro nao sincroniza`() = runTest {
        gateway.signInResult = AuthResult.Error(AuthError.WRONG_CREDENTIALS, "x")
        assertIs<AuthResult.Error>(repo.signInEmail("a@b.c", "1"))
        assertEquals(0, remote.fetches)
    }

    @Test
    fun `primeiro login sem internet continua logado e nao trava (resultado em poucos segundos)`() = runTest {
        remote.hang = true
        assertEquals(AuthResult.Ok, repo.signInEmail("a@b.c", "123456"))
        assertTrue(currentTime <= 15_000, "demorou $currentTime ms")
        remote.hang = false
        remote.fetchError = IOException("sem rede")
        assertEquals(AuthResult.Ok, repo.signInEmail("a@b.c", "123456"))
        assertEquals(0, gateway.signOuts)
    }

    @Test
    fun `cadastro com e-mail envia verificacao e define o nome`() = runTest {
        assertEquals(AuthResult.Ok, repo.signUpEmail("Ana", "a@b.c", "123456"))
        assertEquals("Ana", gateway.signedUpName)
        assertEquals(1, gateway.verificationSent)
        // falha no e-mail de verificação não derruba o cadastro
        gateway.verificationResult = AuthResult.Error(AuthError.NETWORK, "x")
        assertEquals(AuthResult.Ok, repo.signUpEmail("Ana", "a@b.c", "123456"))
    }

    @Test
    fun `Google e senha com o mesmo e-mail pedem a senha uma vez e vinculam`() = runTest {
        gateway.googleResult = AuthResult.Error(AuthError.EMAIL_IN_USE, "ja existe", email = "a@b.c")
        val r = repo.signInGoogle()
        assertIs<AuthResult.Error>(r)
        assertEquals(AuthError.EMAIL_IN_USE, r.kind)
        assertEquals("a@b.c", r.email)
        assertEquals(0, remote.fetches)

        assertEquals(AuthResult.Ok, repo.linkGoogleToEmail("a@b.c", "segredo"))
        assertEquals(listOf("a@b.c" to "segredo"), gateway.linked)
        assertEquals(1, remote.fetches)
    }

    @Test
    fun `sair com pendencia e bloqueado e nao limpa`() = runTest {
        local.dirtyDocs = listOf(sujo)
        val r = repo.signOut()
        assertIs<AuthResult.Error>(r)
        assertEquals(AuthError.SIGN_OUT_BLOCKED, r.kind)
        assertFalse(local.cleared)
        assertEquals(0, gateway.signOuts)
    }

    @Test
    fun `sair limpa o aparelho e depois sai da conta, e corrida vira bloqueado`() = runTest {
        assertEquals(AuthResult.Ok, repo.signOut())
        assertTrue(local.cleared)
        assertEquals(1, gateway.signOuts)

        local.cleared = false
        local.onClear = { throw IllegalStateException("linha suja") }
        val r = repo.signOut()
        assertEquals(AuthError.SIGN_OUT_BLOCKED, (r as AuthResult.Error).kind)
        assertEquals(1, gateway.signOuts)
    }

    @Test
    fun `apagar conta pede reautenticacao quando o Firebase exige e continua depois`() = runTest {
        gateway.deleteResults += AuthResult.Error(AuthError.NEEDS_RECENT_LOGIN, "x")
        assertEquals(AuthResult.Ok, repo.deleteAccount("senha"))
        assertEquals(listOf<String?>("senha"), gateway.reauthCalls)
        assertEquals(2, gateway.deleteCalls)

        // reautenticação falha: devolve o erro dela
        gateway.deleteResults += AuthResult.Error(AuthError.NEEDS_RECENT_LOGIN, "x")
        gateway.reauthResult = AuthResult.Error(AuthError.WRONG_CREDENTIALS, "senha errada")
        val r = repo.deleteAccount("errada")
        assertEquals(AuthError.WRONG_CREDENTIALS, (r as AuthResult.Error).kind)
    }

    @Test
    fun `apagar conta - ordem e marcar pendente, apagar dados, apagar o usuario`() = runTest {
        assertEquals(AuthResult.Ok, repo.deleteAccount(null))
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertTrue(gateway.reauthCalls.isEmpty())
    }

    @Test
    fun `retomar exclusao pendente apaga o usuario e segue como visitante`() = runTest {
        assertFalse(repo.resumeDeletionIfNeeded())
        assertEquals(0, gateway.deleteCalls)

        remote.pending = true
        assertTrue(repo.resumeDeletionIfNeeded())
        assertEquals(listOf("dados", "usuario"), ev.list)

        remote.pending = true
        gateway.deleteResults += AuthResult.Error(AuthError.NEEDS_RECENT_LOGIN, "x")
        assertTrue(repo.resumeDeletionIfNeeded())
        assertEquals(1, gateway.signOuts)
    }

    @Test
    fun `Firebase indisponivel devolve UNAVAILABLE sem travar`() = runTest {
        gateway.signInResult = AuthResult.Error(AuthError.UNAVAILABLE, "indisponivel")
        gateway.googleResult = AuthResult.Error(AuthError.UNAVAILABLE, "indisponivel")
        assertEquals(AuthError.UNAVAILABLE, (repo.signInEmail("a@b.c", "1") as AuthResult.Error).kind)
        assertEquals(AuthError.UNAVAILABLE, (repo.signInGoogle() as AuthResult.Error).kind)
        assertEquals(0, remote.fetches)
    }
}
