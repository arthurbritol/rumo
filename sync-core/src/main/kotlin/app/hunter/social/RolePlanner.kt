package app.hunter.social

/** Planejamento do rolê: alcance sugerido a partir das médias de orçamento e custo por km de quem aceitou. */
object RolePlanner {
    private const val TOLERANCE = 0.01

    fun plan(members: List<Member>): RolePlan {
        val accepted = members.filter { it.state == MemberState.ACCEPTED }
        val budgets = accepted.mapNotNull { it.budget }.filter { it.isFinite() && it >= 0.0 }
        val costs = accepted.mapNotNull { it.costPerKm }.filter { it.isFinite() && it > 0.0 }
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
            val own = m.costPerKm?.takeIf { it.isFinite() && it > 0.0 }
            // Quem não tem dados de consumo é medido pelo custo médio do grupo (e também recebe o aviso de falta de dados).
            val cost = own ?: avgCost
            val warnings = buildList {
                if (reach != null && cost != null && m.budget != null && m.budget.isFinite() && m.budget >= 0.0 &&
                    cost * reach - m.budget > TOLERANCE
                ) add(MemberWarning.OVER_BUDGET)
                if (own == null) add(MemberWarning.NO_CONSUMPTION_DATA)
            }
            MemberPlan(m.uid, m.budget, m.costPerKm, warnings)
        }
        return RolePlan(avgBudget, avgCost, reach, noReach, plans)
    }
}
