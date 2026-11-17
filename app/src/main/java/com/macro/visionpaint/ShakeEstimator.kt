package com.macro.visionpaint

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mede quanto a tela inteira se deslocou entre dois quadros (tremor da câmera, giro, recuo),
 * com a correlação de fase do OpenCV (phaseCorrelate) numa imagem pequena em cinza.
 *
 * Com esse deslocamento a posição dos alvos é corrigida mesmo quando a detecção falha (borrão),
 * e dá para saber se a tela está tremendo.
 *
 * Cuidados (testados com imagens sintéticas):
 *  - só olha o miolo da tela (15% a 85%), onde ficam menos botões e menos HUD;
 *  - a mira fixa do jogo e os próprios alvos são "apagados" da conta (máscara). Sem isso, a mira,
 *    que não sai do lugar, vira o pico dominante e o deslocamento medido fica perto de zero;
 *  - se a confiança do pico for baixa (cena lisa, muito movimento próprio), devolve null em vez
 *    de um número errado.
 */
class ShakeEstimator {

    companion object {
        private const val SMALL_W = 128
        private const val MIN_RESPONSE = 0.30
        private const val MAX_SHIFT_FRAC = 0.20f
    }

    private var prev: Mat? = null
    private var prevHoles: List<FloatArray> = emptyList()
    private var win: Mat? = null

    /** Quanto a tela "treme": média do desvio do deslocamento em relação ao deslocamento suave. */
    var amplitude = 0f
        private set
    private var emaX = 0f
    private var emaY = 0f

    fun reset() {
        prev?.release()
        prev = null
        prevHoles = emptyList()
        win?.release()
        win = null
        amplitude = 0f
        emaX = 0f
        emaY = 0f
    }

    /**
     * [frame]: imagem RGB da captura. [holes]: círculos (cx, cy, raio) em pixels do quadro que
     * não devem entrar na conta (mira e alvos). Devolve (dx, dy) em pixels do quadro: quanto o
     * conteúdo da tela andou em relação ao quadro anterior; ou null se não deu para medir.
     */
    fun estimate(frame: Mat, holes: List<FloatArray>): FloatArray? {
        val fw = frame.cols()
        val fh = frame.rows()
        val x0 = (fw * 0.15f).toInt()
        val x1 = (fw * 0.85f).toInt()
        val y0 = (fh * 0.15f).toInt()
        val y1 = (fh * 0.85f).toInt()
        if (x1 - x0 < 32 || y1 - y0 < 32) return null

        val cw = SMALL_W
        val ch = maxOf(16, ((y1 - y0) * cw.toFloat() / (x1 - x0)).roundToInt())
        val k = (x1 - x0).toFloat() / cw

        // Miolo da tela em cinza, pequeno, em ponto flutuante e sem o brilho médio.
        val gray = Mat()
        val crop = frame.submat(y0, y1, x0, x1)
        try {
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGB2GRAY)
        } finally {
            crop.release()
        }
        val small8 = Mat()
        Imgproc.resize(gray, small8, Size(cw.toDouble(), ch.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        val cur = Mat()
        small8.convertTo(cur, CvType.CV_32F)
        small8.release()
        Core.subtract(cur, Scalar(Core.mean(cur).`val`[0]), cur)

        val before = prev
        val beforeHoles = prevHoles
        prev = cur
        prevHoles = holes
        if (before == null || before.cols() != cw || before.rows() != ch) {
            before?.release()
            return null
        }

        // Máscara: 1 = vale, 0 = apagado, com borda suave. Vale a união dos buracos dos dois
        // quadros, aplicada aos dois (senão a borda do buraco vira "movimento").
        val mask = Mat.ones(ch, cw, CvType.CV_32F)
        for (h in beforeHoles + holes) {
            val px = (h[0] - x0) / k
            val py = (h[1] - y0) / k
            val r = maxOf(2f, h[2] / k)
            Imgproc.circle(mask, Point(px.toDouble(), py.toDouble()), r.toInt(), Scalar(0.0), -1)
        }
        Imgproc.GaussianBlur(mask, mask, Size(0.0, 0.0), 2.0)

        val a = Mat()
        val b = Mat()
        Core.multiply(before, mask, a)
        Core.multiply(cur, mask, b)
        before.release()
        mask.release()

        var w = win
        if (w == null || w.cols() != cw || w.rows() != ch) {
            w?.release()
            w = Mat()
            Imgproc.createHanningWindow(w, Size(cw.toDouble(), ch.toDouble()), CvType.CV_32F)
            win = w
        }

        val response = DoubleArray(1)
        val shift = try {
            Imgproc.phaseCorrelate(a, b, w, response)
        } finally {
            a.release()
            b.release()
        }

        val dx = (shift.x * k).toFloat()
        val dy = (shift.y * k).toFloat()
        val limit = MAX_SHIFT_FRAC * min(fw, fh)
        if (response[0] < MIN_RESPONSE || hypot(dx, dy) > limit) {
            amplitude *= 0.8f
            return null
        }

        // Tremor = o quanto este deslocamento foge do deslocamento "suave" (um giro constante
        // da câmera quase não conta; uma sacudida conta).
        val devX = dx - emaX
        val devY = dy - emaY
        amplitude = 0.7f * amplitude + 0.3f * hypot(devX, devY)
        emaX = 0.6f * emaX + 0.4f * dx
        emaY = 0.6f * emaY + 0.4f * dy
        return floatArrayOf(dx, dy)
    }
}
