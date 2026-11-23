package com.macro.visionpaint

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Câmera orbitando a origem (o centro do modelo). [ex] = direita, [ey] = cima, [ez] = de trás
 * (do modelo para a câmera). Projeção em perspectiva: sx = cx + f * x / z, sy = cy - f * y / z.
 */
class CamInfo(
    val ex: FloatArray,
    val ey: FloatArray,
    val ez: FloatArray,
    val pos: FloatArray,
    val f: Float,
    val cx: Float,
    val cy: Float
)

/** Imagem do renderizador: cor ARGB (alfa 0 = fundo) e profundidade (distância pela câmera; infinito = fundo). */
class Render(val w: Int, val h: Int) {
    val argb = IntArray(w * h)
    val depth = FloatArray(w * h)
    var cam: CamInfo? = null
}

/**
 * Renderizador por software (sem Android, sem OpenGL): z-buffer, perspectiva, luz simples,
 * cor por material/vértice/textura. Serve para a pré-visualização, para escolher a área de mira
 * (devolve a profundidade de cada pixel) e para gerar as vistas que o app procura na tela.
 */
object Rasterizer {
    /** Distância da câmera à origem, em unidades do modelo (o modelo cabe numa esfera de raio 0,87). */
    const val CAM_DIST = 2.0f

    private const val AMBIENT = 0.42f
    private val LIGHT = norm3(floatArrayOf(0.30f, 0.55f, 0.78f))

    private fun norm3(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    /**
     * Câmera olhando para a origem. [yawDeg]: giro em volta do eixo vertical (0 = olhando de frente,
     * pelo lado +Z); [pitchDeg]: positivo = câmera acima do modelo. [pxPerUnit] = pixels por unidade
     * (a unidade é o maior lado do modelo) na distância da origem.
     */
    fun camera(yawDeg: Float, pitchDeg: Float, pxPerUnit: Float, cx: Float, cy: Float, dist: Float = CAM_DIST): CamInfo {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.coerceIn(-85f, 85f).toDouble())
        val dx = (sin(yaw) * cos(pitch)).toFloat()
        val dy = sin(pitch).toFloat()
        val dz = (cos(yaw) * cos(pitch)).toFloat()
        val ez = floatArrayOf(dx, dy, dz)
        // direita = cima(0,1,0) x ez
        val ex = norm3(floatArrayOf(ez[2], 0f, -ez[0]))
        // cima da câmera = ez x ex
        val ey = floatArrayOf(
            ez[1] * ex[2] - ez[2] * ex[1],
            ez[2] * ex[0] - ez[0] * ex[2],
            ez[0] * ex[1] - ez[1] * ex[0]
        )
        return CamInfo(ex, ey, ez, floatArrayOf(dx * dist, dy * dist, dz * dist), pxPerUnit * dist, cx, cy)
    }

    /** Projeta o ponto 3D [p]; devolve (x da tela, y da tela, profundidade). */
    fun project(cam: CamInfo, p: FloatArray, out: FloatArray = FloatArray(3)): FloatArray {
        val rx = p[0] - cam.pos[0]
        val ry = p[1] - cam.pos[1]
        val rz = p[2] - cam.pos[2]
        val vx = rx * cam.ex[0] + ry * cam.ex[1] + rz * cam.ex[2]
        val vy = rx * cam.ey[0] + ry * cam.ey[1] + rz * cam.ey[2]
        val z = -(rx * cam.ez[0] + ry * cam.ez[1] + rz * cam.ez[2])
        out[0] = cam.cx + cam.f * vx / z
        out[1] = cam.cy - cam.f * vy / z
        out[2] = z
        return out
    }

