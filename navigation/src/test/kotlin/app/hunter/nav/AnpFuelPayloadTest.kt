package app.hunter.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnpFuelPayloadTest {

    @Test
    fun `deserializa payload completo com postos, cidades e estados`() {
        val json = """
        {
          "v": "1.0",
          "geradoEm": "2026-11-10T12:00:00Z",
          "cidades": {
            "sp_campinas": {
              "uf": "SP",
              "mun": "Campinas",
              "lat": -22.9099,
              "lon": -47.0626,
              "gcAvg": 5.89,
              "gcMin": 5.49,
              "gcMax": 6.19,
              "gaAvg": 6.09,
              "etAvg": 3.99,
              "dsAvg": 5.79,
              "ref": "2026-11-08"
            }
          },
          "estados": {
            "SP": {
              "uf": "SP",
              "lat": -23.55,
              "lon": -46.63,
              "gcAvg": 5.92,
              "etAvg": 4.02,
              "dsAvg": 5.82,
              "ref": "2026-11-08"
            }
          },
          "postos": [
            {
              "id": "posto_001",
              "nome": "Auto Posto Shell Portal",
              "bandeira": "Shell",
              "cityKey": "sp_campinas",
              "uf": "SP",
              "endereco": "Av. Brasil, 1500",
              "bairro": "Guanabara",
              "cep": "13073-010",
              "lat": -22.8900,
              "lon": -47.0700,
              "gc": 5.79,
              "ga": 5.99,
              "et": 3.95,
              "ds": 5.69,
              "data": "2026-11-08"
            }
          ]
        }
        """.trimIndent()

        val payload = AnpFuelPayload.fromJson(json)

        assertEquals("1.0", payload.v)
        assertEquals("2026-11-10T12:00:00Z", payload.geradoEm)
        assertEquals(1, payload.cidades.size)
        assertEquals(1, payload.estados.size)
        assertEquals(1, payload.postos.size)

        val city = payload.cidades["sp_campinas"]
        assertNotNull(city)
        assertEquals("SP", city.uf)
        assertEquals("Campinas", city.mun)
        assertEquals(5.89, city.gcAvg)
        assertEquals(5.49, city.gcMin)
        assertEquals(6.19, city.gcMax)
        assertEquals(6.09, city.gaAvg)
        assertNull(city.gaMin)
        assertEquals(3.99, city.etAvg)
        assertEquals("2026-11-08", city.ref)

        val state = payload.estados["SP"]
        assertNotNull(state)
        assertEquals("SP", state.uf)
        assertEquals(5.92, state.gcAvg)
        assertEquals(4.02, state.etAvg)
        assertEquals(5.82, state.dsAvg)

        val station = payload.postos[0]
        assertEquals("posto_001", station.id)
        assertEquals("Auto Posto Shell Portal", station.nome)
        assertEquals("Shell", station.bandeira)
        assertEquals("sp_campinas", station.cityKey)
        assertEquals(5.79, station.gc)
        assertEquals(5.99, station.ga)
        assertEquals(3.95, station.et)
        assertEquals(5.69, station.ds)
        assertEquals("2026-11-08", station.data)
    }

    @Test
    fun `ignora campos desconhecidos no json para tolerancia a evolucao`() {
        val json = """
        {
          "v": "1.1",
          "campoDesconhecidoExtra": "ignorar",
          "geradoEm": "2026-11-10T15:00:00Z",
          "cidades": {},
          "estados": {},
          "postos": [
            {
              "id": "p1",
              "nome": "Posto Ipiranga",
              "bandeira": "Ipiranga",
              "cityKey": "pe_recife",
              "uf": "PE",
              "endereco": "Av. Agamenon Magalhaes",
              "bairro": "Derby",
              "cep": "50000-000",
              "lat": -8.05,
              "lon": -34.90,
              "novoPrecoGnv": 4.50
            }
          ]
        }
        """.trimIndent()

        val payload = AnpFuelPayload.fromJson(json)
        assertEquals("1.1", payload.v)
        assertEquals(1, payload.postos.size)
        assertEquals("Posto Ipiranga", payload.postos[0].nome)
        assertNull(payload.postos[0].gc)
    }

    @Test
    fun `serializa e deserializa roundtrip mantendo integridade dos dados`() {
        val original = AnpFuelPayload(
            v = "1.0",
            geradoEm = "2026-11-10T10:00:00Z",
            cidades = mapOf(
                "pe_recife" to AnpCityDto(
                    uf = "PE",
                    mun = "Recife",
                    lat = -8.0476,
                    lon = -34.8770,
                    gcAvg = 5.85,
                    etAvg = 4.10,
                    ref = "2026-11-09"
                )
            ),
            estados = mapOf(
                "PE" to AnpStateDto(
                    uf = "PE",
                    lat = -8.05,
                    lon = -34.90,
                    gcAvg = 5.88,
                    etAvg = 4.12,
                    ref = "2026-11-09"
                )
            ),
            postos = listOf(
                AnpStationDto(
                    id = "posto_br_01",
                    nome = "Posto BR Derby",
                    bandeira = "Petrobras / Vibra",
                    cityKey = "pe_recife",
                    uf = "PE",
                    endereco = "Praça do Derby, 10",
                    bairro = "Derby",
                    cep = "52010-000",
                    lat = -8.0560,
                    lon = -34.9020,
                    gc = 5.84,
                    ga = 6.04,
                    et = 4.09,
                    ds = 5.75,
                    data = "2026-11-09"
                )
            )
        )

        val json = AnpFuelPayload.toJson(original)
        val deserialized = AnpFuelPayload.fromJson(json)

        assertEquals(original.v, deserialized.v)
        assertEquals(original.geradoEm, deserialized.geradoEm)
        assertEquals(original.cidades.size, deserialized.cidades.size)
        assertEquals(original.estados.size, deserialized.estados.size)
        assertEquals(original.postos.size, deserialized.postos.size)
        assertEquals(original.postos[0].nome, deserialized.postos[0].nome)
        assertEquals(original.postos[0].gc, deserialized.postos[0].gc)
    }
}
