package app.hunter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

private class RiderPalette(
    val bodyFrom: Int,
    val bodyTo: Int,
    val handlebar: Int,
    val shoulders: Int,
    val helmetFrom: Int,
    val helmetTo: Int,
)

private val GRAY = RiderPalette(
    bodyFrom = 0xFF7F93B2.toInt(), bodyTo = 0xFFC7D3E6.toInt(), handlebar = 0xFFCDD7E6.toInt(),
    shoulders = 0xFF5B6F8C.toInt(), helmetFrom = 0xFFFFFFFF.toInt(), helmetTo = 0xFF9FB0C8.toInt(),
)
private val ORANGE = RiderPalette(
    bodyFrom = 0xFFD9600A.toInt(), bodyTo = 0xFFFFA14D.toInt(), handlebar = 0xFFFFD2A6.toInt(),
    shoulders = 0xFFB44F08.toInt(), helmetFrom = 0xFFFFF2E6.toInt(), helmetTo = 0xFFFF9A45.toInt(),
)
private const val WHEEL = 0xFF1B232F.toInt()
private const val RING_STROKE = 0xFFFFC58F.toInt()
private const val RING_FILL = 0x33FFA14D

/**
 * Moto vista de cima, apontando para o norte (o mapa a gira com o rumo): duas rodas estreitas, tanque oval com
 * gradiente da esquerda para a direita, guidão, ombros e capacete. Cinza para os outros, laranja para a própria
 * moto; com [destaque] (combina com você) ganha um anel laranja claro. Anônima: nada de nome ou foto.
 */
fun riderBitmap(density: Float, destaque: Boolean, proprio: Boolean): Bitmap {
    val size = (56 * density).toInt().coerceAtLeast(2)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = size.toFloat()
    val cx = s / 2f
    val pal = if (proprio) ORANGE else GRAY
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    if (destaque) {
        paint.style = Paint.Style.FILL
        paint.color = RING_FILL
        c.drawCircle(cx, cx, s * 0.47f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.color = RING_STROKE
        c.drawCircle(cx, cx, s * 0.45f, paint)
    }

    // Rodas: duas barras estreitas, uma na frente e uma atrás.
    paint.style = Paint.Style.FILL
    paint.color = WHEEL
    val wheelW = s * 0.07f
    c.drawRoundRect(RectF(cx - wheelW / 2, s * 0.11f, cx + wheelW / 2, s * 0.31f), wheelW / 2, wheelW / 2, paint)
    c.drawRoundRect(RectF(cx - wheelW / 2, s * 0.69f, cx + wheelW / 2, s * 0.89f), wheelW / 2, wheelW / 2, paint)

    // Tanque oval com gradiente da esquerda para a direita.
    val tank = RectF(cx - s * 0.11f, s * 0.27f, cx + s * 0.11f, s * 0.66f)
    paint.shader = LinearGradient(tank.left, 0f, tank.right, 0f, pal.bodyFrom, pal.bodyTo, Shader.TileMode.CLAMP)
    c.drawOval(tank, paint)
    paint.shader = null

    // Ombros (vistos de cima) e guidão.
    paint.color = pal.shoulders
    c.drawOval(RectF(cx - s * 0.16f, s * 0.46f, cx + s * 0.16f, s * 0.58f), paint)
    paint.color = pal.handlebar
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 0.04f * s
    paint.strokeCap = Paint.Cap.ROUND
    c.drawLine(cx - s * 0.20f, s * 0.36f, cx + s * 0.20f, s * 0.36f, paint)

    // Capacete: esfera com gradiente radial.
    paint.style = Paint.Style.FILL
    val hy = s * 0.55f
    val hr = s * 0.075f
    paint.shader = RadialGradient(cx - hr * 0.3f, hy - hr * 0.3f, hr * 1.4f, pal.helmetFrom, pal.helmetTo, Shader.TileMode.CLAMP)
    c.drawCircle(cx, hy, hr, paint)
    paint.shader = null
    return bmp
}
