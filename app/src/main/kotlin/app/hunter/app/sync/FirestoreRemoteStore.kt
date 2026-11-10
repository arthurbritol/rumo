package app.hunter.app.sync

import app.hunter.sync.Kind
import app.hunter.sync.OdometerDoc
import app.hunter.sync.RemoteSnapshot
import app.hunter.sync.RemoteStore
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * [RemoteStore] sobre o Firestore, nos caminhos permitidos por firebase/firestore.rules:
 * usuarios/{uid} (perfil), subcoleções por [Kind.path], config/hodometro e config/exclusao.
 *
 * [deleteSocial] apaga os dados sociais da conta (convites, amizades, rolês etc.) e roda no início de
 * [deleteAllUserData]; o padrão não faz nada (testes e builds sem social).
 */
class FirestoreRemoteStore(
    private val db: FirebaseFirestore,
    private val uid: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deleteSocial: suspend (uid: String) -> Unit = {},
) : RemoteStore {
    private val user: DocumentReference get() = db.collection("usuarios").document(uid)
    private val odometerRef: DocumentReference get() = user.collection("config").document("hodometro")
    private val markerRef: DocumentReference get() = user.collection("config").document("exclusao")
    private fun col(kind: Kind): CollectionReference = user.collection(kind.path)

    override suspend fun fetchAll(): RemoteSnapshot {
        val docs = Kind.entries.flatMap { kind ->
            col(kind).get(Source.SERVER).await().documents.map { mapToDoc(kind, it.id, it.data.orEmpty()) }
        }
        val odo = odometerRef.get(Source.SERVER).await().data?.let(::mapToOdometer)
        val profile = user.get(Source.SERVER).await().data?.let(::mapToProfile)
        return RemoteSnapshot(docs, odo, profile)
    }

    override suspend fun push(docs: List<SyncDoc>) {
        // Converte tudo antes de gravar: um registro inválido (IllegalArgumentException) propaga
        // sem alteração e não deixa envio pela metade; quem chama reporta pelo onError do agendador.
        val prepared = docs.map { it to docToMap(it) }
        for (chunk in batches(prepared)) {
            val batch = db.batch()
            chunk.forEach { (doc, map) -> batch.set(col(doc.kind).document(doc.gid), map) }
            batch.commit().await()
        }
    }

    override suspend fun pushOdometer(o: OdometerDoc) {
        odometerRef.set(odometerToMap(o)).await()
    }

    override suspend fun pushProfile(p: UserProfile) {
        user.set(profileToMap(p, clock())).await()
    }

    override suspend fun markDeletionPending() {
        markerRef.set(mapOf("pending" to true)).await()
    }

    override suspend fun deletionPending(): Boolean =
        markerRef.get(Source.SERVER).await().getBoolean("pending") ?: false

    override suspend fun deleteAllUserData() {
        // Social primeiro: resumível (a marca de exclusão já foi gravada) e só depois o perfil some.
        deleteSocial(uid)
        for (kind in Kind.entries) {
            while (true) {
                val page = col(kind).limit(DELETE_PAGE_SIZE.toLong()).get(Source.SERVER).await().documents
                if (page.isEmpty()) break
                val batch = db.batch()
                page.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
        odometerRef.delete().await()
        user.delete().await()
        // A marca (config/exclusao, só pending:true) NÃO é apagada: é permanente, para que outro aparelho
        // ainda logado nesta conta veja a exclusão e nunca reenvie os dados dele.
    }
}
