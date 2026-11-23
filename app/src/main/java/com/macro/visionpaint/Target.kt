package com.macro.visionpaint

import android.graphics.Rect

/**
 * Um alvo encontrado. [rect] já está em pixels reais da tela.
 * [predicted] = a imagem não foi vista neste quadro: a posição é só uma previsão (o alvo foi
 * perdido por um instante, por borrão por exemplo, e o app segue a última posição).
 * [aim] = ponto de mira escolhido no modelo 3D (pixels reais da tela, raio incluso); só existe
 * para alvos que vêm de modelos 3D. Para imagens, a mira é o centro do [rect].
 */
data class Target(
    val rect: Rect,
    val score: Float,
    val tplId: String,
    val predicted: Boolean = false,
    val aim: AimSpot? = null
) {
    val cx: Float get() = rect.exactCenterX()
    val cy: Float get() = rect.exactCenterY()

    /** Onde mirar: o ponto escolhido no modelo, ou o centro do alvo. */
    val ax: Float get() = aim?.x ?: cx
    val ay: Float get() = aim?.y ?: cy

    val isModel: Boolean get() = tplId.startsWith(ModelMatcher.ID_PREFIX)
}
