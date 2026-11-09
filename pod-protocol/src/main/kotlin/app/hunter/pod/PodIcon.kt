package app.hunter.pod

/**
 * Códigos de ícone do Pod (bytes [2] e [7] do pacote de navegação), PROTOCOL.md §6.
 * Intervalos não testados no Pod: 0B–13, 23–2A, 32–3B (parcialmente rotatórias), 40–41, 43, 45–FE.
 */
object PodIcon {
    const val DESTINATION = 0x00
    const val DESTINATION_LEFT = 0x01
    const val DESTINATION_RIGHT = 0x02
    const val MERGE_RIGHT = 0x03
    const val MERGE_LEFT = 0x04
    const val FORK_RIGHT = 0x05
    const val FORK_LEFT = 0x06
    const val OFF_RAMP_RIGHT = 0x07
    const val OFF_RAMP_LEFT = 0x08
    const val STRAIGHT = 0x09
    const val ROUNDABOUT_CLOCKWISE = 0x0A
    const val TURN_LEFT = 0x14
    const val TURN_RIGHT = 0x15
    const val SHARP_LEFT = 0x16
    const val SHARP_RIGHT = 0x17
    const val SLIGHT_LEFT = 0x18
    const val SLIGHT_RIGHT = 0x19
    const val U_TURN_CLOCKWISE = 0x1A
    const val MERGE = 0x1B
    const val RE_ROUTING = 0x1C
    const val ON_RAMP_RIGHT = 0x1D
    const val ON_RAMP_LEFT = 0x1E
    const val ON_RAMP_SLIGHT_RIGHT = 0x1F
    const val ON_RAMP_SLIGHT_LEFT = 0x20
    const val ON_RAMP_SHARP_RIGHT = 0x21
    const val ON_RAMP_SHARP_LEFT = 0x22
    const val KEEP_RIGHT_SOFT = 0x27
    const val KEEP_LEFT_SOFT = 0x28
    const val KEEP_LANE_LEFT = 0x2B
    const val KEEP_LANE_RIGHT = 0x2C
    const val OFF_RAMP_SLIGHT_RIGHT = 0x2D
    const val OFF_RAMP_SLIGHT_LEFT = 0x2E
    const val OFF_RAMP_KEEP_RIGHT = 0x2F
    const val OFF_RAMP_KEEP_LEFT = 0x30
    const val ROUNDABOUT_COUNTER_CLOCKWISE = 0x31
    const val DEPART = 0x3C
    const val U_TURN_COUNTER_CLOCKWISE = 0x3D
    const val FERRY_BOAT = 0x3E
    const val FERRY_TRAIN = 0x3F
    const val NO_MOBILE_DATA = 0x42
    const val LOW_BATTERY = 0x44

    /** Ícone grande "rota iniciada": só funciona como ícone grande (byte [2]), não como seta pequena. */
    const val ROUTE_STARTED = 0x45

    /** Sem ícone (byte [7] sem próxima manobra). */
    const val NONE = 0xFF

    /** Tela de bússola (byte [2] = 0x41). */
    const val COMPASS_SCREEN = 0x41

    /**
     * Rotatória em mão direita: 0x32 + (saída − 1), saídas 1–9 (fora disso é limitado).
     * Mão esquerda (0x0B + ...) não implementada, como no WazeTripper.
     */
    fun roundaboutRightHand(exit: Int): Int = 0x32 + (exit.coerceIn(1, 9) - 1)
}
