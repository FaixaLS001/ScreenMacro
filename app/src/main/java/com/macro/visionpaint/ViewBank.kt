package com.macro.visionpaint

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Uma vista do modelo (de um ângulo e de uma altura de câmera): os pontos do contorno (silhueta e
 * bordas onde uma parte tapa outra) com a direção da borda, e onde fica o ponto de mira.
 *
 * Tudo em "unidades do modelo" (o maior lado do modelo = 1) e em coordenadas de imagem (y para
 * baixo), com a origem no centro do modelo. [pts] guarda 5 números por ponto:
 * u, v, c, s, peso, onde (c, s) = (cos 2θ, sin 2θ) e θ é a direção da normal da borda
 * (o dobro do ângulo, para ignorar o sentido: a borda vale igual de claro para escuro e o inverso).
 */
class BankView(
    val yaw: Float,
    val pitch: Float,
    val pts: FloatArray,
    val aimU: Float,
    val aimV: Float,
    val minU: Float,
    val maxU: Float,
    val minV: Float,
    val maxV: Float
) {
    val count: Int get() = pts.size / 5

    /**
     * Direção da normal da borda de cada ponto, em radianos (-pi/2 a pi/2): metade do ângulo de
     * (c, s), que guardam o dobro dele.
     */
    val normalAngle: FloatArray by lazy {
        FloatArray(count) { 0.5f * atan2(pts[it * 5 + 3], pts[it * 5 + 2]) }
    }
}

/**
 * As vistas de um modelo: [pitches].size alturas de câmera x [yawCount] giros. A vista
 * (pitch i, giro j) fica em [views] no índice i * yawCount + j.
 */
class ViewBank(
    val yawCount: Int,
    val pitches: FloatArray,
    val views: Array<BankView>,
    /** Raio da área de mira, em unidades do modelo. */
    val aimRadius: Float
) {
    val yawStep: Float get() = 360f / yawCount

    fun index(pitchIdx: Int, yawIdx: Int): Int =
        pitchIdx.coerceIn(0, pitches.size - 1) * yawCount + ((yawIdx % yawCount) + yawCount) % yawCount

    fun pitchOf(i: Int): Int = i / yawCount
    fun yawOf(i: Int): Int = i % yawCount

    /** Vistas vizinhas de [i]: um passo de giro para cada lado e uma altura acima/abaixo. */
    fun neighbors(i: Int): IntArray {
        val p = pitchOf(i)
        val y = yawOf(i)
        val out = ArrayList<Int>(8)
        for (dp in -1..1) {
            val np = p + dp
            if (np < 0 || np >= pitches.size) continue
            for (dy in -1..1) {
                if (dp == 0 && dy == 0) continue
                out.add(index(np, y + dy))
            }
        }
        return out.toIntArray()
    }
}

object BankBuilder {
    /** Lado (px) da imagem de cada vista e pixels por unidade do modelo nela. */
    const val SIZE = 200
    const val PX = 100f

    /** Fundo para o cálculo da borda: mais longe que qualquer ponto do modelo. */
    private const val BG_DEPTH = 4.0f

    /** Diferença de profundidade (unidades) entre vizinhos que conta como uma parte tapando a outra. */
    private const val INNER_JUMP = 0.08f

    val DEFAULT_PITCHES = floatArrayOf(-10f, 15f, 40f)

    /**
     * Gera as vistas. [aim] = ponto de mira em coordenadas do [model] normalizado,
     * [aimRadius] = raio da área de mira (unidades), [yawStepDeg] = passo do giro.
     */
    fun build(
        model: Model3D,
        aim: FloatArray,
        aimRadius: Float,
        yawStepDeg: Float = 20f,
        pitches: FloatArray = DEFAULT_PITCHES
    ): ViewBank {
        val yawCount = max(4, (360f / yawStepDeg).roundToInt())
        val r = Render(SIZE, SIZE)
        val views = ArrayList<BankView>()
        for (pitch in pitches) {
            for (j in 0 until yawCount) {
                val yaw = j * 360f / yawCount
                val cam = Rasterizer.camera(yaw, pitch, PX, SIZE / 2f, SIZE / 2f)
                Rasterizer.render(model, cam, r, shaded = false, depthOnly = true)
                views.add(extract(r, cam, aim, yaw, pitch))
            }
        }
        return ViewBank(yawCount, pitches, views.toTypedArray(), aimRadius)
    }

    private fun extract(r: Render, cam: CamInfo, aim: FloatArray, yaw: Float, pitch: Float): BankView {
        val w = r.w
        val h = r.h
        val z = FloatArray(w * h)
        for (i in z.indices) {
            val d = r.depth[i]
            z[i] = if (d.isInfinite()) BG_DEPTH else d
        }
        val pts = FloatList(4096)
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val o = y * w + x
                if (r.depth[o].isInfinite()) continue
                val zc = z[o]
                var sil = false
                var inner = false
                for (nb in intArrayOf(o - 1, o + 1, o - w, o + w)) {
                    if (r.depth[nb].isInfinite()) sil = true
                    else if (z[nb] - zc > INNER_JUMP) inner = true
                }
                if (!sil && !inner) continue
                val gx = (z[o - w + 1] + 2f * z[o + 1] + z[o + w + 1]) - (z[o - w - 1] + 2f * z[o - 1] + z[o + w - 1])
                val gy = (z[o + w - 1] + 2f * z[o + w] + z[o + w + 1]) - (z[o - w - 1] + 2f * z[o - w] + z[o - w + 1])
                val g2 = gx * gx + gy * gy
                if (g2 < 1e-10f) continue
                val u = (x + 0.5f - cam.cx) / PX
                val v = (y + 0.5f - cam.cy) / PX
                pts.add(u)
                pts.add(v)
                pts.add((gx * gx - gy * gy) / g2)
                pts.add(2f * gx * gy / g2)
                pts.add(if (sil) 1f else 0.5f)
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
        }
        val a = Rasterizer.project(cam, aim)
        return BankView(
            yaw, pitch, pts.toArray(),
            (a[0] - cam.cx) / PX, (a[1] - cam.cy) / PX,
            minU, maxU, minV, maxV
        )
    }
}
