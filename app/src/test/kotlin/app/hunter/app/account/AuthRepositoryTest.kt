package app.hunter.app.account

import app.hunter.sync.Kind
import app.hunter.sync.LocalStore
import app.hunter.sync.OdometerDoc
import app.hunter.sync.RemoteSnapshot
import app.hunter.sync.RemoteStore
import app.hunter.sync.SyncDoc
import app.hunter.sync.SyncEngine
import app.hunter.sync.UserProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Events { val list = mutableListOf<String>() }

private class FakeFlag : PendingDeletionFlag {
    var value: String? = null
    var failSet = false
    var onSet: (() -> Unit)? = null
    override fun uid() = value
    override fun set(uid: String): Boolean {
        if (failSet) return false
        onSet?.invoke()
        value = uid
        return true
    }
    override fun clear() { value = null }
}

private class FakeLocal : LocalStore {
    var dirtyDocs = emptyList<SyncDoc>()
    var cleared = false
    var onClear: (() -> Unit)? = null
    override suspend fun all() = emptyList<SyncDoc>()
    override suspend fun dirty() = dirtyDocs
    override suspend fun apply(docs: List<SyncDoc>) {}
    override suspend fun markClean(docs: List<SyncDoc>) {}
    override suspend fun odometer(): OdometerDoc? = null
    override suspend fun applyOdometer(o: OdometerDoc, dirty: Boolean) {}
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
    var cloudHasData = true
    var deleteError: Throwable? = null
    var markError: Throwable? = null
    var hangDelete = false
    override suspend fun fetchAll(): RemoteSnapshot {
        fetches++
        if (hang) awaitCancellation()
        fetchError?.let { throw it }
        return RemoteSnapshot(emptyList(), null, null)
    }
    override suspend fun push(docs: List<SyncDoc>) {}
    override suspend fun pushOdometer(o: OdometerDoc) {}
    override suspend fun pushProfile(p: UserProfile) {}
    override suspend fun markDeletionPending() { markError?.let { throw it }; pending = true; ev.list += "pendente" }
    override suspend fun deletionPending() = pending
    override suspend fun deleteAllUserData() {
        if (hangDelete) awaitCancellation()
        deleteError?.let { throw it }
        ev.list += "dados"; cloudHasData = false // a marca é permanente
    }
}

private class FakeAuthGateway(val ev: Events) : AuthGateway {
    override val state = MutableStateFlow<AuthState>(AuthState.SignedIn("u", "Ana", "a@b.c", true))
    var signInResult: AuthResult = AuthResult.Ok
    var googleResult: AuthResult = AuthResult.Ok
    var linkResult: AuthResult = AuthResult.Ok
    var verificationResult: AuthResult = AuthResult.Ok
    val deleteResults = ArrayDeque<AuthResult>()
    var reauthResult: AuthResult = AuthResult.Ok
    var signInUid = "u"
    var signedUpName: String? = null
    var verificationSent = 0
    val reauthCalls = mutableListOf<String?>()
    val linked = mutableListOf<Pair<String, String>>()
    var signOuts = 0
    var deleteCalls = 0
    override var providerIsPassword = false
    var signUpResult: AuthResult = AuthResult.Ok
    var onDelete: (suspend () -> Unit)? = null
    /** Simula o listener do Firebase ainda não ter rodado: o login dá Ok mas o estado segue Guest. */
    var stateLagsSignIn = false

    override suspend fun signInGoogle(): AuthResult {
        if (googleResult is AuthResult.Ok && !stateLagsSignIn) state.value = AuthState.SignedIn(signInUid, "Ana", "a@b.c", true)
        return googleResult
    }
    override suspend fun signUpEmail(name: String, email: String, password: String): AuthResult {
        signedUpName = name
        return signUpResult
    }
    override suspend fun signInEmail(email: String, password: String): AuthResult {
        if (signInResult is AuthResult.Ok && !stateLagsSignIn) state.value = AuthState.SignedIn(signInUid, "Ana", email, true)
        return signInResult
    }
    override suspend fun linkGoogleToEmail(email: String, password: String): AuthResult {
        linked += email to password
        return linkResult
    }
    override suspend fun resetPassword(email: String): AuthResult = AuthResult.Ok
    override suspend fun sendVerification(): AuthResult { verificationSent++; return verificationResult }
    var onReauth: (() -> Unit)? = null
    val deletedUids = mutableListOf<String>()
    override suspend fun reauthenticate(password: String?): AuthResult {
        reauthCalls += password
        onReauth?.invoke()
        return reauthResult
    }
    override suspend fun deleteAuthUser(expectedUid: String): AuthResult {
        deleteCalls++
        deletedUids += expectedUid
        onDelete?.invoke()
        ev.list += "usuario"
        return deleteResults.removeFirstOrNull() ?: AuthResult.Ok
    }
    override fun signOut() { signOuts++; state.value = AuthState.Guest; ev.list += "sair" }
}

