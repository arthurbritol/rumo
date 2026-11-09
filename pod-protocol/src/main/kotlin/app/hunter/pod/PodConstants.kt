package app.hunter.pod

/**
 * Constantes de transporte e de tempo do Pod. Valores de ref/wazetripper/docs/PROTOCOL.md §1
 * (marcados como "Confirmed"/"Observed" lá; a Hunter 350 ainda não foi testada).
 */
object PodConstants {
    /** Nome BLE anunciado pelo Pod. */
    const val DEVICE_NAME = "RE_DISP"

    /** Serviço "Wireless UART" da NXP; o celular também hospeda um GATT server com o mesmo UUID. */
    const val SERVICE_UUID = "01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0"
    const val CHARACTERISTIC_UUID = "01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0"

    /** Espera entre a descoberta de serviços e a primeira escrita (escrever antes derruba o link). */
    const val POST_DISCOVERY_DELAY_MS = 200L

    /** Watchdog do firmware: o Pod derruba o link ~5 s sem pacote. */
    const val FIRMWARE_WATCHDOG_MS = 5_000L

    /** Enviar PING_FW após este tempo sem nenhuma escrita (checar a cada 1 s). */
    const val PING_INTERVAL_MS = 2_000L

    /** Telas dinâmicas (bússola, navegação) devem ser reenviadas neste intervalo mesmo sem mudança. */
    const val SCREEN_REFRESH_MS = 2_000L

    /** Janela em que o Pod anuncia após ligar a ignição (~30 s, observado). */
    const val ADVERTISING_WINDOW_MS = 30_000L

    // Sequência de handshake com dispositivo já pareado (PROTOCOL.md §3).
    const val RESUME_TO_SET_TIME_DELAY_MS = 200L
    const val SET_TIME_TO_PING_DELAY_MS = 150L
    const val PINGS_TO_CONNECTED_DELAY_MS = 300L
}
