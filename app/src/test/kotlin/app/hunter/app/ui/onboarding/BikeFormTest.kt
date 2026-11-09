package app.hunter.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BikeFormTest {
    private fun fields(f: BikeForm, year: Int = 2026) = f.validate(year).map { it.field }.toSet()

    @Test fun `completo exige marca, modelo e km`() {
        val f = BikeForm(short = false)
        assertEquals(setOf(FormField.BRAND, FormField.MODEL, FormField.ODOMETER), fields(f))
        f.selectBike("Royal Enfield", "Hunter 350")
        f.odometerKm = "1200"
        assertTrue(f.validate(2026).isEmpty())
    }

    @Test fun `curto exige só km e tanque`() {
        val f = BikeForm(short = true)
        assertEquals(setOf(FormField.ODOMETER), fields(f)) // tanque já vem com o padrão
        f.editTank("")
        assertEquals(setOf(FormField.ODOMETER, FormField.TANK), fields(f))
        f.odometerKm = "500"
        f.editTank("13")
        assertTrue(f.validate(2026).isEmpty())
    }

    @Test fun `Outra aceita modelo em texto livre`() {
        val f = BikeForm(short = false)
        f.selectBike("Outra", "Minha custom 500")
        f.odometerKm = "10"
        assertTrue(f.validate(2026).isEmpty())
        assertEquals("Minha custom 500", f.toProfile("Ana").model)
    }

    @Test fun `km negativo ou vazio é recusado`() {
        val f = BikeForm(short = true)
        f.odometerKm = "-5"
        assertEquals(setOf(FormField.ODOMETER), fields(f))
        f.odometerKm = "  "
        assertEquals(setOf(FormField.ODOMETER), fields(f))
        f.odometerKm = "abc"
        assertEquals(setOf(FormField.ODOMETER), fields(f))
        f.odometerKm = "0"
        assertTrue(f.validate(2026).isEmpty())
    }

    @Test fun `tanque padrão da marca e modelo sugerido e editável`() {
        val f = BikeForm(short = false)
        f.selectBike("Royal Enfield", "Hunter 350")
        assertEquals("13", f.tankLiters)
        f.selectBike("Royal Enfield", "Meteor 350")
        assertEquals("15", f.tankLiters)
        f.editTank("14,5")
        f.selectBike("Royal Enfield", "Hunter 350") // edição do usuário não é sobrescrita
        assertEquals("14,5", f.tankLiters)
        f.odometerKm = "100"
        assertEquals(14.5, f.toOdometer(1L).tankLiters)
        assertEquals(13.0, BikeForm.defaultTankLiters("Outra", "qualquer"))
    }

    @Test fun `ano fora de 1950 até o ano atual é recusado`() {
        val f = BikeForm(short = false)
        f.selectBike("Honda", "CG 160")
        f.odometerKm = "1"
        f.year = "1949"
        assertEquals(setOf(FormField.YEAR), fields(f))
        f.year = "2027"
        assertEquals(setOf(FormField.YEAR), fields(f))
        f.year = "2026"
        assertTrue(f.validate(2026).isEmpty())
        f.year = ""
        assertTrue(f.validate(2026).isEmpty())
        assertNull(f.toProfile("A").year)
    }

    @Test fun `gera perfil e hodômetro`() {
        val f = BikeForm(short = false)
        f.selectBike("Royal Enfield", "Hunter 350")
        f.year = "2024"
        f.odometerKm = "1.234,5".replace(".", "")
        val p = f.toProfile("Ana")
        assertEquals("Ana", p.displayName)
        assertEquals("Royal Enfield", p.brand)
        assertEquals(2024, p.year)
        val o = f.toOdometer(99L, tripsDistanceM = 34_500.0)
        assertEquals(1200.0, o.offsetKm, 1e-9) // 1234,5 km no painel - 34,5 km de viagens
        assertEquals(13.0, o.tankLiters)
        assertEquals(99L, o.updatedAtMs)
    }

    @Test fun `curto não leva marca nem modelo ao perfil`() {
        val f = BikeForm(short = true)
        f.selectBike("Honda", "CG 160")
        assertNull(f.toProfile("").brand)
    }
}
