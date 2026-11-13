package com.macro.visionpaint

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometria do rastreio (sem Android): onde procurar de novo um alvo visto no quadro anterior.
 * Tudo em pixels da imagem da captura (a imagem reduzida), não da tela real.
 */
object TrackMath {

    /**
     * Quanto o alvo pode ter andado entre dois quadros: uma parte da tela mais metade do tamanho
     * do modelo. Se andar além disso, o rastreio falha e vale a varredura completa.
     */
    fun margin(frameW: Int, frameH: Int, tplW: Int, tplH: Int): Int =
        max(24, (0.16f * min(frameW, frameH)).toInt()) + max(tplW, tplH) / 2

    /**
     * Região de busca [esquerda, topo, direita, baixo] (direita e baixo exclusivos) ao redor de um
     * modelo de [tplW] x [tplH] que estava em ([x], [y]). Devolve null se a região não comporta o
     * modelo (por exemplo, a posição antiga ficou fora do quadro).
     */
    fun roi(x: Int, y: Int, tplW: Int, tplH: Int, frameW: Int, frameH: Int): IntArray? {
        val m = margin(frameW, frameH, tplW, tplH)
        val x0 = max(0, x - m)
        val y0 = max(0, y - m)
        val x1 = min(frameW, x + tplW + m)
        val y1 = min(frameH, y + tplH + m)
        if (x1 - x0 < tplW || y1 - y0 < tplH) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    /** Dois achados do mesmo modelo, em ([x1], [y1]) e ([x2], [y2]), são o mesmo objeto? */
    fun sameSpot(x1: Int, y1: Int, x2: Int, y2: Int, tplW: Int, tplH: Int): Boolean =
        abs(x1 - x2) < tplW / 2 && abs(y1 - y2) < tplH / 2
}
