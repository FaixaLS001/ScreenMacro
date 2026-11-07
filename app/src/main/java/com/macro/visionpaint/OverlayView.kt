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

/**
 * Camada transparente sobre a tela: pinta os alvos reconhecidos e desenha a rota do dedo.
 * Não recebe toques (os toques passam direto para o app que está por baixo).
 */
class OverlayView(context: Context) : View(context) {

    private var targets: List<Target> = emptyList()
    private var routeTarget: Target? = null
    private var originX = 0f
    private var originY = 0f
    private var showRoute = true
    private var paintColor = Color.RED
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
        targets: List<Target>,
        route: Target?,
        originX: Float,
        originY: Float,
        showRoute: Boolean,
        color: Int,
        alphaPct: Int
    ) {
        this.targets = targets
        this.routeTarget = route
        this.originX = originX
        this.originY = originY
        this.showRoute = showRoute
        this.paintColor = color
        this.alphaPct = alphaPct
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Pintura translúcida por cima do alvo + contorno sólido por fora dele.
        fill.color = paintColor
        fill.alpha = alphaPct * 255 / 100
        stroke.color = paintColor
        val half = stroke.strokeWidth / 2f

        for (t in targets) {
            val r = t.rect
            canvas.drawRect(
                r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fill
            )
            canvas.drawRect(r.left - half, r.top - half, r.right + half, r.bottom + half, stroke)
        }

        val rt = routeTarget
        if (showRoute && rt != null) {
            line.color = paintColor
            canvas.drawLine(originX, originY, rt.cx, rt.cy, line)

            dot.color = paintColor
            canvas.drawCircle(originX, originY, 9f * d, dot)
            canvas.drawCircle(originX, originY, 9f * d, ring)
            canvas.drawCircle(rt.cx, rt.cy, 5f * d, dot)
            canvas.drawCircle(rt.cx, rt.cy, 5f * d, ring)
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
