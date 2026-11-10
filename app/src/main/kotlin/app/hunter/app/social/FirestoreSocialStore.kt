package app.hunter.app.social

import app.hunter.app.sync.DELETE_PAGE_SIZE
import app.hunter.app.sync.await
import app.hunter.social.Activity
import app.hunter.social.Friend
import app.hunter.social.Invite
import app.hunter.social.Member
import app.hunter.social.PublicProfile
import app.hunter.social.Role
import app.hunter.social.SearchHit
import app.hunter.social.SearchKey
import app.hunter.social.SocialStore
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * [SocialStore] sobre o Firestore, nos caminhos e formatos de firebase/firestore.rules. Toda leitura pontual usa
 * [Source.SERVER]; as operações marcadas "lote" no [SocialStore] são um único `WriteBatch` (atômico).
 *
 * Detalhes impostos pelas regras:
 * - Apagar um documento INEXISTENTE de `convites` ou `amizades` é recusado (a regra lê `resource.data`, nulo);
 *   por isso essas exclusões consultam antes (por consulta, pois um `get` de documento inexistente também é recusado).
 * - Todo `list` precisa de uma consulta que as regras provem: `convites` por `de` ou `para`, `amizades` por `a` ou
 *   `b`, `roles` por `participantes array-contains`, `busca` com `limit` <= 20.
 * - O [FirebaseFirestore] vem de fora e só é tocado quando uma operação roda (o app compila e roda sem
 *   google-services.json; este objeto só deve ser criado quando o Firebase existe).
 */
