package app.hunter.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingFlowTest {
    private fun flow(seen: Boolean = false) = OnboardingFlow(seen)

    @Test
    fun `primeira vez começa na apresentação`() {
        assertEquals(Step.INTRO, flow(seen = false).first())
    }

    @Test
    fun `quem já viu vai direto a DONE`() {
        assertEquals(Step.DONE, flow(seen = true).first())
    }

    @Test
    fun `Pular na apresentação vai ao login`() {
        assertEquals(Step.LOGIN, flow().next(Step.INTRO, signedIn = false, skippedLogin = false, hasBikeInAccount = false))
    }

    @Test
    fun `Pular o login vai às permissões e depois ao cadastro curto`() {
        val f = flow()
        assertEquals(Step.PERMISSIONS, f.next(Step.LOGIN, signedIn = false, skippedLogin = true, hasBikeInAccount = false))
        assertEquals(Step.BIKE, f.next(Step.PERMISSIONS, signedIn = false, skippedLogin = true, hasBikeInAccount = false))
        assertTrue(f.shortBike(signedIn = false))
        assertEquals(Step.DONE, f.next(Step.BIKE, signedIn = false, skippedLogin = true, hasBikeInAccount = false))
    }

    @Test
    fun `login com conta que já tem moto pula o cadastro`() {
        val f = flow()
        assertEquals(Step.PERMISSIONS, f.next(Step.LOGIN, signedIn = true, skippedLogin = false, hasBikeInAccount = false))
        assertEquals(Step.DONE, f.next(Step.PERMISSIONS, signedIn = true, skippedLogin = false, hasBikeInAccount = true))
    }

    @Test
    fun `a moto da conta vale no momento da transição, não na construção`() {
        val f = flow()
        // Logo após o login o perfil ainda não chegou; ao sair das permissões já chegou.
        assertEquals(Step.PERMISSIONS, f.next(Step.LOGIN, signedIn = true, skippedLogin = false, hasBikeInAccount = false))
        assertEquals(Step.DONE, f.next(Step.PERMISSIONS, signedIn = true, skippedLogin = false, hasBikeInAccount = true))
    }

    @Test
    fun `login sem moto leva ao cadastro completo`() {
        val f = flow()
        assertEquals(Step.BIKE, f.next(Step.PERMISSIONS, signedIn = true, skippedLogin = false, hasBikeInAccount = false))
        assertFalse(f.shortBike(signedIn = true))
    }

    @Test
    fun `sem login nem pulo o login continua`() {
        assertEquals(Step.LOGIN, flow().next(Step.LOGIN, signedIn = false, skippedLogin = false, hasBikeInAccount = false))
    }

    @Test
    fun `reabrir a apresentação pelo menu de ajuda começa na apresentação e termina sem repetir login nem moto`() {
        val f = flow(seen = true)
        assertEquals(Step.DONE, f.first()) // início normal: nada a mostrar
        val start = f.reopenIntro()
        assertEquals(Step.INTRO, start)
        assertEquals(Step.DONE, f.next(start, signedIn = true, skippedLogin = false, hasBikeInAccount = true))
        assertEquals(Step.DONE, f.next(start, signedIn = true, skippedLogin = false, hasBikeInAccount = false))
    }

    @Test
    fun `reabrir a apresentação como visitante também termina nela, sem login nem permissões nem moto`() {
        val f = flow(seen = true)
        assertEquals(Step.DONE, f.next(f.reopenIntro(), signedIn = false, skippedLogin = false, hasBikeInAccount = false))
    }

    @Test
    fun `primeira vez como visitante continua indo ao login`() {
        assertEquals(Step.LOGIN, flow(seen = false).next(Step.INTRO, signedIn = false, skippedLogin = false, hasBikeInAccount = false))
    }
}
