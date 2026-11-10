package app.hunter.app.social

import app.hunter.social.Friend
import app.hunter.social.MemberState
import app.hunter.social.Refusal
import app.hunter.social.Role
import app.hunter.social.WeekMath
import java.time.ZoneId
import java.util.Locale

/*
 * Mapeamento puro (sem Android) dos modelos sociais para as linhas que a aba Amigos mostra. Tudo em pt-BR.
 */

/** Amigo na lista: [bikeLabel] é "Marca Modelo" e [weekKmLabel] os km da semana atual ("0 km" se a atividade é velha). */
data class FriendRow(val uid: String, val name: String, val bikeLabel: String, val weekKmLabel: String)

/** Convite (recebido ou enviado) com o nome da outra pessoa, ou "Alguém" quando não há nome público. */
data class InviteRow(val uid: String, val name: String)

/**
 * Rolê na lista. [pending]: ainda sou só convidado (preciso aceitar). [creatorName]: nome do criador quando é
 * meu amigo, senão nulo. [expired]: venceu (só o criador ainda o vê, para poder renovar ou encerrar).
 */
data class RoleRow(
    val id: String,
    val name: String,
    val creatorName: String?,
    val isCreator: Boolean,
    val pending: Boolean,
    val participantCount: Int,
    val expiresAtMs: Long,
    val expired: Boolean,
)

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

const val NO_BIKE_LABEL = "Moto não informada"
const val UNKNOWN_FRIEND_NAME = "Amigo"
const val UNKNOWN_PERSON_NAME = "Alguém"

/** "Honda CG 160"; só uma das partes quando falta a outra; "Moto não informada" sem nenhuma. */
fun bikeLabel(brand: String?, model: String?): String =
    listOfNotNull(brand?.trim()?.ifEmpty { null }, model?.trim()?.ifEmpty { null })
        .joinToString(" ").ifEmpty { NO_BIKE_LABEL }

/** Km com uma casa decimal e vírgula ("12,3 km"); zero (ou que arredonda para zero) é só "0 km". */
fun weekKmLabel(km: Double): String {
    val text = String.format(PT_BR, "%.1f", km)
    return if (text == "0,0" || !km.isFinite()) "0 km" else "$text km"
}

/** Amigos do mais para o menos quilômetros na semana e, no empate, por nome. */
fun friendRows(friends: List<Friend>, nowMs: Long, zone: ZoneId): List<FriendRow> =
    friends.map { f ->
        val km = WeekMath.visibleWeekKm(f.activity, nowMs, zone)
        km to FriendRow(
            uid = f.profile.uid,
            name = f.profile.displayName.trim().ifEmpty { UNKNOWN_FRIEND_NAME },
            bikeLabel = bikeLabel(f.profile.brand, f.profile.model),
            weekKmLabel = weekKmLabel(km),
        )
    }.sortedWith(compareByDescending<Pair<Double, FriendRow>> { it.first }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.name })
        .map { it.second }

/** Linhas de convite para [uids], com os nomes já conhecidos em [names]. */
fun inviteRows(uids: List<String>, names: Map<String, String>): List<InviteRow> =
    uids.map { InviteRow(it, names[it]?.trim()?.ifEmpty { null } ?: UNKNOWN_PERSON_NAME) }

/**
 * Linhas dos rolês de [me]. [states] traz meu estado em cada rolê (ausente = desconhecido, não pendente). Rolê
 * vencido só aparece para o criador, marcado [RoleRow.expired]; os demais somem (o TTL do Firestore apaga depois).
 */
fun roleRows(
    roles: List<Role>,
    me: String,
    states: Map<String, MemberState?>,
    nameOf: (String) -> String?,
    nowMs: Long,
): List<RoleRow> = roles.mapNotNull { r ->
    val expired = r.expiresAtMs <= nowMs
    val isCreator = r.creatorUid == me
    if (expired && !isCreator) return@mapNotNull null
    RoleRow(
        id = r.id,
        name = r.name,
        creatorName = if (isCreator) null else nameOf(r.creatorUid),
        isCreator = isCreator,
        pending = states[r.id] == MemberState.INVITED,
        participantCount = r.participants.size,
        expiresAtMs = r.expiresAtMs,
        expired = expired,
    )
}

/** Texto em pt-BR para cada recusa das regras sociais. */
fun refusalMessage(reason: Refusal): String = when (reason) {
    Refusal.SELF -> "Você não pode fazer isso consigo mesmo."
    Refusal.ALREADY_FRIENDS -> "Vocês já são amigos."
    Refusal.FRIEND_LIMIT -> "Você chegou ao limite de 50 amigos."
    Refusal.BLOCKED_BY_ME -> "Você bloqueou esta pessoa."
    Refusal.TOO_SHORT -> "Digite pelo menos 3 letras para buscar."
    Refusal.ROLE_FULL -> "Um rolê tem no máximo 10 pessoas."
    Refusal.NOT_FRIENDS -> "Só dá para chamar amigos para o rolê."
    Refusal.NOT_CREATOR -> "Só quem criou o rolê pode fazer isso."
    Refusal.ROLE_EXPIRED -> "Este rolê já expirou."
    Refusal.INVITE_GONE -> "Este convite não existe mais."
    Refusal.NAME_INVALID -> "Dê um nome ao rolê, com até 60 letras."
}