class FirestoreSocialStore(
    private val db: FirebaseFirestore,
    private val clock: () -> Long = System::currentTimeMillis,
) : SocialStore {

    private fun ref(path: String): DocumentReference = db.document(path)

    private suspend fun Query.fetch(): QuerySnapshot = get(Source.SERVER).await()

    private fun DocumentSnapshot.map(): Map<String, Any?> =
        data.orEmpty().mapValues { (_, v) -> if (v is Timestamp) v.toDate().time else v }

    // ---------------------------------------------------------------- perfil, atividade, busca

    /** Lote: grava `perfisPublicos/{uid}` e o índice `busca/{uid}` (apagado se não pesquisável ou sem chave). */
    override suspend fun publishProfile(p: PublicProfile) {
        val batch = db.batch()
        batch.set(ref("perfisPublicos/${p.uid}"), publicProfileToMap(p))
        val busca = if (p.searchable) buscaToMap(p) else null
        if (busca != null) batch.set(ref("busca/${p.uid}"), busca) else batch.delete(ref("busca/${p.uid}"))
        batch.commit().await()
    }

    override suspend fun publishActivity(uid: String, a: Activity) {
        ref("atividade/$uid").set(activityToMap(a)).await()
    }

    /**
     * Consulta `busca` ordenada por `displayNameLower` com `startAt(prefixo)`/`endAt(prefixo + U+F8FF)`. As regras
     * só aceitam `limit` <= 20: pedidos maiores viram páginas sequenciais de 20 (`startAfter` do último
     * documento), até [MAX_SEARCH_TOTAL]. Isso cobre a margem que o engine pede para filtrar amigos e bloqueados.
     */
    override suspend fun search(prefixLower: String, limit: Int): List<SearchHit> {
        val prefix = SearchKey.normalize(prefixLower)
        val hits = ArrayList<SearchHit>()
        var last: DocumentSnapshot? = null
        for (size in searchPages(limit)) {
            val base = db.collection("busca").orderBy("displayNameLower")
            val q = (if (last == null) base.startAt(prefix) else base.startAfter(last))
                .endAt(prefix + "").limit(size.toLong())
            val docs = q.fetch().documents
            docs.mapNotNullTo(hits) { mapToSearchHit(it.id, it.map()) }
            if (docs.size < size) break
            last = docs.last()
        }
        return hits
    }

    // ---------------------------------------------------------------- convites

    override suspend fun sendInvite(from: String, to: String) {
        ref("convites/${inviteId(from, to)}").set(inviteToMap(from, to, clock())).await()
    }

    /** Quem convidou apaga o convite (se ainda existir). */
    override suspend fun cancelInvite(from: String, to: String) {
        deleteAll(sentInvitesQuery(from).fetch().documents.filter { it.getString("para") == to }.map { it.reference })
    }

    /** O destinatário recusa (apaga) o convite de [from] (se ainda existir). */
    override suspend fun declineInvite(from: String, to: String) {
        deleteAll(receivedInvitesQuery(to).fetch().documents.filter { it.getString("de") == from }.map { it.reference })
    }

    /** Lote: cria `amizades/{par}` e apaga `convites/{from_to}`; as regras conferem com `existsAfter`. */
    override suspend fun acceptInvite(from: String, to: String) {
        val batch = db.batch()
        batch.set(ref("amizades/${pairId(from, to)}"), friendshipToMap(from, to, clock()))
        batch.delete(ref("convites/${inviteId(from, to)}"))
        batch.commit().await()
    }

    override suspend fun invitesReceived(uid: String): List<Invite> =
        receivedInvitesQuery(uid).fetch().documents.mapNotNull { mapToInvite(it.map()) }

    override suspend fun invitesSent(uid: String): List<Invite> =
        sentInvitesQuery(uid).fetch().documents.mapNotNull { mapToInvite(it.map()) }

    private fun receivedInvitesQuery(uid: String): Query = db.collection("convites").whereEqualTo("para", uid)
    private fun sentInvitesQuery(uid: String): Query = db.collection("convites").whereEqualTo("de", uid)

    // ---------------------------------------------------------------- amizades

    private fun friendsQueries(uid: String): List<Query> = listOf(
        db.collection("amizades").whereEqualTo("a", uid),
        db.collection("amizades").whereEqualTo("b", uid),
    )

    private fun otherOf(uid: String, d: DocumentSnapshot): String? {
        val a = d.getString("a")
        val b = d.getString("b")
        return if (a == uid) b else a
    }

    /** As duas consultas (`a == uid` e `b == uid`), devolvendo o outro uid do par. */
    override suspend fun friendUids(uid: String): Set<String> =
        friendsQueries(uid).flatMap { q -> q.fetch().documents.mapNotNull { otherOf(uid, it) } }.toSet()

    /** Perfil público e atividade de cada amigo, lidos em paralelo (no máximo [FRIEND_READ_PARALLELISM] de cada vez). */
    override suspend fun friends(uid: String): List<Friend> = loadFriends(friendUids(uid))

    private suspend fun loadFriends(uids: Set<String>): List<Friend> = coroutineScope {
        val gate = Semaphore(FRIEND_READ_PARALLELISM)
        uids.sorted().map { f ->
            async {
                gate.withPermit {
                    val profile = ref("perfisPublicos/$f").get(Source.SERVER).await().let { d ->
                        d.takeIf { it.exists() }?.let { mapToPublicProfile(f, it.map()) }
                    } ?: PublicProfile(f, "Amigo", null, null, null, false)
                    val activity = ref("atividade/$f").get(Source.SERVER).await().let { d ->
                        d.takeIf { it.exists() }?.let { mapToActivity(it.map()) }
                    }
                    Friend(profile, activity)
                }
            }
        }.awaitAll()
    }

    /** Apaga a amizade (se existir): o id do par decide em qual das duas consultas ela aparece. */
    override suspend fun unfriend(uid: String, other: String) {
        deleteAll(friendshipRefs(uid, other))
    }

    private suspend fun friendshipRefs(uid: String, other: String): List<DocumentReference> {
        val pair = pairId(uid, other)
        val q = db.collection("amizades").whereEqualTo(if (uid < other) "a" else "b", uid)
        return q.fetch().documents.filter { it.id == pair }.map { it.reference }
    }

    // ---------------------------------------------------------------- bloqueios

    /**
     * Lote: grava `bloqueios/{uid_other}` e apaga a amizade e os convites entre os dois que existirem (só os
     * existentes: as regras recusam apagar `convites`/`amizades` inexistentes).
     */
    override suspend fun block(uid: String, other: String) {
        val doomed = friendshipRefs(uid, other) +
            sentInvitesQuery(uid).fetch().documents.filter { it.getString("para") == other }.map { it.reference } +
            receivedInvitesQuery(uid).fetch().documents.filter { it.getString("de") == other }.map { it.reference }
        val batch = db.batch()
        batch.set(ref("bloqueios/${blockId(uid, other)}"), blockToMap(clock()))
        doomed.forEach { batch.delete(it) }
        batch.commit().await()
    }

    override suspend fun unblock(uid: String, other: String) {
        ref("bloqueios/${blockId(uid, other)}").delete().await()
    }

    /**
     * Consulta dos bloqueios de [uid]: faixa do id do documento (`uid_` até `uid_` + U+F8FF).
     *
     * ATENÇÃO: medido no emulador, as regras ATUAIS recusam esta consulta (em `list` o `id` é nulo, então
     * `idComecaCom` falha). Ver o relatório da Task 4: é preciso um campo `quem` e uma regra de `list` por ele.
     * Quando as regras mudarem, só esta função muda.
     */
    private fun blockedQuery(uid: String): Query = db.collection("bloqueios")
        .whereGreaterThanOrEqualTo(FieldPath.documentId(), "${uid}_")
        .whereLessThanOrEqualTo(FieldPath.documentId(), "${uid}_")

    override suspend fun blocked(uid: String): Set<String> =
        blockedQuery(uid).fetch().documents.map { it.id.removePrefix("${uid}_") }.toSet()

    // ---------------------------------------------------------------- observação em tempo real

    /** Flow do snapshot da consulta; o ouvinte sai ao cancelar e erros encerram o flow com a exceção. */
    private fun Query.snapshots(): Flow<QuerySnapshot> = callbackFlow {
        val reg = addSnapshotListener { snap, err ->
            if (err != null) close(err) else if (snap != null) trySend(snap)
        }
        awaitClose { reg.remove() }
    }

    override fun observeInvitesReceived(uid: String): Flow<List<Invite>> =
        receivedInvitesQuery(uid).snapshots().map { s -> s.documents.mapNotNull { mapToInvite(it.map()) } }

    /** Qualquer mudança nas amizades recarrega perfis e atividades (os ouvintes só avisam; a leitura é pontual). */
    override fun observeFriends(uid: String): Flow<List<Friend>> =
        merge(*friendsQueries(uid).map { it.snapshots() }.toTypedArray())
            .conflate()
            .map { friends(uid) }

    override fun observeRoles(uid: String): Flow<List<Role>> =
        rolesQuery(uid).snapshots().map { s -> s.documents.mapNotNull { mapToRole(it.id, it.map()) } }

    override fun observeMembers(roleId: String): Flow<List<Member>> =
        db.collection("roles/$roleId/membros").snapshots()
            .map { s -> s.documents.mapNotNull { mapToMember(it.id, it.map()) } }

    // ---------------------------------------------------------------- rolês

    private fun rolesQuery(uid: String): Query = db.collection("roles").whereArrayContains("participantes", uid)

    /** Lote único: rolê + membro do criador (aceito) + membros convidados (só estado, nome e atualizadoEm). */
    override suspend fun createRole(role: Role, creator: Member, invited: List<Member>) {
        val now = clock()
        val batch = db.batch()
        // expiraEm vai como Timestamp (e não número) para a política de TTL do Firestore apagar o rolê vencido.
        val roleMap = roleToMap(role, now) + ("expiraEm" to Timestamp(java.util.Date(role.expiresAtMs)))
        batch.set(ref("roles/${role.id}"), roleMap)
        batch.set(ref("roles/${role.id}/membros/${creator.uid}"), memberToMap(creator, now))
        invited.forEach { batch.set(ref("roles/${role.id}/membros/${it.uid}"), memberToMap(it, now)) }
        batch.commit().await()
    }

    override suspend fun acceptRole(roleId: String, uid: String) {
        ref("roles/$roleId/membros/$uid").update(acceptMemberUpdate(clock())).await()
    }

    /**
     * Lote: apaga o próprio membro e tira o uid de `participantes` (`arrayRemove` é o que a regra `saidaPropria`
     * espera: só `participantes` muda e perde só o próprio uid). Verificado no emulador.
     */
    override suspend fun leaveRole(roleId: String, uid: String) {
        val batch = db.batch()
        batch.delete(ref("roles/$roleId/membros/$uid"))
        batch.update(ref("roles/$roleId"), "participantes", FieldValue.arrayRemove(uid))
        batch.commit().await()
    }

    /**
     * Lote (criador): apaga todos os membros e depois o rolê. Os ids dos membros vêm de `participantes` (apagar
     * documento de membro não exige que ele exista) somados à listagem de `membros`, que é opcional.
     */
    override suspend fun closeRole(roleId: String) {
        val role = ref("roles/$roleId").get(Source.SERVER).await()
        if (!role.exists()) return
        closeRoleDoc(role)
    }

    private suspend fun closeRoleDoc(role: DocumentSnapshot) {
        val ids = LinkedHashSet<String>()
        (role.get("participantes") as? List<*>)?.filterIsInstance<String>()?.let(ids::addAll)
        runCatching { role.reference.collection("membros").fetch().documents.forEach { ids += it.id } }
        val batch = db.batch()
        ids.forEach { batch.delete(role.reference.collection("membros").document(it)) }
        batch.delete(role.reference)
        batch.commit().await()
    }

    override suspend fun setMemberFigures(roleId: String, uid: String, budget: Double?, costPerKm: Double?) {
        ref("roles/$roleId/membros/$uid").update(figuresUpdate(budget, costPerKm, clock())).await()
    }

    override suspend fun roles(uid: String): List<Role> =
        rolesQuery(uid).fetch().documents.mapNotNull { mapToRole(it.id, it.map()) }

    override suspend fun members(roleId: String): List<Member> =
        db.collection("roles/$roleId/membros").fetch().documents.mapNotNull { mapToMember(it.id, it.map()) }

    // ---------------------------------------------------------------- apagar a conta

    private suspend fun deleteAll(refs: List<DocumentReference>) {
        for (chunk in refs.chunked(DELETE_PAGE_SIZE)) {
            val batch = db.batch()
            chunk.forEach { batch.delete(it) }
            batch.commit().await()
        }
    }

    /** Apaga, em páginas de [DELETE_PAGE_SIZE], tudo que [q] devolve, até a consulta ficar vazia. */
    private suspend fun deleteQuery(q: Query) {
        while (true) {
            val page = q.limit(DELETE_PAGE_SIZE.toLong()).fetch().documents
            if (page.isEmpty()) return
            deleteAll(page.map { it.reference })
        }
    }

    /**
     * Apaga todo o social de [uid]. Idempotente e resumível: cada etapa só olha o que ainda existe e pode ser
     * repetida depois de uma queda. Ordem: 1) `busca` (some da pesquisa primeiro); 2) rolês (criados: membros e rolê
     * num lote; os demais: saída própria); 3) convites enviados e recebidos; 4) amizades; 5) bloqueios feitos;
     * 6) `perfisPublicos` e `atividade` (só os amigos os liam, e as amizades já se foram).
     */
    override suspend fun deleteAllSocial(uid: String) {
        ref("busca/$uid").delete().await()
        while (true) {
            val page = rolesQuery(uid).limit(DELETE_PAGE_SIZE.toLong()).fetch().documents
            if (page.isEmpty()) break
            for (role in page) {
                if (role.getString("criadorUid") == uid) closeRoleDoc(role) else leaveRole(role.id, uid)
            }
        }
        deleteQuery(sentInvitesQuery(uid))
        deleteQuery(receivedInvitesQuery(uid))
        friendsQueries(uid).forEach { deleteQuery(it) }
        deleteQuery(blockedQuery(uid))
        val batch = db.batch()
        batch.delete(ref("perfisPublicos/$uid"))
        batch.delete(ref("atividade/$uid"))
        batch.commit().await()
    }

    private companion object {
        const val FRIEND_READ_PARALLELISM = 8
    }
}
