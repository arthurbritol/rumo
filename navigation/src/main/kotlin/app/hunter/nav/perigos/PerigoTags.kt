package app.hunter.nav.perigos

/**
 * Tags do OpenStreetMap -> [Perigo]. Puro, sem rede.
 *
 * - `highway=speed_camera`, `enforcement=maxspeed|speed_camera` -> RADAR (guarda `maxspeed` numérico)
 * - `enforcement=traffic_signals|red_light` -> SEMAFORO_COM_CAMERA
 * - `traffic_calming=bump|hump|table|cushion|speed_table` -> LOMBADA (`rumble_strip` -> FAIXA_SONORA)
 * - `railway=level_crossing` -> TRAVESSIA_FERROVIARIA
 *
 * Câmeras e radares valem antes: um nó com `highway=speed_camera` e `traffic_calming=table` é radar.
 */
object PerigoTags {
    private val CALMING_LOMBADA = setOf("bump", "hump", "table", "cushion", "speed_table")

    fun tipoDeTags(tags: Map<String, String>): Perigo? {
        val enforcement = tags["enforcement"]?.split(';')?.map { it.trim() }.orEmpty()
        return when {
            tags["highway"] == "speed_camera" -> Perigo.RADAR
            "maxspeed" in enforcement || "speed_camera" in enforcement -> Perigo.RADAR
            "traffic_signals" in enforcement || "red_light" in enforcement -> Perigo.SEMAFORO_COM_CAMERA
            tags["traffic_calming"]?.trim() in CALMING_LOMBADA -> Perigo.LOMBADA
            tags["traffic_calming"]?.trim() == "rumble_strip" -> Perigo.FAIXA_SONORA
            tags["railway"] == "level_crossing" -> Perigo.TRAVESSIA_FERROVIARIA
            else -> null
        }
    }

    /** `maxspeed`: "60", "60 km/h", "40 mph" (convertido). "none", "signals", "BR:urban"... viram nulo. */
    fun limiteKmh(valor: String?): Int? {
        val s = valor?.trim()?.lowercase() ?: return null
        val m = Regex("""^(\d{1,3})(?:\s*(km/h|kmh|mph))?$""").matchEntire(s) ?: return null
        val n = m.groupValues[1].toInt()
        val kmh = if (m.groupValues[2] == "mph") Math.round(n * 1.609344).toInt() else n
        return kmh.takeIf { it in 5..200 }
    }

    private val PONTOS = mapOf(
        "n" to 0.0, "nne" to 22.5, "ne" to 45.0, "ene" to 67.5, "e" to 90.0, "ese" to 112.5, "se" to 135.0, "sse" to 157.5,
        "s" to 180.0, "ssw" to 202.5, "sw" to 225.0, "wsw" to 247.5, "w" to 270.0, "wnw" to 292.5, "nw" to 315.0, "nnw" to 337.5,
    )

    /** `direction`/`camera:direction`: graus ou ponto cardeal. "forward"/"backward"/"both" dependem da via e são ignorados. */
    fun direcaoGraus(tags: Map<String, String>): Double? {
        val v = (tags["direction"] ?: tags["camera:direction"] ?: tags["traffic_calming:direction"])?.trim()?.lowercase() ?: return null
        PONTOS[v]?.let { return it }
        val n = v.replace(',', '.').toDoubleOrNull() ?: return null
        return ((n % 360.0) + 360.0) % 360.0
    }
}
