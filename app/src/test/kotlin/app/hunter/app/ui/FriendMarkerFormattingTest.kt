package app.hunter.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FriendMarkerFormattingTest {
    private val nowMs = 1_700_000_000_000L

    @Test
    fun `tempo decorrido menor que 30s formata como agora`() {
        assertEquals("agora", formatFriendElapsed(updatedAtMs = nowMs, nowMs = nowMs))
        assertEquals("agora", formatFriendElapsed(updatedAtMs = nowMs - 10_000L, nowMs = nowMs))
        assertEquals("agora", formatFriendElapsed(updatedAtMs = nowMs - 29_999L, nowMs = nowMs))
    }

    @Test
    fun `tempo decorrido no futuro ou negativo formata como agora`() {
        assertEquals("agora", formatFriendElapsed(updatedAtMs = nowMs + 5_000L, nowMs = nowMs))
    }

    @Test
    fun `tempo decorrido entre 30s e menos de 2 minutos formata como ha 1 min`() {
        assertEquals("há 1 min", formatFriendElapsed(updatedAtMs = nowMs - 30_000L, nowMs = nowMs))
        assertEquals("há 1 min", formatFriendElapsed(updatedAtMs = nowMs - 60_000L, nowMs = nowMs))
        assertEquals("há 1 min", formatFriendElapsed(updatedAtMs = nowMs - 90_000L, nowMs = nowMs))
        assertEquals("há 1 min", formatFriendElapsed(updatedAtMs = nowMs - 119_999L, nowMs = nowMs))
    }

    @Test
    fun `tempo decorrido de 2 minutos formata como ha 2 min`() {
        assertEquals("há 2 min", formatFriendElapsed(updatedAtMs = nowMs - 120_000L, nowMs = nowMs))
    }

    @Test
    fun `tempo decorrido de 3 minutos formata como ha 3 min`() {
        assertEquals("há 3 min", formatFriendElapsed(updatedAtMs = nowMs - 180_000L, nowMs = nowMs))
        assertEquals("há 3 min", formatFriendElapsed(updatedAtMs = nowMs - 200_000L, nowMs = nowMs))
    }

    @Test
    fun `tempo decorrido de 5 minutos formata como ha 5 min`() {
        assertEquals("há 5 min", formatFriendElapsed(updatedAtMs = nowMs - 300_000L, nowMs = nowMs))
    }

    @Test
    fun `tempo decorrido maior que 5 minutos retorna null`() {
        assertNull(formatFriendElapsed(updatedAtMs = nowMs - 300_001L, nowMs = nowMs))
        assertNull(formatFriendElapsed(updatedAtMs = nowMs - 600_000L, nowMs = nowMs))
    }

    @Test
    fun `FriendMarker possui defaults corretos para bearing e timeAgoText`() {
        val marker = FriendMarker(
            uid = "u1",
            name = "Brito",
            lat = -8.05,
            lon = -34.9,
        )
        assertEquals("u1", marker.uid)
        assertEquals("Brito", marker.name)
        assertEquals(-8.05, marker.lat)
        assertEquals(-34.9, marker.lon)
        assertNull(marker.bearing)
        assertEquals("", marker.timeAgoText)
    }
}
