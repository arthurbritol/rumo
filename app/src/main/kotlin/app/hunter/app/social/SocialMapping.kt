package app.hunter.app.social

import app.hunter.app.sync.clampText
import app.hunter.app.sync.safeDisplayName
import app.hunter.social.Activity
import app.hunter.social.Invite
import app.hunter.social.LiveLocation
import app.hunter.social.LocationPermission
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.PublicProfile
import app.hunter.social.Role
import app.hunter.social.SearchKey
import app.hunter.social.SearchHit

/*
 * Funções puras entre os modelos sociais e os mapas do Firestore. Cada mapa respeita exatamente o que
 * firebase/firestore.rules aceita (campos permitidos, 128 caracteres nos uids, números em 0..1e12, no máximo
 * 10 participantes), para que nenhuma escrita seja recusada por formato.
 */

/** Limite das regras para uids dentro de convites e bloqueios. */
const val MAX_UID_CHARS = 128

/** As regras de `busca` só aceitam consulta com `limit` de até 20. */
const val MAX_SEARCH_PAGE = 20

/** Teto de resultados que o adaptador de busca busca no total (várias páginas de [MAX_SEARCH_PAGE]). */
const val MAX_SEARCH_TOTAL = 200

/** Máximo de participantes de um rolê (criador + 9). */
const val MAX_ROLE_PARTICIPANTS = 10

/** Limite superior (exclusivo) dos valores (km, orçamento, custo) aceitos nas regras. */
private const val NUMBER_LIMIT = 1_000_000_000_000.0

/** Limite superior (exclusivo) de instantes em milissegundos nas regras (hoje ~1.8e12). */
private const val EPOCH_LIMIT = 100_000_000_000_000.0