    /** O contrário de [project]: o ponto 3D que está no pixel ([sx], [sy]) à profundidade [z]. */
    fun unproject(cam: CamInfo, sx: Float, sy: Float, z: Float): FloatArray {
        val vx = (sx - cam.cx) * z / cam.f
        val vy = -(sy - cam.cy) * z / cam.f
        return floatArrayOf(
            cam.pos[0] + vx * cam.ex[0] + vy * cam.ey[0] - z * cam.ez[0],
            cam.pos[1] + vx * cam.ex[1] + vy * cam.ey[1] - z * cam.ez[1],
            cam.pos[2] + vx * cam.ex[2] + vy * cam.ey[2] - z * cam.ez[2]
        )
    }

    /** Ponto 3D do modelo que está no pixel ([x], [y]) de [r], ou null se ali é fundo. */
    fun pick(r: Render, x: Int, y: Int): FloatArray? {
        val cam = r.cam ?: return null
        if (x < 0 || y < 0 || x >= r.w || y >= r.h) return null
        val z = r.depth[y * r.w + x]
        if (z.isInfinite()) return null
        return unproject(cam, x + 0.5f, y + 0.5f, z)
    }

    /**
     * Desenha [model] em [out]. [depthOnly] = só a profundidade (mais rápido: não calcula cor);
     * os pixels cobertos saem brancos.
     */
    fun render(model: Model3D, cam: CamInfo, out: Render, shaded: Boolean = true, depthOnly: Boolean = false) {
        java.util.Arrays.fill(out.argb, 0)
        java.util.Arrays.fill(out.depth, Float.POSITIVE_INFINITY)
        out.cam = cam
        val w = out.w
        val h = out.h
        val argb = out.argb
        val depth = out.depth

        for ((pi, part) in model.parts.withIndex()) {
            val nv = part.pos.size / 3
            val sx = FloatArray(nv)
            val sy = FloatArray(nv)
            val iz = FloatArray(nv)
            val nx = FloatArray(nv)
            val ny = FloatArray(nv)
            val nzv = FloatArray(nv)
            val nrm = model.nrm[pi]
            val tmp = FloatArray(3)
            val pt = FloatArray(3)
            for (v in 0 until nv) {
                pt[0] = part.pos[v * 3]; pt[1] = part.pos[v * 3 + 1]; pt[2] = part.pos[v * 3 + 2]
                project(cam, pt, tmp)
                sx[v] = tmp[0]
                sy[v] = tmp[1]
                iz[v] = if (tmp[2] > 0.05f) 1f / tmp[2] else 0f
                val a = nrm[v * 3]
                val b = nrm[v * 3 + 1]
                val c = nrm[v * 3 + 2]
                nx[v] = a * cam.ex[0] + b * cam.ex[1] + c * cam.ex[2]
                ny[v] = a * cam.ey[0] + b * cam.ey[1] + c * cam.ey[2]
                nzv[v] = a * cam.ez[0] + b * cam.ez[1] + c * cam.ez[2]
            }

            val tex = part.tex
            val uv = if (tex != null) part.uv else null
            val vcol = part.vcol
            val base = part.baseColor
            val br = (base shr 16) and 0xFF
            val bg = (base shr 8) and 0xFF
            val bb = base and 0xFF
            // Cor por vértice já multiplicada pela cor do material.
            val cr = FloatArray(nv)
            val cg = FloatArray(nv)
            val cb = FloatArray(nv)
            for (v in 0 until nv) {
                if (vcol != null) {
                    val c = vcol[v]
                    cr[v] = ((c shr 16) and 0xFF) * br / 255f
                    cg[v] = ((c shr 8) and 0xFF) * bg / 255f
                    cb[v] = (c and 0xFF) * bb / 255f
                } else {
                    cr[v] = br.toFloat()
                    cg[v] = bg.toFloat()
                    cb[v] = bb.toFloat()
                }
            }

            val idx = part.idx
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t]
                val b = idx[t + 1]
                val c = idx[t + 2]
                t += 3
                if (iz[a] == 0f || iz[b] == 0f || iz[c] == 0f) continue
                val x0 = sx[a]; val y0 = sy[a]
                val x1 = sx[b]; val y1 = sy[b]
                val x2 = sx[c]; val y2 = sy[c]
                val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
                if (abs(area) < 1e-6f) continue
                val minX = max(0, floor(min(x0, min(x1, x2)) - 0.5f).toInt())
                val maxX = min(w - 1, floor(max(x0, max(x1, x2)) + 0.5f).toInt() + 1)
                val minY = max(0, floor(min(y0, min(y1, y2)) - 0.5f).toInt())
                val maxY = min(h - 1, floor(max(y0, max(y1, y2)) + 0.5f).toInt() + 1)
                if (minX > maxX || minY > maxY) continue
                val invArea = 1f / area
                val za = iz[a]; val zb = iz[b]; val zc = iz[c]

                for (py in minY..maxY) {
                    val fy = py + 0.5f
                    for (px in minX..maxX) {
                        val fx = px + 0.5f
                        val w0 = ((x1 - fx) * (y2 - fy) - (x2 - fx) * (y1 - fy)) * invArea
                        val w1 = ((x2 - fx) * (y0 - fy) - (x0 - fx) * (y2 - fy)) * invArea
                        val w2 = 1f - w0 - w1
                        if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                        val inv = w0 * za + w1 * zb + w2 * zc
                        if (inv <= 0f) continue
                        val z = 1f / inv
                        val o = py * w + px
                        if (z >= depth[o]) continue
                        depth[o] = z
                        if (depthOnly) {
                            argb[o] = -1
                            continue
                        }

                        // Pesos corrigidos pela perspectiva.
                        val p0 = w0 * za * z
                        val p1 = w1 * zb * z
                        val p2 = w2 * zc * z

                        var r: Float
                        var g: Float
                        var bl: Float
                        if (uv != null && tex != null) {
                            var u = p0 * uv[a * 2] + p1 * uv[b * 2] + p2 * uv[c * 2]
                            var v = p0 * uv[a * 2 + 1] + p1 * uv[b * 2 + 1] + p2 * uv[c * 2 + 1]
                            u -= floor(u)
                            v -= floor(v)
                            val tx = min(tex.w - 1, (u * tex.w).toInt())
                            val ty = min(tex.h - 1, (v * tex.h).toInt())
                            val tc = tex.argb[ty * tex.w + tx]
                            r = ((tc shr 16) and 0xFF) * br / 255f
                            g = ((tc shr 8) and 0xFF) * bg / 255f
                            bl = (tc and 0xFF) * bb / 255f
                        } else {
                            r = p0 * cr[a] + p1 * cr[b] + p2 * cr[c]
                            g = p0 * cg[a] + p1 * cg[b] + p2 * cg[c]
                            bl = p0 * cb[a] + p1 * cb[b] + p2 * cb[c]
                        }
                        if (shaded) {
                            var qx = p0 * nx[a] + p1 * nx[b] + p2 * nx[c]
                            var qy = p0 * ny[a] + p1 * ny[b] + p2 * ny[c]
                            var qz = p0 * nzv[a] + p1 * nzv[b] + p2 * nzv[c]
                            val l = sqrt(qx * qx + qy * qy + qz * qz)
                            if (l > 1e-9f) {
                                qx /= l; qy /= l; qz /= l
                            }
                            // Duas faces: a normal sempre vira para o lado da câmera.
                            if (qz < 0f) {
                                qx = -qx; qy = -qy; qz = -qz
                            }
                            val d = max(0f, qx * LIGHT[0] + qy * LIGHT[1] + qz * LIGHT[2])
                            val k = AMBIENT + (1f - AMBIENT) * d
                            r *= k; g *= k; bl *= k
                        }
                        argb[o] = (0xFF shl 24) or
                            (r.toInt().coerceIn(0, 255) shl 16) or
                            (g.toInt().coerceIn(0, 255) shl 8) or
                            bl.toInt().coerceIn(0, 255)
                    }
                }
            }
        }
    }
}
