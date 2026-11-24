package app.hunter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.hunter.nav.perigos.Perigo

/** Id da imagem do tipo no estilo do mapa (um por tipo, não por ponto). */
fun perigoIconId(tipo: Perigo): String = "perigo-" + tipo.name.lowercase()

private const val VERMELHO = 0xFFD32F2F.toInt()
private const val AMARELO = 0xFFFFC107.toInt()
private const val ESCURO = 0xFF1B2230.toInt()
private const val BRANCO = 0xFFFFFFFF.toInt()

/**
 * Ícone do perigo desenhado em código (mesmo padrão dos outros pinos do mapa): radar e semáforo com câmera num disco
 * branco de aro vermelho; lombada e faixa sonora num triângulo amarelo de advertência; travessia de trem num disco amarelo com X.
 * Quadrado de [sizePx] px, fundo transparente.
 */
fun perigoBitmap(tipo: Perigo, sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = sizePx.toFloat()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    when (tipo) {
        Perigo.RADAR, Perigo.RADAR_DE_TRECHO -> {
            disco(c, p, s, BRANCO, VERMELHO)
            p.style = Paint.Style.FILL; p.color = ESCURO
            c.drawRoundRect(RectF(s * 0.27f, s * 0.36f, s * 0.73f, s * 0.64f), s * 0.05f, s * 0.05f, p)
            c.drawRect(RectF(s * 0.40f, s * 0.29f, s * 0.60f, s * 0.38f), p)
            p.color = BRANCO; c.drawCircle(s * 0.5f, s * 0.5f, s * 0.085f, p)
            p.color = VERMELHO; c.drawCircle(s * 0.5f, s * 0.5f, s * 0.04f, p)
        }
        Perigo.SEMAFORO_COM_CAMERA -> {
            disco(c, p, s, BRANCO, VERMELHO)
            p.style = Paint.Style.FILL; p.color = ESCURO
            c.drawRoundRect(RectF(s * 0.38f, s * 0.20f, s * 0.62f, s * 0.80f), s * 0.07f, s * 0.07f, p)
            val cores = intArrayOf(VERMELHO, AMARELO, 0xFF2E7D32.toInt())
            cores.forEachIndexed { i, cor -> p.color = cor; c.drawCircle(s * 0.5f, s * (0.32f + i * 0.18f), s * 0.057f, p) }
        }
        Perigo.LOMBADA, Perigo.FAIXA_SONORA -> {
            triangulo(c, p, s)
            p.style = Paint.Style.FILL; p.color = ESCURO
            if (tipo == Perigo.LOMBADA) {
                // lombada vista de lado: meia-lua sobre o piso
                val arco = RectF(s * 0.28f, s * 0.50f, s * 0.72f, s * 0.86f)
                c.drawArc(arco, 180f, 180f, true, p)
            } else {
                for (i in 0..2) c.drawRect(RectF(s * 0.30f, s * (0.52f + i * 0.11f), s * 0.70f, s * (0.58f + i * 0.11f)), p)
            }
        }
        Perigo.CURVA_PERIGOSA, Perigo.ZONA_ESCOLAR, Perigo.TRAVESSIA_DE_ANIMAIS, Perigo.CRUZAMENTO_PERIGOSO, Perigo.QUEDA_DE_PEDRAS -> {
            // triângulo de advertência com "!" (um desenho por tipo fica para depois)
            triangulo(c, p, s)
            p.style = Paint.Style.FILL; p.color = ESCURO
            c.drawRoundRect(RectF(s * 0.46f, s * 0.32f, s * 0.54f, s * 0.64f), s * 0.03f, s * 0.03f, p)
            c.drawCircle(s * 0.5f, s * 0.74f, s * 0.045f, p)
        }
        Perigo.TRAVESSIA_FERROVIARIA -> {
            disco(c, p, s, AMARELO, ESCURO)
            p.style = Paint.Style.STROKE; p.color = ESCURO; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = s * 0.11f
            c.drawLine(s * 0.30f, s * 0.30f, s * 0.70f, s * 0.70f, p)
            c.drawLine(s * 0.70f, s * 0.30f, s * 0.30f, s * 0.70f, p)
        }
    }
    return bmp
}

private fun disco(c: Canvas, p: Paint, s: Float, fundo: Int, aro: Int) {
    p.style = Paint.Style.FILL; p.color = fundo
    c.drawCircle(s / 2, s / 2, s * 0.46f, p)
    p.style = Paint.Style.STROKE; p.color = aro; p.strokeWidth = s * 0.09f
    c.drawCircle(s / 2, s / 2, s * 0.42f, p)
}

private fun triangulo(c: Canvas, p: Paint, s: Float) {
    val tri = Path().apply {
        moveTo(s * 0.5f, s * 0.07f); lineTo(s * 0.95f, s * 0.88f); lineTo(s * 0.05f, s * 0.88f); close()
    }
    p.style = Paint.Style.FILL; p.color = AMARELO; c.drawPath(tri, p)
    p.style = Paint.Style.STROKE; p.color = VERMELHO; p.strokeWidth = s * 0.07f; p.strokeJoin = Paint.Join.ROUND
    c.drawPath(tri, p)
}