/** Id do par de amigos: os dois uids em ordem alfabética ligados por "_" (igual a `parId` das regras). */
fun pairId(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"

/** Id do convite de [from] para [to]. */
fun inviteId(from: String, to: String): String = "${from}_$to"

/** Id do bloqueio de [by] contra [target]; começa com o uid de quem bloqueia (exigência das regras). */
fun blockId(by: String, target: String): String = "${by}_$target"

/**
 * Tamanhos das páginas para pedir [requested] resultados de busca: páginas de até [MAX_SEARCH_PAGE] (limite das
 * regras), no máximo [MAX_SEARCH_TOTAL] no total. Ex.: 45 vira [20, 20, 5].
 */
fun searchPages(requested: Int): List<Int> {
    var left = requested.coerceIn(0, MAX_SEARCH_TOTAL)
    val pages = ArrayList<Int>()
    while (left > 0) {
        val n = minOf(left, MAX_SEARCH_PAGE)
        pages += n
        left -= n
    }
    return pages
}

private fun numOrNull(x: Double?): Double? = x?.takeIf { it.isFinite() && it >= 0.0 && it < NUMBER_LIMIT }

private fun boundedNumber(x: Double): Double = if (x.isNaN()) 0.0 else x.coerceIn(0.0, NUMBER_LIMIT - 1)

private fun boundedEpoch(x: Long): Long = x.toDouble().coerceIn(0.0, EPOCH_LIMIT - 1).toLong()

private fun optionalText(s: String?): String? = s?.let(::clampText)?.ifEmpty { null }

/** Mapa de `perfisPublicos/{uid}`; `displayNameLower` é a chave [SearchKey] (omitida só se o nome normaliza para vazio). */
fun publicProfileToMap(p: PublicProfile): Map<String, Any?> = buildMap {
    val name = safeDisplayName(p.displayName)
    put("displayName", name)
    SearchKey.normalize(name).ifEmpty { null }?.let { put("displayNameLower", it) }
    put("brand", optionalText(p.brand))
    put("model", optionalText(p.model))
    if (p.year != null && p.year in 1950..2100) put("year", p.year)
    put("searchable", p.searchable)
}

/**
 * Mapa de `busca/{uid}` com a chave [SearchKey] em `displayNameLower`, ou nulo quando o nome normaliza para vazio
 * (nome só com marcas; as regras exigem a chave com 1..60 caracteres). Nesse caso o documento de busca deve ser apagado.
 */
fun buscaToMap(p: PublicProfile): Map<String, Any?>? {
    val name = safeDisplayName(p.displayName)
    val lower = SearchKey.normalize(name).ifEmpty { return null }
    return mapOf(
        "displayName" to name,
        "displayNameLower" to lower,
        "brand" to optionalText(p.brand),
        "model" to optionalText(p.model),
    )
}

/** Inverso de [publicProfileToMap]. */
fun mapToPublicProfile(uid: String, data: Map<String, Any?>): PublicProfile? {
    val name = data["displayName"] as? String ?: return null
    return PublicProfile(
        uid = uid,
        displayName = name,
        brand = data["brand"] as? String,
        model = data["model"] as? String,
        year = (data["year"] as? Number)?.toInt(),
        searchable = data["searchable"] as? Boolean ?: false,
    )
}

/** Resultado de busca a partir de um documento de `busca/{uid}`. */
fun mapToSearchHit(uid: String, data: Map<String, Any?>): SearchHit? {
    val name = data["displayName"] as? String ?: return null
    return SearchHit(uid, name, data["brand"] as? String, data["model"] as? String)
}

/** Mapa de `atividade/{uid}`; km em 0..1e12 e início da semana em 0..1e14 (as regras recusariam fora disso). */
fun activityToMap(a: Activity): Map<String, Any?> = mapOf(
    "weekStartMs" to boundedEpoch(a.weekStartMs),
    "weekKm" to boundedNumber(a.weekKm),
)

fun mapToActivity(data: Map<String, Any?>): Activity? {
    val start = data["weekStartMs"] as? Number ?: return null
    val km = data["weekKm"] as? Number ?: return null
    return Activity(start.toLong(), km.toDouble())
}

/** Mapa de `convites/{de_para}`. */
fun inviteToMap(from: String, to: String, nowMs: Long): Map<String, Any?> {
    require(from != to) { "Convite para si mesmo." }
    require(from.length <= MAX_UID_CHARS && to.length <= MAX_UID_CHARS) { "uid acima de $MAX_UID_CHARS caracteres." }
    return mapOf("de" to from, "para" to to, "criadoEm" to nowMs)
}

fun mapToInvite(data: Map<String, Any?>): Invite? {
    val from = data["de"] as? String ?: return null
    val to = data["para"] as? String ?: return null
    return Invite(from, to, (data["criadoEm"] as? Number)?.toLong() ?: 0L)
}

/** Mapa de `amizades/{par}`: `a` < `b` (as regras exigem). */
fun friendshipToMap(x: String, y: String, nowMs: Long): Map<String, Any?> {
    require(x != y) { "Amizade consigo mesmo." }
    val a = minOf(x, y)
    val b = maxOf(x, y)
    return mapOf("a" to a, "b" to b, "criadoEm" to nowMs)
}

/**
 * Mapa de `bloqueios/{por_alvo}`: `criadoEm` e `quem` (uid de quem bloqueia, igual ao uid da conta). `quem` existe
 * para a consulta `where quem == uid`: as regras não conseguem provar uma consulta por prefixo do id.
 */
fun blockToMap(by: String, nowMs: Long): Map<String, Any?> {
    require(by.length <= MAX_UID_CHARS) { "uid acima de $MAX_UID_CHARS caracteres." }
    return mapOf("criadoEm" to nowMs, "quem" to by)
}

private const val STATE_INVITED = "convidado"
private const val STATE_ACCEPTED = "aceito"

/**
 * Mapa de `roles/{id}/membros/{uid}`. Convidado leva só estado, nome e atualizadoEm (as regras recusam números
 * na criação do convidado); aceito leva também orçamento e custo por km (nulos se fora de 0..1e12).
 */
fun memberToMap(m: Member, nowMs: Long): Map<String, Any?> = buildMap {
    when (m.state) {
        MemberState.INVITED -> put("estado", STATE_INVITED)
        MemberState.ACCEPTED -> put("estado", STATE_ACCEPTED)
    }
    put("nome", clampText(m.name))
    if (m.state == MemberState.ACCEPTED) {
        put("orcamento", numOrNull(m.budget))
        put("custoPorKm", numOrNull(m.costPerKm))
    }
    put("atualizadoEm", nowMs)
}

/** Atualização do próprio membro ao aceitar o convite. */
fun acceptMemberUpdate(nowMs: Long): Map<String, Any?> = mapOf("estado" to STATE_ACCEPTED, "atualizadoEm" to nowMs)

/** Atualização só do orçamento do próprio membro (o custo por km não entra: quem o publica é outro caminho). */
fun budgetUpdate(budget: Double?, nowMs: Long): Map<String, Any?> = mapOf(
    "orcamento" to numOrNull(budget),
    "atualizadoEm" to nowMs,
)

/** Atualização só do custo por km do próprio membro (o orçamento não entra). */
fun costUpdate(costPerKm: Double?, nowMs: Long): Map<String, Any?> = mapOf(
    "custoPorKm" to numOrNull(costPerKm),
    "atualizadoEm" to nowMs,
)

fun mapToMember(uid: String, data: Map<String, Any?>): Member? {
    val state = when (data["estado"]) {
        STATE_ACCEPTED -> MemberState.ACCEPTED
        STATE_INVITED -> MemberState.INVITED
        else -> return null
    }
    return Member(
        uid = uid,
        name = data["nome"] as? String ?: "",
        state = state,
        budget = (data["orcamento"] as? Number)?.toDouble(),
        costPerKm = (data["custoPorKm"] as? Number)?.toDouble(),
    )
}

/** Milissegundos para (segundos, nanos) de um `Timestamp`, com divisão pelo piso (nanos sempre em 0..999_999_999). */
fun millisToSecondsNanos(ms: Long): Pair<Long, Int> =
    Math.floorDiv(ms, 1000L) to (Math.floorMod(ms, 1000L) * 1_000_000L).toInt()

/** Inverso de [millisToSecondsNanos]. */
fun secondsNanosToMillis(seconds: Long, nanos: Int): Long = seconds * 1000L + nanos / 1_000_000

/**
 * Mapa de `roles/{id}`: lança [IllegalArgumentException] se as regras o recusariam (nome vazio, 1..10 participantes
 * distintos, criador fora da lista). `expiraEm` é o ÚNICO ponto onde o instante é convertido: [expiresAt] recebe os
 * milissegundos e devolve o valor gravado (o adaptador devolve um `Timestamp` para o TTL; o padrão é o número).
 */
fun roleToMap(r: Role, nowMs: Long, expiresAt: (Long) -> Any = { it }): Map<String, Any?> {
    val name = clampText(r.name)
    require(name.isNotEmpty()) { "Nome do rolê em branco." }
    require(r.participants.size in 1..MAX_ROLE_PARTICIPANTS) { "Rolê com ${r.participants.size} participantes." }
    require(r.participants.toSet().size == r.participants.size) { "Participantes repetidos." }
    require(r.creatorUid in r.participants) { "O criador precisa participar." }
    return mapOf(
        "nome" to name,
        "criadorUid" to r.creatorUid,
        "participantes" to r.participants,
        "expiraEm" to expiresAt(r.expiresAtMs),
        "criadoEm" to nowMs,
    )
}

/** Inverso de [roleToMap]; `expiraEm` precisa já vir em milissegundos (o adaptador converte Timestamp antes). */
fun mapToRole(id: String, data: Map<String, Any?>): Role? {
    val name = data["nome"] as? String ?: return null
    val creator = data["criadorUid"] as? String ?: return null
    val participants = (data["participantes"] as? List<*>)?.filterIsInstance<String>() ?: return null
    val expires = (data["expiraEm"] as? Number)?.toLong() ?: return null
    return Role(id, name, creator, participants, expires)
}

/** Mapa de `localizacoes/{uid}`: coordenadas, rumo, velocidade e instante da atualização. */
fun liveLocationToMap(loc: LiveLocation): Map<String, Any?> = mapOf(
    "latitude" to if (loc.latitude.isNaN()) 0.0 else loc.latitude.coerceIn(-90.0, 90.0),
    "longitude" to if (loc.longitude.isNaN()) 0.0 else loc.longitude.coerceIn(-180.0, 180.0),
    "bearing" to loc.bearing?.let { if (it.isNaN()) null else it.coerceIn(0f, 360f) },
    "speedKmh" to loc.speedKmh?.let { if (it.isNaN()) null else it.coerceIn(0f, (NUMBER_LIMIT - 1).toFloat()) },
    "updatedAtMs" to boundedEpoch(loc.updatedAtMs),
)

/** Inverso de [liveLocationToMap]. Retorna nulo se faltarem campos obrigatórios ou forem inválidos. */
fun mapToLiveLocation(data: Map<String, Any?>): LiveLocation? {
    val lat = (data["latitude"] as? Number)?.toDouble() ?: return null
    val lng = (data["longitude"] as? Number)?.toDouble() ?: return null
    val updated = (data["updatedAtMs"] as? Number)?.toLong() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    val bearing = (data["bearing"] as? Number)?.toFloat()
    val speed = (data["speedKmh"] as? Number)?.toFloat()
    return LiveLocation(
        latitude = lat,
        longitude = lng,
        bearing = bearing,
        speedKmh = speed,
        updatedAtMs = updated,
    )
}

/** Mapa de `permissoesLocalizacao/{de_para}`. */
fun locationPermissionToMap(de: String, para: String, criadoEm: Long): Map<String, Any?> {
    require(de != para) { "Permissão para si mesmo." }
    require(de.length <= MAX_UID_CHARS && para.length <= MAX_UID_CHARS) { "uid acima de $MAX_UID_CHARS caracteres." }
    return mapOf("de" to de, "para" to para, "criadoEm" to boundedEpoch(criadoEm))
}

/** Inverso de [locationPermissionToMap]. */
fun mapToLocationPermission(data: Map<String, Any?>): LocationPermission? {
    val de = data["de"] as? String ?: return null
    val para = data["para"] as? String ?: return null
    val criadoEm = (data["criadoEm"] as? Number)?.toLong() ?: return null
    return LocationPermission(de, para, criadoEm)
}

