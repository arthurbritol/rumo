package app.hunter.sync

enum class Step { INTRO, LOGIN, PERMISSIONS, BIKE, DONE }

/**
 * Ordem da primeira abertura: apresentação, login (pode ser pulado), permissões, cadastro da moto.
 * [seen]: a apresentação já foi concluída antes.
 *
 * Se a conta já tem moto ([next] `hasBikeInAccount`) o cadastro é pulado. Esse dado muda depois do login
 * (o perfil só chega na primeira sincronização), então vem como argumento a cada transição.
 * Task 13: passar o valor ATUAL (perfil/hodômetro lidos depois da primeira sincronização), não um valor
 * lido antes do login.
 */
class OnboardingFlow(private val seen: Boolean) {
    fun first(): Step = if (seen) Step.DONE else Step.INTRO

    /** Entrada explícita para reabrir a apresentação pelo menu de ajuda (quando [first] já é DONE). */
    fun reopenIntro(): Step = Step.INTRO

    fun next(from: Step, signedIn: Boolean, skippedLogin: Boolean, hasBikeInAccount: Boolean): Step = when (from) {
        Step.INTRO -> when {
            seen -> Step.DONE // reaberta pelo menu de ajuda: só a apresentação, nunca login, permissões nem moto
            signedIn -> Step.PERMISSIONS
            else -> Step.LOGIN
        }
        Step.LOGIN -> if (signedIn || skippedLogin) Step.PERMISSIONS else Step.LOGIN
        Step.PERMISSIONS -> if (signedIn && hasBikeInAccount) Step.DONE else Step.BIKE
        Step.BIKE, Step.DONE -> Step.DONE
    }

    /** Sem conta: cadastro curto (km e tanque); com conta: completo. */
    fun shortBike(signedIn: Boolean): Boolean = !signedIn
}
