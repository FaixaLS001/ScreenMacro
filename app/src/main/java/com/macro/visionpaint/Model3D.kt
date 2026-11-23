package com.macro.visionpaint

import kotlin.math.max
import kotlin.math.sqrt

/** Imagem de textura já decodificada (ARGB, linha por linha). */
class TexImage(val w: Int, val h: Int, val argb: IntArray)

/**
 * Um pedaço da malha com um só material.
 * [pos] = x,y,z por vértice; [idx] = 3 índices por triângulo; [uv] = u,v por vértice (ou null);
 * [vcol] = cor ARGB por vértice (ou null); [baseColor] = cor do material (ARGB); [tex] = textura.
 */
class MeshPart(
    val pos: FloatArray,
    val idx: IntArray,
    val uv: FloatArray?,
    val vcol: IntArray?,
    val baseColor: Int,
    val tex: TexImage?
)

class Mesh(val parts: List<MeshPart>) {
    val triangleCount: Int get() = parts.sumOf { it.idx.size / 3 }
}

/** Erro de leitura com mensagem pronta para mostrar ao usuário. */
class ModelFormatException(msg: String) : Exception(msg)

/**
 * Modelo pronto para desenhar: centro da caixa na origem, maior dimensão = 1 (a "unidade") e
 * "para cima" = +Y. [nrm] = normais suaves por vértice (uma lista por parte).
 * [min] e [max] = caixa do modelo já normalizada.
 */
class Model3D(
    val parts: List<MeshPart>,
    val nrm: List<FloatArray>,
    val min: FloatArray,
    val max: FloatArray
) {
    /** Altura (eixo "para cima") em unidades. */
    val height: Float get() = max[1] - min[1]

    /** Ponto dentro da caixa dado em frações (0 a 1) de cada eixo. */
    fun fromFractions(fx: Float, fy: Float, fz: Float): FloatArray = floatArrayOf(
        min[0] + fx * (max[0] - min[0]),
        min[1] + fy * (max[1] - min[1]),
        min[2] + fz * (max[2] - min[2])
    )

    /**
     * Ponto no nível [fy] (0 = base, 1 = topo, na altura do modelo) com x e z iguais à mediana dos vértices
     * numa faixa de ±[band] (fração da altura) em volta desse nível. Serve para os atalhos "cabeça",
     * "peito" etc.: acha o meio da parte que está naquela altura mesmo que o modelo seja assimétrico
     * (arma na frente, mochila atrás).
     */
    fun levelPoint(fy: Float, band: Float = 0.04f): FloatArray {
        val h = height
        val y = min[1] + fy * h
        val lo = y - band * h
        val hi = y + band * h
        val xs = FloatList(256)
        val zs = FloatList(256)
        for (p in parts) {
            var i = 0
            while (i + 2 < p.pos.size) {
                val py = p.pos[i + 1]
                if (py in lo..hi) {
                    xs.add(p.pos[i])
                    zs.add(p.pos[i + 2])
                }
                i += 3
            }
        }
        if (xs.n == 0) return floatArrayOf((min[0] + max[0]) / 2f, y, (min[2] + max[2]) / 2f)
        val a = xs.toArray()
        val b = zs.toArray()
        java.util.Arrays.sort(a)
        java.util.Arrays.sort(b)
        return floatArrayOf(a[a.size / 2], y, b[b.size / 2])
    }

    /** O contrário de [fromFractions]. */
    fun toFractions(p: FloatArray): FloatArray {
        fun f(v: Float, lo: Float, hi: Float): Float = if (hi - lo < 1e-9f) 0.5f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return floatArrayOf(f(p[0], min[0], max[0]), f(p[1], min[1], max[1]), f(p[2], min[2], max[2]))
    }
}

