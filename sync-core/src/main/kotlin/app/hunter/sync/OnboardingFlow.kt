package app.hunter.sync

enum class Step { INTRO, LOGIN, PERMISSIONS, BIKE, DONE }

/**
 * Ordem da primeira abertura: apresentação, login (pode ser pulado), permissões, cadastro da moto.
 * [seen]: a apresentação já foi concluída antes. [hasBikeInAccount]: a conta já tem moto no perfil.
 */
class OnboardingFlow(private val seen: Boolean, private val hasBikeInAccount: Boolean) {
    fun first(): Step = if (seen) Step.DONE else Step.INTRO

    fun next(from: Step, signedIn: Boolean, skippedLogin: Boolean): Step = when (from) {
        Step.INTRO -> when {
            signedIn && seen -> Step.DONE // reaberta pelo menu de ajuda: nada de login nem de permissões de novo
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
