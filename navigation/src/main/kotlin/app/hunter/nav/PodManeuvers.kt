package app.hunter.nav

import app.hunter.pod.BottomInfo
import app.hunter.pod.PodIcon
import app.hunter.pod.PodPackets
import java.time.LocalDateTime

/**
 * Tradução das manobras do Valhalla para os ícones do Pod (tabela de ícones: docs/PROTOCOL.md §6 do
 * WazeTripper; a tradução do Waze está em §7 e foi usada como guia para keep/exit/U-turn).
 *
 * Códigos do Valhalla: 1–3 partida, 4–6 destino, 7 vira, 8 continue, 9 leve à direita, 10 direita,
 * 11 forte à direita, 12/13 retorno (dir./esq.), 14 forte à esquerda, 15 esquerda, 16 leve à esquerda,
 * 17 rampa reta, 18/19 rampa dir./esq., 20/21 saída dir./esq., 22 siga reto, 23/24 mantenha dir./esq.,
 * 25/37/38 entrada na via, 26 entra na rotatória, 27 sai da rotatória, 28/29 balsa.
 * Confiança: ícones de reta, keep e exit foram vistos no Pod; os demais são "prováveis" (ver PROTOCOL.md).
 */
object PodManeuvers {

    /** Ícone grande (byte [2]) da manobra. */
    fun icon(m: Maneuver): Int = when (m.type) {
        1, 2, 3 -> PodIcon.DEPART
        4 -> PodIcon.DESTINATION
        5 -> PodIcon.DESTINATION_RIGHT
        6 -> PodIcon.DESTINATION_LEFT
        7, 8, 17, 22, 27, 29 -> PodIcon.STRAIGHT
        9 -> PodIcon.SLIGHT_RIGHT
        10 -> PodIcon.TURN_RIGHT
        11 -> PodIcon.SHARP_RIGHT
        12 -> PodIcon.U_TURN_CLOCKWISE
        13 -> PodIcon.U_TURN_COUNTER_CLOCKWISE
        14 -> PodIcon.SHARP_LEFT
        15 -> PodIcon.TURN_LEFT
        16 -> PodIcon.SLIGHT_LEFT
        18 -> PodIcon.ON_RAMP_RIGHT
        19 -> PodIcon.ON_RAMP_LEFT
        20 -> PodIcon.OFF_RAMP_SLIGHT_RIGHT
        21 -> PodIcon.OFF_RAMP_SLIGHT_LEFT
        23 -> PodIcon.KEEP_RIGHT_SOFT
        24 -> PodIcon.KEEP_LEFT_SOFT
        25 -> PodIcon.MERGE
        37 -> PodIcon.MERGE_RIGHT
        38 -> PodIcon.MERGE_LEFT
        26 -> PodIcon.roundaboutRightHand(m.roundaboutExit ?: 1)
        28 -> PodIcon.FERRY_BOAT
        else -> PodIcon.STRAIGHT
    }

    /** Seta pequena (byte [7]) da manobra seguinte; keep/exit usam os ícones genéricos de faixa (2B/2C). */
    fun nextIcon(m: Maneuver?): Int = when {
        m == null || m.isDestination -> PodIcon.NONE
        m.type == 20 || m.type == 23 -> PodIcon.KEEP_LANE_RIGHT
        m.type == 21 || m.type == 24 -> PodIcon.KEEP_LANE_LEFT
        else -> icon(m)
    }
}

/** O que mostrar na linha inferior do Pod durante a navegação. */
enum class PodBottomMode { ARRIVAL_TIME, TIME_REMAINING, TOTAL_DISTANCE }

object PodNavPackets {
    /** Pacote de navegação para o estado atual. [now] só é usado no modo hora de chegada. */
    fun navigation(
        p: NavProgress,
        night: Boolean,
        mode: PodBottomMode = PodBottomMode.ARRIVAL_TIME,
        is12h: Boolean = false,
        now: LocalDateTime = LocalDateTime.now(),
    ): ByteArray {
        val etaMin = Math.ceil(p.remainingS / 60.0).toInt()
        val bottom: BottomInfo = when (mode) {
            PodBottomMode.ARRIVAL_TIME -> BottomInfo.ArrivalTime.from(now, etaMin, is12h)
            PodBottomMode.TIME_REMAINING -> BottomInfo.TimeRemaining(etaMin)
            PodBottomMode.TOTAL_DISTANCE -> BottomInfo.TotalDistance(p.remainingM.toInt())
        }
        return PodPackets.navigation(
            maneuver = PodManeuvers.icon(p.upcoming),
            distanceM = p.distanceToUpcomingM.toInt(),
            nextManeuver = PodManeuvers.nextIcon(p.then),
            bottom = bottom,
            night = night,
        )
    }
}
