package com.macro.visionpaint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Camada de tela cheia com os dois marcadores do modo mira:
 *  - PRESS: onde o dedo pressiona para começar a arrastar;
 *  - AIM: onde fica a mira do jogo (quase invisível quando está em uso).
 *
 * Normalmente não recebe toques (passam direto para o jogo). Enquanto um marcador está sendo
 * posicionado ([startPlacing]), ela captura os toques: tocar ou arrastar em qualquer lugar
 * leva o marcador até lá. As posições ficam salvas em [MacroSettings].
 */
class AimMarkers(private val ctx: Context) {

    companion object {
        const val NONE = 0
        const val PRESS = 1
        const val AIM = 2

        /** Opacidade dos marcadores quando estão em uso (quase invisíveis). */
        private const val IDLE_ALPHA = 0.22f
        private const val LOCKED_ALPHA = 0.5f
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: MarkerView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var added = false
    private var locked = false

    // Posições em fração da tela (0 a 1).
    private var pressX = 0.5f
    private var pressY = 0.8f
    private var aimX = 0.5f
    private var aimY = 0.5f

    /** Qual marcador está sendo posicionado agora ([NONE], [PRESS] ou [AIM]). */
    @Volatile
    var placing = NONE
        private set

    fun show() {
        if (added) return
        loadPositions()

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        l.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            l.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val v = MarkerView(ctx)
        view = v
        lp = l
        wm.addView(v, l)
        added = true
    }

    fun hide() {
        if (!added) return
        stopPlacing()
        try {
            view?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        view = null
        added = false
    }

    /** Verde quando a mira está fixa no centro da imagem. Chamar na thread principal. */
    fun setLocked(v: Boolean) {
        if (locked == v) return
        locked = v
        view?.invalidate()
    }

    /** Começa a posicionar o marcador [which] com o dedo. */
    fun startPlacing(which: Int) {
        if (!added || which == NONE) return
        if (placing != NONE) stopPlacing()
        loadPositions()
        placing = which
        setTouchable(true)
        view?.invalidate()
    }

    /** Termina o posicionamento e salva. */
    fun stopPlacing() {
        if (placing == NONE) return
        savePlaced()
        placing = NONE
        setTouchable(false)
        view?.invalidate()
    }

    private fun loadPositions() {
        pressX = MacroSettings.originXPm / 1000f
        pressY = MacroSettings.originYPm / 1000f
        aimX = MacroSettings.aimXPm / 1000f
        aimY = MacroSettings.aimYPm / 1000f
    }

    private fun savePlaced() {
        when (placing) {
            PRESS -> {
                MacroSettings.originXPm = (pressX * 1000f).roundToInt()
                MacroSettings.originYPm = (pressY * 1000f).roundToInt()
            }
            AIM -> {
                MacroSettings.aimXPm = (aimX * 1000f).roundToInt()
                MacroSettings.aimYPm = (aimY * 1000f).roundToInt()
            }
        }
    }

    private fun setTouchable(touchable: Boolean) {
        val l = lp ?: return
        val v = view ?: return
        l.flags = if (touchable) {
            l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm.updateViewLayout(v, l)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- desenho

    private inner class MarkerView(context: Context) : View(context) {

        private val d = resources.displayMetrics.density

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * d
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
        }
        private val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC212121")
        }
        private val amber = Color.parseColor("#FFC107")
        private val green = Color.parseColor("#43A047")

        override fun onDraw(c: Canvas) {
            // Tamanho real da tela: é nele que o macro calcula onde arrastar, então os
            // marcadores usam o mesmo, e não o tamanho desta camada.
            val real = ScreenInfo.realSize(ctx)
            val w = real.first.toFloat()
            val h = real.second.toFloat()
            val p = placing
            // Sem posicionar, vale o que está salvo (os sliders da tela principal podem ter mudado).
            if (p == NONE) loadPositions()

            val pressAlpha = when (p) {
                PRESS -> 1f
                AIM -> 0.6f
                else -> IDLE_ALPHA
            }
            val aimAlpha = when {
                p == AIM -> 1f
                p == PRESS -> 0.6f
                locked -> LOCKED_ALPHA
                else -> IDLE_ALPHA
            }

            if (p != NONE) {
                val gx = (if (p == PRESS) pressX else aimX) * w
                val gy = (if (p == PRESS) pressY else aimY) * h
                guides(c, gx, gy, w, h)
            }
            drawPress(c, pressX * w, pressY * h, pressAlpha)
            drawAim(c, aimX * w, aimY * h, aimAlpha, locked && p == NONE)

            if (p != NONE) {
                val x = (if (p == PRESS) pressX else aimX) * w
                val y = (if (p == PRESS) pressY else aimY) * h
                c.drawText(if (p == PRESS) "TOQUE" else "MIRA", x + 22f * d, y - 20f * d, text)
                banner(
                    c, w,
                    if (p == PRESS) "1 - Onde o dedo pressiona para arrastar" else "2 - Posição da mira do jogo",
                    "Toque ou arraste na tela. Termine em OK no painel."
                )
            }
        }

        private fun alphaOf(a: Float) = (a.coerceIn(0f, 1f) * 255f).toInt()

        /** Linhas finas cruzando a tela inteira, para dar para alinhar mesmo com o dedo por cima. */
        private fun guides(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            paint.color = Color.WHITE
            paint.alpha = 90
            c.drawLine(0f, y, w, y, paint)
            c.drawLine(x, 0f, x, h, paint)
        }

        /** Ponto de pressão do dedo: anel e ponto, na cor âmbar. */
        private fun drawPress(c: Canvas, x: Float, y: Float, a: Float) {
            val r = 15f * d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f * d
            paint.color = Color.BLACK
            paint.alpha = alphaOf(a * 0.7f)
            c.drawCircle(x, y, r, paint)
            paint.strokeWidth = 1.6f * d
            paint.color = amber
            paint.alpha = alphaOf(a)
            c.drawCircle(x, y, r, paint)

            paint.style = Paint.Style.FILL
            paint.color = amber
            paint.alpha = alphaOf(a)
            c.drawCircle(x, y, 3.5f * d, paint)
        }

        /** Mira: anel com quatro marcas cruzando, branca (ou verde quando fixa). */
        private fun drawAim(c: Canvas, x: Float, y: Float, a: Float, lockedNow: Boolean) {
            val r = 11f * d
            val inner = r - 5f * d
            val outer = r + 8f * d
            val col = if (lockedNow) green else Color.WHITE

            for (pass in 0..1) {
                paint.style = Paint.Style.STROKE
                if (pass == 0) {
                    paint.strokeWidth = 3.5f * d
                    paint.color = Color.BLACK
                    paint.alpha = alphaOf(a * 0.7f)
                } else {
                    paint.strokeWidth = 1.6f * d
                    paint.color = col
                    paint.alpha = alphaOf(a)
                }
                c.drawCircle(x, y, r, paint)
                c.drawLine(x - outer, y, x - inner, y, paint)
                c.drawLine(x + inner, y, x + outer, y, paint)
                c.drawLine(x, y - outer, x, y - inner, paint)
                c.drawLine(x, y + inner, x, y + outer, paint)
            }
        }

        private fun banner(c: Canvas, w: Float, title: String, sub: String) {
            val tw = maxOf(text.measureText(title), text.measureText(sub))
            val padX = 14f * d
            val padY = 10f * d
            val lineH = 18f * d
            val top = 72f * d
            val rect = RectF(
                (w - tw) / 2f - padX, top,
                (w + tw) / 2f + padX, top + padY * 2 + lineH * 2
            )
            c.drawRoundRect(rect, 10f * d, 10f * d, bannerBg)
            text.textAlign = Paint.Align.CENTER
            c.drawText(title, w / 2f, top + padY + lineH * 0.8f, text)
            c.drawText(sub, w / 2f, top + padY + lineH * 1.85f, text)
            text.textAlign = Paint.Align.LEFT
        }

        // ------------------------------------------------------------ toque (só ao posicionar)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = placing
            if (p == NONE) return false
            val real = ScreenInfo.realSize(ctx)
            if (real.first <= 0 || real.second <= 0) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    // rawX/rawY: posição na tela, a mesma usada pelo toque automático.
                    val fx = (e.rawX / real.first).coerceIn(0f, 1f)
                    val fy = (e.rawY / real.second).coerceIn(0f, 1f)
                    if (p == PRESS) {
                        pressX = fx
                        pressY = fy
                    } else {
                        aimX = fx
                        aimY = fy
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP) savePlaced()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> savePlaced()
            }
            return true
        }
    }
}
