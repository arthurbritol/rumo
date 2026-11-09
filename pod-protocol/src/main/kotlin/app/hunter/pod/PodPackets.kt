package app.hunter.pod

import java.time.LocalDateTime

/**
 * Montagem dos pacotes de 20 bytes do Tripper Pod. Reimplementação em Kotlin do formato descrito em
 * ref/wazetripper/docs/PROTOCOL.md e de TripperProtocol.java (WazeTripper, Apache-2.0; ver NOTICE.md).
 *
 * Layout do pacote de navegação (CMD_NAVIGATE = 0x10 0x11):
 *   [0]=0x10 [1]=0x11 [2]=ícone da manobra [3-4]=distância (big-endian) [5]=unidade (1=m, 2=km×10)
 *   [6]=intensidade/noturno [7]=ícone da próxima manobra (FF = nenhum) [8-9]=FF FF (radar experimental)
 *   [10]=0x41 [11-13]=linha inferior (FF FF FF = sem dado) [14-17]=00 [18-19]=CRC.
 */
object PodPackets {

    private const val CMD_NAVIGATE_0 = 0x10
    private const val CMD_NAVIGATE_1 = 0x11
    private const val CMD_KEEPALIVE = 0x40
    private const val CMD_PING = 0x03
    private const val CMD_HANDSHAKE = 0x21
    private const val CMD_PIN = 0x20
    private const val CMD_SET_TIME = 0x50

    private inline fun frame(fill: (ByteArray) -> Unit): ByteArray =
        PodPacket.withCrc(ByteArray(PodPacket.PAYLOAD_SIZE).also(fill))

    private operator fun ByteArray.set(i: Int, v: Int) {
        this[i] = v.toByte()
    }

    // ---------- Pacotes fixos ----------

    /**
     * Handshake: [newDevice] = true pede ao Pod que mostre o PIN (0x21 01); false retoma um
     * dispositivo já pareado (0x21 00).
     */
    fun handshake(newDevice: Boolean): ByteArray = frame {
        it[0] = CMD_HANDSHAKE
        it[1] = if (newDevice) 1 else 0
    }

    /** Keepalive de firmware (0x03). */
    fun ping(): ByteArray = frame { it[0] = CMD_PING }

    /** Confirmação do PIN (0x20): [1..6] = ASCII do PIN (até 6 caracteres; o excedente é truncado). */
    fun pin(pin: String): ByteArray = frame {
        it[0] = CMD_PIN
        val ascii = pin.toByteArray(Charsets.UTF_8)
        System.arraycopy(ascii, 0, it, 1, minOf(ascii.size, 6))
    }

    /**
     * SET_TIME (0x50): [1]=hora, [2]=minuto. 24h: hora crua. 12h: hora (1–12) com 0x40 somado em AM
     * (nada em PM). Esquema diferente do ETA na linha inferior (ver [BottomInfo.ArrivalTime]).
     */
    fun setTime(hour24: Int, minute: Int, is12h: Boolean): ByteArray = frame {
        it[0] = CMD_SET_TIME
        it[1] = if (!is12h) hour24 else {
            val hour12 = (hour24 % 12).let { h -> if (h == 0) 12 else h }
            hour12 or (if (hour24 < 12) 0x40 else 0x00)
        }
        it[2] = minute
    }

    // ---------- Telas ----------

    /** Bússola sem rota: reaproveita CMD_NAVIGATE com a tela 0x41 e [14]=direção. Reenviar a cada ~2 s. */
    fun compass(direction: CompassDirection, night: Boolean): ByteArray = frame {
        it[0] = CMD_NAVIGATE_0
        it[1] = CMD_NAVIGATE_1
        it[2] = PodIcon.COMPASS_SCREEN
        it[6] = if (night) 1 else 0
        it[7] = 0xFF
        it[11] = 0xFF
        it[12] = 0xFF
        it[13] = 0xFF
        it[14] = direction.code
    }

    /** Bússola a partir do rumo em graus (0 = norte). */
    fun compass(bearingDeg: Float, night: Boolean): ByteArray =
        compass(CompassDirection.fromBearing(bearingDeg), night)

    /**
     * Navegação de alto nível. Calcula a intensidade do byte [6] a partir da distância
     * ([distanceIntensity]) e soma o flag noturno.
     */
    fun navigation(
        maneuver: Int,
        distanceM: Int,
        nextManeuver: Int = PodIcon.NONE,
        bottom: BottomInfo = BottomInfo.None,
        night: Boolean = false,
    ): ByteArray = navigationRaw(
        maneuver, distanceM, nextManeuver, nextDistanceM = -1, bottom = bottom,
        byte6 = distanceIntensity(distanceM, night),
    )

    /**
     * Navegação com todos os bytes explícitos (usado para reproduzir capturas reais).
     * [nextDistanceM] < 0 vira FF FF em [8-9]; só o "radar experimental" usa esse campo (não verificado).
     */
    fun navigationRaw(
        maneuver: Int,
        distanceM: Int,
        nextManeuver: Int,
        nextDistanceM: Int,
        bottom: BottomInfo,
        byte6: Int,
    ): ByteArray = frame {
        val d = encodeDistance(distanceM)
        it[0] = CMD_NAVIGATE_0
        it[1] = CMD_NAVIGATE_1
        it[2] = maneuver
        it[3] = d.high
        it[4] = d.low
        it[5] = d.unit
        it[6] = byte6
        it[7] = nextManeuver
        val nd = if (nextDistanceM < 0) 0xFFFF else minOf(nextDistanceM, 0xFFFE)
        it[8] = nd shr 8
        it[9] = nd and 0xFF
        it[10] = 0x41
        val b = bottom.bytes()
        it[11] = b[0]
        it[12] = b[1]
        it[13] = b[2]
    }

