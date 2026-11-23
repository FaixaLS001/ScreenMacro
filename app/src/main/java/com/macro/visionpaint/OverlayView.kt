package com.macro.visionpaint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.View

/** Um alvo encontrado, já com a cor em que deve ser pintado. */
data class Painted(val target: Target, val color: Int)

/**
 * Camada transparente sobre a tela: pinta os alvos reconhecidos e desenha a rota do dedo.
 * Não recebe toques (os toques passam direto para o app que está por baixo).
 */
class OverlayView(context: Context) : View(context) {

    private var items: List<Painted> = emptyList()
    private var route: Painted? = null
    private var originX = 0f
    private var originY = 0f
    private var showRoute = true
    private var alphaPct = 45

    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Color.WHITE
    }

    /** Sombra escura sob o anel branco, para ele aparecer também em fundo claro. */
    private val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * d
        color = Color.argb(150, 0, 0, 0)
    }

    fun update(
        items: List<Painted>,
        route: Painted?,
        originX: Float,
        originY: Float,
        showRoute: Boolean,
        alphaPct: Int
    ) {
        this.items = items
        this.route = route
        this.originX = originX
        this.originY = originY
        this.showRoute = showRoute
        this.alphaPct = alphaPct
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val half = stroke.strokeWidth / 2f
        // A janela inteira é translúcida (ver TouchSafe): reforça o preenchimento na mesma
        // proporção para a pintura manter a opacidade escolhida (até o máximo que a janela dá).
        val boost = 1f / TouchSafe.PAINT_WINDOW_ALPHA

        // Pintura translúcida por cima de cada alvo + contorno sólido, cada um na sua cor.
        for (p in items) {
            val r = p.target.rect
            fill.color = p.color
            // Alvo só previsto (a imagem sumiu por um instante): pintura mais fraca.
            val a = if (p.target.predicted) alphaPct / 2 else alphaPct
            fill.alpha = (a * 255 / 100 * boost).toInt().coerceAtMost(255)
            stroke.color = p.color
            canvas.drawRect(
                r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fill
            )
            canvas.drawRect(r.left - half, r.top - half, r.right + half, r.bottom + half, stroke)

            // Modelo 3D: anel na área de mira escolhida (o centro do retângulo não é o ponto de mira).
            val spot = p.target.aim
            if (spot != null) {
                val rr = maxOf(spot.r, 6f * d)
                ring.alpha = if (p.target.predicted) 120 else 255
                dot.color = p.color
                canvas.drawCircle(spot.x, spot.y, rr, ringDark)
                canvas.drawCircle(spot.x, spot.y, rr, ring)
                canvas.drawCircle(spot.x, spot.y, 3f * d, dot)
                ring.alpha = 255
            }
        }

        val rt = route
        if (showRoute && rt != null) {
            line.color = rt.color
            canvas.drawLine(originX, originY, rt.target.ax, rt.target.ay, line)

            dot.color = rt.color
            canvas.drawCircle(originX, originY, 9f * d, dot)
            canvas.drawCircle(originX, originY, 9f * d, ring)
            canvas.drawCircle(rt.target.ax, rt.target.ay, 5f * d, dot)
            canvas.drawCircle(rt.target.ax, rt.target.ay, 5f * d, ring)
        }
    }
}

/**
 * Android 12+ bloqueia os toques que passam por camadas de outro app quando a opacidade somada
 * delas passa de 0,8 (proteção contra "tapjacking"). Só conta a opacidade da JANELA
 * (WindowManager.LayoutParams.alpha), não o que está desenhado nela: uma camada de tela cheia
 * transparente, mas com alpha 1,0, ainda bloqueia o toque no app de baixo.
 *
 * As duas camadas de tela cheia (pintura e marcadores da mira) usam os valores abaixo:
 * 1 - (1 - 0,55) * (1 - 0,35) = 0,71, abaixo do limite. O desenho de cada uma é reforçado na
 * mesma proporção (1 / alpha da janela) para continuar com a mesma aparência.
 */
object TouchSafe {
    const val PAINT_WINDOW_ALPHA = 0.55f
    const val MARKER_WINDOW_ALPHA = 0.35f
}

/** Tamanho real da tela (muda quando o aparelho gira). */
object ScreenInfo {
    fun realSize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }
}
