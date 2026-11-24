package app.hunter.nav.perigos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PardalCsvTest {
    private fun recurso(nome: String) = javaClass.getResource("/pardal/$nome")!!.readText()

    @Test
    fun `radares reais - fixo com limite e sentido, fixo sem limite nem sentido`() {
        val r = PardalCsv.radares(recurso("radares.csv"))
        assertEquals(2, r.size)
        val a = r[0]
        assertEquals("pFIXED:-25.056361,-48.555666", a.id)
        assertEquals(Perigo.RADAR, a.tipo)
        assertEquals(60, a.limiteKmh)
        assertEquals(184.0, a.direcaoGraus)
        assertTrue(a.sentido)
        val b = r[1]
        assertEquals(Perigo.RADAR, b.tipo)
        assertNull(b.limiteKmh)
        assertNull(b.direcaoGraus)
        assertFalse(b.sentido)
    }

    @Test
    fun `lombada real TABLE vira LOMBADA e ponto de atencao real vira travessia de trem`() {
        val l = PardalCsv.lombadas(recurso("lombadas.csv")).single()
        assertEquals(Perigo.LOMBADA, l.tipo)
        assertEquals("pTABLE:-24.749155,-47.551462", l.id)
        assertFalse(l.sentido)
        val p = PardalCsv.pontosAtencao(recurso("pontos_atencao.csv")).single()
        assertEquals(Perigo.TRAVESSIA_FERROVIARIA, p.tipo)
        assertEquals(-24.706340, p.lat)
        assertEquals(-48.067195, p.lon)
    }

    @Test
    fun `mapeamento das lombadas`() {
        val csv = "lat,lng,kind\n" + listOf("BUMP", "HUMP", "TABLE", "CUSHION", "UNSPECIFIED", "RUMBLE_STRIP")
            .mapIndexed { i, k -> "-24.7$i,-47.5,$k" }.joinToString("\n")
        assertEquals(
            listOf(Perigo.LOMBADA, Perigo.LOMBADA, Perigo.LOMBADA, Perigo.LOMBADA, Perigo.LOMBADA, Perigo.FAIXA_SONORA),
            PardalCsv.lombadas(csv).map { it.tipo },
        )
    }

    @Test
    fun `mapeamento dos pontos de atencao e kinds ignorados`() {
        val csv = "lat,lng,kind\n" + listOf(
            "LEVEL_CROSSING", "CURVE", "SCHOOL_ZONE", "ANIMAL_CROSSING", "DANGEROUS_JUNCTION", "FALLING_ROCKS", "HAZARD", "SPEED_DISPLAY",
        ).mapIndexed { i, k -> "-24.7$i,-47.5,$k" }.joinToString("\n")
        assertEquals(
            listOf(
                Perigo.TRAVESSIA_FERROVIARIA, Perigo.CURVA_PERIGOSA, Perigo.ZONA_ESCOLAR,
                Perigo.TRAVESSIA_DE_ANIMAIS, Perigo.CRUZAMENTO_PERIGOSO, Perigo.QUEDA_DE_PEDRAS,
            ),
            PardalCsv.pontosAtencao(csv).map { it.tipo },
        )
        assertEquals(CategoriaPerigo.ATENCAO, Perigo.CURVA_PERIGOSA.categoria)
        assertEquals(CategoriaPerigo.RADARES, Perigo.RADAR_DE_TRECHO.categoria)
        assertEquals("Radar de trecho", Perigo.RADAR_DE_TRECHO.nome)
        assertFalse(CategoriaPerigo.ATENCAO.ligadaPorPadrao)
        assertEquals(listOf(300), Perigo.CURVA_PERIGOSA.limiaresM.toList())
        assertEquals(listOf(800, 300), Perigo.RADAR_DE_TRECHO.limiaresM.toList())
        assertFalse(Perigo.RADAR_DE_TRECHO.curtoNoUltimo)
    }

    @Test
    fun `radar de trecho guarda o fim e semaforo com camera`() {
        val csv = """
            lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg
            -23.100000,-46.200000,SECTION,80,PRF,1,-23.110000,-46.210000,90
            -23.300000,-46.400000,RED_LIGHT,,OSM,1,,,
            -23.500000,-46.600000,SPEED_DISPLAY,,OSM,1,,,
        """.trimIndent()
        val r = PardalCsv.radares(csv)
        assertEquals(2, r.size)
        val t = r[0]
        assertEquals(Perigo.RADAR_DE_TRECHO, t.tipo)
        assertEquals(80, t.limiteKmh)
        assertEquals(-23.1, t.lat)
        assertEquals(-23.11, t.fimLat)
        assertEquals(-46.21, t.fimLon)
        assertEquals(Perigo.SEMAFORO_COM_CAMERA, r[1].tipo)
        assertNull(r[1].fimLat)
    }

    @Test
    fun `cabecalho em outra ordem e com coluna nova`() {
        val csv = "direction_deg,extra,kind,lng,lat,active,limit_kmh\n90,x,FIXED,-48.5,-25.0,1,50\n"
        val p = PardalCsv.radares(csv).single()
        assertEquals(-25.0, p.lat)
        assertEquals(-48.5, p.lon)
        assertEquals(50, p.limiteKmh)
        assertEquals(90.0, p.direcaoGraus)
    }

    @Test
    fun `linhas ruins sao ignoradas e as boas ficam`() {
        val csv = """
            lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg
            abc,-48.5,FIXED,60,OSM,1,,,
            -25.0
            -25.0,-48.5,FIXED,60,OSM,1,,,
            -95.0,-48.5,FIXED,60,OSM,1,,,

            -26.0,-48.5,FIXED
        """.trimIndent()
        assertEquals(listOf(-25.0, -26.0), PardalCsv.radares(csv).map { it.lat })
    }

    @Test
    fun `active zero e descartado`() {
        val csv = "lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg\n" +
            "-25.0,-48.5,FIXED,60,OSM,0,,,\n-25.1,-48.5,FIXED,60,OSM,1,,,\n"
        assertEquals(listOf(-25.1), PardalCsv.radares(csv).map { it.lat })
    }

    @Test
    fun `kind desconhecido e ignorado`() {
        val csv = "lat,lng,kind\n-25.0,-48.5,FOO\n-25.1,-48.5,BUMP\n"
        assertEquals(1, PardalCsv.lombadas(csv).size)
        assertEquals(0, PardalCsv.pontosAtencao(csv).size)
    }

    @Test
    fun `cabecalho sem coluna obrigatoria, vazio, BOM e CRLF`() {
        assertTrue(PardalCsv.lombadas("lat,kind\n-25.0,BUMP\n").isEmpty())
        assertTrue(PardalCsv.lombadas("").isEmpty())
        assertEquals(1, PardalCsv.lombadas("﻿lat,lng,kind\r\n-25.0,-48.5,BUMP\r\n").size)
    }

    @Test
    fun `id estavel com seis casas e sem repeticao`() {
        val r = PardalCsv.lombadas("lat,lng,kind\n-25.1,-48.5,BUMP\n-25.100000,-48.500000,BUMP\n")
        assertEquals(listOf("pBUMP:-25.100000,-48.500000"), r.map { it.id })
    }
}
