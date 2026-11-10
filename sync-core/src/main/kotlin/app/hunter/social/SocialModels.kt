package app.hunter.social

/** Uma viagem reduzida ao que a semana precisa: instante de início e distância em metros. */
data class TripSlice(val startedAtMs: Long, val distanceM: Double)

/** Atividade publicada por alguém: km rodados na semana que começou em [weekStartMs]. */
data class Activity(val weekStartMs: Long, val weekKm: Double)

/** Situação de um participante em um rolê. */
enum class MemberState { INVITED, ACCEPTED }

/** Participante de um rolê. [budget] e [costPerKm] são nulos enquanto não informados. */
data class Member(
    val uid: String,
    val name: String,
    val state: MemberState,
    val budget: Double?,
    val costPerKm: Double?,
)

/** Aviso individual do planejamento. */
enum class MemberWarning { OVER_BUDGET, NO_CONSUMPTION_DATA }

/** Linha do planejamento para um participante que aceitou. */
data class MemberPlan(val uid: String, val budget: Double?, val costPerKm: Double?, val warnings: List<MemberWarning>) {
    /** O aviso mais importante (passar do orçamento vem antes de faltar dado de consumo); nulo sem avisos. */
    val warning: MemberWarning? get() = warnings.firstOrNull()
}

/** Motivo de não haver alcance sugerido. */
enum class NoReach { NO_ACCEPTED_MEMBERS, NO_BUDGETS, NO_CONSUMPTION_DATA }

/** Resultado do planejamento: médias, alcance sugerido (ou o motivo de não haver) e avisos por membro. */
data class RolePlan(
    val averageBudget: Double?,
    val averageCostPerKm: Double?,
    val suggestedReachKm: Double?,
    val noReach: NoReach?,
    val members: List<MemberPlan>,
)
