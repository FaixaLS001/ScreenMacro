package com.macro.visionpaint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvTemplates: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvAccess: TextView
    private lateinit var btnStart: Button
    private lateinit var swatch: View
    private val ui = Handler(Looper.getMainLooper())

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            var ok = 0
            for (u in uris) if (TemplateStore.addFromUri(this, u)) ok++
            toast("$ok imagem(ns) adicionada(s)")
            refresh()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                ui.postDelayed({ refresh() }, 700)
                // Volta para o app/jogo que estava por baixo.
                moveTaskToBack(true)
            } else {
                toast("Compartilhamento de tela não autorizado.")
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MacroSettings.init(this)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        root.addView(heading("Vision Paint Macro", 22f))
        root.addView(
            text(
                "Envie imagens de referência. Quando uma delas aparecer na tela, " +
                    "o app pinta o local e (se ligado) arrasta o dedo até ela."
            )
        )

        // 1) Permissões
        root.addView(heading("1. Permissões", 17f))
        tvOverlay = text("")
        root.addView(tvOverlay)
        root.addView(button("Permitir sobreposição na tela") {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        tvAccess = text("")
        root.addView(tvAccess)
        root.addView(button("Ativar serviço de acessibilidade") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(
            text(
                "Se o Android bloquear a ativação (app instalado fora da loja): " +
                    "Configurações > Apps > Vision Paint Macro > menu ⋮ > " +
                    "\"Permitir configurações restritas\"."
            )
        )

        // 2) Imagens
        root.addView(heading("2. Imagens de referência", 17f))
        tvTemplates = text("")
        root.addView(tvTemplates)
        root.addView(button("Adicionar imagens") {
            pickImages.launch(arrayOf("image/*"))
        })
        root.addView(button("Apagar todas as imagens") {
            TemplateStore.clear(this)
            refresh()
        })
        root.addView(
            text("Use recortes com detalhes (ícones, personagens). Cores lisas sem detalhe não são reconhecidas.")
        )

        // 3) Pintura
        root.addView(heading("3. Pintura", 17f))
        swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
            setBackgroundColor(MacroSettings.color)
        }
        root.addView(swatch)
        val c = MacroSettings.color
        val rgb = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
        val names = arrayOf("Vermelho", "Verde", "Azul")
        for (i in 0..2) {
            root.addView(slider(names[i], 0, 255, rgb[i], "") { v ->
                rgb[i] = v
                val col = Color.rgb(rgb[0], rgb[1], rgb[2])
                MacroSettings.color = col
                swatch.setBackgroundColor(col)
            })
        }
        root.addView(slider("Opacidade da pintura", 15, 75, MacroSettings.paintAlphaPct, "%") {
            MacroSettings.paintAlphaPct = it
        })
        root.addView(slider("Sensibilidade", 50, 98, (MacroSettings.threshold * 100).toInt(), "%") {
            MacroSettings.threshold = it / 100f
        })
        root.addView(
            slider("Qualidade da captura (vale ao iniciar)", 30, 100, MacroSettings.scalePct, "%") {
                MacroSettings.scalePct = it
            }
        )

        // 4) Arrasto
        root.addView(heading("4. Arrasto do dedo", 17f))
        root.addView(switch("Arrastar automaticamente até o alvo", MacroSettings.dragEnabled) {
            MacroSettings.dragEnabled = it
        })
        root.addView(switch("Mostrar a rota na tela", MacroSettings.showRoute) {
            MacroSettings.showRoute = it
        })
        root.addView(slider("Ponto de partida - horizontal", 0, 100, MacroSettings.originXPct, "%") {
            MacroSettings.originXPct = it
        })
        root.addView(slider("Ponto de partida - vertical", 0, 100, MacroSettings.originYPct, "%") {
            MacroSettings.originYPct = it
        })
        root.addView(slider("Velocidade do arrasto", 300, 6000, MacroSettings.dragSpeed, " px/s") {
            MacroSettings.dragSpeed = it
        })
        root.addView(slider("Pausa entre arrastos", 0, 3000, MacroSettings.cooldownMs, " ms") {
            MacroSettings.cooldownMs = it
        })

        // Iniciar / parar
        btnStart = button("INICIAR") { onStartStop() }
        btnStart.textSize = 18f
        root.addView(btnStart)

        return ScrollView(this).apply { addView(root) }
    }

    private fun onStartStop() {
        if (CaptureService.running) {
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            )
            ui.postDelayed({ refresh() }, 700)
            return
        }
        if (TemplateStore.count(this) == 0) {
            toast("Adicione ao menos uma imagem de referência.")
            return
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            toast("Permita a sobreposição na tela primeiro.")
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun refresh() {
        tvTemplates.text = "Imagens de referência: ${TemplateStore.count(this)}"
        tvOverlay.text = if (AndroidSettings.canDrawOverlays(this)) {
            "✔ Sobreposição permitida"
        } else {
            "✘ Sobreposição não permitida"
        }
        tvAccess.text = if (MacroAccessibilityService.instance != null) {
            "✔ Acessibilidade ativa"
        } else {
            "✘ Acessibilidade desativada (só é necessária para o arrasto)"
        }
        btnStart.text = if (CaptureService.running) "PARAR" else "INICIAR"
    }

    // ------------------------------------------------------------------ helpers de UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun heading(t: String, size: Float) = TextView(this).apply {
        text = t
        textSize = size
        setPadding(0, dp(18), 0, dp(6))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun text(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, start: Boolean, onChange: (Boolean) -> Unit) =
        SwitchCompat(this).apply {
            text = label
            isChecked = start
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun slider(
        label: String,
        min: Int,
        max: Int,
        start: Int,
        unit: String,
        onChange: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val tv = TextView(this).apply { text = "$label: $start$unit" }
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = (start - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tv.text = "$label: $v$unit"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv)
        box.addView(sb)
        return box
    }
}
