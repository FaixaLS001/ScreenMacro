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

    /** Ponto de partida do dedo (posição X em % da largura da tela). */
    var originXPct: Int
        get() = sp.getInt("originX", 50)
        set(v) = sp.edit().putInt("originX", v.coerceIn(0, 100)).apply()

    /** Ponto de partida do dedo (posição Y em % da altura da tela). */
    var originYPct: Int
        get() = sp.getInt("originY", 80)
        set(v) = sp.edit().putInt("originY", v.coerceIn(0, 100)).apply()

    /** Desenha a linha da rota do dedo até o alvo. */
    var showRoute: Boolean
        get() = sp.getBoolean("showRoute", true)
        set(v) = sp.edit().putBoolean("showRoute", v).apply()
}
