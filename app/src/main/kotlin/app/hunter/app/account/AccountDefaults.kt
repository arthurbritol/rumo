package app.hunter.app.account

import app.hunter.model.TipoVeiculo
import app.hunter.app.veiculo.TextosDoVeiculo
import app.hunter.app.sync.clampText
import app.hunter.sync.UserProfile

/** O que fazer com as sugestões iniciais de manutenção (uma única vez por instalação). */
enum class SeedDecision { SEED, MARK_ONLY, WAIT }

/**
 * Já há itens (vindos da nuvem ou do uso sem conta): nunca semeia, só marca como feito. Sem itens e logado numa
 * conta que ainda não sincronizou neste processo: ESPERA — a nuvem pode trazer os itens com histórico, e uma
 * semente criada antes viraria duplicata deles. Sem conta (ou já sincronizado e a conta vazia): semeia.
 */
fun maintenanceSeedDecision(itemCount: Int, signedIn: Boolean, syncedThisAccount: Boolean): SeedDecision = when {
    itemCount > 0 -> SeedDecision.MARK_ONLY
    signedIn && !syncedThisAccount -> SeedDecision.WAIT
    else -> SeedDecision.SEED
}

/**
 * Perfil a criar depois de uma sincronização bem-sucedida de quem não tem perfil (nem local, nem vindo da
 * nuvem): usuário 0.3 que criou a conta, visitante que entrou depois, quem tocou "Preencher depois". Nome: o da
 * conta, senão a parte local do e-mail, senão o nome padrão do veículo ativo [tipo] ("Motorista" para carro; "Motociclista" para moto ou sem veículo) (aparado, 1..60); marca e modelo vazios. Null se já há perfil.
 */
fun defaultProfileFor(
    existing: UserProfile?, accountName: String?, email: String?, voice: Boolean, tipo: TipoVeiculo? = null,
): UserProfile? {
    if (existing != null) return null
    val name = accountName?.let(::clampText)?.takeIf { it.isNotEmpty() }
        ?: email?.substringBefore('@')?.let(::clampText)?.takeIf { it.isNotEmpty() }
        ?: TextosDoVeiculo.nomePadrao(tipo)
    return UserProfile(displayName = name, brand = null, model = null, year = null, voice = voice)
}
