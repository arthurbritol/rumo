package app.hunter.app.nearby

/** Espera entre tentativas de reconexão: [baseMs], dobrando a cada tentativa até [maxMs]. */
class Backoff(val baseMs: Long = 1_000, val maxMs: Long = 30_000) {
    fun nextDelayMs(attempt: Int): Long {
        var d = baseMs
        repeat(attempt.coerceIn(0, 40)) {
            d = (d * 2).coerceAtMost(maxMs)
        }
        return d.coerceAtMost(maxMs)
    }
}
