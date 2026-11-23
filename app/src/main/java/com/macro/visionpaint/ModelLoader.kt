package com.macro.visionpaint

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.StringTokenizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Lista de floats que cresce sozinha (sem Android, sem boxing). */
internal class FloatList(cap: Int = 1024) {
    var a = FloatArray(cap)
    var n = 0
    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }

    fun toArray(): FloatArray = a.copyOf(n)
}

internal class IntList(cap: Int = 1024) {
    var a = IntArray(cap)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }

    fun toArray(): IntArray = a.copyOf(n)
}

/**
 * Lê modelos 3D: .obj, .glb / .gltf (com os dados dentro do arquivo), .stl (binário e texto).
 * Sem Android: as texturas são decodificadas por [decoder] (no app, com o BitmapFactory).
 * Cada parte do .glb mantém a cor do material, a cor dos vértices e a textura; .obj e .stl não
 * têm cor (saem em cinza-azulado).
 */
object ModelLoader {
    const val MAX_BYTES = 120 * 1024 * 1024
    const val MAX_TRIANGLES = 1_500_000
    val DEFAULT_COLOR: Int = 0xFFB4BAC8.toInt()

    fun load(bytes: ByteArray, nameHint: String, decoder: ((ByteArray) -> TexImage?)? = null): Mesh {
        if (bytes.size > MAX_BYTES) throw ModelFormatException("Arquivo grande demais (máximo 120 MB).")
        if (bytes.isEmpty()) throw ModelFormatException("Arquivo vazio.")
        val lower = nameHint.lowercase()
        val mesh = try {
            when {
                isGlb(bytes) -> parseGlb(bytes, decoder)
                firstNonSpace(bytes) == '{'.code.toByte() -> parseGltfText(bytes, decoder)
                lower.endsWith(".stl") || isBinaryStl(bytes) || looksLikeAsciiStl(bytes) -> parseStl(bytes)
                else -> parseObj(bytes)
            }
        } catch (e: ModelFormatException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw ModelFormatException("Sem memória para abrir este modelo (é muito pesado).")
        } catch (e: Exception) {
            throw ModelFormatException("Não consegui ler o modelo: ${e.message ?: e.javaClass.simpleName}")
        }
        val tris = mesh.triangleCount
        if (tris == 0) throw ModelFormatException("O arquivo não tem nenhum triângulo (só pontos ou linhas?).")
        if (tris > MAX_TRIANGLES) throw ModelFormatException("Modelo pesado demais ($tris triângulos; máximo $MAX_TRIANGLES).")
        return mesh
    }

