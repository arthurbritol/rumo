package app.hunter.app.ui.onboarding

import app.hunter.sync.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class BikeProfileMergeTest {
    private fun form() = BikeForm(short = false).apply {
        selectBike("Honda", "CG 160")
        year = "2022"
        odometerKm = "10"
    }

    @Test fun `nome da conta e limitado a 60 caracteres depois de aparar`() {
        val p = BikeProfileMerge.merge(null, form(), "  " + "a".repeat(80) + "  ", voice = true)
        assertEquals(60, p.displayName.length)
        assertEquals("a".repeat(60), p.displayName)
    }

    @Test fun `nome vazio vira Motociclista`() {
        assertEquals("Motociclista", BikeProfileMerge.merge(null, form(), "   ", voice = true).displayName)
    }

    @Test fun `perfil existente com nome mantém o nome e atualiza só moto`() {
        val existing = UserProfile("Nome Antigo", "Yamaha", "XJ6", 2010, voice = false)
        val p = BikeProfileMerge.merge(existing, form(), "Nome da Conta", voice = false)
        assertEquals("Nome Antigo", p.displayName)
        assertEquals("Honda", p.brand)
        assertEquals("CG 160", p.model)
        assertEquals(2022, p.year)
        assertEquals(false, p.voice)
    }

    @Test fun `perfil existente com nome em branco usa o nome da conta`() {
        val existing = UserProfile("  ", null, null, null)
        assertEquals("Ana", BikeProfileMerge.merge(existing, form(), "Ana", voice = true).displayName)
    }
}
