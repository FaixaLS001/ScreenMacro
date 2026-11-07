package com.macro.visionpaint

import android.graphics.BitmapFactory
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/** Um alvo encontrado. [rect] já está em pixels reais da tela. */
data class Target(val rect: Rect, val score: Float) {
    val cx: Float get() = rect.exactCenterX()
    val cy: Float get() = rect.exactCenterY()
}

/**
 * Procura na imagem da tela os modelos enviados pelo usuário (template matching com OpenCV).
 * [scale] é a escala da captura em relação à tela real (ex.: 0.5 = metade do tamanho).
 */
class Detector(private val scale: Float) {

    private val templates = ArrayList<Mat>()

    fun templateCount(): Int = templates.size

    fun loadTemplates(files: List<File>) {
        release()
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
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
            templates.add(small)
        }
    }

    /**
     * [frame]: imagem RGB da tela na escala da captura.
     * Usa TM_CCOEFF_NORMED, que ignora mudanças uniformes de brilho/contraste, então a
     * própria pintura translúcida do overlay quase não atrapalha o reconhecimento.
     */
    fun detect(frame: Mat, threshold: Float, maxPerTemplate: Int = 6): List<Target> {
        val found = ArrayList<Target>()
        val res = Mat()
        val inv = 1f / scale

        for (tpl in templates) {
            if (tpl.cols() > frame.cols() || tpl.rows() > frame.rows()) continue
            Imgproc.matchTemplate(frame, tpl, res, Imgproc.TM_CCOEFF_NORMED)

            var n = 0
            while (n < maxPerTemplate) {
                val mm = Core.minMaxLoc(res)
                if (mm.maxVal.isNaN() || mm.maxVal < threshold) break

                val x = mm.maxLoc.x.toInt()
                val y = mm.maxLoc.y.toInt()
                found.add(
                    Target(
                        Rect(
                            (x * inv).toInt(),
                            (y * inv).toInt(),
                            ((x + tpl.cols()) * inv).toInt(),
                            ((y + tpl.rows()) * inv).toInt()
                        ),
                        mm.maxVal.toFloat()
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
        res.release()
        return found
    }

    fun release() {
        templates.forEach { it.release() }
        templates.clear()
    }
}
