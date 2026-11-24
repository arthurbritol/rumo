package app.hunter.nav.perigos

import java.util.Locale

/**
 * Leitor dos CSVs do Pardal (radares, lombadas e pontos de atenção) -> [PerigoPonto]. Puro, sem rede e sem dependências.
 *
 * - As colunas são lidas PELO NOME do cabeçalho: ordem diferente e colunas novas são toleradas.
 * - CSV simples, sem aspas; linhas ruins (número de colunas diferente do cabeçalho, número inválido, kind desconhecido) são ignoradas.
 * - `active=0` é descartado em radares. Kinds sem tipo nosso (HAZARD, SPEED_DISPLAY...) são ignorados.
 * - Id estável: "p<kind>:<lat6>,<lng6>" (6 casas), sem repetir.
 */
object PardalCsv {
    private val RADAR = mapOf(
        "FIXED" to Perigo.RADAR,
        "SECTION" to Perigo.RADAR_DE_TRECHO,
        "RED_LIGHT" to Perigo.SEMAFORO_COM_CAMERA,
    )
    private val LOMBADA = mapOf(
        "BUMP" to Perigo.LOMBADA,
        "HUMP" to Perigo.LOMBADA,
        "TABLE" to Perigo.LOMBADA,
        "CUSHION" to Perigo.LOMBADA,
        "UNSPECIFIED" to Perigo.LOMBADA,
        "RUMBLE_STRIP" to Perigo.FAIXA_SONORA,
    )
    private val ATENCAO = mapOf(
        "LEVEL_CROSSING" to Perigo.TRAVESSIA_FERROVIARIA,
        "CURVE" to Perigo.CURVA_PERIGOSA,
        "SCHOOL_ZONE" to Perigo.ZONA_ESCOLAR,
        "ANIMAL_CROSSING" to Perigo.TRAVESSIA_DE_ANIMAIS,
        "DANGEROUS_JUNCTION" to Perigo.CRUZAMENTO_PERIGOSO,
        "FALLING_ROCKS" to Perigo.QUEDA_DE_PEDRAS,
    )

    /** `radares.csv`: `lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg`. */
    fun radares(csv: String): List<PerigoPonto> = ler(csv) { l ->
        if (l.campo("active")?.trim() == "0") return@ler null
        val kind = l.kind() ?: return@ler null
        val (lat, lon) = l.coord("lat", "lng") ?: return@ler null
        val tipo = RADAR[kind] ?: return@ler null
        val direcao = l.campo("direction_deg")?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?.let { ((it % 360.0) + 360.0) % 360.0 }
        val fim = if (tipo == Perigo.RADAR_DE_TRECHO) l.coord("end_lat", "end_lng") else null
        // Radar de trecho sem sentido na fonte: usa o rumo da entrada para o fim do trecho (vale a até 100º, ver PerigosAdiante.vale).
        val derivado = if (direcao == null && fim != null) rumo(lat, lon, fim.first, fim.second) else null
        val sentidoGraus = direcao ?: derivado
        PerigoPonto(
            id = id(kind, lat, lon),
            tipo = tipo,
            lat = lat,
            lon = lon,
            limiteKmh = PerigoTags.limiteKmh(l.campo("limit_kmh")),
            direcaoGraus = sentidoGraus,
            sentido = sentidoGraus != null,
            fimLat = fim?.first,
            fimLon = fim?.second,
        )
    }

    /** Rumo (0..360) de a para b; null se são o mesmo ponto. */
    private fun rumo(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double? {
        val dx = (bLon - aLon) * Math.cos(Math.toRadians((aLat + bLat) / 2))
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) return null
        return (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
    }

    /** `lombadas.csv`: `lat,lng,kind`. */
    fun lombadas(csv: String): List<PerigoPonto> = simples(csv, LOMBADA)

    /** `pontos_atencao.csv`: `lat,lng,kind`. */
    fun pontosAtencao(csv: String): List<PerigoPonto> = simples(csv, ATENCAO)

    private fun simples(csv: String, mapa: Map<String, Perigo>): List<PerigoPonto> = ler(csv) { l ->
        val kind = l.kind() ?: return@ler null
        val (lat, lon) = l.coord("lat", "lng") ?: return@ler null
        val tipo = mapa[kind] ?: return@ler null
        PerigoPonto(id(kind, lat, lon), tipo, lat, lon)
    }

    fun id(kind: String, lat: Double, lon: Double): String =
        "p$kind:${String.format(Locale.ROOT, "%.6f", lat)},${String.format(Locale.ROOT, "%.6f", lon)}"

    private class Linha(val cabecalho: Map<String, Int>, val campos: List<String>) {
        fun campo(nome: String): String? = cabecalho[nome]?.let { campos.getOrNull(it) }?.takeIf { it.isNotBlank() }
        fun kind(): String? = campo("kind")?.trim()?.uppercase()
        fun coord(nLat: String, nLon: String): Pair<Double, Double>? {
            val lat = campo(nLat)?.trim()?.toDoubleOrNull() ?: return null
            val lon = campo(nLon)?.trim()?.toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }
    }

    private fun ler(csv: String, linha: (Linha) -> PerigoPonto?): List<PerigoPonto> {
        val it = csv.removePrefix("﻿").lineSequence().iterator()
        if (!it.hasNext()) return emptyList()
        val cabecalho = HashMap<String, Int>()
        val nomes = it.next().split(',')
        val largura = nomes.size
        nomes.forEachIndexed { i, n -> cabecalho.putIfAbsent(n.trim().lowercase(), i) }
        if ("lat" !in cabecalho || "lng" !in cabecalho || "kind" !in cabecalho) return emptyList()
        val vistos = HashSet<String>()
        val out = ArrayList<PerigoPonto>()
        while (it.hasNext()) {
            val texto = it.next()
            if (texto.isBlank()) continue
            val campos = texto.split(',')
            if (campos.size != largura) continue // linha com colunas a mais ou a menos que o cabeçalho: descartada
            val p = runCatching { linha(Linha(cabecalho, campos)) }.getOrNull() ?: continue
            if (vistos.add(p.id)) out += p
        }
        return out
    }
}
