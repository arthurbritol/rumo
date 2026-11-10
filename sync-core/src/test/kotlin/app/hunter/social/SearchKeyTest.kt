package app.hunter.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchKeyTest {
    @Test
    fun `remove acentos`() {
        assertEquals("joao silva", SearchKey.normalize("João Silva"))
        assertEquals("agata", SearchKey.normalize("Ágata"))
        assertEquals("izmir", SearchKey.normalize("İzmir"))
    }

    @Test
    fun `minusculas sem letras maiusculas ASCII`() {
        assertEquals("ana lima", SearchKey.normalize("ANA LiMa"))
    }

    @Test
    fun `apara e reduz espacos multiplos`() {
        assertEquals("ana maria lima", SearchKey.normalize("  Ana \t Maria   Lima  "))
    }

    @Test
    fun `limita a 60 caracteres`() {
        assertTrue(SearchKey.normalize("a".repeat(100)).length <= 60)
        assertEquals("", SearchKey.normalize("   "))
    }
}
