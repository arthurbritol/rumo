package app.hunter.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OnboardingFlowTest {
    @Test
    fun `primeira vez começa na apresentação`() {
        assertEquals(Step.INTRO, OnboardingFlow(seen = false, hasBikeInAccount = false).first())
    }

    @Test
    fun `quem já viu vai direto a DONE`() {
        assertEquals(Step.DONE, OnboardingFlow(seen = true, hasBikeInAccount = false).first())
    }

    @Test
    fun `Pular na apresentação vai ao login`() {
        val f = OnboardingFlow(seen = false, hasBikeInAccount = false)
        assertEquals(Step.LOGIN, f.next(Step.INTRO, signedIn = false, skippedLogin = false))
    }

    @Test
    fun `Pular o login vai às permissões e depois ao cadastro curto`() {
        val f = OnboardingFlow(seen = false, hasBikeInAccount = false)
        assertEquals(Step.PERMISSIONS, f.next(Step.LOGIN, signedIn = false, skippedLogin = true))
        assertEquals(Step.BIKE, f.next(Step.PERMISSIONS, signedIn = false, skippedLogin = true))
        assertTrue(f.shortBike(signedIn = false))
        assertEquals(Step.DONE, f.next(Step.BIKE, signedIn = false, skippedLogin = true))
    }

    @Test
    fun `login com conta que já tem moto pula o cadastro`() {
        val f = OnboardingFlow(seen = false, hasBikeInAccount = true)
        assertEquals(Step.PERMISSIONS, f.next(Step.LOGIN, signedIn = true, skippedLogin = false))
        assertEquals(Step.DONE, f.next(Step.PERMISSIONS, signedIn = true, skippedLogin = false))
    }

    @Test
    fun `login sem moto leva ao cadastro completo`() {
        val f = OnboardingFlow(seen = false, hasBikeInAccount = false)
        assertEquals(Step.BIKE, f.next(Step.PERMISSIONS, signedIn = true, skippedLogin = false))
        assertFalse(f.shortBike(signedIn = true))
    }

    @Test
    fun `sem login nem pulo o login continua`() {
        val f = OnboardingFlow(seen = false, hasBikeInAccount = false)
        assertEquals(Step.LOGIN, f.next(Step.LOGIN, signedIn = false, skippedLogin = false))
    }

    @Test
    fun `reabrir a apresentação pelo menu de ajuda não repete o login se já está logado`() {
        val f = OnboardingFlow(seen = true, hasBikeInAccount = true)
        assertNotEquals(Step.LOGIN, f.next(Step.INTRO, signedIn = true, skippedLogin = false))
        assertEquals(Step.DONE, f.next(Step.INTRO, signedIn = true, skippedLogin = false))
    }
}