class AuthRepositoryTest {
    private val ev = Events()
    private val local = FakeLocal()
    private val remote = FakeRemote(ev)
    private val gateway = FakeAuthGateway(ev)
    private val flag = FakeFlag()
    private val repo = AuthRepository(gateway, SyncEngine(local, remote) { 1L }, flag)
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

    private fun err(k: AuthError) = AuthResult.Error(k, "x")

    @Test
    fun `apagar conta - reautentica antes de tocar na nuvem e falha sem apagar nada`() = runTest {
        gateway.providerIsPassword = true
        gateway.reauthResult = err(AuthError.WRONG_CREDENTIALS)
        val r = repo.deleteAccount("errada")
        assertEquals(AuthError.WRONG_CREDENTIALS, (r as AuthResult.Error).kind)
        assertTrue(remote.cloudHasData)
        assertFalse(remote.pending)
        assertEquals(0, gateway.deleteCalls)
        assertTrue(ev.list.isEmpty())
    }

    @Test
    fun `apagar conta - reautenticacao que troca a conta logada nao apaga nada de nenhuma conta`() = runTest {
        gateway.onReauth = { gateway.state.value = AuthState.SignedIn("B", "Bia", "b@b.c", true) }
        val r = repo.deleteAccount(null)
        assertIs<AuthResult.Error>(r)
        assertTrue(remote.cloudHasData)
        assertFalse(remote.pending)
        assertEquals(0, gateway.deleteCalls)
        assertTrue(ev.list.isEmpty())
        assertNull(flag.value)
    }

    @Test
    fun `apagar conta - conta trocada depois da checagem e recusada pelo uid fixado`() = runTest {
        val gate = app.hunter.app.sync.SyncGate { (gateway.state.value as? AuthState.SignedIn)?.uid }
        val stores = mapOf("u" to FakeRemote(ev), "B" to FakeRemote(ev))
        val perUser = app.hunter.app.sync.PerUserRemoteStore(
            { (gateway.state.value as? AuthState.SignedIn)?.uid }, { gate.pinnedUid },
        ) { stores.getValue(it) }
        val r = AuthRepository(gateway, SyncEngine(local, perUser) { 1L }, flag, gate)
        flag.onSet = { gateway.state.value = AuthState.SignedIn("B", "Bia", "b@b.c", true) }
        assertIs<AuthResult.Error>(r.deleteAccount(null))
        assertTrue(stores.values.all { it.cloudHasData && !it.pending })
        assertEquals(0, gateway.deleteCalls)
    }

    @Test
    fun `apagar o usuario de auth leva o uid esperado`() = runTest {
        assertEquals(AuthResult.Ok, repo.deleteAccount(null))
        assertEquals(listOf("u"), gateway.deletedUids)
    }

    @Test
    fun `apagar conta - usuario de senha sem senha da WRONG_CREDENTIALS sem abrir o Google`() = runTest {
        gateway.providerIsPassword = true
        val r = repo.deleteAccount(null)
        assertEquals(AuthError.WRONG_CREDENTIALS, (r as AuthResult.Error).kind)
        assertTrue(gateway.reauthCalls.isEmpty())
        assertTrue(remote.cloudHasData)
    }

    @Test
    fun `apagar conta - usuario Google reautentica pelo Google e Google cancelado nao apaga nada`() = runTest {
        gateway.reauthResult = err(AuthError.CANCELLED)
        val r = repo.deleteAccount("ignorada")
        assertEquals(AuthError.CANCELLED, (r as AuthResult.Error).kind)
        assertEquals(listOf<String?>(null), gateway.reauthCalls)
        assertTrue(remote.cloudHasData)
        assertFalse(remote.pending)
    }

    @Test
    fun `apagar conta - ordem e reautenticar, marcar pendente, apagar dados, apagar o usuario`() = runTest {
        gateway.providerIsPassword = true
        assertEquals(AuthResult.Ok, repo.deleteAccount("senha"))
        assertEquals(listOf<String?>("senha"), gateway.reauthCalls)
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertFalse(remote.cloudHasData)
        assertTrue(remote.pending) // marca permanente: nenhum aparelho volta a enviar para este uid
        assertNull(flag.value)
    }

