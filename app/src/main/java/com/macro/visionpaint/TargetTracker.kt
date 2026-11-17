package com.macro.visionpaint

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/** Um achado de imagem, em pixels da imagem da captura. [cx], [cy] = centro. */
class Detection(
    val tplId: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val score: Float,
    /** true = achado só pela cor (sem a imagem confirmar): é uma medida menos confiável. */
    val byColor: Boolean = false
)

/** Um alvo que está sendo seguido. A posição vem do filtro de Kalman (já sem o tremor). */
class Track(
    val id: Int,
    val tplId: String,
    cx: Float,
    cy: Float,
    var w: Float,
    var h: Float,
    var score: Float,
    nowMs: Long
) {
    val kx = Kalman1D(cx)
    val ky = Kalman1D(cy)
    val cx: Float get() = kx.p
    val cy: Float get() = ky.p

    /** Última vez em que a imagem (ou a cor) foi vista de fato. */
    var lastSeenMs = nowMs

    /** true enquanto o alvo não é visto: a posição é só uma previsão ("inércia"). */
    var missing = false

    /** Desde quando só a cor sustenta este alvo (a imagem não combina); -1 = a imagem combina. */
    var colorOnlySinceMs = -1L
}

/**
 * Lista de alvos seguidos, com a lógica de histerese e inércia (sem Android):
 *  - achar um alvo novo exige nota alta; seguir um que já se conhece aceita nota menor
 *    (quem chama usa dois limites diferentes);
 *  - se o alvo some por um instante, a previsão (Kalman + deslocamento da tela) continua por
 *    [dropExpired] `holdMs` milissegundos antes de desistir;
 *  - a posição é suavizada por um filtro de Kalman (liga/desliga em [configure]).
 */
class TrackManager {

    companion object {
        /** Tempo em que a velocidade do alvo perdido cai para ~37% (a previsão não foge). */
        private const val LOST_VEL_TAU_S = 0.25f
        private const val MEAS_SIGMA_PX = 3f

        /** Quanto tempo a cor sozinha pode sustentar um alvo sem a imagem confirmar. */
        const val COLOR_ONLY_MAX_MS = 1500L
    }

    val tracks = ArrayList<Track>()

    private var kalmanOn = true
    private var accelSigma = 500f
    private var nextId = 1

    fun clear() = tracks.clear()

    fun isEmpty(): Boolean = tracks.isEmpty()

    /**
     * [smooth] de 0 a 100: quanto maior, mais liso (e um pouco mais atrasado). A agilidade do
     * filtro vai de 2500 px/s² (0 = quase sem filtro) a ~500 (50, padrão) e 100 px/s² (100).
     * Em simulação, o padrão nunca ficou pior que a medida crua e reduziu o tremor em 10% a 30%.
     */
    fun configure(kalman: Boolean, smooth: Int) {
        kalmanOn = kalman
        accelSigma = 2500f * 0.04f.pow(smooth.coerceIn(0, 100) / 100f)
    }

    /** Avança todos os alvos [dt] segundos, já somando o deslocamento da tela ([sx], [sy]). */
    fun advance(dt: Float, sx: Float, sy: Float) {
        for (t in tracks) {
            val damp = if (t.missing) exp(-dt / LOST_VEL_TAU_S) else 1f
            t.kx.predict(dt, sx, accelSigma, damp)
            t.ky.predict(dt, sy, accelSigma, damp)
        }
    }

    /** O alvo [t] foi visto em ([cx], [cy]) com tamanho [w] x [h] e nota [score]. */
    fun hit(t: Track, d: Detection, nowMs: Long) {
        val jump = hypot(d.cx - t.cx, d.cy - t.cy)
        if (!kalmanOn || jump > 1.5f * max(t.w, t.h)) {
            // Sem filtro, ou o alvo "pulou" (reapareceu longe): recomeça dele.
            t.kx.reset(d.cx)
            t.ky.reset(d.cy)
        } else {
            // Medida pior (nota baixa, ou só a cor) pesa menos.
            var r = MEAS_SIGMA_PX * MEAS_SIGMA_PX * (1f + 6f * (1f - d.score).coerceIn(0f, 1f))
            if (d.byColor) r *= 3f
            t.kx.update(d.cx, r)
            t.ky.update(d.cy, r)
        }
        t.w = d.w
        t.h = d.h
        t.score = d.score
        t.lastSeenMs = nowMs
        t.missing = false
        if (d.byColor) {
            if (t.colorOnlySinceMs < 0) t.colorOnlySinceMs = nowMs
        } else {
            t.colorOnlySinceMs = -1L
        }
    }

