package app.hunter.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/** Ângulo (graus, sentido horário, 0 = em frente) da seta de cada manobra do Valhalla. */
private fun angleFor(type: Int): Float = when (type) {
    9 -> 45f; 10 -> 90f; 11 -> 135f
    14 -> -135f; 15 -> -90f; 16 -> -45f
    18 -> 40f; 19 -> -40f
    20 -> 30f; 21 -> -30f
    23 -> 20f; 24 -> -20f
    37 -> 15f; 38 -> -15f
    else -> 0f
}

/**
 * Ícone da manobra desenhado em vetor (sem imagens): seta com a curva certa, retorno em U,
 * rotatória e destino. Os mesmos códigos do Valhalla usados em [app.hunter.nav.PodManeuvers].
 */
@Composable
fun ManeuverGlyph(type: Int, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.13f
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            4, 5, 6 -> destination(s, color)
            12, 13 -> uTurn(s, color, stroke, right = type == 12)
            26 -> roundabout(s, color, stroke)
            else -> arrow(s, color, stroke, angleFor(type))
        }
    }
}

private fun p(s: Float, x: Float, y: Float) = Offset(x * s, y * s)

private fun DrawScope.arrow(s: Float, color: Color, stroke: Stroke, deg: Float) {
    if (deg == 0f) {
        drawLine(color, p(s, 0.5f, 0.92f), p(s, 0.5f, 0.20f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        head(s, color, p(s, 0.5f, 0.20f), 0f, -1f)
        return
    }
    val th = Math.toRadians(deg.toDouble())
    val dx = sin(th).toFloat()
    val dy = -cos(th).toFloat()
    val start = p(s, 0.5f, 0.92f)
    val bend = p(s, 0.5f, 0.58f)
    val end = Offset(bend.x + dx * 0.36f * s, bend.y + dy * 0.36f * s)
    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(bend.x, bend.y)
        lineTo(end.x, end.y)
    }
    drawPath(path, color, style = stroke)
    head(s, color, end, dx, dy)
}

/** Ponta da seta em [at], apontando na direção ([dx], [dy]). */
private fun DrawScope.head(s: Float, color: Color, at: Offset, dx: Float, dy: Float) {
    val len = kotlin.math.sqrt(dx * dx + dy * dy).let { if (it == 0f) 1f else it }
    val ux = dx / len
    val uy = dy / len
    val px = -uy
    val py = ux
    val tip = Offset(at.x + ux * 0.13f * s, at.y + uy * 0.13f * s)
    val a = Offset(at.x - ux * 0.06f * s + px * 0.17f * s, at.y - uy * 0.06f * s + py * 0.17f * s)
    val b = Offset(at.x - ux * 0.06f * s - px * 0.17f * s, at.y - uy * 0.06f * s - py * 0.17f * s)
    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(a.x, a.y); lineTo(b.x, b.y); close() }, color)
}

private fun DrawScope.uTurn(s: Float, color: Color, stroke: Stroke, right: Boolean) {
    val m = if (right) 1f else -1f
    val x0 = 0.5f - 0.17f * m
    val x1 = 0.5f + 0.17f * m
    val path = Path().apply {
        moveTo(x0 * s, 0.92f * s)
        lineTo(x0 * s, 0.36f * s)
        quadraticTo(x0 * s, 0.10f * s, 0.5f * s, 0.10f * s)
        quadraticTo(x1 * s, 0.10f * s, x1 * s, 0.36f * s)
        lineTo(x1 * s, 0.60f * s)
    }
    drawPath(path, color, style = stroke)
    head(s, color, p(s, x1, 0.60f), 0f, 1f)
}

private fun DrawScope.roundabout(s: Float, color: Color, stroke: Stroke) {
    drawCircle(color, radius = s * 0.20f, center = p(s, 0.5f, 0.50f), style = stroke)
    drawLine(color, p(s, 0.5f, 0.98f), p(s, 0.5f, 0.70f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(color, p(s, 0.5f, 0.30f), p(s, 0.5f, 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    head(s, color, p(s, 0.5f, 0.12f), 0f, -1f)
}

private fun DrawScope.destination(s: Float, color: Color) {
    val c = p(s, 0.5f, 0.38f)
    val path = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(c.x - 0.26f * s, c.y - 0.26f * s, c.x + 0.26f * s, c.y + 0.26f * s))
        moveTo(c.x - 0.22f * s, c.y + 0.15f * s)
        lineTo(0.5f * s, 0.92f * s)
        lineTo(c.x + 0.22f * s, c.y + 0.15f * s)
        close()
    }
    drawPath(path, color)
    drawCircle(Color.Black.copy(alpha = 0.55f), radius = 0.1f * s, center = c)
}
