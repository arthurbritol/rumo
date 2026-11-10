package app.hunter.app.ui

import app.hunter.app.UPDATE_CHECK_INTERVAL_MS
import app.hunter.app.UpdateCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateStatusTextTest {
    @Test
    fun `sem busca nao mostra texto e cada resultado tem a sua mensagem`() {
        assertNull(updateStatusText(UpdateCheck.IDLE))
        val textos = UpdateCheck.entries.filter { it != UpdateCheck.IDLE }.map { assertNotNull(updateStatusText(it)) }
        assertEquals(textos.size, textos.toSet().size, "mensagens distintas")
        assertTrue("mais recente" in updateStatusText(UpdateCheck.UP_TO_DATE)!!)
    }

    @Test
    fun `consulta automatica a cada 15 minutos no maximo`() {
        assertEquals(15 * 60_000L, UPDATE_CHECK_INTERVAL_MS)
    }
}
