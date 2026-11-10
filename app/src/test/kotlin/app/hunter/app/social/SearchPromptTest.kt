package app.hunter.app.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchPromptTest {
    @Test
    fun `pergunta de busca só uma vez e só logado`() {
        assertTrue(SearchPrompt.shouldAsk(askedBefore = false, signedIn = true))
        assertFalse(SearchPrompt.shouldAsk(askedBefore = true, signedIn = true))
        assertFalse(SearchPrompt.shouldAsk(askedBefore = false, signedIn = false))
        assertFalse(SearchPrompt.shouldAsk(askedBefore = true, signedIn = false))
    }

    private class MemoryPrefs(var asked: Boolean = false, var searchable: Boolean = true) : SearchPrefs {
        override fun asked() = asked
        override fun searchable() = searchable
        override fun set(searchable: Boolean) {
            this.searchable = searchable
            asked = true
        }
    }

    @Test
    fun `responder marca como perguntado e guarda a escolha`() {
        val p = MemoryPrefs()
        assertTrue(p.searchable()) // padrão ligado
        assertFalse(p.asked())
        p.set(false)
        assertTrue(p.asked())
        assertEquals(false, p.searchable())
        assertFalse(SearchPrompt.shouldAsk(p.asked(), signedIn = true))
    }
}