    private fun firstNonSpace(b: ByteArray): Byte {
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c != 32 && c != 9 && c != 10 && c != 13 && c != 0xEF && c != 0xBB && c != 0xBF) return x
        }
        return 0
    }

    private fun isGlb(b: ByteArray): Boolean =
        b.size >= 12 && b[0] == 'g'.code.toByte() && b[1] == 'l'.code.toByte() && b[2] == 'T'.code.toByte() && b[3] == 'F'.code.toByte()

    // ------------------------------------------------------------------ OBJ

    private fun parseObj(bytes: ByteArray): Mesh {
        val text = String(bytes, Charsets.UTF_8)
        val vx = FloatList()   // x,y,z do arquivo
        val vc = IntList()     // cor por vértice do arquivo (extensão "v x y z r g b")
        var anyColor = false
        val tx = FloatList()   // u,v
        var nPos = 0
        var nUv = 0

        val outPos = FloatList()
        val outUv = FloatList()
        val outCol = IntList()
        val idx = IntList()
        val remap = HashMap<Long, Int>()
        var usesUv = false

        for (raw in text.lineSequence()) {
            if (raw.length < 2) continue
            val c0 = raw[0]
            if (c0 == '#') continue
            if (c0 == 'v' && raw[1] == ' ') {
                val st = StringTokenizer(raw)
                st.nextToken()
                val x = st.nextToken().toFloat()
                val y = st.nextToken().toFloat()
                val z = st.nextToken().toFloat()
                vx.add(x); vx.add(y); vx.add(z)
                if (st.countTokens() >= 3) {
                    val r = st.nextToken().toFloat()
                    val g = st.nextToken().toFloat()
                    val b = st.nextToken().toFloat()
                    vc.add(packRgb(r, g, b))
                    anyColor = true
                } else {
                    vc.add(0)
                }
                nPos++
            } else if (c0 == 'v' && raw[1] == 't') {
                val st = StringTokenizer(raw)
                st.nextToken()
                val u = st.nextToken().toFloat()
                val v = if (st.hasMoreTokens()) st.nextToken().toFloat() else 0f
                tx.add(u); tx.add(v)
                nUv++
            } else if (c0 == 'f' && raw[1] == ' ') {
                val st = StringTokenizer(raw)
                st.nextToken()
                val face = IntList(8)
                while (st.hasMoreTokens()) {
                    val tok = st.nextToken()
                    val s1 = tok.indexOf('/')
                    val vi: Int
                    var ti = -1
                    if (s1 < 0) {
                        vi = resolve(tok.toInt(), nPos)
                    } else {
                        vi = resolve(tok.substring(0, s1).toInt(), nPos)
                        val s2 = tok.indexOf('/', s1 + 1)
                        val tt = if (s2 < 0) tok.substring(s1 + 1) else tok.substring(s1 + 1, s2)
                        if (tt.isNotEmpty()) ti = resolve(tt.toInt(), nUv)
                    }
                    if (vi < 0 || vi >= nPos) throw ModelFormatException("Face com índice de vértice inválido.")
                    if (ti >= nUv) ti = -1
                    if (ti >= 0) usesUv = true
                    val key = (vi.toLong() shl 32) or (ti + 1).toLong()
                    var o = remap[key]
                    if (o == null) {
                        o = outPos.n / 3
                        remap[key] = o
                        outPos.add(vx.a[vi * 3]); outPos.add(vx.a[vi * 3 + 1]); outPos.add(vx.a[vi * 3 + 2])
                        if (ti >= 0) {
                            outUv.add(tx.a[ti * 2]); outUv.add(tx.a[ti * 2 + 1])
                        } else {
                            outUv.add(0f); outUv.add(0f)
                        }
                        outCol.add(vc.a[vi])
                    }
                    face.add(o)
                }
                // Triangulação em leque (vale para polígonos convexos).
                var k = 1
                while (k + 1 < face.n) {
                    idx.add(face.a[0]); idx.add(face.a[k]); idx.add(face.a[k + 1])
                    k++
                }
            }
        }
        if (idx.n == 0) throw ModelFormatException("O .obj não tem faces.")
        val part = MeshPart(
            outPos.toArray(), idx.toArray(),
            if (usesUv) outUv.toArray() else null,
            if (anyColor) outCol.toArray() else null,
            if (anyColor) 0xFFFFFFFF.toInt() else DEFAULT_COLOR,
            null
        )
        return Mesh(listOf(part))
    }

    private fun resolve(i: Int, count: Int): Int = if (i > 0) i - 1 else count + i

    private fun packRgb(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    // ------------------------------------------------------------------ STL

    private fun isBinaryStl(b: ByteArray): Boolean {
        if (b.size < 84) return false
        val n = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(80).toLong() and 0xFFFFFFFFL
        return 84L + 50L * n == b.size.toLong()
    }

    private fun looksLikeAsciiStl(b: ByteArray): Boolean {
        val head = String(b, 0, min(b.size, 400), Charsets.ISO_8859_1).trimStart().lowercase()
        return head.startsWith("solid") && head.contains("facet")
    }

    private fun parseStl(b: ByteArray): Mesh {
        val raw = FloatList()
        if (isBinaryStl(b)) {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.getInt(80)
            var o = 84
            for (t in 0 until n) {
                o += 12 // normal
                for (k in 0 until 9) raw.add(bb.getFloat(o + 4 * k))
                o += 36 + 2
            }
        } else {
            for (line in String(b, Charsets.ISO_8859_1).lineSequence()) {
                val s = line.trim()
                if (s.startsWith("vertex")) {
                    val st = StringTokenizer(s)
                    st.nextToken()
                    raw.add(st.nextToken().toFloat()); raw.add(st.nextToken().toFloat()); raw.add(st.nextToken().toFloat())
                }
            }
        }
        // Une os vértices iguais (o STL repete cada vértice em cada triângulo), para as normais ficarem suaves.
        val pos = FloatList()
        val idx = IntList()
        val map = HashMap<Triple<Int, Int, Int>, Int>()
        var i = 0
        while (i + 2 < raw.n) {
            val key = Triple(
                java.lang.Float.floatToIntBits(raw.a[i]),
                java.lang.Float.floatToIntBits(raw.a[i + 1]),
                java.lang.Float.floatToIntBits(raw.a[i + 2])
            )
            var o = map[key]
            if (o == null) {
                o = pos.n / 3
                map[key] = o
                pos.add(raw.a[i]); pos.add(raw.a[i + 1]); pos.add(raw.a[i + 2])
            }
            idx.add(o)
            i += 3
        }
        val n3 = (idx.n / 3) * 3
        return Mesh(listOf(MeshPart(pos.toArray(), idx.a.copyOf(n3), null, null, DEFAULT_COLOR, null)))
    }

    // ------------------------------------------------------------------ glTF / GLB

    private fun parseGlb(b: ByteArray, decoder: ((ByteArray) -> TexImage?)?): Mesh {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(4) != 2) throw ModelFormatException("Só o glTF versão 2 é aceito.")
        var off = 12
        var json: String? = null
        var bin: ByteArray? = null
        while (off + 8 <= b.size) {
            val len = bb.getInt(off)
            val type = bb.getInt(off + 4)
            val start = off + 8
            val end = start + len
            if (len < 0 || end > b.size) throw ModelFormatException("Arquivo .glb cortado ou corrompido.")
            if (type == 0x4E4F534A && json == null) json = String(b, start, len, Charsets.UTF_8)
            else if (type == 0x004E4942 && bin == null) bin = b.copyOfRange(start, end)
            off = end
        }
        if (json == null) throw ModelFormatException("O .glb não tem a parte JSON.")
        return GltfReader(MiniJson.parse(json).asMap() ?: throw ModelFormatException("JSON do glTF inválido."), bin, decoder).read()
    }

    private fun parseGltfText(b: ByteArray, decoder: ((ByteArray) -> TexImage?)?): Mesh {
        val j = MiniJson.parse(String(b, Charsets.UTF_8)).asMap() ?: throw ModelFormatException("JSON do glTF inválido.")
        return GltfReader(j, null, decoder).read()
    }

    /** Decodifica base64 sem depender de java.util.Base64 (só existe no Android 8+). */
    internal fun base64(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in s) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> -1
            }
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private class GltfReader(
        val j: Map<String, Any?>,
        val glbBin: ByteArray?,
        val decoder: ((ByteArray) -> TexImage?)?
    ) {
        private val accessors = j["accessors"].asList() ?: emptyList()
        private val views = j["bufferViews"].asList() ?: emptyList()
        private val bufferCache = HashMap<Int, ByteArray>()
        private val texCache = HashMap<Int, TexImage?>()
        private val texTried = HashSet<Int>()

        fun read(): Mesh {
            val req = j["extensionsRequired"].asList() ?: emptyList()
            for (r in req) {
                if (r == "KHR_draco_mesh_compression" || r == "EXT_meshopt_compression") {
                    throw ModelFormatException("O modelo usa compressão ($r). Exporte o .glb sem compressão.")
                }
            }
            val nodes = j["nodes"].asList() ?: emptyList()
            val roots = ArrayList<Int>()
            val scenes = j["scenes"].asList()
            if (scenes != null && scenes.isNotEmpty()) {
                val si = (j["scene"].asInt() ?: 0).coerceIn(0, scenes.size - 1)
                for (n in scenes[si].asMap()?.get("nodes").asList() ?: emptyList()) n.asInt()?.let { roots.add(it) }
            } else {
                val children = HashSet<Int>()
                for (n in nodes) for (c in n.asMap()?.get("children").asList() ?: emptyList()) c.asInt()?.let { children.add(it) }
                for (i in nodes.indices) if (i !in children) roots.add(i)
            }
            val parts = ArrayList<MeshPart>()
            val ident = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            if (nodes.isEmpty()) {
                // glTF só com malhas, sem nós.
                val meshes = j["meshes"].asList() ?: emptyList()
                for (m in meshes) meshParts(m.asMap(), ident, parts)
            } else {
                for (r in roots) visit(r, ident, 0, parts)
            }
            if (parts.isEmpty()) throw ModelFormatException("O glTF não tem malhas de triângulos.")
            return Mesh(parts)
        }

        private fun visit(ni: Int, parent: FloatArray, depth: Int, out: MutableList<MeshPart>) {
            if (depth > 64) return
            val nodes = j["nodes"].asList() ?: return
            val n = nodes.getOrNull(ni).asMap() ?: return
            val world = mul(parent, local(n))
            val mi = n["mesh"].asInt()
            if (mi != null) {
                val meshes = j["meshes"].asList()
                meshParts(meshes?.getOrNull(mi).asMap(), world, out)
            }
            for (c in n["children"].asList() ?: emptyList()) c.asInt()?.let { visit(it, world, depth + 1, out) }
        }

        private fun local(n: Map<String, Any?>): FloatArray {
            val m = n["matrix"].asList()
            if (m != null && m.size == 16) return FloatArray(16) { m[it].asFloat() ?: 0f }
            val t = n["translation"].asList()
            val r = n["rotation"].asList()
            val s = n["scale"].asList()
            val tx = t?.get(0).asFloat() ?: 0f
            val ty = t?.get(1).asFloat() ?: 0f
            val tz = t?.get(2).asFloat() ?: 0f
            var qx = r?.get(0).asFloat() ?: 0f
            var qy = r?.get(1).asFloat() ?: 0f
            var qz = r?.get(2).asFloat() ?: 0f
            var qw = r?.get(3).asFloat() ?: 1f
            val ql = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
            if (ql > 1e-9f) {
                qx /= ql; qy /= ql; qz /= ql; qw /= ql
            }
            val sx = s?.get(0).asFloat() ?: 1f
            val sy = s?.get(1).asFloat() ?: 1f
            val sz = s?.get(2).asFloat() ?: 1f
            val r00 = 1f - 2f * (qy * qy + qz * qz)
            val r01 = 2f * (qx * qy - qz * qw)
            val r02 = 2f * (qx * qz + qy * qw)
            val r10 = 2f * (qx * qy + qz * qw)
            val r11 = 1f - 2f * (qx * qx + qz * qz)
            val r12 = 2f * (qy * qz - qx * qw)
            val r20 = 2f * (qx * qz - qy * qw)
            val r21 = 2f * (qy * qz + qx * qw)
            val r22 = 1f - 2f * (qx * qx + qy * qy)
            return floatArrayOf(
                r00 * sx, r10 * sx, r20 * sx, 0f,
                r01 * sy, r11 * sy, r21 * sy, 0f,
                r02 * sz, r12 * sz, r22 * sz, 0f,
                tx, ty, tz, 1f
            )
        }

        /** a * b, matrizes 4x4 em ordem de colunas. */
        private fun mul(a: FloatArray, b: FloatArray): FloatArray {
            val o = FloatArray(16)
            for (c in 0 until 4) for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
                o[c * 4 + r] = s
            }
            return o
        }

        private fun meshParts(mesh: Map<String, Any?>?, world: FloatArray, out: MutableList<MeshPart>) {
            if (mesh == null) return
            for (p in mesh["primitives"].asList() ?: emptyList()) {
                val prim = p.asMap() ?: continue
                primitive(prim, world)?.let { out.add(it) }
            }
        }

        private fun primitive(p: Map<String, Any?>, world: FloatArray): MeshPart? {
            val mode = p["mode"].asInt() ?: 4
            if (mode < 4 || mode > 6) return null
            if (p["extensions"].asMap()?.containsKey("KHR_draco_mesh_compression") == true) {
                throw ModelFormatException("O modelo usa compressão Draco. Exporte o .glb sem compressão.")
            }
            val attrs = p["attributes"].asMap() ?: return null
            val posAcc = attrs["POSITION"].asInt() ?: return null
            val pos = readFloats(posAcc)
            val nv = pos.size / 3
            var i = 0
            while (i + 2 < pos.size) {
                val x = pos[i]
                val y = pos[i + 1]
                val z = pos[i + 2]
                pos[i] = world[0] * x + world[4] * y + world[8] * z + world[12]
                pos[i + 1] = world[1] * x + world[5] * y + world[9] * z + world[13]
                pos[i + 2] = world[2] * x + world[6] * y + world[10] * z + world[14]
                i += 3
            }
            val raw = p["indices"].asInt()?.let { readInts(it) } ?: IntArray(nv) { it }
            val tri = IntList(raw.size + 3)
            when (mode) {
                4 -> {
                    var t = 0
                    while (t + 2 < raw.size) {
                        tri.add(raw[t]); tri.add(raw[t + 1]); tri.add(raw[t + 2])
                        t += 3
                    }
                }
                5 -> for (t in 0 until raw.size - 2) {
                    if (t % 2 == 0) { tri.add(raw[t]); tri.add(raw[t + 1]); tri.add(raw[t + 2]) }
                    else { tri.add(raw[t + 1]); tri.add(raw[t]); tri.add(raw[t + 2]) }
                }
                else -> for (t in 1 until raw.size - 1) {
                    tri.add(raw[0]); tri.add(raw[t]); tri.add(raw[t + 1])
                }
            }
            // Descarta triângulos com índice fora da lista de vértices.
            val good = IntList(tri.n + 3)
            var t = 0
            while (t + 2 < tri.n) {
                val a = tri.a[t]
                val b = tri.a[t + 1]
                val c = tri.a[t + 2]
                if (a in 0 until nv && b in 0 until nv && c in 0 until nv) {
                    good.add(a); good.add(b); good.add(c)
                }
                t += 3
            }
            if (good.n == 0) return null

            val uv = attrs["TEXCOORD_0"].asInt()?.let { readFloats(it) }?.takeIf { it.size >= nv * 2 }
            val vcol: IntArray? = attrs["COLOR_0"].asInt()?.let { ai ->
                val comps = compCount(accessor(ai)["type"] as? String ?: "VEC3")
                val f = readFloats(ai)
                if (comps < 3 || f.size < nv * comps) null
                else IntArray(nv) { v -> packLinear(f[v * comps], f[v * comps + 1], f[v * comps + 2], 1f) }
            }

            var base = 0xFFFFFFFF.toInt()
            var tex: TexImage? = null
            var texMissing = false
            val mat = p["material"].asInt()?.let { j["materials"].asList()?.getOrNull(it).asMap() }
            if (mat != null) {
                var factor: List<Any?>? = null
                var texIndex: Int? = null
                val pbr = mat["pbrMetallicRoughness"].asMap()
                if (pbr != null) {
                    factor = pbr["baseColorFactor"].asList()
                    texIndex = pbr["baseColorTexture"].asMap()?.get("index").asInt()
                } else {
                    val sg = mat["extensions"].asMap()?.get("KHR_materials_pbrSpecularGlossiness").asMap()
                    if (sg != null) {
                        factor = sg["diffuseFactor"].asList()
                        texIndex = sg["diffuseTexture"].asMap()?.get("index").asInt()
                    }
                }
                if (factor != null && factor.size >= 3) {
                    base = packLinear(
                        factor[0].asFloat() ?: 1f, factor[1].asFloat() ?: 1f, factor[2].asFloat() ?: 1f, 1f
                    )
                }
                if (texIndex != null && uv != null) {
                    tex = texture(texIndex)
                    if (tex == null) texMissing = true
                }
            }
            // Sem como decodificar a textura: usa um cinza no lugar, para não sair branco puro.
            if (texMissing) base = scaleColor(base, 0.7f)
            return MeshPart(pos, good.toArray(), if (tex != null) uv else null, vcol, base, tex)
        }

        private fun scaleColor(c: Int, k: Float): Int {
            val r = ((c shr 16) and 0xFF) * k
            val g = ((c shr 8) and 0xFF) * k
            val b = (c and 0xFF) * k
            return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }

        /** Cor linear (glTF) para sRGB de 8 bits, como o navegador mostra. */
        private fun packLinear(r: Float, g: Float, b: Float, a: Float): Int {
            fun enc(x: Float): Int = (x.coerceIn(0f, 1f).toDouble().pow(1.0 / 2.2) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            return ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(b)
        }

        private fun texture(ti: Int): TexImage? {
            if (!texTried.add(ti)) return texCache[ti]
            var img: TexImage? = null
            try {
                val dec = decoder
                if (dec != null) {
                    val src = j["textures"].asList()?.getOrNull(ti).asMap()?.get("source").asInt()
                    val image = if (src != null) j["images"].asList()?.getOrNull(src).asMap() else null
                    if (image != null) {
                        val uri = image["uri"] as? String
                        val data: ByteArray? = when {
                            uri != null && uri.startsWith("data:") -> base64(uri.substring(uri.indexOf(',') + 1))
                            uri != null -> null
                            else -> image["bufferView"].asInt()?.let { viewBytes(it) }
                        }
                        if (data != null) img = dec(data)
                    }
                }
            } catch (e: Exception) {
                img = null
            }
            texCache[ti] = img
            return img
        }

        private fun viewBytes(vi: Int): ByteArray {
            val bv = views.getOrNull(vi).asMap() ?: throw ModelFormatException("bufferView $vi inválido.")
            val off = bv["byteOffset"].asInt() ?: 0
            val len = bv["byteLength"].asInt() ?: 0
            val buf = buffer(bv["buffer"].asInt() ?: 0)
            if (off < 0 || len < 0 || off + len > buf.size) throw ModelFormatException("bufferView fora do arquivo.")
            return buf.copyOfRange(off, off + len)
        }

        private fun buffer(i: Int): ByteArray {
            bufferCache[i]?.let { return it }
            val b = j["buffers"].asList()?.getOrNull(i).asMap() ?: throw ModelFormatException("buffer $i inválido.")
            val uri = b["uri"] as? String
            val data = when {
                uri == null -> glbBin ?: throw ModelFormatException("O buffer $i não tem dados.")
                uri.startsWith("data:") -> base64(uri.substring(uri.indexOf(',') + 1))
                else -> throw ModelFormatException("O modelo usa o arquivo externo \"$uri\". Exporte como .glb (tudo em um arquivo).")
            }
            bufferCache[i] = data
            return data
        }

        private fun accessor(i: Int): Map<String, Any?> =
            accessors.getOrNull(i).asMap() ?: throw ModelFormatException("accessor $i inválido.")

        private fun compCount(type: String): Int = when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT2" -> 4
            "MAT3" -> 9
            "MAT4" -> 16
            else -> throw ModelFormatException("Tipo de dado desconhecido: $type")
        }

        private fun compSize(ct: Int): Int = when (ct) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            5125, 5126 -> 4
            else -> throw ModelFormatException("Tipo de número desconhecido: $ct")
        }

        private class Loc(val buf: ByteBuffer, val base: Int, val stride: Int, val count: Int, val comps: Int, val ct: Int)

        private fun locate(ai: Int): Loc? {
            val a = accessor(ai)
            val count = a["count"].asInt() ?: 0
            val comps = compCount(a["type"] as? String ?: "SCALAR")
            val ct = a["componentType"].asInt() ?: 5126
            val bvi = a["bufferView"].asInt() ?: return null
            val bv = views.getOrNull(bvi).asMap() ?: throw ModelFormatException("bufferView $bvi inválido.")
            val buf = buffer(bv["buffer"].asInt() ?: 0)
            val base = (bv["byteOffset"].asInt() ?: 0) + (a["byteOffset"].asInt() ?: 0)
            val elem = comps * compSize(ct)
            val stride = bv["byteStride"].asInt()?.takeIf { it > 0 } ?: elem
            val last = base.toLong() + (count - 1).toLong() * stride + elem
            if (count > 0 && (base < 0 || last > buf.size)) throw ModelFormatException("Dados do accessor $ai passam do fim do arquivo.")
            return Loc(ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN), base, stride, count, comps, ct)
        }

        fun readFloats(ai: Int): FloatArray {
            val a = accessor(ai)
            val norm = a["normalized"] == true
            val l = locate(ai) ?: return FloatArray((a["count"].asInt() ?: 0) * compCount(a["type"] as? String ?: "SCALAR"))
            val out = FloatArray(l.count * l.comps)
            val cs = compSize(l.ct)
            for (e in 0 until l.count) {
                for (c in 0 until l.comps) {
                    val pos = l.base + e * l.stride + c * cs
                    val v: Float = when (l.ct) {
                        5126 -> l.buf.getFloat(pos)
                        5120 -> l.buf.get(pos).toFloat().let { if (norm) max(it / 127f, -1f) else it }
                        5121 -> (l.buf.get(pos).toInt() and 0xFF).toFloat().let { if (norm) it / 255f else it }
                        5122 -> l.buf.getShort(pos).toFloat().let { if (norm) max(it / 32767f, -1f) else it }
                        5123 -> (l.buf.getShort(pos).toInt() and 0xFFFF).toFloat().let { if (norm) it / 65535f else it }
                        else -> (l.buf.getInt(pos).toLong() and 0xFFFFFFFFL).toFloat()
                    }
                    out[e * l.comps + c] = v
                }
            }
            return out
        }

        fun readInts(ai: Int): IntArray {
            val l = locate(ai) ?: return IntArray(0)
            val out = IntArray(l.count)
            for (e in 0 until l.count) {
                val pos = l.base + e * l.stride
                out[e] = when (l.ct) {
                    5121 -> l.buf.get(pos).toInt() and 0xFF
                    5123 -> l.buf.getShort(pos).toInt() and 0xFFFF
                    5125 -> l.buf.getInt(pos)
                    5120 -> l.buf.get(pos).toInt()
                    5122 -> l.buf.getShort(pos).toInt()
                    else -> l.buf.getFloat(pos).toInt()
                }
            }
            return out
        }
    }
}
