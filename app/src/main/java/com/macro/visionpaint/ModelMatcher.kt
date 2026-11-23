package com.macro.visionpaint

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Procura de modelos 3D no quadro (sem Android e sem OpenCV: as contas de imagem ficam atrás de
 * [FieldOps], que o app implementa com OpenCV e os testes com Kotlin simples).
 *
 * Como funciona, em resumo:
 *  - de cada vista do modelo (um ângulo e uma altura de câmera) sai um "molde" de orientações de
 *    borda: em cada pixel, o vetor (cos 2θ, sin 2θ) da direção da borda. O dobro do ângulo faz a
 *    borda valer igual de claro para escuro e o inverso;
 *  - o quadro vira o mesmo tipo de campo (gradiente, direção dobrada, um pouco espalhado). A
 *    correlação (soma dos produtos) entre molde e campo é alta onde as bordas apontam para os
 *    mesmos lados que as do molde;
 *  - a busca grossa testa poucas vistas em vários tamanhos (moldes pequenos, no quadro reduzido);
 *    cada candidato é refinado trocando de tamanho, de vista vizinha e de inclinação (roll);
 *  - cada pose refinada é conferida pela cobertura do contorno ([Coverage]); a nota que sai é
 *    essa confiança, e é ela que as notas "achar" e "seguir" comparam.
 */

/** Pico de uma resposta: valor e posição (no mapa de respostas). */
class Peak(val v: Float, val x: Int, val y: Int)

/** Mapa de respostas da correlação de um molde numa janela do campo. */
interface Response {
    /** Posição, no campo, do canto de cima à esquerda da janela (o canto do molde em (0, 0) do mapa). */
    val offX: Int
    val offY: Int
    fun maxLoc(): Peak?

    /** Apaga (põe [v]) o retângulo [x0, x1) x [y0, y1) do mapa (cortado nas bordas). */
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, v: Float)
    fun release()
}

/** Campo de orientações do quadro num nível de redução. */
interface Field {
    val w: Int
    val h: Int

    /** Pixels do quadro por pixel do campo (em x e em y). */
    val sx: Float
    val sy: Float

    /**
     * Correlação do molde [t] com a janela [x0, x1) x [y0, y1) do campo, dividida pela norma do
     * molde. Null se a janela não comporta o molde.
     */
    fun respond(t: Template, x0: Int, y0: Int, x1: Int, y1: Int): Response?
    fun release()
}

/** As contas de imagem de que o [ModelMatcher] precisa, sobre o quadro atual. */
interface FieldOps {
    val frameW: Int
    val frameH: Int

    /** Campo do quadro reduzido 2^[k] vezes. */
    fun field(k: Int): Field

    /** Bordas do quadro (para a cobertura), no tamanho dele. */
    fun edgeMap(): EdgeMap

    /** Libera o que a implementação guardou no molde (ex.: a matriz do OpenCV). */
    fun releaseTemplate(t: Template)
}

/**
 * Molde de uma vista, numa certa escala e inclinação. [data] = (c, s) de cada pixel, intercalados,
 * linha por linha; [n] = soma dos módulos (para normalizar a nota). ([ox], [oy]) = onde fica a
 * origem do modelo (o centro dele) dentro do molde; ([ax], [ay]) = ponto de mira em relação à origem.
 * Tudo em pixels do molde.
 */
class Template(
    val w: Int,
    val h: Int,
    val data: FloatArray,
    val n: Float,
    val ox: Float,
    val oy: Float,
    val ax: Float,
    val ay: Float
) {
    /** Espaço para a implementação guardar uma versão pronta para ela (não mexer). */
    var handle: Any? = null
}

/** A pose achada: modelo, vista, inclinação e tamanho (nível), e onde a origem fica em relação ao centro da caixa. */
class ModelPose(
    val model: Int,
    val view: Int,
    val roll: Int,
    val level: Int,
    val originDx: Float,
    val originDy: Float
)

class MatcherOptions(
    /** true = busca grossa com 9 vistas (giro de 40 em 40 graus, uma altura); false = 27 vistas. */
    val fast: Boolean = true,
    /** Aceita modelos inclinados (roll de até 20 graus). */
    val roll: Boolean = true,
    val maxCandidates: Int = 80,
    val perLevel: Int = 4,
    val topPeaks: Int = 4
)

