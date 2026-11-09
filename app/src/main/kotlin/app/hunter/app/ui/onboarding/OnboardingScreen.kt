package app.hunter.app.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.hunter.sync.OnboardingFlow
import app.hunter.sync.Step

/**
 * Hospeda os passos da primeira abertura. Recebe tudo por parâmetro (a integração com a conta fica
 * para a MainActivity): [signedIn] e [flow] vêm de fora; cada tela de passo é um slot.
 *
 * [initialStep]: use `flow.reopenIntro()` para reabrir a apresentação pelo menu de ajuda (com [flow] visto).
 * [hasBikeInAccount] é lido a cada transição: Task 13 deve devolver o valor atual depois da primeira sincronização.
 *
 * Ao chegar em DONE grava a preferência e chama [onFinished]. [login] recebe `onSkip` (Pular o login) e
 * `onDone` (conta pronta; a tela de login chama quando quiser, p.ex. depois do aviso de e-mail de confirmação).
 */
@Composable
fun OnboardingScreen(
    flow: OnboardingFlow,
    signedIn: Boolean,
    hasBikeInAccount: () -> Boolean,
    prefs: OnboardingPrefs,
    onFinished: () -> Unit,
    login: @Composable (onSkip: () -> Unit, onDone: () -> Unit) -> Unit,
    permissions: @Composable (onNext: () -> Unit) -> Unit,
    bike: @Composable (short: Boolean, onNext: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    initialStep: Step = flow.first(),
) {
    var step by rememberSaveable { mutableStateOf(initialStep) }
    var skippedLogin by rememberSaveable { mutableStateOf(false) }
    val currentFlow by rememberUpdatedState(flow)
    val currentSignedIn by rememberUpdatedState(signedIn)
    val currentHasBike by rememberUpdatedState(hasBikeInAccount)
    val finish by rememberUpdatedState(onFinished)

    fun advance() {
        step = currentFlow.next(step, currentSignedIn, skippedLogin, currentHasBike())
    }

    LaunchedEffect(step) {
        if (step == Step.DONE) {
            prefs.markSeen()
            finish()
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (step) {
                Step.INTRO -> IntroPages(onFinish = { advance() })
                Step.LOGIN -> login(
                    {
                        skippedLogin = true
                        advance()
                    },
                    { advance() },
                )
                Step.PERMISSIONS -> permissions { advance() }
                Step.BIKE -> bike(flow.shortBike(signedIn)) { advance() }
                Step.DONE -> Unit
            }
        }
    }
}
