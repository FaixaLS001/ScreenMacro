package com.macro.visionpaint

import org.opencv.core.Mat
import kotlin.math.max
import kotlin.math.min

/** Ajustes de um quadro. Posições em pixels da imagem da captura. */
class TrackParams(
    /** Nota para ACHAR um alvo (alta). */
    val acquire: Float,
    /** Nota para SEGUIR um alvo já achado (menor; nunca passa de [acquire]). */
    val follow: Float,
    /** Por quanto tempo (ms) segue a previsão de um alvo perdido antes de desistir. */
    val holdMs: Long,
    val kalmanOn: Boolean,
    val kalmanSmooth: Int,
    val shakeComp: Boolean,
    /** Onde fica a mira fixa do jogo (fica de fora da medida do tremor). */
    val aimX: Float,
    val aimY: Float,
    /** Notas dos modelos 3D (a escala é outra, a da confiança do [ModelMatcher]): achar e seguir. */
    val modelAcquire: Float = 0.50f,
    val modelFollow: Float = 0.35f
)

/**
 * O que acontece a cada quadro, sem Android (só OpenCV), para o app e os testes usarem o mesmo
 * código:
 *  1. mede quanto a tela inteira andou (tremor/giro);
 *  2. prevê onde cada alvo está (velocidade + deslocamento da tela);
 *  3. procura cada alvo de novo só ao redor da previsão (nota de SEGUIR, mais baixa);
 *     se não achar, o alvo fica "perdido" e a previsão continua por [TrackParams.holdMs];
 *  4. de tempos em tempos faz a varredura completa (nota de ACHAR, alta) para achar alvos novos
 *     e retomar os perdidos.
 */
class TrackingPipeline(
    private val det: Detector,
    private val clock: () -> Long
) {
    companion object {
        private const val MIN_FULL_SCAN_MS = 500L
        private const val LOST_FULL_SCAN_MS = 150L
    }

    val tracks = TrackManager()
    private val shake = ShakeEstimator()

    private var lastFrameMs = 0L
    private var lastFullScan = 0L
    private var lastFullMs = 0L

    /** Quanto a tela treme (px da captura). */
    val shakeAmplitude: Float get() = shake.amplitude

    /** Esquece tudo (pausa, rotação da tela, reinício). */
    fun reset() {
        tracks.clear()
        shake.reset()
        lastFrameMs = 0L
        lastFullScan = 0L
        lastFullMs = 0L
    }

    /** Processa o quadro [rgb] (muda o quadro se o desfoque leve estiver ligado). [now] = hora do quadro (ms). */
    fun process(rgb: Mat, now: Long, p: TrackParams): List<Track> {
        det.modelAcquire = p.modelAcquire
        det.modelFollow = min(p.modelFollow, p.modelAcquire)
        det.prepare(rgb)

        val dt = if (lastFrameMs == 0L) 0.07f else ((now - lastFrameMs) / 1000f).coerceIn(0.01f, 0.5f)
        lastFrameMs = now
        val fw = rgb.cols()
        val fh = rgb.rows()

        // 1) Quanto a tela inteira andou desde o quadro anterior.
        var sx = 0f
        var sy = 0f
        if (p.shakeComp) {
            val shift = try {
                shake.estimate(rgb, holes(fw, fh, p))
            } catch (e: Exception) {
                null
            }
            if (shift != null) {
                sx = shift[0]
                sy = shift[1]
            }
        } else {
            shake.reset()
        }

        // 2) Previsão.
        tracks.configure(p.kalmanOn, p.kalmanSmooth)
        tracks.advance(dt, sx, sy)

        // 3) Busca local. Perto da previsão vale a nota de seguir.
        for (t in ArrayList(tracks.tracks)) {
            val hit = det.matchNear(rgb, t.tplId, t.cx, t.cy, p.follow, p.acquire, t.pose)
            if (hit != null) tracks.hit(t, hit, now) else tracks.miss(t)
        }
        tracks.dedupe()

        // 4) Varredura completa: lenta (a pintura fica parada enquanto roda), então o intervalo
        // cresce junto com o tempo que ela leva. Com algum alvo perdido, roda mais cedo.
        val interval = when {
            tracks.isEmpty() -> 0L
            tracks.tracks.any { it.missing } -> max(LOST_FULL_SCAN_MS, 2 * lastFullMs)
            else -> max(MIN_FULL_SCAN_MS, 3 * lastFullMs)
        }
        if (now - lastFullScan >= interval) {
            val t0 = clock()
            // Com cor, a imagem só confirma: aceita nota entre a de achar e a de seguir.
            val dets = det.detect(rgb, p.acquire, (p.acquire + p.follow) / 2f)
            lastFullScan = clock()
            lastFullMs = lastFullScan - t0
            tracks.associate(dets, now, 0.35f * min(fw, fh))
        }
        tracks.dropExpired(now, p.holdMs)
        return tracks.tracks
    }

    /**
     * Círculos (cx, cy, raio) que ficam de fora da medida do tremor: a mira fixa do jogo (não anda
     * com a cena e dominaria a medida) e os próprios alvos (andam sozinhos).
     */
    private fun holes(fw: Int, fh: Int, p: TrackParams): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        out.add(floatArrayOf(p.aimX, p.aimY, 0.13f * min(fw, fh)))
        for (t in tracks.tracks) out.add(floatArrayOf(t.cx, t.cy, 0.75f * max(t.w, t.h) + 6f))
        return out
    }
}
