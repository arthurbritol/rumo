package app.hunter.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotaPorTipoTest {
    private val a = LatLon(-8.0, -34.0)
    private val b = LatLon(-8.1, -34.1)
    private fun req(p: RouteProfile, t: TipoDeRota) = ValhallaClient.buildRequest(a, b, p, 1, tipo = t)

    @Test fun `moto e o padrao e nao muda o pedido`() {
        assertEquals(ValhallaClient.buildRequest(a, b, RouteProfile.CURVES, 2), req(RouteProfile.CURVES, TipoDeRota.MOTO).replace("\"alternates\":1", "\"alternates\":2"))
        assertTrue(req(RouteProfile.FASTEST, TipoDeRota.MOTO).contains("\"costing\":\"motorcycle\""))
    }

    @Test fun `carro usa costing auto com highways tolls e ferry`() {
        val r = req(RouteProfile.FASTEST, TipoDeRota.CARRO)
        val json = Json.parseToJsonElement(r).jsonObject
        assertEquals("\"auto\"", json["costing"].toString())
        val opts = json["costing_options"]!!.jsonObject
        assertEquals(setOf("auto"), opts.keys)
        assertEquals(setOf("use_highways", "use_tolls", "use_ferry"), opts["auto"]!!.jsonObject.keys)
        assertFalse(r.contains("motorcycle"))
        assertFalse(r.contains("use_trails"))
        assertFalse(r.contains("exclude_unpaved"))
    }

    @Test fun `carro sem pedagio e evitar balsa ajustam as opcoes`() {
        assertTrue(req(RouteProfile.NO_TOLLS, TipoDeRota.CARRO).contains("\"use_tolls\":0.0"))
        assertTrue(req(RouteProfile.NO_FERRY, TipoDeRota.CARRO).contains("\"use_ferry\":0.0"))
        assertTrue(req(RouteProfile.FASTEST, TipoDeRota.CARRO).contains("\"use_ferry\":0.5"))
    }

    @Test fun `perfil que o tipo nao tem cai em mais rapida no pedido`() {
        assertEquals(req(RouteProfile.FASTEST, TipoDeRota.CARRO), req(RouteProfile.CURVES, TipoDeRota.CARRO))
        assertEquals(req(RouteProfile.FASTEST, TipoDeRota.MOTO), req(RouteProfile.NO_FERRY, TipoDeRota.MOTO))
    }

    @Test fun `perfis por tipo`() {
        assertEquals(listOf(RouteProfile.FASTEST, RouteProfile.CURVES, RouteProfile.NO_TOLLS, RouteProfile.NO_DIRT), RouteProfile.paraTipo(TipoDeRota.MOTO))
        assertEquals(listOf(RouteProfile.FASTEST, RouteProfile.NO_TOLLS, RouteProfile.NO_FERRY), RouteProfile.paraTipo(TipoDeRota.CARRO))
    }

    @Test fun `queda do perfil invalido e leitura segura do nome`() {
        assertEquals(RouteProfile.FASTEST, RouteProfile.valido(RouteProfile.CURVES, TipoDeRota.CARRO))
        assertEquals(RouteProfile.FASTEST, RouteProfile.valido(RouteProfile.NO_DIRT, TipoDeRota.CARRO))
        assertEquals(RouteProfile.NO_FERRY, RouteProfile.valido(RouteProfile.NO_FERRY, TipoDeRota.CARRO))
        assertEquals(RouteProfile.FASTEST, RouteProfile.valido(RouteProfile.NO_FERRY, TipoDeRota.MOTO))
        assertEquals(RouteProfile.NO_TOLLS, RouteProfile.deNome("NO_TOLLS"))
        assertEquals(RouteProfile.FASTEST, RouteProfile.deNome("INEXISTENTE"))
        assertEquals(RouteProfile.FASTEST, RouteProfile.deNome(null))
    }
}
