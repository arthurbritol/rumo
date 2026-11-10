package app.hunter.social

/** Planejamento do rolê: alcance sugerido a partir das médias de orçamento e custo por km de quem aceitou. */
object RolePlanner {
    private const val TOLERANCE = 0.01

    fun plan(members: List<Member>): RolePlan {
        val accepted = members.filter { it.state == MemberState.ACCEPTED }
        val budgets = accepted.mapNotNull { it.budget }
        val costs = accepted.mapNotNull { it.costPerKm }.filter { it > 0.0 }
        val avgBudget = budgets.takeIf { it.isNotEmpty() }?.average()
        val avgCost = costs.takeIf { it.isNotEmpty() }?.average()
        val reach = if (avgBudget != null && avgCost != null) avgBudget / avgCost else null
        val noReach = when {
            reach != null -> null
            accepted.isEmpty() -> NoReach.NO_ACCEPTED_MEMBERS
            avgBudget == null -> NoReach.NO_BUDGETS
            else -> NoReach.NO_CONSUMPTION_DATA
        }
        val plans = accepted.map { m ->
            val own = m.costPerKm?.takeIf { it > 0.0 }
            val warning = when {
                own == null -> MemberWarning.NO_CONSUMPTION_DATA
                reach != null && m.budget != null && own * reach - m.budget > TOLERANCE -> MemberWarning.OVER_BUDGET
                else -> null
            }
            MemberPlan(m.uid, m.budget, m.costPerKm, warning)
        }
        return RolePlan(avgBudget, avgCost, reach, noReach, plans)
    }
}
