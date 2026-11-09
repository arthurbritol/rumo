package app.hunter.app.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun fmtKm(km: Double, decimals: Int = 1) = String.format(Locale.getDefault(), "%.${decimals}f km", km)
fun fmtKmh(kmh: Double) = String.format(Locale.getDefault(), "%.0f km/h", kmh)
fun fmtNum(v: Double, decimals: Int = 1) = String.format(Locale.getDefault(), "%.${decimals}f", v)
fun fmtMoney(v: Double) = String.format(Locale.getDefault(), "R$ %.2f", v)
fun fmtM(m: Double) = String.format(Locale.getDefault(), "%.0f m", m)

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

fun fmtDateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
fun fmtDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** 850 m / 1,2 km / 12 km (distância curta para a navegação). */
fun fmtDist(m: Double): String = when {
    m < 100 -> String.format(Locale.getDefault(), "%.0f m", (m / 10).coerceAtLeast(0.0).let { Math.round(it) * 10.0 })
    m < 1000 -> String.format(Locale.getDefault(), "%.0f m", Math.round(m / 50.0) * 50.0)
    m < 10_000 -> String.format(Locale.getDefault(), "%.1f km", m / 1000)
    else -> String.format(Locale.getDefault(), "%.0f km", m / 1000)
}

/** "27 min" ou "1 h 05". */
fun fmtEta(seconds: Double): String {
    val min = Math.ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    return if (min < 60) "$min min" else String.format(Locale.getDefault(), "%d h %02d", min / 60, min % 60)
}

/** Hora de chegada (agora + [seconds]) como "17:42". */
fun fmtArrival(seconds: Double): String {
    val t = java.time.LocalTime.now().plusSeconds(seconds.toLong())
    return String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
}

/** Aceita vírgula ou ponto decimal. */
fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