    @Test
    fun `apagar conta - falha ao apagar o usuario deixa nuvem vazia e marca pendente`() = runTest {
        gateway.deleteResults += err(AuthError.NETWORK)
        val r = repo.deleteAccount(null)
        assertEquals(AuthError.NETWORK, (r as AuthResult.Error).kind)
        assertTrue(r.message.contains("automaticamente"))
        assertFalse(remote.cloudHasData)
        assertTrue(remote.pending)
        assertEquals("u", flag.value)
        assertEquals(0, gateway.signOuts)
    }

    @Test
    fun `apagar conta - falha ao apagar o usuario - a bandeira local conclui no proximo inicio`() = runTest {
        gateway.deleteResults += err(AuthError.NETWORK)
        assertIs<AuthResult.Error>(repo.deleteAccount(null))
        assertTrue(remote.pending)
        assertFalse(remote.cloudHasData)
        assertEquals("u", flag.value)
        assertEquals(0, gateway.signOuts)

        assertTrue(repo.resumeDeletionIfNeeded())
        assertNull(flag.value)
        assertTrue(remote.pending)
        assertEquals(listOf("pendente", "dados", "usuario", "pendente", "dados", "usuario"), ev.list)
    }

    @Test
    fun `aparelho B ve a marca de uma conta ja apagada - para de sincronizar e sai localmente`() = runTest {
        remote.pending = true // apagada no aparelho A
        gateway.deleteResults += err(AuthError.USER_GONE)
        assertTrue(repo.resumeDeletionIfNeeded())
        assertNull(flag.value)
        assertEquals(1, gateway.signOuts)
        assertIs<AuthState.Guest>(gateway.state.value)
        assertTrue(remote.pending)
    }

    @Test
    fun `login numa conta com marca de exclusao conclui a exclusao em vez de sincronizar`() = runTest {
        remote.pending = true
        gateway.state.value = AuthState.Guest
        gateway.deleteResults += err(AuthError.USER_GONE)
        val r = repo.signInEmail("a@b.c", "123456")
        assertIs<AuthResult.Error>(r)
        assertTrue(remote.pending)
        assertEquals(1, gateway.signOuts)
        assertNull(flag.value)
    }

    @Test
    fun `apagar conta - sem resposta da rede volta com erro em 30 s, bandeira ligada e continua logado`() = runTest {
        remote.hangDelete = true
        val r = repo.deleteAccount(null)
        assertEquals(AuthError.NETWORK, (r as AuthResult.Error).kind)
        assertEquals(30_000L, currentTime)
        assertEquals("u", flag.value)
        assertEquals(0, gateway.deleteCalls)
        assertIs<AuthState.SignedIn>(gateway.state.value)

        remote.hangDelete = false
        gateway.onDelete = { awaitCancellation() }
        val t0 = currentTime
        assertIs<AuthResult.Error>(repo.deleteAccount(null))
        assertEquals(30_000L, currentTime - t0)
        assertEquals("u", flag.value)
        assertIs<AuthState.SignedIn>(gateway.state.value)
    }

    @Test
    fun `entrar com a bandeira ligada conclui a exclusao antes de sincronizar`() = runTest {
        flag.value = "u"
        gateway.state.value = AuthState.Guest
        val r = repo.signInEmail("a@b.c", "123456")
        assertIs<AuthResult.Error>(r)
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertEquals(0, remote.fetches)
        assertNull(flag.value)
    }

    @Test
    fun `apagar conta - falha ao apagar os dados devolve NETWORK sem chamar o gateway`() = runTest {
        remote.deleteError = IOException("sem rede")
        val r = repo.deleteAccount(null)
        assertEquals(AuthError.NETWORK, (r as AuthResult.Error).kind)
        assertEquals(0, gateway.deleteCalls)
        assertEquals("u", flag.value)
    }

    @Test
    fun `apagar conta - cancelar o chamador no meio nao interrompe a sequencia`() = runTest {
        val job = launch { repo.deleteAccount(null) }
        gateway.onDelete = { job.cancel(); yield() }
        job.join()
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertNull(flag.value)
    }

    @Test
    fun `retomar sem exclusao pendente nao faz nada`() = runTest {
        assertFalse(repo.resumeDeletionIfNeeded())
        assertEquals(0, gateway.deleteCalls)
    }

    @Test
    fun `retomar exclusao pendente apaga dados e usuario e segue como visitante`() = runTest {
        remote.pending = true
        flag.value = "u"
        assertTrue(repo.resumeDeletionIfNeeded())
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertTrue(remote.pending)
        assertNull(flag.value)
        assertEquals(0, gateway.signOuts)
    }