class ModelMatcher(
    private val ops: FieldOps,
    private val pxMin: Float,
    pxMax: Float,
    private val opt: MatcherOptions = MatcherOptions()
) {
    companion object {
        /** Prefixo do id dos modelos 3D nos alvos (para não confundir com os ids das imagens). */
        const val ID_PREFIX = "m:"

        private const val LEVEL_STEP = 1.1
        private const val TMAX = 56.0
        private const val COARSE_EVERY = 2
        private const val ROLL_STEP = 10f
        private const val ROLL_MAX = 20f
        private const val FIRST_SEARCH = 0.45f
        private const val TRACK_SEARCH = 0.12f
        private const val TRACK_SEARCH_WIDE = 0.3f
        private const val NMS = 0.3f
        private const val PRIOR = 0.10f
        private const val PRIOR_REF = 22f
        private const val CACHE_MAX = 1500
        private const val MIN_GAIN = 1e-4f
        private const val POLISH_ITER = 14
        private const val POLISH_GAIN = 0.003f
        private const val POLISH_TOP = 12
    }

    private class Model(val id: String, val bank: ViewBank)

    private class Cand(val score: Float, val vi: Int, val level: Int, val cx: Float, val cy: Float, val p: Float)

    /** Uma pose refinada: origem do modelo em pixels do quadro e o molde que combinou. */
    private class Fit(
        val score: Float, val ox: Float, val oy: Float, val t: Template,
        val sx: Float, val sy: Float, val vi: Int, val ri: Int, val level: Int
    )

    private val models = ArrayList<Model>()
    private val byId = HashMap<String, Int>()

    private val nLevels: Int = max(1, floor(ln(pxMax / pxMin) / ln(LEVEL_STEP)).toInt() + 1)
    private val pLevel = FloatArray(nLevels) { (pxMin * LEVEL_STEP.pow(it)).toFloat() }
    private val kLevel = IntArray(nLevels) { max(0, ceil(ln(pLevel[it] / TMAX) / ln(2.0)).toInt()) }

    private val rolls: FloatArray = if (opt.roll) {
        val n = (2 * ROLL_MAX / ROLL_STEP).toInt() + 1
        FloatArray(n) { -ROLL_MAX + it * ROLL_STEP }
    } else {
        floatArrayOf(0f)
    }
    private val roll0 = rolls.indexOfFirst { it == 0f }

    private val fields = HashMap<Int, Field>()
    private var edge: EdgeMap? = null

    private val cache = object : LinkedHashMap<Long, Template>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Template>): Boolean {
            if (size > CACHE_MAX) {
                ops.releaseTemplate(eldest.value)
                return true
            }
            return false
        }
    }

    fun modelCount(): Int = models.size

    /** Número de tamanhos (níveis) que a busca percorre. */
    fun levels(): Int = nLevels

    /** [id] sem o prefixo [ID_PREFIX]; o alvo sai com o id completo. */
    fun addModel(id: String, bank: ViewBank) {
        byId[ID_PREFIX + id] = models.size
        models.add(Model(id, bank))
    }

    /** Chamar a cada quadro novo, depois de dar o quadro a [FieldOps]. */
    fun newFrame() {
        for (f in fields.values) f.release()
        fields.clear()
        edge = null
    }

    fun release() {
        newFrame()
        for (t in cache.values) ops.releaseTemplate(t)
        cache.clear()
        models.clear()
        byId.clear()
    }

    private fun field(k: Int): Field = fields.getOrPut(k) { ops.field(k) }

    private fun edgeMap(): EdgeMap = edge ?: ops.edgeMap().also { edge = it }

    // ------------------------------------------------------------------ moldes

    private fun template(mi: Int, vi: Int, ri: Int, level: Int): Template? {
        val key = (mi.toLong() shl 28) or (vi.toLong() shl 16) or (ri.toLong() shl 8) or level.toLong()
        val hit = cache[key]
        if (hit != null) return hit
        val t = buildTemplate(models[mi].bank.views[vi], ri, level) ?: return null
        cache[key] = t
        return t
    }

    private fun buildTemplate(view: BankView, ri: Int, level: Int): Template? {
        val n = view.count
        if (n == 0) return null
        val roll = Math.toRadians(rolls[ri].toDouble())
        val cr = cos(roll).toFloat()
        val sr = sin(roll).toFloat()
        val c2 = cos(2 * roll).toFloat()
        val s2 = sin(2 * roll).toFloat()
        val tpx = pLevel[level] / (1 shl kLevel[level]).toFloat()
        val pts = view.pts

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var minx = Float.MAX_VALUE
        var miny = Float.MAX_VALUE
        var maxx = -Float.MAX_VALUE
        var maxy = -Float.MAX_VALUE
        for (i in 0 until n) {
            val u = pts[i * 5]
            val v = pts[i * 5 + 1]
            val x = tpx * (u * cr - v * sr)
            val y = tpx * (u * sr + v * cr)
            xs[i] = x
            ys[i] = y
            if (x < minx) minx = x
            if (x > maxx) maxx = x
            if (y < miny) miny = y
            if (y > maxy) maxy = y
        }
        val w = floor(maxx - minx + 0.5f).toInt() + 1
        val h = floor(maxy - miny + 0.5f).toInt() + 1
        if (w < 1 || h < 1 || w * h > 90_000) return null

        val sc = FloatArray(w * h)
        val ss = FloatArray(w * h)
        val cnt = IntArray(w * h)
        for (i in 0 until n) {
            val ix = min(w - 1, max(0, floor(xs[i] - minx + 0.5f).toInt()))
            val iy = min(h - 1, max(0, floor(ys[i] - miny + 0.5f).toInt()))
            val o = i * 5
            val wt = pts[o + 4]
            val c = pts[o + 2] * c2 - pts[o + 3] * s2
            val s = pts[o + 2] * s2 + pts[o + 3] * c2
            val j = iy * w + ix
            sc[j] += wt * c
            ss[j] += wt * s
            cnt[j]++
        }
        val data = FloatArray(w * h * 2)
        var norm = 0.0
        for (j in 0 until w * h) {
            val k = max(cnt[j], 1)
            val a = sc[j] / k
            val b = ss[j] / k
            data[2 * j] = a
            data[2 * j + 1] = b
            norm += sqrt((a * a + b * b).toDouble())
        }
        val au = view.aimU
        val av = view.aimV
        return Template(
            w, h, data, norm.toFloat(), -minx, -miny,
            tpx * (au * cr - av * sr), tpx * (au * sr + av * cr)
        )
    }

    // ------------------------------------------------------------------ busca grossa

    private fun coarseViews(bank: ViewBank): List<Int> {
        val out = ArrayList<Int>()
        for (i in bank.views.indices) {
            if (bank.yawOf(i) % 2 != 0) continue
            // No modo rápido só a altura do meio.
            if (opt.fast && bank.pitches.size > 1 && bank.pitchOf(i) != bank.pitches.size / 2) continue
            out.add(i)
        }
        return out
    }

    /** Candidatos (nota da correlação, vista, nível, origem no quadro), já sem repetidos. */
    private fun coarse(mi: Int): List<Cand> {
        val bank = models[mi].bank
        val views = coarseViews(bank)
        val cands = ArrayList<Cand>()
        var level = 0
        while (level < nLevels) {
            val fld = field(kLevel[level])
            for (vi in views) {
                val t = template(mi, vi, roll0, level) ?: continue
                val resp = fld.respond(t, 0, 0, fld.w, fld.h) ?: continue
                try {
                    for (k in 0 until opt.topPeaks) {
                        val pk = resp.maxLoc() ?: break
                        if (pk.v < 0f) break
                        val cx = (pk.x + resp.offX + t.ox + 0.5f) * fld.sx - 0.5f
                        val cy = (pk.y + resp.offY + t.oy + 0.5f) * fld.sy - 0.5f
                        cands.add(Cand(pk.v, vi, level, cx, cy, pLevel[level]))
                        val r = max(3, (0.3f * max(t.w, t.h)).toInt())
                        resp.fill(pk.x - r, pk.y - r, pk.x + r + 1, pk.y + r + 1, -1f)
                    }
                } finally {
                    resp.release()
                }
            }
            level += COARSE_EVERY
        }
        cands.sortByDescending { it.score }

        val keep = ArrayList<Cand>()
        val perLevel = IntArray(nLevels)
        for (c in cands) {
            if (opt.perLevel > 0 && perLevel[c.level] >= opt.perLevel) continue
            var far = true
            for (k in keep) {
                // Só quem tem tamanho parecido disputa o lugar: um molde pequeno que combinou com
                // um pedaço do corpo não pode apagar o candidato do tamanho certo.
                if (abs(c.level - k.level) <= COARSE_EVERY && hypot(c.cx - k.cx, c.cy - k.cy) < NMS * max(c.p, k.p)) {
                    far = false
                    break
                }
            }
            if (far) {
                keep.add(c)
                perLevel[c.level]++
            }
            if (keep.size >= opt.maxCandidates) break
        }
        return keep
    }

    // ------------------------------------------------------------------ refinamento

    /**
     * Melhor posição do molde (vista [vi], inclinação [ri], nível [level]) com a origem do modelo a
     * até [search] pixels do quadro de ([ocx], [ocy]).
     */
    private fun evalPose(mi: Int, vi: Int, ri: Int, level: Int, ocx: Float, ocy: Float, search: Float): Fit? {
        val t = template(mi, vi, ri, level) ?: return null
        val fld = field(kLevel[level])
        val ex = (ocx + 0.5f) / fld.sx - 0.5f
        val ey = (ocy + 0.5f) / fld.sy - 0.5f
        val rx = search / fld.sx + 2f
        val ry = search / fld.sy + 2f
        var x0 = floor(ex - t.ox - rx).toInt()
        var y0 = floor(ey - t.oy - ry).toInt()
        var x1 = ceil(ex - t.ox + rx).toInt() + t.w
        var y1 = ceil(ey - t.oy + ry).toInt() + t.h
        x0 = max(0, x0)
        y0 = max(0, y0)
        x1 = min(fld.w, x1)
        y1 = min(fld.h, y1)
        val resp = fld.respond(t, x0, y0, x1, y1) ?: return null
        try {
            val pk = resp.maxLoc() ?: return null
            val cx = (pk.x + resp.offX + t.ox + 0.5f) * fld.sx - 0.5f
            val cy = (pk.y + resp.offY + t.oy + 0.5f) * fld.sy - 0.5f
            return Fit(pk.v, cx, cy, t, fld.sx, fld.sy, vi, ri, level)
        } finally {
            resp.release()
        }
    }

    /**
     * Sobe a ladeira da nota: troca o tamanho (+-1 nível), a vista (vizinhas) e a inclinação (+-1 passo),
     * ficando com o que melhora, até nada melhorar (no máximo 4 voltas).
     */
    private fun refine(mi: Int, vi: Int, ri: Int, level: Int, ocx: Float, ocy: Float, search0: Float): Fit? {
        var best = evalPose(mi, vi, ri, level, ocx, ocy, search0) ?: return null
        val bank = models[mi].bank
        for (iter in 0 until 4) {
            var improved = false
            for (dl in intArrayOf(-1, 1)) {
                val nl = best.level + dl
                if (nl < 0 || nl >= nLevels) continue
                val r = evalPose(mi, best.vi, best.ri, nl, best.ox, best.oy, 0.1f * pLevel[nl])
                if (r != null && r.score > best.score + MIN_GAIN) {
                    best = r
                    improved = true
                }
            }
            for (nvi in bank.neighbors(best.vi)) {
                val r = evalPose(mi, nvi, best.ri, best.level, best.ox, best.oy, 0.06f * pLevel[best.level])
                if (r != null && r.score > best.score + MIN_GAIN) {
                    best = r
                    improved = true
                }
            }
            for (dr in intArrayOf(-1, 1)) {
                val nr = best.ri + dr
                if (nr < 0 || nr >= rolls.size) continue
                val r = evalPose(mi, best.vi, nr, best.level, best.ox, best.oy, 0.06f * pLevel[best.level])
                if (r != null && r.score > best.score + MIN_GAIN) {
                    best = r
                    improved = true
                }
            }
            if (!improved) break
        }
        return best
    }

    // ------------------------------------------------------------------ resultado

    private fun toDetection(mi: Int, ox: Float, oy: Float, vi: Int, ri: Int, level: Int, conf: Float): Detection? {
        val t = template(mi, vi, ri, level) ?: return null
        val p = pLevel[level]
        val u = (1 shl kLevel[level]).toFloat()
        val cx = ox + ((t.w - 1) / 2f - t.ox) * u
        val cy = oy + ((t.h - 1) / 2f - t.oy) * u
        val spot = AimSpot(ox + t.ax * u, oy + t.ay * u, models[mi].bank.aimRadius * p)
        return Detection(
            ID_PREFIX + models[mi].id, cx, cy, t.w * u, t.h * u, conf, false,
            spot, ModelPose(mi, vi, ri, level, ox - cx, oy - cy)
        )
    }

    // ------------------------------------------------------------------ ajuste fino pela cobertura

    /** Pose em pixels do quadro e a cobertura do contorno nela. */
    private class Placed(val ox: Float, val oy: Float, val vi: Int, val ri: Int, val level: Int, val cov: Float)

    private fun coverAt(mi: Int, em: EdgeMap, ox: Float, oy: Float, vi: Int, ri: Int, level: Int): Float {
        val p = pLevel[level]
        return Coverage.cover(em, models[mi].bank.views[vi], ox, oy, p, rolls[ri], Coverage.radiusFor(p))
    }

    /**
     * A correlação de orientações é suave (acha o lugar aproximado) mas escorrega ao longo de bordas
     * compridas; a cobertura do contorno é mais exigente. Partindo da pose achada, sobe a ladeira da
     * cobertura: anda 1 a 2 px, troca o tamanho, a vista e a inclinação, o que melhorar.
     */
    private fun polish(mi: Int, em: EdgeMap, start: Placed): Placed {
        val bank = models[mi].bank
        var cur = start
        var big = true
        for (iter in 0 until POLISH_ITER) {
            val p = pLevel[cur.level]
            val step = if (big) max(2f, 0.04f * p) else max(1f, 0.015f * p)
            var best: Placed? = null
            fun consider(ox: Float, oy: Float, vi: Int, ri: Int, level: Int) {
                val c = coverAt(mi, em, ox, oy, vi, ri, level)
                val b = best
                if (b == null || c > b.cov) best = Placed(ox, oy, vi, ri, level, c)
            }
            consider(cur.ox + step, cur.oy, cur.vi, cur.ri, cur.level)
            consider(cur.ox - step, cur.oy, cur.vi, cur.ri, cur.level)
            consider(cur.ox, cur.oy + step, cur.vi, cur.ri, cur.level)
            consider(cur.ox, cur.oy - step, cur.vi, cur.ri, cur.level)
            for (dl in intArrayOf(-1, 1)) {
                val nl = cur.level + dl
                if (nl in 0 until nLevels) consider(cur.ox, cur.oy, cur.vi, cur.ri, nl)
            }
            for (nvi in bank.neighbors(cur.vi)) consider(cur.ox, cur.oy, nvi, cur.ri, cur.level)
            for (dr in intArrayOf(-1, 1)) {
                val nr = cur.ri + dr
                if (nr in rolls.indices) consider(cur.ox, cur.oy, cur.vi, nr, cur.level)
            }
            val b = best
            if (b != null && b.cov > cur.cov + POLISH_GAIN) {
                cur = b
            } else if (big) {
                big = false
            } else {
                break
            }
        }
        return cur
    }

    private fun rank(conf: Float, p: Float): Float = conf + PRIOR * ln(p / PRIOR_REF)

    /**
     * Varredura completa. Devolve as poses com confiança de pelo menos [acquire], no máximo
     * [maxPerModel] por modelo (uma por lugar).
     */
    fun detect(acquire: Float, maxPerModel: Int = 3): List<Detection> {
        val out = ArrayList<Detection>()
        for (mi in models.indices) {
            val bank = models[mi].bank
            val placed = ArrayList<Placed>()
            var em: EdgeMap? = null
            for (c in coarse(mi)) {
                val f = refine(mi, c.vi, roll0, c.level, c.cx, c.cy, FIRST_SEARCH * c.p) ?: continue
                val e = em ?: edgeMap().also { em = it }
                placed.add(Placed(f.ox, f.oy, f.vi, f.ri, f.level, coverAt(mi, e, f.ox, f.oy, f.vi, f.ri, f.level)))
            }
            val e = em ?: continue

            // Só os mais promissores (pela cobertura e pelo tamanho) passam pelo ajuste fino e pela
            // nota com desconto, que são as etapas mais caras.
            placed.sortByDescending { rank(it.cov, pLevel[it.level]) }
            class Scored(val pl: Placed, val conf: Float, val p: Float)
            val scored = ArrayList<Scored>()
            for (i in 0 until min(POLISH_TOP, placed.size)) {
                val pl = polish(mi, e, placed[i])
                val p = pLevel[pl.level]
                val conf = Coverage.confidence(
                    e, bank.views[pl.vi], pl.ox, pl.oy, p, rolls[pl.ri], Coverage.radiusFor(p), pl.cov
                )
                scored.add(Scored(pl, conf, p))
            }

            val ranked = scored.filter { it.conf >= acquire }.sortedByDescending { rank(it.conf, it.p) }
            val kept = ArrayList<Scored>()
            for (r in ranked) {
                if (kept.size >= maxPerModel) break
                if (kept.all { hypot(r.pl.ox - it.pl.ox, r.pl.oy - it.pl.oy) > NMS * max(r.p, it.p) }) kept.add(r)
            }
            for (r in kept) {
                val d = toDetection(mi, r.pl.ox, r.pl.oy, r.pl.vi, r.pl.ri, r.pl.level, r.conf)
                if (d != null) out.add(d)
            }
        }
        return out
    }

    /**
     * Rastreio: procura de novo o modelo [tplId] a partir da pose [pose] (a da última vez), com o
     * centro da caixa esperado em ([cx], [cy]). Perto do esperado vale a nota [follow]; quanto
     * mais longe, mais perto de [acquire] é exigida. Null se não achou.
     */
    fun matchNear(tplId: String, cx: Float, cy: Float, follow: Float, acquire: Float, pose: Any?): Detection? {
        val p0 = pose as? ModelPose ?: return null
        val mi = byId[tplId] ?: return null
        if (mi != p0.model || p0.level >= nLevels || p0.roll >= rolls.size) return null
        val gx = cx + p0.originDx
        val gy = cy + p0.originDy
        val em = edgeMap()
        var bestPl: Placed? = null
        var bestConf = Float.NEGATIVE_INFINITY
        var bestNeed = 0f
        // Primeiro só ao redor da previsão (um raio grande deixaria a correlação escorregar ao longo
        // de uma borda comprida: o contorno de um corpo reto combina em várias alturas); se não
        // bastar, uma segunda tentativa com raio maior.
        for (frac in floatArrayOf(TRACK_SEARCH, TRACK_SEARCH_WIDE)) {
            val search = max(frac * pLevel[p0.level], 6f)
            val f = evalPose(mi, p0.view, p0.roll, p0.level, gx, gy, search) ?: continue
            val pl = polish(mi, em, Placed(f.ox, f.oy, f.vi, f.ri, f.level, coverAt(mi, em, f.ox, f.oy, f.vi, f.ri, f.level)))
            val p = pLevel[pl.level]
            val conf = Coverage.confidence(
                em, models[mi].bank.views[pl.vi], pl.ox, pl.oy, p, rolls[pl.ri], Coverage.radiusFor(p), pl.cov
            )
            // Perto da previsão vale a nota de seguir; longe, a de achar.
            val d = hypot(pl.ox - gx, pl.oy - gy)
            val t = ((d - 0.35f * p) / (0.85f * p)).coerceIn(0f, 1f)
            val need = follow + (max(follow, acquire) - follow) * t
            if (conf - need > bestConf - bestNeed) {
                bestPl = pl
                bestConf = conf
                bestNeed = need
            }
            if (conf >= need) break
        }
        val pl = bestPl ?: return null
        if (bestConf < bestNeed) return null
        val conf = bestConf
        return toDetection(mi, pl.ox, pl.oy, pl.vi, pl.ri, pl.level, conf)
    }
}
