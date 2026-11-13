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

        // Pintura translúcida por cima de cada alvo + contorno sólido, cada um na sua cor.
        for (p in items) {
            val r = p.target.rect
            fill.color = p.color
            fill.alpha = alphaPct * 255 / 100
            stroke.color = p.color
            canvas.drawRect(
                r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fill
            )
            canvas.drawRect(r.left - half, r.top - half, r.right + half, r.bottom + half, stroke)
        }

        val rt = route
        if (showRoute && rt != null) {
            line.color = rt.color
            canvas.drawLine(originX, originY, rt.target.cx, rt.target.cy, line)

            dot.color = rt.color
            canvas.drawCircle(originX, originY, 9f * d, dot)
            canvas.drawCircle(originX, originY, 9f * d, ring)
            canvas.drawCircle(rt.target.cx, rt.target.cy, 5f * d, dot)
            canvas.drawCircle(rt.target.cx, rt.target.cy, 5f * d, ring)
        }
    }
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
