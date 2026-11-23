package com.macro.visionpaint

/**
 * Filtro de Kalman de um eixo (estado = posição e velocidade). Sem Android, só matemática.
 * Todas as unidades são pixels da imagem da captura e segundos.
 *
 * Serve para suavizar o tremor da posição medida e para prever onde o alvo estará quando a
 * detecção falhar por um instante (borrão, por exemplo).
 */
class Kalman1D(pos: Float) {

    companion object {
        /** Incerteza inicial da velocidade: ainda não se sabe nada, então começa alta. */
        private const val VEL_VAR0 = 22500f
        private const val POS_VAR0 = 10f
    }

    var p = pos
        private set
    var v = 0f
        private set

    private var p00 = POS_VAR0
    private var p01 = 0f
    private var p11 = VEL_VAR0

    /** Recomeça em [pos], sem velocidade (alvo que reapareceu em outro lugar). */
    fun reset(pos: Float) {
        p = pos
        v = 0f
        p00 = POS_VAR0
        p01 = 0f
        p11 = VEL_VAR0
    }

    /**
     * Avança [dt] segundos. [shift] é um deslocamento já conhecido (o da tela inteira, medido por
     * [ShakeEstimator]) e entra direto na posição. [accelSigma] (px/s²) diz o quanto o alvo pode
     * mudar de velocidade: menor = mais liso, maior = mais ágil. [damp] (0 a 1) amortece a
     * velocidade, usado enquanto o alvo está perdido para a previsão não fugir.
     */
    fun predict(dt: Float, shift: Float, accelSigma: Float, damp: Float = 1f) {
        v *= damp
        p += v * dt + shift
        val q = accelSigma * accelSigma
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val n00 = p00 + 2f * dt * p01 + dt2 * p11 + q * dt3 / 3f
        val n01 = p01 + dt * p11 + q * dt2 / 2f
        val n11 = p11 + q * dt
        p00 = n00
        p01 = n01
        p11 = n11
    }

    /** Corrige com a posição medida [z]. [measVar] = variância (px²) do erro da medida. */
    fun update(z: Float, measVar: Float) {
        val s = p00 + measVar
        val k0 = p00 / s
        val k1 = p01 / s
        val err = z - p
        p += k0 * err
        v += k1 * err
        val old01 = p01
        p00 -= k0 * p00
        p01 -= k0 * p01
        p11 -= k1 * old01
    }
}
