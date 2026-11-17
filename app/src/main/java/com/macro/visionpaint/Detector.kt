package com.macro.visionpaint

import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** Uma imagem de referência a carregar. [byColor] = também usa a cor dela para achar o alvo. */
class TplSource(val id: String, val file: File, val byColor: Boolean)

/**
 * Procura na imagem da tela os modelos enviados pelo usuário (template matching com OpenCV).
 * [scale] é a escala da captura em relação à tela real (ex.: 0.5 = metade do tamanho).
 * Tudo aqui é em pixels da imagem da captura (a reduzida), não da tela real.
 *
 * Contra desfoque e tremor:
 *  - [blurSigma] > 0 borra levemente o quadro e os modelos antes de comparar;
 *  - [useVariants] guarda, para cada modelo, versões borradas e um pouco maiores/menores, e a
 *    busca local usa a que melhor combinar (o rastreio é numa região pequena, então custa pouco);
 *  - modelos com "achar por cor" usam uma máscara HSV da cor marcante da imagem para achar
 *    candidatos, e a imagem só confirma; no rastreio, a cor serve de reserva quando a imagem
 *    não combina por causa do borrão.
 */
class Detector(
    private val scale: Float,
    private val blurSigma: Float = 0f,
    private val useVariants: Boolean = true,
    private val hueTol: Int = 14
) {

    private class Variant(val mat: Mat) {
        val w: Int get() = mat.cols()
        val h: Int get() = mat.rows()
    }

    private class Loaded(
        val id: String,
        val variants: List<Variant>,
        val range: ColorRange?,
        val wantColor: Boolean
    ) {
        val base: Mat get() = variants[0].mat

        /** Tamanho (ímpar) do "fechamento" da máscara de cor: une pedaços separados por detalhes finos. */
        val closeK: Int
            get() {
                val k = max(5, (0.14f * max(base.cols(), base.rows())).roundToInt())
                return if (k % 2 == 0) k + 1 else k
            }
    }

    private class Best(val score: Float, val x: Int, val y: Int, val v: Variant)

    /**
     * Onde o rastreio espera o alvo (em pixels da região de busca) e as duas notas da histerese.
     * Perto da previsão vale a nota de SEGUIR; longe dela, a nota de ACHAR.
     */
    private class Guide(val px: Float, val py: Float, val follow: Float, val acquire: Float)

    companion object {
        /** Versões borradas/redimensionadas do modelo perdem um pouco, para não inflar o ruído. */
        private const val VARIANT_PENALTY = 0.03f
    }

    private val loaded = ArrayList<Loaded>()
    private val index = HashMap<String, Int>()
    private val kernels = HashMap<Int, Mat>()

    /** Imagens que pediram "achar por cor" mas não têm uma cor marcante (a opção é ignorada). */
    val colorless = ArrayList<String>()

    fun templateCount(): Int = loaded.size

    fun loadTemplates(items: List<TplSource>) {
        release()
        for (it in items) {
            val bmp = BitmapFactory.decodeFile(it.file.absolutePath) ?: continue
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()

            val rgb = Mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgba.release()

            val w = (rgb.cols() * scale).toInt()
            val h = (rgb.rows() * scale).toInt()
            if (w < 6 || h < 6) {
                rgb.release()
                continue
            }
            val small = Mat()
            Imgproc.resize(rgb, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            rgb.release()

            // A cor é analisada na imagem nítida, antes de qualquer desfoque.
            var range: ColorRange? = null
            if (it.byColor) {
                range = analyseColor(small)
                if (range == null) colorless.add(it.id)
            }

            if (blurSigma > 0f) {
                Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), blurSigma.toDouble())
            }

            val vs = ArrayList<Variant>()
            vs.add(Variant(small))
            if (useVariants) {
                val m = max(small.cols(), small.rows())
                val sg = max(1.0, 0.035 * m)
                vs.add(Variant(blurred(small, sg)))
                vs.add(Variant(blurred(small, 2 * sg)))
                for (f in doubleArrayOf(0.88, 1.12)) {
                    val vw = (small.cols() * f).roundToInt()
                    val vh = (small.rows() * f).roundToInt()
                    if (vw < 6 || vh < 6) continue
                    val r = Mat()
                    Imgproc.resize(small, r, Size(vw.toDouble(), vh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    vs.add(Variant(r))
                }
            }
            index[it.id] = loaded.size
            loaded.add(Loaded(it.id, vs, range, it.byColor && range != null))
        }
    }

    private fun blurred(src: Mat, sigma: Double): Mat {
        val d = Mat()
        Imgproc.GaussianBlur(src, d, Size(0.0, 0.0), sigma)
        return d
    }

    private fun analyseColor(rgb: Mat): ColorRange? {
        val hsv = Mat()
        try {
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val bytes = ByteArray(hsv.rows() * hsv.cols() * 3)
            hsv.get(0, 0, bytes)
            return ColorMath.dominant(bytes, hsv.cols(), hsv.rows(), hueTol)
        } finally {
            hsv.release()
        }
    }

    /** Borra o quadro no lugar, se o desfoque leve estiver ligado. Chamar antes de buscar. */
    fun prepare(frame: Mat) {
        if (blurSigma > 0f) {
            Imgproc.GaussianBlur(frame, frame, Size(0.0, 0.0), blurSigma.toDouble())
        }
    }

    /**
     * Melhor combinação do modelo em [region] (testa as variantes se [allVariants]). Com [guide],
     * a nota exigida depende da distância à posição prevista (histerese espacial); sem ele, devolve
     * o melhor pico, e quem chama confere a nota.
     */
    private fun bestMatch(region: Mat, l: Loaded, res: Mat, allVariants: Boolean, guide: Guide? = null): Best? {
        var best: Best? = null
        for ((i, v) in l.variants.withIndex()) {
            if (i > 0 && !allVariants) break
            if (v.w > region.cols() || v.h > region.rows()) continue
            Imgproc.matchTemplate(region, v.mat, res, Imgproc.TM_CCOEFF_NORMED)
            val penalty = if (i == 0) 0f else VARIANT_PENALTY
            val cand = if (guide == null) globalPeak(res, v, penalty) else guidedPeak(res, v, penalty, guide)
            val cur = best
            if (cand != null && (cur == null || cand.score > cur.score)) best = cand
            // Já combinou muito bem: não precisa testar as outras versões.
            if ((best?.score ?: 0f) >= 0.92f) break
        }
        return best
    }

    private fun globalPeak(res: Mat, v: Variant, penalty: Float): Best? {
        val mm = Core.minMaxLoc(res)
        if (mm.maxVal.isNaN()) return null
        return Best(mm.maxVal.toFloat() - penalty, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt(), v)
    }

    /**
     * Dois candidatos: o pico global, que só vale se a nota cresce com a distância à previsão;
     * e o melhor pico perto da previsão, que vale com a nota de seguir. Assim um ruído
     * parecido com o alvo, longe de onde ele deveria estar, não segura um alvo "fantasma".
     */
    private fun guidedPeak(res: Mat, v: Variant, penalty: Float, guide: Guide): Best? {
        var out: Best? = null
        val size = max(v.w, v.h).toFloat()

        val mm = Core.minMaxLoc(res)
        if (!mm.maxVal.isNaN()) {
            val score = mm.maxVal.toFloat() - penalty
            val d = hypot(mm.maxLoc.x + v.w / 2f - guide.px, mm.maxLoc.y + v.h / 2f - guide.py).toFloat()
            val t = ((d - 0.35f * size) / (0.85f * size)).coerceIn(0f, 1f)
            val need = guide.follow + (guide.acquire - guide.follow) * t
            if (score >= need) out = Best(score, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt(), v)
        }

        val rr = (0.35f * size).toInt()
        val cx = (guide.px - v.w / 2f).roundToInt()
        val cy = (guide.py - v.h / 2f).roundToInt()
        val x0 = max(0, cx - rr)
        val y0 = max(0, cy - rr)
        val x1 = minOf(res.cols(), cx + rr + 1)
        val y1 = minOf(res.rows(), cy + rr + 1)
        if (x1 > x0 && y1 > y0) {
            val sub = res.submat(y0, y1, x0, x1)
            val m2 = try {
                Core.minMaxLoc(sub)
            } finally {
                sub.release()
            }
            if (!m2.maxVal.isNaN()) {
                val score = m2.maxVal.toFloat() - penalty
                val cur = out
                if (score >= guide.follow && (cur == null || score > cur.score)) {
                    out = Best(score, x0 + m2.maxLoc.x.toInt(), y0 + m2.maxLoc.y.toInt(), v)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- varredura completa

    /**
     * Varre o quadro inteiro. [acquire] = nota mínima para ACHAR um alvo (alta). Modelos com
     * cor usam a cor para achar candidatos e a imagem só confirma, com nota mínima [confirm]
     * (mais baixa, pois a cor também é prova). Se a cor não achar nada, vale a varredura normal.
     */
    fun detect(frame: Mat, acquire: Float, confirm: Float, maxPerTemplate: Int = 6): List<Detection> {
        val found = ArrayList<Detection>()
        val res = Mat()
        try {
            for (l in loaded) {
                val tw = l.base.cols()
                val th = l.base.rows()
                if (tw > frame.cols() || th > frame.rows()) continue

                val range = l.range
                if (l.wantColor && range != null) {
                    val byColor = colorScan(frame, l, range, confirm, maxPerTemplate, res)
                    if (byColor.isNotEmpty()) {
                        found.addAll(byColor)
                        continue
                    }
                }
                fullMatch(frame, l, acquire, maxPerTemplate, res, found)
            }
        } finally {
            res.release()
        }
        return found
    }

    private fun fullMatch(
        frame: Mat, l: Loaded, threshold: Float, maxPerTemplate: Int, res: Mat,
        out: MutableList<Detection>
    ) {
        val tpl = l.base
        Imgproc.matchTemplate(frame, tpl, res, Imgproc.TM_CCOEFF_NORMED)
        var n = 0
        while (n < maxPerTemplate) {
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal.isNaN() || mm.maxVal < threshold) break

            val x = mm.maxLoc.x.toInt()
            val y = mm.maxLoc.y.toInt()
            out.add(
                Detection(
                    l.id, x + tpl.cols() / 2f, y + tpl.rows() / 2f,
                    tpl.cols().toFloat(), tpl.rows().toFloat(), mm.maxVal.toFloat()
                )
            )

            // Apaga a vizinhança deste resultado para achar o próximo igual.
            val x0 = maxOf(0, x - tpl.cols() / 2)
            val y0 = maxOf(0, y - tpl.rows() / 2)
            val x1 = minOf(res.cols() - 1, x + tpl.cols() / 2)
            val y1 = minOf(res.rows() - 1, y + tpl.rows() / 2)
            Imgproc.rectangle(
                res,
                Point(x0.toDouble(), y0.toDouble()),
                Point(x1.toDouble(), y1.toDouble()),
                Scalar(-1.0),
                -1
            )
            n++
        }
    }

    private fun colorScan(
        frame: Mat, l: Loaded, range: ColorRange, confirm: Float, maxPerTemplate: Int, res: Mat
    ): List<Detection> {
        val out = ArrayList<Detection>()
        val tw = l.base.cols()
        val th = l.base.rows()
        val mask = Mat()
        try {
            colorMask(frame, range, mask, l.closeK)
            val expArea = max(1f, range.frac * tw * th)
            val pad = max(10, (0.3f * max(tw, th)).toInt())
            for (b in blobs(mask, expArea, range, null).take(maxPerTemplate)) {
                // Centro provável do alvo, a partir da mancha de cor.
                val gx = (b[0] - range.offX).roundToInt()
                val gy = (b[1] - range.offY).roundToInt()
                val rx0 = max(0, gx - tw / 2 - pad)
                val ry0 = max(0, gy - th / 2 - pad)
                val rx1 = minOf(frame.cols(), gx + tw / 2 + pad + 1)
                val ry1 = minOf(frame.rows(), gy + th / 2 + pad + 1)
                if (rx1 - rx0 < tw || ry1 - ry0 < th) continue

                val region = frame.submat(ry0, ry1, rx0, rx1)
                val best = try {
                    bestMatch(region, l, res, useVariants)
                } finally {
                    region.release()
                }
                if (best != null && best.score >= confirm) {
                    out.add(
                        Detection(
                            l.id, rx0 + best.x + best.v.w / 2f, ry0 + best.y + best.v.h / 2f,
                            best.v.w.toFloat(), best.v.h.toFloat(), best.score
                        )
                    )
                }
            }
        } finally {
            mask.release()
        }
        return out
    }

    // ---------------------------------------------------------------- busca local (rastreio)

    /**
     * Procura de novo o modelo [tplId] só ao redor de ([cx], [cy]) (onde o rastreio prevê o
     * alvo). Perto da previsão aceita a nota de SEGUIR ([follow], mais baixa); quanto mais longe,
     * mais perto da nota de ACHAR ([acquire]) é exigida. Se a imagem não combina e o modelo tem
     * cor, tenta a mancha de cor mais perto da previsão. Devolve null se não achou.
     */
    fun matchNear(frame: Mat, tplId: String, cx: Float, cy: Float, follow: Float, acquire: Float): Detection? {
        val k = index[tplId] ?: return null
        val l = loaded[k]
        val tw = l.base.cols()
        val th = l.base.rows()
        val r = TrackMath.roi(
            (cx - tw / 2f).roundToInt(), (cy - th / 2f).roundToInt(),
            tw, th, frame.cols(), frame.rows()
        ) ?: return null

        val res = Mat()
        try {
            val region = frame.submat(r[1], r[3], r[0], r[2])
            val guide = Guide(cx - r[0], cy - r[1], follow, max(follow, acquire))
            val best = try {
                bestMatch(region, l, res, useVariants, guide)
            } finally {
                region.release()
            }
            if (best != null) {
                return Detection(
                    tplId, r[0] + best.x + best.v.w / 2f, r[1] + best.y + best.v.h / 2f,
                    best.v.w.toFloat(), best.v.h.toFloat(), best.score
                )
            }

            val range = l.range
            if (l.wantColor && range != null) {
                return colorNear(frame, l, range, r, cx, cy, follow)
            }
            return null
        } finally {
            res.release()
        }
    }

    private fun colorNear(
        frame: Mat, l: Loaded, range: ColorRange, r: IntArray, cx: Float, cy: Float, score: Float
    ): Detection? {
        val tw = l.base.cols()
        val th = l.base.rows()
        val mask = Mat()
        try {
            val region = frame.submat(r[1], r[3], r[0], r[2])
            try {
                colorMask(region, range, mask, l.closeK)
            } finally {
                region.release()
            }
            val expArea = max(1f, range.frac * tw * th)
            // Bordas da região que são cortes artificiais (não são a borda do quadro): uma mancha
            // grande cortada ali parece pequena e do tamanho certo, mas não é o alvo.
            val cut = booleanArrayOf(r[0] > 0, r[1] > 0, r[2] < frame.cols(), r[3] < frame.rows())
            // Mancha mais perto da posição prevista (dentro da região).
            var pick: FloatArray? = null
            var pickD = Float.MAX_VALUE
            for (b in blobs(mask, expArea, range, cut)) {
                val d = kotlin.math.hypot(b[0] + r[0] - range.offX - cx, b[1] + r[1] - range.offY - cy)
                if (d < pickD) {
                    pickD = d
                    pick = b
                }
            }
            val b = pick ?: return null
            return Detection(
                l.id, r[0] + b[0] - range.offX, r[1] + b[1] - range.offY,
                tw.toFloat(), th.toFloat(), score, byColor = true
            )
        } finally {
            mask.release()
        }
    }

    // ---------------------------------------------------------------- cor (HSV)

    /** Máscara (8 bits) dos pixels de [src] (RGB) que têm a cor de [range], já "fechada". */
    private fun colorMask(src: Mat, range: ColorRange, mask: Mat, closeK: Int) {
        val hsv = Mat()
        val tmp = Mat()
        try {
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)
            val lo = range.hue - hueTol
            val hi = range.hue + hueTol
            val ranges = ArrayList<IntArray>()
            if (lo < 0) {
                ranges.add(intArrayOf(0, hi))
                ranges.add(intArrayOf(lo + 180, 179))
            } else if (hi > 179) {
                ranges.add(intArrayOf(lo, 179))
                ranges.add(intArrayOf(0, hi - 180))
            } else {
                ranges.add(intArrayOf(lo, hi))
            }
            for ((i, rg) in ranges.withIndex()) {
                val out = if (i == 0) mask else tmp
                Core.inRange(
                    hsv,
                    Scalar(rg[0].toDouble(), range.sMin.toDouble(), range.vMin.toDouble()),
                    Scalar(rg[1].toDouble(), 255.0, 255.0),
                    out
                )
                if (i > 0) Core.bitwise_or(mask, tmp, mask)
            }
            // Fecha buracos pequenos: o borrão e a borda do alvo deixam a mancha esburacada.
            var kn = kernels[closeK]
            if (kn == null) {
                kn = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(closeK.toDouble(), closeK.toDouble()))
                kernels[closeK] = kn
            }
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kn)
        } finally {
            hsv.release()
            tmp.release()
        }
    }

    /**
     * Manchas da máscara com área plausível (0,25x a 5x a esperada) e caixa de formato parecido
     * com a da imagem de referência (0,4x a 2,2x), da mais parecida com a esperada para a menos.
     * Cada uma é [centroX, centroY, área] em pixels da máscara. [cut] = quais bordas da máscara
     * (esquerda, topo, direita, baixo) são cortes artificiais: manchas que encostam nelas são
     * descartadas.
     */
    private fun blobs(mask: Mat, expArea: Float, range: ColorRange, cut: BooleanArray?): List<FloatArray> {
        val labels = Mat()
        val stats = Mat()
        val cent = Mat()
        val out = ArrayList<FloatArray>()
        try {
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cent, 8, CvType.CV_32S)
            val st = IntArray(5)
            val c = DoubleArray(2)
            for (i in 1 until n) {
                stats.get(i, 0, st)
                cent.get(i, 0, c)
                val area = st[Imgproc.CC_STAT_AREA].toFloat()
                if (area < 0.25f * expArea || area > 5f * expArea) continue
                val bw = st[Imgproc.CC_STAT_WIDTH].toFloat()
                val bh = st[Imgproc.CC_STAT_HEIGHT].toFloat()
                if (bw < 0.4f * range.bw || bw > 2.2f * range.bw) continue
                if (bh < 0.4f * range.bh || bh > 2.2f * range.bh) continue
                if (cut != null) {
                    val l = st[Imgproc.CC_STAT_LEFT]
                    val t = st[Imgproc.CC_STAT_TOP]
                    if ((cut[0] && l <= 0) || (cut[1] && t <= 0) ||
                        (cut[2] && l + bw.toInt() >= mask.cols()) ||
                        (cut[3] && t + bh.toInt() >= mask.rows())
                    ) continue
                }
                val ctr = refine(mask, c[0].toFloat(), c[1].toFloat(), range)
                out.add(floatArrayOf(ctr[0], ctr[1], area))
            }
        } finally {
            labels.release()
            stats.release()
            cent.release()
        }
        out.sortBy { abs(ln(it[2] / expArea)) }
        return out
    }

    /**
     * Refina o centro de uma mancha pelo centro de massa de TODOS os pixels da cor numa janela ao
     * redor (como um passo de mean-shift). Quando um detalhe fino do alvo (uma listra clara, o
     * borrão) parte a mancha em pedaços, o centro de um pedaço sozinho erra; o da janela, não.
     */
    private fun refine(mask: Mat, cx: Float, cy: Float, range: ColorRange): FloatArray {
        var x = cx
        var y = cy
        val hw = max(4, (0.65f * range.bw).toInt())
        val hh = max(4, (0.65f * range.bh).toInt())
        for (iter in 0 until 3) {
            val x0 = max(0, (x - hw).toInt())
            val y0 = max(0, (y - hh).toInt())
            val x1 = minOf(mask.cols(), (x + hw).toInt() + 1)
            val y1 = minOf(mask.rows(), (y + hh).toInt() + 1)
            if (x1 <= x0 || y1 <= y0) break
            val sub = mask.submat(y0, y1, x0, x1)
            val m = try {
                Imgproc.moments(sub, true)
            } finally {
                sub.release()
            }
            if (m.m00 <= 0.0) break
            x = x0 + (m.m10 / m.m00).toFloat()
            y = y0 + (m.m01 / m.m00).toFloat()
        }
        return floatArrayOf(x, y)
    }

    fun release() {
        for (l in loaded) for (v in l.variants) v.mat.release()
        loaded.clear()
        index.clear()
        colorless.clear()
        for (k in kernels.values) k.release()
        kernels.clear()
    }
}
