package app.hunter.app.ui

import app.hunter.app.ui.onboarding.FormField
import app.hunter.sync.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileEditFormTest {
    private fun fields(f: ProfileEditForm) = f.validate(2026).map { it.field }.toSet()

    @Test
    fun `comeca com o perfil atual e devolve o perfil editado mantendo a voz`() {
        val f = ProfileEditForm.from(UserProfile("Ana", "Honda", "CG 160", 2020, voice = false), fallbackName = "x")
        assertEquals("Ana", f.name)
        assertEquals("2020", f.year)
        f.model = "  CB 300F Twister "
        f.year = ""
        assertTrue(f.validate(2026).isEmpty())
        assertEquals(UserProfile("Ana", "Honda", "CB 300F Twister", null, voice = true), f.toProfile(voice = true))
    }

    @Test
    fun `sem perfil comeca com o nome da conta e moto vazia (marca e modelo opcionais)`() {
        val f = ProfileEditForm.from(null, fallbackName = "Bia")
        assertEquals("Bia", f.name)
        assertTrue(f.validate(2026).isEmpty())
        assertEquals(UserProfile("Bia", null, null, null), f.toProfile(voice = true))
    }

    @Test
    fun `nome obrigatorio e textos ate 60 e ano entre 1950 e o atual`() {
        val f = ProfileEditForm.from(null, fallbackName = "  ")
        assertEquals(setOf(FormField.NAME), fields(f))
        f.name = "n".repeat(61)
        f.brand = "b".repeat(61)
        f.model = "m".repeat(61)
        f.year = "1949"
        assertEquals(setOf(FormField.NAME, FormField.BRAND, FormField.MODEL, FormField.YEAR), fields(f))
        f.name = "Ana"; f.brand = "b".repeat(60); f.model = "m".repeat(60); f.year = "2026"
        assertTrue(f.validate(2026).isEmpty())
        f.year = "2027"
        assertEquals(setOf(FormField.YEAR), fields(f))
    }
}
