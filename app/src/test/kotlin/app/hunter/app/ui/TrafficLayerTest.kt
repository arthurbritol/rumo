package app.hunter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficLayerTest {
    @Test
    fun `sem servidor configurado a camada nao existe`() {
        assertNull(TrafficLayer.template(base = "", token = "abc"))
        assertNull(TrafficLayer.template(base = "   ", token = ""))
    }

    @Test
    fun `modelo de endereco leva o token e ignora barra final`() {
        assertEquals(
            "https://rumo-traffic.x.workers.dev/flow/{z}/{x}/{y}.png?t=abc123",
            TrafficLayer.template(base = "https://rumo-traffic.x.workers.dev/", token = "abc123"),
        )
    }

    @Test
    fun `token com caracteres especiais e codificado e token vazio some`() {
        assertEquals("https://h/flow/{z}/{x}/{y}.png?t=a%2Bb%26c", TrafficLayer.template(base = "https://h", token = "a+b&c"))
        assertEquals("https://h/flow/{z}/{x}/{y}.png", TrafficLayer.template(base = "https://h", token = ""))
    }
}
