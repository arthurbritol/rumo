package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.MemberState
import app.hunter.social.PublicProfile
import app.hunter.social.SocialStore
import app.hunter.social.TripSlice
import app.hunter.social.WeekMath
import app.hunter.sync.UserProfile
import app.hunter.trips.data.TripEntity
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/** Viagens terminadas ([TripEntity.endedAtMs] preenchido) reduzidas ao que a semana precisa; a que ainda grava fica de fora. */
fun finishedSlices(trips: List<TripEntity>): List<TripSlice> =
    trips.filter { it.endedAtMs != null }.map { TripSlice(it.startedAtMs, it.distanceM) }

/**
 * Publica o que os amigos veem de [uid]: perfil público (e o índice de busca, se a pessoa quer aparecer nela),
 * km da semana e custo por km nos rolês de que participa. Sem Android nem Firebase (só o [SocialStore] e lambdas),
 * então roda na JVM nos testes. Não captura exceções: quem chama decide (o [SocialGraph] as engole e tenta no
 * próximo gatilho).
 *
 * As leituras de viagens e de custo são `suspend` porque vêm do Room.
 */
class SocialPublisher(
    private val store: SocialStore,
    private val uid: String,
    private val readProfile: () -> UserProfile?,
    private val readTrips: suspend () -> List<TripSlice>,
    private val readCostPerKm: suspend () -> Double?,
    private val searchable: () -> Boolean,
    private val zone: ZoneId,
    private val clock: () -> Long,
) {
    /** No máximo uma publicação por minuto; relógio que voltou para trás não bloqueia. */
    fun shouldPublish(lastPublishedAtMs: Long, nowMs: Long): Boolean =
        nowMs < lastPublishedAtMs || nowMs - lastPublishedAtMs >= MIN_INTERVAL_MS

    /**
     * 1) Perfil público para os amigos, sempre; `busca/{uid}` só com [searchable] (senão o documento é apagado).
     * 2) `atividade/{uid}` com os km da semana atual das viagens terminadas.
     * 3) O custo por km em cada rolê em que já aceitei, mantendo o orçamento que o membro já tinha. Rolê vencido,
     *    convite ainda pendente (as regras só deixam o membro aceito alterar valores) e custo já publicado são
     *    pulados. Uma falha num rolê não impede os outros; a primeira falha é relançada no fim.
     * Sem perfil (sem nome) não publica nada.
     */
    suspend fun publishAll() {
        val profile = readProfile() ?: return
        val inSearch = searchable()
        store.publishProfile(PublicProfile(uid, profile.displayName, profile.brand, profile.model, profile.year, inSearch))
        if (!inSearch) store.removeFromSearch(uid)

        val now = clock()
        val weekStart = WeekMath.weekStartMs(now, zone)
        store.publishActivity(uid, Activity(weekStart, WeekMath.weekKm(readTrips(), weekStart, zone)))

        val cost = readCostPerKm()
        var failure: Exception? = null
        for (role in store.roles(uid)) {
            if (role.expiresAtMs <= now) continue
            try {
                val me = store.member(role.id, uid) ?: continue
                if (me.state != MemberState.ACCEPTED || me.costPerKm == cost) continue
                store.setMemberFigures(role.id, uid, me.budget, cost)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        failure?.let { throw it }
    }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}
