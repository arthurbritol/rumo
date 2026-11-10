package app.hunter.app.social

import app.hunter.social.LiveLocation
import app.hunter.social.LocationPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirestoreSocialStoreMappingTest {

    @Test
    fun `liveLocationToMap contem todas as chaves esperadas com valores validos`() {
        val loc = LiveLocation(
            latitude = -23.55052,
            longitude = -46.633308,
            bearing = 180.5f,
            speedKmh = 95.0f,
            updatedAtMs = 1700000000000L,
        )
        val map = liveLocationToMap(loc)
        assertEquals(
            setOf("latitude", "longitude", "bearing", "speedKmh", "updatedAtMs"),
            map.keys,
        )
        assertEquals(-23.55052, map["latitude"])
        assertEquals(-46.633308, map["longitude"])
        assertEquals(180.5f, map["bearing"])
        assertEquals(95.0f, map["speedKmh"])
        assertEquals(1700000000000L, map["updatedAtMs"])
    }

    @Test
    fun `liveLocationToMap lida com bearing e speedKmh nulos`() {
        val loc = LiveLocation(
            latitude = -23.55052,
            longitude = -46.633308,
            bearing = null,
            speedKmh = null,
            updatedAtMs = 1700000000000L,
        )
        val map = liveLocationToMap(loc)
        assertEquals(
            setOf("latitude", "longitude", "bearing", "speedKmh", "updatedAtMs"),
            map.keys,
        )
        assertNull(map["bearing"])
        assertNull(map["speedKmh"])
    }

    @Test
    fun `liveLocationToMap limita valores fora das faixas validas`() {
        val loc = LiveLocation(
            latitude = 100.0,
            longitude = -200.0,
            bearing = 450.0f,
            speedKmh = -5.0f,
            updatedAtMs = Long.MAX_VALUE,
        )
        val map = liveLocationToMap(loc)
        assertEquals(90.0, map["latitude"])
        assertEquals(-180.0, map["longitude"])
        assertEquals(360.0f, map["bearing"])
        assertEquals(0.0f, map["speedKmh"])
        assertEquals(99_999_999_999_999L, map["updatedAtMs"])
    }

    @Test
    fun `mapToLiveLocation converte mapa completo com seguranca de tipos`() {
        val map = mapOf<String, Any?>(
            "latitude" to -23.55052,
            "longitude" to -46.633308,
            "bearing" to 180.5,
            "speedKmh" to 95.0,
            "updatedAtMs" to 1700000000000L,
        )
        val loc = mapToLiveLocation(map)
        assertNotNull(loc)
        assertEquals(-23.55052, loc.latitude)
        assertEquals(-46.633308, loc.longitude)
        assertEquals(180.5f, loc.bearing)
        assertEquals(95.0f, loc.speedKmh)
        assertEquals(1700000000000L, loc.updatedAtMs)
    }

    @Test
    fun `mapToLiveLocation converte numeros inteiros e floats corretamente`() {
        val map = mapOf<String, Any?>(
            "latitude" to -23,
            "longitude" to -46,
            "bearing" to 90,
            "speedKmh" to 60,
            "updatedAtMs" to 1700000000000L,
        )
        val loc = mapToLiveLocation(map)
        assertNotNull(loc)
        assertEquals(-23.0, loc.latitude)
        assertEquals(-46.0, loc.longitude)
        assertEquals(90.0f, loc.bearing)
        assertEquals(60.0f, loc.speedKmh)
    }

    @Test
    fun `mapToLiveLocation com opcionais ausentes ou nulos`() {
        val map = mapOf<String, Any?>(
            "latitude" to -23.55052,
            "longitude" to -46.633308,
            "updatedAtMs" to 1700000000000L,
        )
        val loc = mapToLiveLocation(map)
        assertNotNull(loc)
        assertNull(loc.bearing)
        assertNull(loc.speedKmh)
    }

    @Test
    fun `mapToLiveLocation retorna nulo para mapas invalidos ou incompletos`() {
        assertNull(mapToLiveLocation(emptyMap()))
        assertNull(mapToLiveLocation(mapOf("latitude" to -23.55, "updatedAtMs" to 1700000000000L)))
        assertNull(mapToLiveLocation(mapOf("longitude" to -46.63, "updatedAtMs" to 1700000000000L)))
        assertNull(mapToLiveLocation(mapOf("latitude" to -23.55, "longitude" to -46.63)))
        assertNull(mapToLiveLocation(mapOf("latitude" to 100.0, "longitude" to -46.63, "updatedAtMs" to 1700000000000L)))
        assertNull(mapToLiveLocation(mapOf("latitude" to -23.55, "longitude" to 200.0, "updatedAtMs" to 1700000000000L)))
    }

    @Test
    fun `locationPermissionToMap gera mapa correto e valida restricoes`() {
        val map = locationPermissionToMap("user_a", "user_b", 1700000000000L)
        assertEquals(
            mapOf("de" to "user_a", "para" to "user_b", "criadoEm" to 1700000000000L),
            map,
        )
        val parsed = mapToLocationPermission(map)
        assertEquals(LocationPermission("user_a", "user_b", 1700000000000L), parsed)

        assertFailsWith<IllegalArgumentException> {
            locationPermissionToMap("user_a", "user_a", 1700000000000L)
        }
        assertFailsWith<IllegalArgumentException> {
            locationPermissionToMap("user_a", "x".repeat(129), 1700000000000L)
        }
    }

    @Test
    fun `locationPermission docId combina de e para`() {
        assertEquals("user_a_user_b", LocationPermission.docId("user_a", "user_b"))
    }
}
