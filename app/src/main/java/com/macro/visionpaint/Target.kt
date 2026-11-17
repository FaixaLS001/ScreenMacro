package com.macro.visionpaint

import android.graphics.Rect

/**
 * Um alvo encontrado. [rect] já está em pixels reais da tela.
 * [predicted] = a imagem não foi vista neste quadro: a posição é só uma previsão (o alvo foi
 * perdido por um instante, por borrão por exemplo, e o app segue a última posição).
 */
data class Target(
    val rect: Rect,
    val score: Float,
    val tplId: String,
    val predicted: Boolean = false
) {
    val cx: Float get() = rect.exactCenterX()
    val cy: Float get() = rect.exactCenterY()
}