/** Qual eixo do arquivo aponta "para cima" (a maioria dos arquivos .glb usa Y; .stl e Blender, Z). */
object UpAxis {
    const val Y = 0
    const val Z = 1
    const val NEG_Y = 2
    const val NEG_Z = 3
    val names = arrayOf("Y (padrão)", "Z", "-Y", "-Z")
}

object ModelPrep {

    /** Gira a malha para o eixo "para cima" virar +Y, centraliza e ajusta o tamanho (maior lado = 1). */
    fun prepare(mesh: Mesh, up: Int): Model3D {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        val rotated = ArrayList<FloatArray>()
        for (p in mesh.parts) {
            val q = FloatArray(p.pos.size)
            var i = 0
            while (i + 2 < p.pos.size) {
                val x = p.pos[i]
                val y = p.pos[i + 1]
                val z = p.pos[i + 2]
                val nx: Float
                val ny: Float
                val nz: Float
                when (up) {
                    UpAxis.Z -> { nx = x; ny = z; nz = -y }
                    UpAxis.NEG_Y -> { nx = x; ny = -y; nz = -z }
                    UpAxis.NEG_Z -> { nx = x; ny = -z; nz = y }
                    else -> { nx = x; ny = y; nz = z }
                }
                q[i] = nx
                q[i + 1] = ny
                q[i + 2] = nz
                if (nx < mn[0]) mn[0] = nx
                if (ny < mn[1]) mn[1] = ny
                if (nz < mn[2]) mn[2] = nz
                if (nx > mx[0]) mx[0] = nx
                if (ny > mx[1]) mx[1] = ny
                if (nz > mx[2]) mx[2] = nz
                i += 3
            }
            rotated.add(q)
        }
        val ext = max(mx[0] - mn[0], max(mx[1] - mn[1], mx[2] - mn[2]))
        if (!(ext > 1e-9f) || ext.isInfinite()) throw ModelFormatException("O modelo está vazio ou sem tamanho.")
        val cx = (mn[0] + mx[0]) / 2f
        val cy = (mn[1] + mx[1]) / 2f
        val cz = (mn[2] + mx[2]) / 2f
        val k = 1f / ext

        val parts = ArrayList<MeshPart>()
        val normals = ArrayList<FloatArray>()
        for ((pi, p) in mesh.parts.withIndex()) {
            val q = rotated[pi]
            var i = 0
            while (i + 2 < q.size) {
                q[i] = (q[i] - cx) * k
                q[i + 1] = (q[i + 1] - cy) * k
                q[i + 2] = (q[i + 2] - cz) * k
                i += 3
            }
            parts.add(MeshPart(q, p.idx, p.uv, p.vcol, p.baseColor, p.tex))
            normals.add(vertexNormals(q, p.idx))
        }
        return Model3D(
            parts, normals,
            floatArrayOf((mn[0] - cx) * k, (mn[1] - cy) * k, (mn[2] - cz) * k),
            floatArrayOf((mx[0] - cx) * k, (mx[1] - cy) * k, (mx[2] - cz) * k)
        )
    }

    /** Normais por vértice: soma das normais das faces (ponderada pela área), normalizada. */
    private fun vertexNormals(pos: FloatArray, idx: IntArray): FloatArray {
        val n = FloatArray(pos.size)
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t] * 3
            val b = idx[t + 1] * 3
            val c = idx[t + 2] * 3
            val ux = pos[b] - pos[a]
            val uy = pos[b + 1] - pos[a + 1]
            val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]
            val vy = pos[c + 1] - pos[a + 1]
            val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (o in intArrayOf(a, b, c)) {
                n[o] += nx
                n[o + 1] += ny
                n[o + 2] += nz
            }
            t += 3
        }
        var i = 0
        while (i + 2 < n.size) {
            val l = sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
            if (l > 1e-12f) {
                n[i] /= l
                n[i + 1] /= l
                n[i + 2] /= l
            } else {
                n[i + 1] = 1f
            }
            i += 3
        }
        return n
    }
}
