package com.macro.visionpaint

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Faixa de cor de uma imagem de referência (HSV do OpenCV: matiz 0 a 179, S e V 0 a 255).
 * [frac] = fração da imagem que tem essa cor; [offX], [offY] = onde fica o centro da mancha de
 * cor em relação ao centro da imagem (para achar o centro do alvo a partir da mancha).
 */
class ColorRange(
    val hue: Int,
    val sMin: Int,
    val vMin: Int,
    val frac: Float,
    val offX: Float,
    val offY: Float,
    /** Largura e altura (px) da mancha de cor na imagem: manchas de outro formato são rejeitadas. */
    val bw: Float,
    val bh: Float
)

/** Análise de cor sem Android nem OpenCV (recebe os bytes H,S,V já convertidos). */
object ColorMath {

    private const val MIN_S = 90
    private const val MIN_V = 60

    fun hueDist(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 180 - d)
    }

    /**
     * Acha a cor dominante "marcante" (saturada e clara) de uma imagem [w] x [h] cujos pixels
     * estão em [hsv] como H,S,V,H,S,V,... Devolve null se a imagem não tem uma cor marcante
     * (imagem cinza, ou a cor ocupa muito pouco): aí a detecção por cor não ajuda.
     */
    fun dominant(hsv: ByteArray, w: Int, h: Int, hueTol: Int): ColorRange? {
        val n = w * h
        if (n <= 0 || hsv.size < n * 3) return null

        val hist = FloatArray(180)
        var vivid = 0
        for (i in 0 until n) {
            val hh = hsv[3 * i].toInt() and 0xFF
            val ss = hsv[3 * i + 1].toInt() and 0xFF
            val vv = hsv[3 * i + 2].toInt() and 0xFF
            if (ss >= MIN_S && vv >= MIN_V) {
                hist[hh % 180] += ss * vv / 255f
                vivid++
            }
        }
        if (vivid < 0.06f * n) return null

        // Pico do histograma de matiz (suavizado e circular: o vermelho fica nas duas pontas).
        var peak = 0
        var peakVal = -1f
        for (c in 0 until 180) {
            var s = 0f
            for (d in -5..5) s += hist[(c + d + 180) % 180]
            if (s > peakVal) {
                peakVal = s
                peak = c
            }
        }

        val sh = IntArray(256)
        val vh = IntArray(256)
        val xh = IntArray(w)
        val yh = IntArray(h)
        var m = 0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            val hh = hsv[3 * i].toInt() and 0xFF
            val ss = hsv[3 * i + 1].toInt() and 0xFF
            val vv = hsv[3 * i + 2].toInt() and 0xFF
            if (ss >= MIN_S && vv >= MIN_V && hueDist(hh, peak) <= hueTol) {
                sh[ss]++
                vh[vv]++
                xh[i % w]++
                yh[i / w]++
                m++
                sx += (i % w).toDouble()
                sy += (i / w).toDouble()
            }
        }
        if (m < 0.04f * n) return null

        val sMin = max(60, percentile(sh, m, 0.10f) - 25)
        val vMin = max(45, percentile(vh, m, 0.10f) - 35)
        val offX = (sx / m).toFloat() - (w - 1) / 2f
        val offY = (sy / m).toFloat() - (h - 1) / 2f
        // Caixa da mancha sem os pixels soltos (3% de cada ponta).
        val bw = (percentile(xh, m, 0.97f) - percentile(xh, m, 0.03f) + 1).coerceAtLeast(1).toFloat()
        val bh = (percentile(yh, m, 0.97f) - percentile(yh, m, 0.03f) + 1).coerceAtLeast(1).toFloat()
        return ColorRange(peak, sMin, vMin, m.toFloat() / n, offX, offY, bw, bh)
    }

    private fun percentile(hist: IntArray, total: Int, q: Float): Int {
        val target = (total * q).toInt()
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc > target) return i
        }
        return hist.size - 1
    }
}
