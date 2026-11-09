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

/** Aceita vírgula ou ponto decimal. */
fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
