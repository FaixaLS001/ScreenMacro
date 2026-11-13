package com.macro.visionpaint

import kotlin.math.hypot

/**
 * Decisão para um quadro: [locked] = a mira já está no centro da imagem (fixa);
 * senão, arrastar o dedo ([dx], [dy]) a partir do ponto de pressão.
 */
class AimStep(val locked: Boolean, val dx: Float, val dy: Float) {
    val swipe: Boolean get() = !locked && hypot(dx, dy) >= 0.5f
}

/**
 * Controle da mira em malha fechada (sem Android, só matemática).
 *
 * A cada quadro recebe o erro (centro da imagem - posição da mira), em pixels da tela.
 * Se o erro é maior que a zona de travamento, pede um arrasto de dedo proporcional a ele.
 * Depois do arrasto, o erro é medido de novo; assim a mira chega no centro em poucos passos
 * mesmo que o jogo responda mais ou menos que o esperado, e se mantém fixa enquanto a imagem
 * não sair da zona.
 *
 * [setGain]: palpite inicial de "pixels de dedo por pixel de erro". Com [learn] ligado, a
 * resposta real do jogo é medida depois de cada arrasto e o ganho se ajusta sozinho (limitado
 * a no máximo 4x acima do palpite, para uma medida ruim não descontrolar a mira).
 */
class AimController {

    companion object {
        /** Fração do erro que cada arrasto tenta corrigir (menos que 1 evita passar do ponto). */
        const val ETA = 0.85f

        /** A mira só "solta" do alvo quando o erro passa de tolerância x este fator. */
        const val UNLOCK_FACTOR = 1.6f

        const val LEARN_RATE = 0.4f
        const val LEARN_MAX_AGE_MS = 2500L
        const val MIN_SIGNAL_PX = 6f
        const val HINT_STREAK = 4
    }

    /** Raio (px) da zona em que a mira conta como fixa no centro. */
    var tolerance = 14f

    var invertX = false
    var invertY = false
    var learn = true

    /** true enquanto a mira está fixa no alvo. */
    var locked = false
        private set

    private var gain = 1f
    private var sBase = 1f   // resposta esperada pelo palpite do usuário (1 / ganho)
    private var sHat = 1f    // resposta estimada: px de erro removidos por px de dedo

    private class Pending(
        val ex: Float, val ey: Float,   // erro antes do arrasto
        val gx: Float, val gy: Float,   // dedo "lógico" (no sentido certo, sem inversão)
        val key: String, val t: Long
    )

    private var pending: Pending? = null
    private var wrongStreak = 0
    private var hint = false

    /** Palpite de ganho (pixels de dedo por pixel de erro). Trocar o valor reinicia o ajuste. */
    fun setGain(g: Float) {
        val v = g.coerceIn(0.05f, 10f)
        if (v == gain) return
        gain = v
        sBase = 1f / v
        sHat = sBase
        pending = null
    }

    /** Ganho de trabalho atual (para teste/diagnóstico). */
    fun currentGain(): Float = 1f / sHat

    /** A imagem sumiu: solta a mira e esquece o arrasto em andamento. */
    fun lost() {
        locked = false
        pending = null
    }

    /** true uma vez quando a mira parece estar se afastando do alvo (direção invertida?). */
    fun takeHint(): Boolean {
        val h = hint
        hint = false
        return h
    }

    /**
     * [ex], [ey]: erro (alvo - mira). [key]: identifica a imagem seguida (não aprende se mudar).
     * [canAct]: false quando não dá para arrastar agora (arrasto em curso, pausa, painel aberto);
     * nesse caso só atualiza o estado de travamento. [maxStep]: maior arrasto permitido (px).
     */
    fun update(
        ex: Float,
        ey: Float,
        key: String,
        nowMs: Long,
        canAct: Boolean,
        maxStep: Float
    ): AimStep {
        val dist = hypot(ex, ey)
        locked = if (locked) dist <= tolerance * UNLOCK_FACTOR else dist <= tolerance
        if (!canAct) return AimStep(locked, 0f, 0f)

        pending?.let { p ->
            pending = null
            if (p.key == key && nowMs - p.t <= LEARN_MAX_AGE_MS) learnFrom(p, ex, ey)
        }
        if (locked) return AimStep(true, 0f, 0f)

        // Dedo "lógico": no sentido do erro. As inversões só valem para o que vai ao celular.
        var gx = ETA * ex / sHat
        var gy = ETA * ey / sHat
        val len = hypot(gx, gy)
        if (len > maxStep && len > 0f) {
            val k = maxStep / len
            gx *= k
            gy *= k
        }
        pending = Pending(ex, ey, gx, gy, key, nowMs)
        return AimStep(false, if (invertX) -gx else gx, if (invertY) -gy else gy)
    }

