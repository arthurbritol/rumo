package app.hunter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.hunter.app.fuel.PriceSource
import app.hunter.app.fuel.ResolvedFuelStation

/**
 * Aparência de um posto no mapa: pino colorido da bandeira com uma letra (não usamos os logotipos oficiais) e, ao lado,
 * a etiqueta branca com o preço.
 */
enum class StationBrand(val key: String, val color: Int, val glyph: String, val glyphColor: Int) {
    SHELL("shell", 0xFFFFC72C.toInt(), "S", 0xFFDD1D21.toInt()),
    IPIRANGA("ipiranga", 0xFFFFCC00.toInt(), "i", 0xFF0A4DA2.toInt()),
    BR("br", 0xFF009B3A.toInt(), "BR", 0xFFFFDD00.toInt()),
    ALE("ale", 0xFFE4002B.toInt(), "A", 0xFFFFFFFF.toInt()),
    OTHER("other", 0xFFFF7A1A.toInt(), "", 0xFFFFFFFF.toInt()),
}

/** Bandeira de exibição a partir da bandeira da ANP ("RAIZEN", "VIBRA"...) ou do nome do posto no mapa. */
fun stationBrandOf(bandeira: String, nome: String): StationBrand {
    val text = " ${bandeira.lowercase()} ${nome.lowercase()} ".replace(Regex("[^a-zà-ú0-9]"), " ")
    return when {
        "shell" in text || "raizen" in text || "raízen" in text -> StationBrand.SHELL
        "ipiranga" in text -> StationBrand.IPIRANGA
        "petrobras" in text || "vibra" in text || Regex("\\sbr\\s").containsMatchIn(text) -> StationBrand.BR
        Regex("\\sale\\s").containsMatchIn(text) -> StationBrand.ALE
        else -> StationBrand.OTHER
    }
}

/** Texto da etiqueta de preço do marcador. */
fun stationMarkerPriceText(station: ResolvedFuelStation): String =
    if (station.fonte == PriceSource.SEM_PRECO) "sem preço" else formatStationMapPrice(station)

/** Nome da imagem no estilo do mapa: um por combinação bandeira + texto. */
fun stationMarkerKey(station: ResolvedFuelStation): String =
    "fs|${stationBrandOf(station.bandeira, station.nome).key}|${stationMarkerPriceText(station)}"

private const val PIN_D = 30f
private const val RING = 2.5f
private const val TAIL = 8f
private const val PILL_H = 24f
private const val PILL_PAD = 10f

/**
 * Marcador completo. A ponta do pino fica no centro da base da imagem (âncora "bottom"); a etiqueta fica à direita, e a
 * imagem tem o mesmo espaço vazio à esquerda para a ponta ficar centralizada.
 */
fun stationMarkerBitmap(density: Float, brand: StationBrand, priceText: String, hasPrice: Boolean): Bitmap {
    val d = density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 13f * d
        color = if (hasPrice) 0xFF0F172A.toInt() else 0xFF6B7280.toInt()
    }
    val pillW = textPaint.measureText(priceText) + 2 * PILL_PAD * d
    val half = PIN_D / 2f * d
    val side = half + pillW + 4f * d
    val w = (2f * side).toInt()
    val h = ((RING * 2 + PIN_D + TAIL) * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = (RING + PIN_D / 2f) * d
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // Etiqueta branca com o preço (começa por baixo do pino)
    val pill = RectF(cx + half * 0.55f, cy - PILL_H / 2f * d, cx + half * 0.55f + pillW, cy + PILL_H / 2f * d)
    p.style = Paint.Style.FILL
    p.color = 0xFFFFFFFF.toInt()
    c.drawRoundRect(pill, PILL_H / 2f * d, PILL_H / 2f * d, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 1f * d
    p.color = 0x33000000
    c.drawRoundRect(pill, PILL_H / 2f * d, PILL_H / 2f * d, p)
    val fm = textPaint.fontMetrics
    c.drawText(priceText, pill.left + half * 0.45f + PILL_PAD * d, cy - (fm.ascent + fm.descent) / 2f, textPaint)

    // Pino: gota com anel branco e a cor da bandeira
    val tip = (RING * 2 + PIN_D + TAIL) * d
    val drop = Path().apply {
        addCircle(cx, cy, half + RING * d, Path.Direction.CW)
        moveTo(cx - half * 0.62f, cy + half * 0.78f)
        lineTo(cx, tip)
        lineTo(cx + half * 0.62f, cy + half * 0.78f)
        close()
    }
    p.style = Paint.Style.FILL
    p.color = 0xFFFFFFFF.toInt()
    c.drawPath(drop, p)
    p.color = brand.color
    c.drawCircle(cx, cy, half, p)

    if (brand.glyph.isNotEmpty()) {
        val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = brand.glyphColor
            textAlign = Paint.Align.CENTER
            textSize = (if (brand.glyph.length > 1) 12f else 18f) * d
        }
        val gm = g.fontMetrics
        c.drawText(brand.glyph, cx, cy - (gm.ascent + gm.descent) / 2f, g)
    } else {
        // Bomba de combustível simples
        p.color = brand.glyphColor
        c.drawRect(cx - half * 0.36f, cy - half * 0.46f, cx + half * 0.16f, cy + half * 0.46f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * d
        val hose = Path().apply {
            moveTo(cx + half * 0.16f, cy - half * 0.2f)
            lineTo(cx + half * 0.38f, cy - half * 0.2f)
            lineTo(cx + half * 0.38f, cy + half * 0.34f)
        }
        c.drawPath(hose, p)
    }
    return bmp
}
