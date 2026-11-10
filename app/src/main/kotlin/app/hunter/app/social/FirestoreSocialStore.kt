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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /** Dados do documento com cada `Timestamp` convertido em milissegundos (ver [secondsNanosToMillis]). */
    private fun DocumentSnapshot.map(): Map<String, Any?> =
        data.orEmpty().mapValues { (_, v) -> if (v is Timestamp) secondsNanosToMillis(v.seconds, v.nanoseconds) else v }

    private fun millisToTimestamp(ms: Long): Timestamp =
        millisToSecondsNanos(ms).let { (s, n) -> Timestamp(s, n) }

    /** Convites de [de] para [para] que existem (0 ou 1 documento); a consulta já prova a regra (`de`/`para`). */
    private suspend fun inviteRefs(de: String, para: String): List<DocumentReference> =
        db.collection("convites").whereEqualTo("de", de).whereEqualTo("para", para).fetch().documents.map { it.reference }

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

    /** `get` de `perfisPublicos/{uid}` (o dono lê o próprio): o `searchable` publicado, ou nulo se nunca publicou. */
    override suspend fun publishedSearchable(uid: String): Boolean? {
        val d = ref("perfisPublicos/$uid").get(Source.SERVER).await()
        return if (d.exists()) d.getBoolean("searchable") else null
    }

    /** Apaga `busca/{uid}` (as regras permitem ao dono; apagar documento inexistente é aceito). */
    override suspend fun removeFromSearch(uid: String) {
        ref("busca/$uid").delete().await()
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

    /** `get` de `busca/{uid}` (qualquer conta lê; documento ausente = pessoa fora da busca). */
    override suspend fun searchEntry(uid: String): SearchHit? {
        val d = ref("busca/$uid").get().await() // padrão: servidor, com o cache como reserva sem rede
        return if (d.exists()) mapToSearchHit(uid, d.map()) else null
    }

    // ---------------------------------------------------------------- convites

    override suspend fun sendInvite(from: String, to: String) {
        ref("convites/${inviteId(from, to)}").set(inviteToMap(from, to, clock())).await()
    }

    /** Quem convidou apaga o convite (se ainda existir). */
    override suspend fun cancelInvite(from: String, to: String) {
        deleteAll(inviteRefs(from, to))
    }

    /** O destinatário recusa (apaga) o convite de [from] (se ainda existir). */
    override suspend fun declineInvite(from: String, to: String) {
        deleteAll(inviteRefs(from, to))
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

    /**
     * Perfil público e atividade de cada amigo, lidos em paralelo (no máximo [FRIEND_READ_PARALLELISM] de cada vez).
     * Um amigo cuja leitura falha (por exemplo PERMISSION_DENIED logo depois de desfazer a amizade) é pulado em vez
     * de derrubar a lista inteira; perfil ausente vira "Amigo". Cancelamento sempre propaga.
     */
    override suspend fun friends(uid: String): List<Friend> = loadFriends(friendUids(uid))

    private suspend fun loadFriends(uids: Set<String>): List<Friend> = coroutineScope {
        val gate = Semaphore(FRIEND_READ_PARALLELISM)
        uids.sorted().map { f ->
            async {
                gate.withPermit {
                    try {
                        val profileDoc = ref("perfisPublicos/$f").get(Source.SERVER).await()
                        val profile = profileDoc.takeIf { it.exists() }?.let { mapToPublicProfile(f, it.map()) }
                            ?: PublicProfile(f, "Amigo", null, null, null, false)
                        val activityDoc = ref("atividade/$f").get(Source.SERVER).await()
                        Friend(profile, activityDoc.takeIf { it.exists() }?.let { mapToActivity(it.map()) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Apaga a amizade (se existir). */
    override suspend fun unfriend(uid: String, other: String) {
        deleteAll(friendshipRefs(uid, other))
    }

    private suspend fun friendshipRefs(uid: String, other: String): List<DocumentReference> {
        val pair = pairId(uid, other)
        val q = db.collection("amizades").whereEqualTo("a", minOf(uid, other)).whereEqualTo("b", maxOf(uid, other))
        return q.fetch().documents.filter { it.id == pair }.map { it.reference }
    }

    // ---------------------------------------------------------------- bloqueios

    /**
     * Lote: grava `bloqueios/{uid_other}` e apaga a amizade e os convites entre os dois que existirem (só os
     * existentes: as regras recusam apagar `convites`/`amizades` inexistentes).
     */
    override suspend fun block(uid: String, other: String) {
        try {
            commitBlock(uid, other)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            // Corrida: um documento sumiu entre a consulta e o lote (o outro lado cancelou o convite, por exemplo).
            // Consulta de novo e repete o lote uma vez; se falhar outra vez o erro sobe.
            commitBlock(uid, other)
        }
    }

    private suspend fun commitBlock(uid: String, other: String) {
        val doomed = friendshipRefs(uid, other) + inviteRefs(uid, other) + inviteRefs(other, uid)
        val batch = db.batch()
        batch.set(ref("bloqueios/${blockId(uid, other)}"), blockToMap(uid, clock()))
        doomed.forEach { batch.delete(it) }
        batch.commit().await()
    }

    override suspend fun unblock(uid: String, other: String) {
        ref("bloqueios/${blockId(uid, other)}").delete().await()
    }

    /**
     * Bloqueios feitos por [uid]: `where quem == uid`. As regras só admitem `list` por esse campo (em `list` o `id`
     * é nulo, então uma faixa de id não é aceita; medido no emulador).
     */
    private fun blockedQuery(uid: String): Query = db.collection("bloqueios").whereEqualTo("quem", uid)

    override suspend fun blocked(uid: String): Set<String> =
        blockedQuery(uid).fetch().documents.map { it.id.removePrefix("${uid}_") }.toSet()

    // ---------------------------------------------------------------- observação em tempo real

    /**
     * Flow do snapshot da consulta, com buffer conflated (o snapshot mais novo nunca é descartado por um mais
     * antigo). O ouvinte sai ao cancelar. Um erro do ouvinte encerra o flow com a exceção
     * ([FirebaseFirestoreException]): PERMISSION_DENIED quando o rolê foi encerrado ou o usuário saiu dele (as
     * regras deixam de permitir a leitura), UNAVAILABLE quando está sem rede. Quem coleta (ViewModel) trata e
     * decide tentar de novo ou mostrar "sem conexão"; este adaptador não reinicia o ouvinte.
     */
    private fun Query.snapshots(): Flow<QuerySnapshot> = callbackFlow {
        val reg = addSnapshotListener { snap, err ->
            if (err != null) close(err) else if (snap != null) trySend(snap)
        }
        awaitClose { reg.remove() }
    }.buffer(Channel.CONFLATED)

    override fun observeInvitesReceived(uid: String): Flow<List<Invite>> =
        receivedInvitesQuery(uid).snapshots().map { s -> s.documents.mapNotNull { mapToInvite(it.map()) } }

    /**
     * O conjunto de uids dos amigos, a partir dos snapshots das duas consultas (`a == uid` e `b == uid`); só emite
     * quando o conjunto muda. Quem usa relê [friends] ao mudar (um carregamento por mudança, não um por consulta).
     */
    override fun observeFriendUids(uid: String): Flow<Set<String>> {
        val (qa, qb) = friendsQueries(uid)
        fun ids(s: QuerySnapshot) = s.documents.mapNotNull { otherOf(uid, it) }.toSet()
        return combine(qa.snapshots().map(::ids), qb.snapshots().map(::ids)) { a, b -> a + b }.distinctUntilChanged()
    }

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
        batch.set(ref("roles/${role.id}"), roleToMap(role, now, ::millisToTimestamp))
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
     * Lote (criador): apaga os membros e depois o rolê. Os ids dos membros são os de `participantes` (apagar um
     * documento de membro não exige que ele exista), sem listar a subcoleção. É o caminho de limpeza de rolês
     * vencidos: o TTL apaga só o documento do rolê, não os `membros`.
     */
    override suspend fun closeRole(roleId: String) {
        val role = ref("roles/$roleId").get(Source.SERVER).await()
        if (!role.exists()) return
        closeRoleDoc(role)
    }

    private suspend fun closeRoleDoc(role: DocumentSnapshot) {
        val ids = (role.get("participantes") as? List<*>)?.filterIsInstance<String>().orEmpty().toSet()
        val batch = db.batch()
        ids.forEach { batch.delete(role.reference.collection("membros").document(it)) }
        batch.delete(role.reference)
        batch.commit().await()
    }

    /** Só o criador: atualiza `expiraEm` como `Timestamp` (continua elegível ao TTL do Firestore). */
    override suspend fun renewRole(roleId: String, newExpiresAtMs: Long) {
        ref("roles/$roleId").update("expiraEm", millisToTimestamp(newExpiresAtMs)).await()
    }

    override suspend fun setMemberBudget(roleId: String, uid: String, budget: Double?) {
        ref("roles/$roleId/membros/$uid").update(budgetUpdate(budget, clock())).await()
    }

    override suspend fun setMemberCost(roleId: String, uid: String, costPerKm: Double?) {
        ref("roles/$roleId/membros/$uid").update(costUpdate(costPerKm, clock())).await()
    }

    override suspend fun roles(uid: String): List<Role> =
        rolesQuery(uid).fetch().documents.mapNotNull { mapToRole(it.id, it.map()) }

    override suspend fun members(roleId: String): List<Member> =
        db.collection("roles/$roleId/membros").fetch().documents.mapNotNull { mapToMember(it.id, it.map()) }

    /** `get` do próprio documento de membro (as regras o permitem ao dono, convidado ou aceito). */
    override suspend fun member(roleId: String, uid: String): Member? {
        val d = ref("roles/$roleId/membros/$uid").get().await() // padrão: servidor, com o cache como reserva sem rede
        return if (d.exists()) mapToMember(uid, d.map()) else null
    }

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
