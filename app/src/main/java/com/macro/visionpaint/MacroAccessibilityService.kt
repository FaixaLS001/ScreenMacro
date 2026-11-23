package com.macro.visionpaint

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade: é ele que executa o toque/arrasto na tela.
 * Só funciona depois que o usuário ativa o serviço nas configurações de Acessibilidade.
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroAccessibilityService? = null
    }

    /** true enquanto um arrasto está em andamento. */
    @Volatile
    var busy = false

    /** Momento (uptimeMillis) em que o último arrasto terminou. */
    @Volatile
    var lastEnd = 0L

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Arrasta o dedo em linha reta de (fromX, fromY) até (toX, toY) em [durationMs]. */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        swipe(floatArrayOf(fromX, fromY, toX, toY), durationMs)
    }

    /**
     * Arrasta o dedo por uma linha quebrada, em [durationMs] no total.
     * [points]: x0, y0, x1, y1, ... (pelo menos dois pontos).
     */
    fun swipe(points: FloatArray, durationMs: Long) {
        if (busy || points.size < 4) return
        busy = true

        main.post {
            try {
                val size = ScreenInfo.realSize(this)
                val maxX = (size.first - 1).toFloat()
                val maxY = (size.second - 1).toFloat()

                val path = Path().apply {
                    moveTo(points[0].coerceIn(0f, maxX), points[1].coerceIn(0f, maxY))
                    var i = 2
                    while (i + 1 < points.size) {
                        lineTo(points[i].coerceIn(0f, maxX), points[i + 1].coerceIn(0f, maxY))
                        i += 2
                    }
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                val started = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finish()
                    override fun onCancelled(gestureDescription: GestureDescription?) = finish()
                }, null)
                if (!started) finish()
            } catch (e: Exception) {
                finish()
            }
        }
    }

    private fun finish() {
        busy = false
        lastEnd = SystemClock.uptimeMillis()
    }
}