    @Test
    fun `retomar com falha de rede continua logado e mantem a marca`() = runTest {
        remote.pending = true
        gateway.deleteResults += err(AuthError.NETWORK)
        flag.value = "u"
        assertFalse(repo.resumeDeletionIfNeeded())
        assertEquals(0, gateway.signOuts)
        assertEquals("u", flag.value)
        assertIs<AuthState.SignedIn>(gateway.state.value)
        assertFalse(remote.cloudHasData)
    }

    @Test
    fun `retomar com login recente exigido sai localmente e mantem a marca`() = runTest {
        remote.pending = true
        gateway.deleteResults += err(AuthError.NEEDS_RECENT_LOGIN)
        flag.value = "u"
        assertTrue(repo.resumeDeletionIfNeeded())
        assertEquals(1, gateway.signOuts)
        assertEquals("u", flag.value)
    }

    @Test
    fun `cadastro com falha nao envia verificacao nem sincroniza`() = runTest {
        gateway.signUpResult = err(AuthError.WEAK_PASSWORD)
        val r = repo.signUpEmail("Ana", "a@b.c", "1")
        assertEquals(AuthError.WEAK_PASSWORD, (r as AuthResult.Error).kind)
        assertEquals(0, gateway.verificationSent)
        assertEquals(0, remote.fetches)
    }

    @Test
    fun `flag de outro uid e descartada e o login de B sincroniza normalmente`() = runTest {
        flag.value = "A"
        gateway.signInUid = "B"
        gateway.state.value = AuthState.Guest
        assertEquals(AuthResult.Ok, repo.signInEmail("b@b.c", "123456"))
        assertNull(flag.value)
        assertTrue(ev.list.isEmpty())
        assertEquals(0, gateway.deleteCalls)
        assertEquals(1, remote.fetches)
    }

    @Test
    fun `retomar com outro usuario logado descarta a flag e nao apaga nada`() = runTest {
        flag.value = "A"
        assertFalse(repo.resumeDeletionIfNeeded())
        assertNull(flag.value)
        assertTrue(ev.list.isEmpty())
        assertTrue(remote.cloudHasData)
    }

    @Test
    fun `sem usuario logado a flag fica intacta`() = runTest {
        flag.value = "A"
        gateway.state.value = AuthState.Guest
        assertFalse(repo.resumeDeletionIfNeeded())
        assertEquals("A", flag.value)
    }

    @Test
    fun `flag do uid A e login de A concluem a exclusao antes de sincronizar`() = runTest {
        flag.value = "A"
        gateway.signInUid = "A"
        gateway.state.value = AuthState.Guest
        assertIs<AuthResult.Error>(repo.signInEmail("a@b.c", "123456"))
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertEquals(0, remote.fetches)
        assertNull(flag.value)
    }

    @Test
    fun `login recente exigido mantem a flag de A e novo login como A conclui`() = runTest {
        gateway.state.value = AuthState.SignedIn("A", "Ana", "a@b.c", true)
        flag.value = "A"
        gateway.deleteResults += err(AuthError.NEEDS_RECENT_LOGIN)
        assertTrue(repo.resumeDeletionIfNeeded())
        assertEquals("A", flag.value)
        assertEquals(1, gateway.signOuts)
        assertIs<AuthState.Guest>(gateway.state.value)

        ev.list.clear()
        gateway.signInUid = "A"
        assertIs<AuthResult.Error>(repo.signInEmail("a@b.c", "123456"))
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertNull(flag.value)
        assertEquals(0, remote.fetches)
    }

    @Test
    fun `marca na nuvem da conta logada dispara a retomada sem flag`() = runTest {
        remote.pending = true
        assertTrue(repo.resumeDeletionIfNeeded())
        assertEquals(listOf("pendente", "dados", "usuario"), ev.list)
        assertNull(flag.value)
    }

    @Test
    fun `apagar conta guarda o uid logado na flag`() = runTest {
        gateway.deleteResults += err(AuthError.NETWORK)
        repo.deleteAccount(null)
        assertEquals("u", flag.value)
    }

