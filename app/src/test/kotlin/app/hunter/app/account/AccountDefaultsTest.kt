package app.hunter.app.account

import app.hunter.sync.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountDefaultsTest {
    @Test
    fun `semente de manutencao espera a primeira sincronizacao de quem esta logado`() {
        assertEquals(SeedDecision.WAIT, maintenanceSeedDecision(itemCount = 0, signedIn = true, syncedThisAccount = false))
        assertEquals(SeedDecision.SEED, maintenanceSeedDecision(itemCount = 0, signedIn = true, syncedThisAccount = true))
        assertEquals(SeedDecision.SEED, maintenanceSeedDecision(itemCount = 0, signedIn = false, syncedThisAccount = false))
        // já há itens (da nuvem ou do uso sem conta): nunca semeia, só marca como feito
        assertEquals(SeedDecision.MARK_ONLY, maintenanceSeedDecision(itemCount = 3, signedIn = true, syncedThisAccount = false))
        assertEquals(SeedDecision.MARK_ONLY, maintenanceSeedDecision(itemCount = 1, signedIn = false, syncedThisAccount = false))
    }

    @Test
    fun `perfil padrao so nasce quando nao ha perfil (nem local nem vindo da nuvem)`() {
        val existente = UserProfile("Ana", "Honda", "CG 160", 2020)
        assertNull(defaultProfileFor(existente, "Outro", "o@x.com", voice = true))
        assertEquals(
            UserProfile("Ana Lúcia", null, null, null, voice = false),
            defaultProfileFor(null, "  Ana Lúcia  ", "a@x.com", voice = false),
        )
    }

    @Test
    fun `nome do perfil padrao - nome da conta, senao parte local do e-mail, senao Motociclista, limitado a 60`() {
        assertEquals("joao.silva", defaultProfileFor(null, " ", "joao.silva@gmail.com", true)!!.displayName)
        assertEquals("Motociclista", defaultProfileFor(null, "", null, true)!!.displayName)
        assertEquals("Motociclista", defaultProfileFor(null, null, "@x.com", true)!!.displayName)
        assertEquals("n".repeat(60), defaultProfileFor(null, "n".repeat(90), null, true)!!.displayName)
    }
}
