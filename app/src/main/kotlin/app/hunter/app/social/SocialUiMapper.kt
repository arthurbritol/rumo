package app.hunter.app.social

import app.hunter.social.Friend
import app.hunter.social.LiveLocation
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.MemberWarning
import app.hunter.social.NoReach
import app.hunter.social.RolePlanner
import app.hunter.social.Refusal
import app.hunter.social.Role
import app.hunter.social.WeekMath
import java.time.ZoneId
import java.util.Locale

/*
 * Mapeamento puro (sem Android) dos modelos sociais para as linhas que a aba Amigos mostra. Tudo em pt-BR.
 */

/** Amigo na lista: [bikeLabel] é "Marca Modelo" e [weekKmLabel] os km da semana atual ("0 km" se a atividade é velha). */
data class FriendRow(
    val uid: String,
    val name: String,
    val bikeLabel: String,
    val weekKmLabel: String,
    val locationSharedWithHim: Boolean = false,
    val isLiveOnRoad: Boolean = false,
    val liveLatitude: Double? = null,
    val liveLongitude: Double? = null,
    val liveSpeedKmh: Float? = null,
    val liveUpdatedAtMs: Long? = null,
)

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
fun friendRows(
    friends: List<Friend>,
    nowMs: Long,
    zone: ZoneId,
    grantedPermissions: Set<String> = emptySet(),
    activeFriendsOnRoad: Map<String, LiveLocation> = emptyMap(),
): List<FriendRow> =
    friends.map { f ->
        val km = WeekMath.visibleWeekKm(f.activity, nowMs, zone)
        val isShared = f.profile.uid in grantedPermissions
        val loc = activeFriendsOnRoad[f.profile.uid]
        val isLive = loc != null && !loc.isExpired(nowMs) && loc.isValid()
        km to FriendRow(
            uid = f.profile.uid,
            name = f.profile.displayName.trim().ifEmpty { UNKNOWN_FRIEND_NAME },
            bikeLabel = bikeLabel(f.profile.brand, f.profile.model),
            weekKmLabel = weekKmLabel(km),
            locationSharedWithHim = isShared,
            isLiveOnRoad = isLive,
            liveLatitude = if (isLive) loc?.latitude else null,
            liveLongitude = if (isLive) loc?.longitude else null,
            liveSpeedKmh = if (isLive) loc?.speedKmh else null,
            liveUpdatedAtMs = if (isLive) loc?.updatedAtMs else null,
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

// ------------------------------------------------------------------ aba Amigos

const val OFFLINE_BANNER = "Amigos precisa de internet. Mostrando o último dado carregado."

/** Marcador da aba: nada com zero, o número até 99 e "99+" acima. */
fun friendsBadge(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** Convites de amizade recebidos mais convites de rolê ainda sem resposta. */
fun pendingCount(invitesReceived: Int, pendingRoles: Int): Int = invitesReceived + pendingRoles

/** Aviso a exibir: com o banner de offline na tela, as mensagens genéricas de rede são redundantes. */
fun messageToShow(offline: Boolean, message: String?): String? =
    if (offline && (message == OFFLINE_MESSAGE || message == ACTION_FAILED_MESSAGE)) null else message

const val MAX_ROLE_PEOPLE = 10
const val ROLE_LIMIT_NOTICE = "Um rolê tem no máximo 10 pessoas, com você. Escolha até 9 amigos."

/** Marca ou desmarca [uid]; passar do limite de [max] amigos é ignorado. */
fun toggleSelection(selected: Set<String>, uid: String, max: Int = MAX_ROLE_PEOPLE - 1): Set<String> = when {
    uid in selected -> selected - uid
    selected.size >= max -> selected
    else -> selected + uid
}

// ------------------------------------------------------------------ rolê

/** "R$ 1.234,50". */
fun moneyLabel(value: Double): String = "R$ " + String.format(PT_BR, "%,.2f", value)

/** Texto do campo de orçamento para um valor já salvo ("150" ou "150,50"); vazio sem valor. */
fun budgetInputText(value: Double?): String {
    if (value == null || !value.isFinite()) return ""
    val t = String.format(PT_BR, "%.2f", value)
    return if (t.endsWith(",00")) t.dropLast(3) else t
}

private val THOUSANDS = Regex("""\d{1,3}(\.\d{3})+""")
private val DECIMAL = Regex("""\d+(\.\d{1,2})?""")

/** Lê um valor em reais digitado ("150", "150,5", "1.234,50", "R$ 90"); inválido ou vazio vira nulo. */
fun parseBudget(text: String): Double? {
    val t = text.trim().removePrefix("R$").replace(" ", "")
    if (t.isEmpty()) return null
    val norm = when {
        ',' in t -> t.replace(".", "").replace(',', '.')
        THOUSANDS.matches(t) -> t.replace(".", "")
        else -> t
    }
    if (!DECIMAL.matches(norm)) return null
    return norm.toDouble().takeIf { it <= 1_000_000.0 }
}

/** Alcance sugerido em km inteiros, arredondado para baixo (nunca promete mais do que o orçamento paga). */
fun reachLabel(km: Double): String {
    val whole = Math.floor(km).toLong()
    return if (whole < 1) "menos de 1 km" else "até $whole km"
}

/** Aviso de uma linha do rolê; [isMe] troca o sujeito. Nulo quando não há aviso. */
fun warningLabel(warning: MemberWarning?, isMe: Boolean): String? = when (warning) {
    null -> null
    MemberWarning.OVER_BUDGET -> if (isMe) "Passa do que você pode gastar" else "Passa do que esta pessoa pode gastar"
    MemberWarning.NO_CONSUMPTION_DATA -> "Sem dados de consumo"
}

/** Por que não há alcance sugerido. */
fun noReachMessage(reason: NoReach): String = when (reason) {
    NoReach.NO_ACCEPTED_MEMBERS -> "Ninguém aceitou o rolê ainda."
    NoReach.NO_BUDGETS -> "Ninguém informou orçamento ainda. Quando alguém informar, o alcance aparece aqui."
    NoReach.NO_CONSUMPTION_DATA ->
        "Ninguém tem dados de consumo ainda, então não dá para calcular o alcance. Registre abastecimentos no app."
}

/** Participante na tela do rolê. [invited]: ainda não aceitou. [warning]: aviso já em texto. */
data class ParticipantRow(
    val uid: String,
    val name: String,
    val isMe: Boolean,
    val invited: Boolean,
    val budgetLabel: String,
    val warnings: List<String>,
    val canRemove: Boolean,
)

/** Tela do rolê: [averageLabel] média dos orçamentos, [reachText] alcance sugerido ou o motivo de não haver. */
data class RoleDetailUi(
    val roleId: String,
    val name: String,
    val isCreator: Boolean,
    val expired: Boolean,
    val participants: List<ParticipantRow>,
    val averageLabel: String,
    val reachText: String,
    val hasReach: Boolean,
    val myBudget: Double?,
    val iAccepted: Boolean,
)

const val NO_AVERAGE_LABEL = "Sem orçamentos ainda"

/** Monta a tela do rolê a partir dos membros lidos; o planejamento é o do [RolePlanner]. */
fun roleDetailUi(role: RoleRow, members: List<Member>, me: String): RoleDetailUi {
    val plan = RolePlanner.plan(members)
    val warnings = plan.members.associate { it.uid to it.warnings }
    val rows = members
        .sortedWith(compareByDescending<Member> { it.uid == me }.thenBy { it.state != MemberState.ACCEPTED }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .map { m ->
            val isMe = m.uid == me
            val invited = m.state != MemberState.ACCEPTED
            ParticipantRow(
                uid = m.uid,
                name = if (isMe) "Você" else m.name.trim().ifEmpty { UNKNOWN_FRIEND_NAME },
                isMe = isMe,
                invited = invited,
                budgetLabel = when {
                    invited -> "Aguardando resposta"
                    m.budget == null -> "Sem orçamento"
                    else -> moneyLabel(m.budget!!)
                },
                warnings = if (invited) emptyList() else warnings[m.uid].orEmpty().mapNotNull { warningLabel(it, isMe) },
                canRemove = role.isCreator && !isMe,
            )
        }
    val mine = members.firstOrNull { it.uid == me }
    return RoleDetailUi(
        roleId = role.id,
        name = role.name,
        isCreator = role.isCreator,
        expired = role.expired,
        participants = rows,
        averageLabel = plan.averageBudget?.let(::moneyLabel) ?: NO_AVERAGE_LABEL,
        reachText = plan.suggestedReachKm?.let(::reachLabel) ?: noReachMessage(plan.noReach ?: NoReach.NO_BUDGETS),
        hasReach = plan.suggestedReachKm != null,
        myBudget = mine?.budget,
        iAccepted = mine?.state == MemberState.ACCEPTED,
    )
}

/** Segunda linha do rolê na lista. */
fun roleSubtitle(row: RoleRow): String {
    val people = if (row.participantCount == 1) "1 pessoa" else "${row.participantCount} pessoas"
    return when {
        row.pending -> "Convite de ${row.creatorName ?: "um amigo"} · $people"
        row.expired -> "Expirado · abra para renovar por mais 7 dias"
        row.isCreator -> "Você criou · $people"
        row.creatorName != null -> "De ${row.creatorName} · $people"
        else -> people
    }
}
