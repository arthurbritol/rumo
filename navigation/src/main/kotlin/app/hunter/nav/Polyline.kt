package app.hunter.nav

/** Decodificador de polyline codificada (Valhalla usa precisão 6; Google/OSRM padrão usa 5). */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        var factor = 1.0
        repeat(precision) { factor *= 10.0 }
        val out = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var shift = 0
            var result = 0L
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0L
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    /** Codifica (usado em testes e para gravar rotas). */
    fun encode(points: List<LatLon>, precision: Int = 6): String {
        var factor = 1.0
        repeat(precision) { factor *= 10.0 }
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append((((x and 0x1f) or 0x20) + 63).toInt().toChar())
                x = x shr 5
            }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val la = Math.round(p.lat * factor)
            val lo = Math.round(p.lon * factor)
            put(la - pLat)
            put(lo - pLon)
            pLat = la
            pLon = lo
        }
        return sb.toString()
    }
}