    /** Tela "rota iniciada" (~2 s ao começar a navegação): ícone grande 0x45, distância 0, rodapé FF. */
    fun routeStarted(): ByteArray = navigationRaw(
        PodIcon.ROUTE_STARTED, 0, PodIcon.NONE, -1, BottomInfo.None, byte6 = 0,
    )

    /** Tela só com o ícone [screen] e o flag noturno (ex.: recalculando = [PodIcon.RE_ROUTING]). */
    fun screen(screen: Int, night: Boolean): ByteArray = frame {
        it[0] = CMD_NAVIGATE_0
        it[1] = CMD_NAVIGATE_1
        it[2] = screen
        it[6] = if (night) 1 else 0
    }

    /** Recalculando/carregando (desenha o ícone de re-roteamento). */
    fun recalculating(night: Boolean): ByteArray = screen(PodIcon.RE_ROUTING, night)

    /** Ícone de ligação: CMD_KEEPALIVE (0x40) com sub-byte 0x05; reenviar enquanto durar a chamada. */
    fun callIcon(): ByteArray = frame {
        it[0] = CMD_KEEPALIVE
        it[1] = 0x05
    }

    // ---------- Codificação ----------

    class EncodedDistance(val high: Int, val low: Int, val unit: Int) {
        override fun toString() = "%02X %02X %02X".format(high, low, unit)
    }

    /** Até 999 m vai em metros (unidade 1); acima, metros/100 ("km × 10", unidade 2). Limitado a 0xFFFF. */
    fun encodeDistance(meters: Int): EncodedDistance {
        val (value, unit) = if (meters <= 999) meters to 1 else (meters / 100) to 2
        val v = value.coerceIn(0, 0xFFFF)
        return EncodedDistance(v shr 8, v and 0xFF, unit)
    }

    /** Byte [6] por faixa de distância até a manobra (+1 à noite). Tabela de PROTOCOL.md §4. */
    fun distanceIntensity(meters: Int, night: Boolean): Int {
        val base = when {
            meters <= 10 -> 0x50
            meters <= 20 -> 0x40
            meters <= 45 -> 0x30
            meters <= 70 -> 0x20
            meters <= 95 -> 0x10
            else -> 0x00
        }
        return base + if (night) 1 else 0
    }
}

/** Oito setores da bússola, com o byte que o Pod espera (tabela não linear). */
enum class CompassDirection(val code: Int) {
    N(0x10), NE(0x50), E(0x20), SE(0x70), S(0x40), SW(0x80), W(0x30), NW(0x60);

    companion object {
        /** Rumo em graus (0 = norte) → setor de 45° com deslocamento de 22,5°. */
        fun fromBearing(bearingDeg: Float): CompassDirection {
            val normalized = ((bearingDeg % 360f) + 360f) % 360f
            val sector = (((normalized + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
            return entries[sector]
        }
    }
}

/** O que mostrar na linha inferior da tela de navegação (bytes [11-13]). */
sealed interface BottomInfo {
    fun bytes(): IntArray

    /** Sem dado: FF FF FF. */
    data object None : BottomInfo {
        override fun bytes() = intArrayOf(0xFF, 0xFF, 0xFF)
    }

    /** Distância total restante, mesma codificação da distância da manobra. */
    data class TotalDistance(val meters: Int) : BottomInfo {
        override fun bytes(): IntArray {
            if (meters < 0) return None.bytes()
            val d = PodPackets.encodeDistance(meters)
            return intArrayOf(d.high, d.low, d.unit)
        }
    }

    /** Tempo restante: [horas, minutos, 0]; ≤ 0 min vira "sem dado". */
    data class TimeRemaining(val minutes: Int) : BottomInfo {
        override fun bytes(): IntArray =
            if (minutes <= 0) None.bytes() else intArrayOf(minOf(minutes / 60, 23), minOf(minutes % 60, 59), 0)
    }

    /**
     * Hora de chegada. 24h: [hora, minuto, 0]. 12h: [hora 0–11 | 0x40 (AM) ou 0x80 (PM), minuto, 0]
     * — esquema diferente do SET_TIME.
     */
    data class ArrivalTime(val hour24: Int, val minute: Int, val is12h: Boolean) : BottomInfo {
        override fun bytes(): IntArray =
            if (!is12h) intArrayOf(hour24, minute, 0)
            else intArrayOf((hour24 % 12) or (if (hour24 >= 12) 0x80 else 0x40), minute, 0)

        companion object {
            /** Chegada = [now] + [etaMinutes]; ETA ≤ 0 vira "sem dado". */
            fun from(now: LocalDateTime, etaMinutes: Int, is12h: Boolean): BottomInfo {
                if (etaMinutes <= 0) return None
                val t = now.plusMinutes(etaMinutes.toLong())
                return ArrivalTime(t.hour, t.minute, is12h)
            }
        }
    }
}
