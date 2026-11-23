package com.macro.visionpaint

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bordas do quadro: em cada pixel, a direção da borda (0 a pi, direção da normal; sem sinal) ou
 * [NO_EDGE] onde a borda é fraca. [th] tem [w] x [h] valores, linha por linha.
 */
class EdgeMap(val w: Int, val h: Int, val th: FloatArray) {
    companion object {
        const val NO_EDGE = -10f

        /** Borda forte = gradiente (Sobel / 8, depois de um desfoque de 1 px) acima disto. */
        const val TAU = 3f
    }
}

/**
 * Verificação do contorno (sem Android nem OpenCV).
 *
 * A correlação de orientações acha onde o modelo "mais se parece" com o quadro, mas um fundo com
 * muitas bordas também combina bem com qualquer contorno. Por isso, depois de achar uma pose,
 * confere-se de outro jeito: que fração dos pontos do contorno da vista tem, por perto, uma borda
 * forte na direção certa ([cover]). Um contorno que só "cai" numa região cheia de bordas também
 * tem cobertura alta se for deslocado um pouco; um alvo de verdade perde a cobertura quando o
 * contorno sai do lugar. Por isso a nota final ([confidence]) desconta metade da melhor cobertura
 * do contorno deslocado.
 */
object Coverage {
    /** Diferença máxima de direção (graus) para a borda do quadro valer como a do contorno. */
    const val TOL_DEG = 25f

    /** Deslocamento do contorno de controle, em tamanhos do modelo. */
    const val SHIFT = 0.18f

    /** Quanto da cobertura do contorno deslocado é descontada. */
    const val SHIFT_WEIGHT = 0.5f

    private val SHIFTS = arrayOf(
        floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
        floatArrayOf(0.7f, 0.7f), floatArrayOf(-0.7f, 0.7f), floatArrayOf(0.7f, -0.7f), floatArrayOf(-0.7f, -0.7f)
    )

    /** Raio (px) em que se procura a borda do quadro em volta de cada ponto do contorno. */
    fun radiusFor(pxPerUnit: Float): Float = max(1f, 0.02f * pxPerUnit)

    /**
     * Fração (0 a 1, ponderada: silhueta pesa mais que borda interna) dos pontos de [view] que têm uma
     * borda forte na direção certa a no máximo [rad] pixels. O modelo está com a origem em ([ox], [oy]),
     * [p] pixels por unidade e girado [rollDeg] graus.
     */
    fun cover(edge: EdgeMap, view: BankView, ox: Float, oy: Float, p: Float, rollDeg: Float, rad: Float): Float {
        val pts = view.pts
        val ang = view.normalAngle
        val n = view.count
        val w = edge.w
        val h = edge.h
        val th = edge.th
        val roll = Math.toRadians(rollDeg.toDouble())
        val cr = cos(roll).toFloat()
        val sr = sin(roll).toFloat()
        val rollF = roll.toFloat()
        val tol = Math.toRadians(TOL_DEG.toDouble()).toFloat()
        val pi = PI.toFloat()

        // Deslocamentos dentro do círculo de raio [rad].
        val r = ceil(rad).toInt()
        val dxs = IntArray((2 * r + 1) * (2 * r + 1))
        val dys = IntArray(dxs.size)
        var no = 0
        for (dy in -r..r) for (dx in -r..r) {
            if (dx * dx + dy * dy <= rad * rad + 0.01f) {
                dxs[no] = dx
                dys[no] = dy
                no++
            }
        }

        var sw = 0f
        var hw = 0f
        for (i in 0 until n) {
            val o = i * 5
            val wt = pts[o + 4]
            sw += wt
            val u = pts[o]
            val v = pts[o + 1]
            val xi = (ox + p * (u * cr - v * sr)).roundToInt()
            val yi = (oy + p * (u * sr + v * cr)).roundToInt()
            if (xi < 0 || xi >= w || yi < 0 || yi >= h) continue
            var t = (ang[i] + rollF) % pi
            if (t < 0f) t += pi
            var hit = false
            for (k in 0 until no) {
                val xx = min(max(xi + dxs[k], 0), w - 1)
                val yy = min(max(yi + dys[k], 0), h - 1)
                val a = th[yy * w + xx]
                if (a < -5f) continue
                var d = abs(a - t)
                d = min(d, pi - d)
                if (d <= tol) {
                    hit = true
                    break
                }
            }
            if (hit) hw += wt
        }
        return if (sw > 0f) hw / sw else 0f
    }

    /**
     * Nota de confiança de uma pose: cobertura menos metade da melhor cobertura do mesmo contorno
     * deslocado [SHIFT] tamanhos do modelo em 8 direções. Fica por volta de 0,6 a 0,8 num alvo
     * nítido sobre fundo liso; num fundo cheio de bordas, sem o modelo, raramente passa de 0,45.
     * [cov0] = a cobertura sem deslocamento, se já foi calculada.
     */
    fun confidence(
        edge: EdgeMap, view: BankView, ox: Float, oy: Float, p: Float, rollDeg: Float, rad: Float,
        cov0: Float = Float.NaN
    ): Float {
        val c0 = if (cov0.isNaN()) cover(edge, view, ox, oy, p, rollDeg, rad) else cov0
        var worst = 0f
        for (s in SHIFTS) {
            val c = cover(edge, view, ox + SHIFT * p * s[0], oy + SHIFT * p * s[1], p, rollDeg, rad)
            if (c > worst) worst = c
        }
        return c0 - SHIFT_WEIGHT * worst
    }
}