    /**
     * O arrasto pedido por [update] foi encurtado (ex.: para o dedo não sair da tela) e só
     * [k] (0 a 1) dele foi enviado. Ajusta o que o aprendizado espera ver depois.
     */
    fun shrinkPending(k: Float) {
        val p = pending ?: return
        val f = k.coerceIn(0f, 1f)
        pending = Pending(p.ex, p.ey, p.gx * f, p.gy * f, p.key, p.t)
    }

    private fun learnFrom(p: Pending, ex: Float, ey: Float) {
        val gg = p.gx * p.gx + p.gy * p.gy
        if (gg < MIN_SIGNAL_PX * MIN_SIGNAL_PX) return
        // Quanto do erro sumiu, projetado na direção do dedo, por pixel de dedo.
        val s = ((p.ex - ex) * p.gx + (p.ey - ey) * p.gy) / gg
        if (s <= 0f) {
            // Piorou (ou o alvo andou sozinho): não aprende; se repetir, avisa.
            if (++wrongStreak >= HINT_STREAK) {
                wrongStreak = 0
                hint = true
            }
            return
        }
        wrongStreak = 0
        if (!learn) return
        // Limites assimétricos: o ganho pode subir no máximo 4x (uma medida ruim que o faça
        // subir demais faria a mira passar do alvo), mas pode cair bastante (cair só deixa a
        // mira mais lenta, nunca faz ela fugir).
        sHat = (sHat + LEARN_RATE * (s - sHat)).coerceIn(sBase / 4f, sBase * 50f)
    }
}

/** Geometria do arrasto da mira (sem Android). Listas de pontos: x0, y0, x1, y1, ... */
object AimMath {

    /** Encurta (dx, dy), mantendo a direção, para o dedo não passar da borda da tela. */
    fun fit(
        px: Float, py: Float, dx: Float, dy: Float,
        w: Float, h: Float, margin: Float
    ): FloatArray {
        var t = 1f
        if (dx > 0f) t = minOf(t, (w - margin - px) / dx) else if (dx < 0f) t = minOf(t, (margin - px) / dx)
        if (dy > 0f) t = minOf(t, (h - margin - py) / dy) else if (dy < 0f) t = minOf(t, (margin - py) / dy)
        t = t.coerceIn(0f, 1f)
        return floatArrayOf(dx * t, dy * t)
    }

    /**
     * Caminho do dedo para deslocar (dx, dy) a partir de (px, py).
     * Se o deslocamento for menor que [minTravel], o dedo vai além e volta (ou recua e avança),
     * terminando no mesmo lugar: o deslocamento final é o mesmo, mas o movimento passa do
     * limite em que alguns navegadores/jogos ignoram toques muito curtos.
     */
    fun path(
        px: Float, py: Float, dx: Float, dy: Float,
        minTravel: Float, w: Float, h: Float, margin: Float
    ): FloatArray {
        val plain = floatArrayOf(px, py, px + dx, py + dy)
        val d = hypot(dx, dy)
        if (minTravel <= 0f || d < 0.5f || d >= minTravel) return plain

        val ux = dx / d
        val uy = dy / d
        fun inside(x: Float, y: Float) = x >= margin && x <= w - margin && y >= margin && y <= h - margin

        val fx = px + dx + ux * (minTravel - d)
        val fy = py + dy + uy * (minTravel - d)
        if (inside(fx, fy)) return floatArrayOf(px, py, fx, fy, px + dx, py + dy)

        val bx = px - ux * minTravel
        val by = py - uy * minTravel
        if (inside(bx, by)) return floatArrayOf(px, py, bx, by, px + dx, py + dy)

        return plain
    }

    fun length(pts: FloatArray): Float {
        var s = 0f
        var i = 2
        while (i + 1 < pts.size) {
            s += hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1])
            i += 2
        }
        return s
    }
}