    @Test
    fun `login Ok sem uid confirmado (listener atrasado) mantem a flag e nao apaga nem sincroniza`() = runTest {
        flag.value = "A"
        gateway.signInUid = "A"
        gateway.state.value = AuthState.Guest
        gateway.stateLagsSignIn = true
        val r = repo.signInEmail("a@b.c", "123456")
        assertIs<AuthResult.Error>(r)
        assertTrue(r.message.contains("confirmar o login"))
        assertEquals("A", flag.value)
        assertTrue(ev.list.isEmpty())
        assertEquals(0, gateway.deleteCalls)
        assertEquals(0, remote.fetches)
        assertTrue(remote.cloudHasData)

        assertIs<AuthResult.Error>(repo.signInGoogle())
        assertEquals("A", flag.value)
        assertEquals(0, remote.fetches)
        assertEquals(0, gateway.deleteCalls)
    }

    @Test
    fun `login Ok sem uid confirmado e sem flag nao sincroniza`() = runTest {
        gateway.state.value = AuthState.Guest
        gateway.stateLagsSignIn = true
        assertIs<AuthResult.Error>(repo.signInEmail("a@b.c", "123456"))
        assertEquals(0, remote.fetches)
        assertNull(flag.value)
    }

    @Test
    fun `apagar conta - falha ao gravar a flag nao toca em nada`() = runTest {
        flag.failSet = true
        val r = repo.deleteAccount(null)
        assertIs<AuthResult.Error>(r)
        assertTrue(remote.cloudHasData)
        assertFalse(remote.pending)
        assertEquals(0, gateway.deleteCalls)
        assertTrue(ev.list.isEmpty())
        assertNull(flag.value)
        assertIs<AuthState.SignedIn>(gateway.state.value)
    }

    @Test
    fun `Firebase indisponivel devolve UNAVAILABLE sem travar`() = runTest {
        gateway.signInResult = AuthResult.Error(AuthError.UNAVAILABLE, "indisponivel")
        gateway.googleResult = AuthResult.Error(AuthError.UNAVAILABLE, "indisponivel")
        assertEquals(AuthError.UNAVAILABLE, (repo.signInEmail("a@b.c", "1") as AuthResult.Error).kind)
        assertEquals(AuthError.UNAVAILABLE, (repo.signInGoogle() as AuthResult.Error).kind)
        assertEquals(0, remote.fetches)
    }

    private class SlowRemote(val ev: Events, val ms: Long) : RemoteStore {
        var active = 0
        var maxActive = 0
        override suspend fun fetchAll(): RemoteSnapshot {
            active++; maxActive = maxOf(maxActive, active)
            ev.list += "sync-inicio"
            try { kotlinx.coroutines.delay(ms) } finally { active-- }
            ev.list += "sync-fim"
            return RemoteSnapshot(emptyList(), null, null)
        }
        override suspend fun push(docs: List<SyncDoc>) {}
        override suspend fun pushOdometer(o: OdometerDoc) {}
        override suspend fun pushProfile(p: UserProfile) {}
        override suspend fun markDeletionPending() {}
        override suspend fun deletionPending() = false
        override suspend fun deleteAllUserData() {}
    }

    @Test
    fun signOutWaitsForARunningSyncAndNeverInterleaves() = runTest {
        val gate = app.hunter.app.sync.SyncGate()
        val slow = SlowRemote(ev, 5_000)
        val engine = SyncEngine(local, slow) { 1L }
        val r = AuthRepository(gateway, engine, flag, gate)
        val sched = app.hunter.app.sync.SyncScheduler(engine, backgroundScope, 0, 0, {}, gate) { true }
        sched.syncOnConnectivity()
        testScheduler.runCurrent()
        val out = async { r.signOut() }
        testScheduler.advanceTimeBy(4_000); testScheduler.runCurrent()
        assertFalse(out.isCompleted)
        assertEquals(listOf("sync-inicio"), ev.list)
        testScheduler.advanceTimeBy(1_500); testScheduler.runCurrent()
        assertIs<AuthResult.Ok>(out.await())
        assertEquals(listOf("sync-inicio", "sync-fim", "sair"), ev.list)
    }

    @Test
    fun firstSyncAndScheduledSyncNeverRunConcurrently() = runTest {
        val gate = app.hunter.app.sync.SyncGate()
        val slow = SlowRemote(ev, 1_000)
        val engine = SyncEngine(local, slow) { 1L }
        val r = AuthRepository(gateway, engine, flag, gate)
        val sched = app.hunter.app.sync.SyncScheduler(engine, backgroundScope, 0, 0, {}, gate) { true }
        sched.syncOnConnectivity()
        testScheduler.runCurrent()
        val login = async { r.signInEmail("a@b.c", "x") }
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertIs<AuthResult.Ok>(login.await())
        assertEquals(1, slow.maxActive)
        assertEquals(2, ev.list.count { it == "sync-fim" })
    }
}