    /** O alvo não foi visto neste quadro: continua só pela previsão. */
    fun miss(t: Track) {
        t.missing = true
    }

    /** Dois alvos do mesmo modelo no mesmo lugar são o mesmo objeto: fica o melhor. */
    fun dedupe() {
        var i = 0
        while (i < tracks.size) {
            var removed = false
            var j = i + 1
            while (j < tracks.size) {
                val a = tracks[i]
                val b = tracks[j]
                if (a.tplId == b.tplId &&
                    kotlin.math.abs(a.cx - b.cx) < 0.5f * a.w &&
                    kotlin.math.abs(a.cy - b.cy) < 0.5f * a.h
                ) {
                    val dropA = (a.missing && !b.missing) ||
                        (a.missing == b.missing && a.score < b.score)
                    if (dropA) {
                        tracks.removeAt(i)
                        removed = true
                        break
                    } else {
                        tracks.removeAt(j)
                        continue
                    }
                }
                j++
            }
            if (!removed) i++
        }
    }

    /**
     * Junta o resultado de uma varredura completa ([dets], nota alta) com os alvos já seguidos:
     * achado perto de um alvo conhecido não cria outro; alvo perdido que reaparece (mesmo longe,
     * até [reacquireRadius] px) é retomado; o resto vira alvo novo.
     */
    fun associate(dets: List<Detection>, nowMs: Long, reacquireRadius: Float) {
        val used = HashSet<Int>()
        for (d in dets.sortedByDescending { it.score }) {
            val gate = 0.6f * max(d.w, d.h)

            var near: Track? = null
            var nearD = Float.MAX_VALUE
            for (t in tracks) {
                if (t.tplId != d.tplId) continue
                val dist = hypot(d.cx - t.cx, d.cy - t.cy)
                if (dist <= gate && dist < nearD) {
                    near = t
                    nearD = dist
                }
            }
            if (near != null) {
                if (near.id in used) continue // o mesmo objeto achado duas vezes
                used.add(near.id)
                // Alvo que já foi visto neste quadro pela busca local não precisa de ajuda.
                if (near.lastSeenMs != nowMs) hit(near, d, nowMs)
                continue
            }

            var lost: Track? = null
            var lostD = Float.MAX_VALUE
            for (t in tracks) {
                if (t.tplId != d.tplId || !t.missing || t.id in used) continue
                val dist = hypot(d.cx - t.cx, d.cy - t.cy)
                if (dist <= reacquireRadius && dist < lostD) {
                    lost = t
                    lostD = dist
                }
            }
            if (lost != null) {
                used.add(lost.id)
                hit(lost, d, nowMs)
                continue
            }

            val nt = Track(nextId++, d.tplId, d.cx, d.cy, d.w, d.h, d.score, nowMs)
            tracks.add(nt)
            used.add(nt.id)
        }
    }

    /**
     * Desiste dos alvos que ficaram mais de [holdMs] ms sem serem vistos, e dos que só a cor
     * sustenta há mais de [COLOR_ONLY_MAX_MS] (uma mancha da mesma cor não pode virar o alvo
     * para sempre).
     */
    fun dropExpired(nowMs: Long, holdMs: Long) {
        tracks.removeAll {
            nowMs - it.lastSeenMs > holdMs ||
                (it.colorOnlySinceMs >= 0 && nowMs - it.colorOnlySinceMs > COLOR_ONLY_MAX_MS)
        }
    }
}
