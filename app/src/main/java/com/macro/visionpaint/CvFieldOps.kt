package com.macro.visionpaint

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * As contas de imagem do [ModelMatcher] com o OpenCV. Cada chamada aqui é a mesma que foi
 * validada no protótipo em Python (cv2): cinza -> redução por área -> desfoque -> Sobel / 8 ->
 * módulo e ângulo -> (min(módulo / 10, 1) ^ 0,5, ângulo x 2) -> (cos, sin) -> desfoque.
 *
 * Uma instância serve para vários quadros: [setFrame] troca o quadro (e o [ModelMatcher.newFrame]
 * solta os campos do anterior).
 */
class CvFieldOps : FieldOps {

    companion object {
        /** Sensibilidade: gradiente (Sobel / 8) a partir do qual a borda conta com peso cheio. */
        private const val M0 = 10.0
        private const val GAMMA = 0.5
        private const val SIG0 = 1.0
        private const val SIG_SPREAD = 1.5
    }

    private var gray: Mat? = null
    private var w = 0
    private var h = 0

    override val frameW: Int get() = w
    override val frameH: Int get() = h

    /** [rgb]: quadro 8 bits, 3 canais, RGB. Não é guardado nem alterado. */
    fun setFrame(rgb: Mat) {
        gray?.release()
        val g8 = Mat()
        val g = Mat()
        try {
            Imgproc.cvtColor(rgb, g8, Imgproc.COLOR_RGB2GRAY)
            g8.convertTo(g, CvType.CV_32F)
        } finally {
            g8.release()
        }
        gray = g
        w = g.cols()
        h = g.rows()
    }

    override fun field(k: Int): Field {
        val g0 = gray ?: throw IllegalStateException("sem quadro")
        val tmp = ArrayList<Mat>()
        try {
            var g = g0
            if (k > 0) {
                val rw = max(8, (w / (1 shl k).toDouble()).roundToInt())
                val rh = max(8, (h / (1 shl k).toDouble()).roundToInt())
                val r = Mat()
                tmp.add(r)
                Imgproc.resize(g0, r, Size(rw.toDouble(), rh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                g = r
            }
            val blur = Mat()
            tmp.add(blur)
            Imgproc.GaussianBlur(g, blur, Size(0.0, 0.0), SIG0)

            val dx = Mat()
            val dy = Mat()
            tmp.add(dx)
            tmp.add(dy)
            Imgproc.Sobel(blur, dx, CvType.CV_32F, 1, 0, 3, 1.0 / 8.0, 0.0)
            Imgproc.Sobel(blur, dy, CvType.CV_32F, 0, 1, 3, 1.0 / 8.0, 0.0)

            val mag = Mat()
            val ang = Mat()
            tmp.add(mag)
            tmp.add(ang)
            Core.cartToPolar(dx, dy, mag, ang)

            // mag = min(mag / M0, 1) ^ GAMMA
            Imgproc.threshold(mag, mag, M0, 0.0, Imgproc.THRESH_TRUNC)
            mag.convertTo(mag, CvType.CV_32F, 1.0 / M0)
            Core.pow(mag, GAMMA, mag)

            val ang2 = Mat()
            tmp.add(ang2)
            ang.convertTo(ang2, CvType.CV_32F, 2.0)
            val c = Mat()
            val s = Mat()
            tmp.add(c)
            tmp.add(s)
            Core.polarToCart(mag, ang2, c, s)

            val merged = Mat()
            Core.merge(listOf(c, s), merged)
            val f = Mat()
            Imgproc.GaussianBlur(merged, f, Size(0.0, 0.0), SIG_SPREAD)
            merged.release()
            return CvField(f, w.toFloat() / f.cols(), h.toFloat() / f.rows())
        } finally {
            for (m in tmp) m.release()
        }
    }

    override fun edgeMap(): EdgeMap {
        val g0 = gray ?: throw IllegalStateException("sem quadro")
        val tmp = ArrayList<Mat>()
        try {
            val blur = Mat()
            val dx = Mat()
            val dy = Mat()
            val mag = Mat()
            val ang = Mat()
            tmp.add(blur)
            tmp.add(dx)
            tmp.add(dy)
            tmp.add(mag)
            tmp.add(ang)
            Imgproc.GaussianBlur(g0, blur, Size(0.0, 0.0), 1.0)
            Imgproc.Sobel(blur, dx, CvType.CV_32F, 1, 0, 3, 1.0 / 8.0, 0.0)
            Imgproc.Sobel(blur, dy, CvType.CV_32F, 0, 1, 3, 1.0 / 8.0, 0.0)
            Core.cartToPolar(dx, dy, mag, ang)

            val n = w * h
            val m = FloatArray(n)
            val a = FloatArray(n)
            mag.get(0, 0, m)
            ang.get(0, 0, a)
            val pi = Math.PI.toFloat()
            val th = FloatArray(n)
            for (i in 0 until n) {
                th[i] = if (m[i] < EdgeMap.TAU) {
                    EdgeMap.NO_EDGE
                } else {
                    var r = a[i] % pi
                    if (r < 0f) r += pi
                    r
                }
            }
            return EdgeMap(w, h, th)
        } finally {
            for (x in tmp) x.release()
        }
    }

    override fun releaseTemplate(t: Template) {
        (t.handle as? Mat)?.release()
        t.handle = null
    }

    /** Solta o quadro. */
    fun release() {
        gray?.release()
        gray = null
        w = 0
        h = 0
    }

    private class CvField(val mat: Mat, override val sx: Float, override val sy: Float) : Field {
        override val w: Int get() = mat.cols()
        override val h: Int get() = mat.rows()

        override fun respond(t: Template, x0: Int, y0: Int, x1: Int, y1: Int): Response? {
            if (x1 - x0 < t.w || y1 - y0 < t.h) return null
            var tm = t.handle as? Mat
            if (tm == null) {
                tm = Mat(t.h, t.w, CvType.CV_32FC2)
                tm.put(0, 0, t.data)
                t.handle = tm
            }
            val sub = mat.submat(y0, y1, x0, x1)
            val raw = Mat()
            val res = Mat()
            try {
                Imgproc.matchTemplate(sub, tm, raw, Imgproc.TM_CCORR)
                raw.convertTo(res, CvType.CV_32F, 1.0 / max(t.n, 1e-6f).toDouble())
            } finally {
                sub.release()
                raw.release()
            }
            return CvResponse(res, x0, y0)
        }

        override fun release() {
            mat.release()
        }
    }

    private class CvResponse(val mat: Mat, override val offX: Int, override val offY: Int) : Response {
        override fun maxLoc(): Peak? {
            val mm = Core.minMaxLoc(mat)
            if (mm.maxVal.isNaN()) return null
            return Peak(mm.maxVal.toFloat(), mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt())
        }

        override fun fill(x0: Int, y0: Int, x1: Int, y1: Int, v: Float) {
            val a = max(0, x0)
            val b = max(0, y0)
            val c = minOf(mat.cols(), x1)
            val d = minOf(mat.rows(), y1)
            if (c <= a || d <= b) return
            val sub = mat.submat(b, d, a, c)
            try {
                sub.setTo(Scalar(v.toDouble()))
            } finally {
                sub.release()
            }
        }

        override fun release() {
            mat.release()
        }
    }
}
