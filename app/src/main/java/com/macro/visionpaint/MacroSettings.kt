package com.macro.visionpaint

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** Configurações salvas no aparelho, compartilhadas entre a tela principal e os serviços. */
object MacroSettings {
    private const val PREFS = "macro_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** Cor da pintura (qualquer cor). */
    var color: Int
        get() = sp.getInt("color", Color.RED)
        set(v) = sp.edit().putInt("color", v).apply()

    /** Sensibilidade do reconhecimento (0.50 a 0.98). Maior = mais exigente. */
    var threshold: Float
        get() = sp.getFloat("threshold", 0.80f)
        set(v) = sp.edit().putFloat("threshold", v).apply()

    /** Opacidade da pintura em % (15 a 75). */
    var paintAlphaPct: Int
        get() = sp.getInt("paintAlpha", 45)
        set(v) = sp.edit().putInt("paintAlpha", v.coerceIn(15, 75)).apply()

    /** Escala da captura em % (30 a 100). Menor = mais rápido, maior = mais preciso. */
    var scalePct: Int
        get() = sp.getInt("scale", 50)
        set(v) = sp.edit().putInt("scale", v.coerceIn(30, 100)).apply()

    /** Liga o arrasto automático do dedo até o alvo. */
    var dragEnabled: Boolean
        get() = sp.getBoolean("dragEnabled", false)
        set(v) = sp.edit().putBoolean("dragEnabled", v).apply()

    /** Velocidade do arrasto em pixels por segundo. */
    var dragSpeed: Int
        get() = sp.getInt("dragSpeed", 1600)
        set(v) = sp.edit().putInt("dragSpeed", v.coerceIn(300, 6000)).apply()

    /** Pausa mínima entre um arrasto e o próximo, em milissegundos. */
    var cooldownMs: Int
        get() = sp.getInt("cooldownMs", 400)
        set(v) = sp.edit().putInt("cooldownMs", v.coerceIn(0, 5000)).apply()

    /**
     * Ponto de partida do dedo, em milésimos da largura da tela (0 a 1000). É guardado com
     * mais precisão que o percentual para dar para posicionar na tela com o dedo.
     * Se ainda não existe, aproveita o valor em % salvo pelas versões anteriores.
     */
    var originXPm: Int
        get() = sp.getInt("originXm", sp.getInt("originX", 50) * 10)
        set(v) = sp.edit().putInt("originXm", v.coerceIn(0, 1000)).apply()

    /** Ponto de partida do dedo, em milésimos da altura da tela (0 a 1000). */
    var originYPm: Int
        get() = sp.getInt("originYm", sp.getInt("originY", 80) * 10)
        set(v) = sp.edit().putInt("originYm", v.coerceIn(0, 1000)).apply()

    /** Ponto de partida do dedo (posição X em % da largura da tela). */
    var originXPct: Int
        get() = originXPm / 10
        set(v) {
            originXPm = v.coerceIn(0, 100) * 10
        }

    /** Ponto de partida do dedo (posição Y em % da altura da tela). */
    var originYPct: Int
        get() = originYPm / 10
        set(v) {
            originYPm = v.coerceIn(0, 100) * 10
        }

    // ------------------------------------------------------------------ modo mira

    /** Modo mira: em vez de arrastar até o alvo, leva a mira do jogo até o centro da imagem. */
    var aimMode: Boolean
        get() = sp.getBoolean("aimMode", false)
        set(v) = sp.edit().putBoolean("aimMode", v).apply()

    /** Posição da mira do jogo, em milésimos da largura da tela (0 a 1000). */
    var aimXPm: Int
        get() = sp.getInt("aimXm", 500)
        set(v) = sp.edit().putInt("aimXm", v.coerceIn(0, 1000)).apply()

    /** Posição da mira do jogo, em milésimos da altura da tela (0 a 1000). */
    var aimYPm: Int
        get() = sp.getInt("aimYm", 500)
        set(v) = sp.edit().putInt("aimYm", v.coerceIn(0, 1000)).apply()

    /** Quanto o dedo anda por pixel de distância até o alvo, em % (100 = 1 para 1). */
    var aimGainPct: Int
        get() = sp.getInt("aimGain", 100)
        set(v) = sp.edit().putInt("aimGain", v.coerceIn(10, 600)).apply()

    /** Raio (px) da zona em que a mira conta como fixa no centro da imagem. */
    var aimTolPx: Int
        get() = sp.getInt("aimTol", 14)
        set(v) = sp.edit().putInt("aimTol", v.coerceIn(4, 80)).apply()

    /** Pausa entre uma correção da mira e a próxima, em milissegundos. */
    var aimCooldownMs: Int
        get() = sp.getInt("aimCooldown", 120)
        set(v) = sp.edit().putInt("aimCooldown", v.coerceIn(0, 1000)).apply()

    /** Arrasto mínimo (px): correções menores fazem o dedo ir além e voltar. 0 = desligado. */
    var aimMinTravelPx: Int
        get() = sp.getInt("aimMinTravel", 24)
        set(v) = sp.edit().putInt("aimMinTravel", v.coerceIn(0, 80)).apply()

    var aimInvertX: Boolean
        get() = sp.getBoolean("aimInvX", false)
        set(v) = sp.edit().putBoolean("aimInvX", v).apply()

    var aimInvertY: Boolean
        get() = sp.getBoolean("aimInvY", false)
        set(v) = sp.edit().putBoolean("aimInvY", v).apply()

    /** Ajusta a sensibilidade sozinho, medindo como o jogo responde a cada arrasto. */
    var aimLearn: Boolean
        get() = sp.getBoolean("aimLearn", true)
        set(v) = sp.edit().putBoolean("aimLearn", v).apply()

    /** Desenha a linha da rota do dedo até o alvo. */
    var showRoute: Boolean
        get() = sp.getBoolean("showRoute", true)
        set(v) = sp.edit().putBoolean("showRoute", v).apply()

    /** Qual alvo seguir: 0 = mais perto do ponto de partida, 1 = maior nota, 2 = ordem da lista. */
    var targetMode: Int
        get() = sp.getInt("targetMode", 0)
        set(v) = sp.edit().putInt("targetMode", v.coerceIn(0, 2)).apply()

    /** Mostra a bolinha flutuante (pausar / arrasto / parar) enquanto o macro roda. */
    var showBubble: Boolean
        get() = sp.getBoolean("showBubble", true)
        set(v) = sp.edit().putBoolean("showBubble", v).apply()

    /** Última posição da bolinha na tela (-1 = ainda não definida). */
    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()
}
