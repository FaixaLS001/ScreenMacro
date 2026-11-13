package com.macro.visionpaint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvTemplates: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvAccess: TextView
    private lateinit var btnStart: Button
    private lateinit var swatch: View
    private lateinit var swDrag: SwitchCompat
    private lateinit var swAim: SwitchCompat

    /** Atualizam na volta ao app o que o painel de mira pode ter mudado (posições). */
    private val syncs = mutableListOf<() -> Unit>()
    private lateinit var rowsBox: LinearLayout
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
        TemplateStore.init(this)
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
                    "o app pinta o local e (se ligado) arrasta o dedo até ela ou leva a mira " +
                    "do jogo até o centro dela (modo mira)."
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
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsBox)
        root.addView(button("Adicionar imagens") {
            pickImages.launch(arrayOf("image/*"))
        })
        root.addView(button("Apagar todas as imagens") { confirmClear() })
        root.addView(
            text(
                "Toque na cor para trocá-la só nessa imagem. \"Arrastar até ela\" liga o arrasto " +
                    "(ou a mira, no modo mira) apenas para ela. Use ↑ e ↓ para mudar a ordem da lista.\n" +
                    "Use recortes com detalhes (ícones, personagens). Cores lisas sem detalhe " +
                    "não são reconhecidas."
            )
        )

        // 3) Pintura
        root.addView(heading("3. Cor padrão e pintura", 17f))
        root.addView(text("A cor padrão vale para as imagens que não têm cor própria."))
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
        swDrag = switch("Arrastar automaticamente até o alvo", MacroSettings.dragEnabled) {
            MacroSettings.dragEnabled = it
        }
        root.addView(swDrag)
        root.addView(switch("Mostrar a rota na tela", MacroSettings.showRoute) {
            MacroSettings.showRoute = it
        })

        root.addView(text("Qual alvo seguir (entre as imagens com \"Arrastar até ela\"):"))
        val modes = arrayOf(
            "Mais perto do ponto de partida",
            "Maior nota (o mais parecido com a imagem)",
            "Ordem da lista (de cima para baixo)"
        )
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (i in modes.indices) {
            val rb = RadioButton(this).apply {
                text = modes[i]
                id = View.generateViewId()
                tag = i
            }
            group.addView(rb)
            if (i == MacroSettings.targetMode) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val rb = g.findViewById<RadioButton>(checkedId)
            if (rb != null) MacroSettings.targetMode = rb.tag as Int
        }
        root.addView(group)

        root.addView(
            text(
                "Ponto de partida = onde o dedo pressiona para arrastar. Também dá para " +
                    "marcá-lo direto na tela: bolinha > Painel de mira > 1 Toque."
            )
        )
        root.addView(
            positionSlider("Ponto de partida - horizontal", { MacroSettings.originXPct }) {
                MacroSettings.originXPct = it
            }
        )
        root.addView(
            positionSlider("Ponto de partida - vertical", { MacroSettings.originYPct }) {
                MacroSettings.originYPct = it
            }
        )
        root.addView(slider("Velocidade do arrasto", 300, 6000, MacroSettings.dragSpeed, " px/s") {
            MacroSettings.dragSpeed = it
        })
        root.addView(slider("Pausa entre arrastos", 0, 3000, MacroSettings.cooldownMs, " ms") {
            MacroSettings.cooldownMs = it
        })

        // 5) Modo mira
        root.addView(heading("5. Modo mira (travar a mira na imagem)", 17f))
        swAim = switch("Modo mira: levar a mira até o centro da imagem", MacroSettings.aimMode) {
            MacroSettings.aimMode = it
        }
        root.addView(swAim)
        root.addView(
            text(
                "Em vez de arrastar até a imagem, o app arrasta o dedo a partir do ponto de " +
                    "partida para empurrar a mira do jogo até o centro da imagem, corrigindo em " +
                    "passos até ela ficar fixa. Precisa de \"Arrastar automaticamente\" ligado e " +
                    "da acessibilidade ativa.\n\n" +
                    "Na bolinha flutuante, toque em Painel de mira:\n" +
                    "1 Toque: marque onde o dedo pressiona para arrastar.\n" +
                    "2 Mira: marque onde fica a mira do jogo (o marcador fica quase invisível).\n" +
                    "3 Imagem: escolha em que imagem(ns) a mira se fixa.\n" +
                    "Travar: liga/desliga o modo mira."
            )
        )
        root.addView(
            slider("Sensibilidade da mira", 10, 600, MacroSettings.aimGainPct, "%") {
                MacroSettings.aimGainPct = it
            }
        )
        root.addView(
            text(
                "Quanto o dedo anda para cada pixel de distância até a imagem. Se a mira passa " +
                    "do centro, baixe; se fica curta, suba. Com \"Aprender sozinho\" ligado, o app " +
                    "corrige esse valor medindo como o jogo responde."
            )
        )
        root.addView(switch("Aprender a sensibilidade sozinho", MacroSettings.aimLearn) {
            MacroSettings.aimLearn = it
        })
        root.addView(slider("Zona de travamento (raio)", 4, 80, MacroSettings.aimTolPx, " px") {
            MacroSettings.aimTolPx = it
        })
        root.addView(
            slider("Pausa entre correções", 0, 1000, MacroSettings.aimCooldownMs, " ms") {
                MacroSettings.aimCooldownMs = it
            }
        )
        root.addView(
            slider("Arrasto mínimo", 0, 80, MacroSettings.aimMinTravelPx, " px") {
                MacroSettings.aimMinTravelPx = it
            }
        )
        root.addView(
            text(
                "Alguns jogos e navegadores ignoram arrastos muito curtos. Correções menores " +
                    "que o arrasto mínimo fazem o dedo ir além e voltar, terminando no mesmo " +
                    "lugar. Use 0 para desligar."
            )
        )
        root.addView(switch("Inverter horizontal", MacroSettings.aimInvertX) {
            MacroSettings.aimInvertX = it
        })
        root.addView(switch("Inverter vertical", MacroSettings.aimInvertY) {
            MacroSettings.aimInvertY = it
        })
        root.addView(
            text("Se a mira se afasta da imagem em vez de chegar nela, ligue a inversão do eixo.")
        )
        root.addView(
            positionSlider("Posição da mira - horizontal", { MacroSettings.aimXPm / 10 }) {
                MacroSettings.aimXPm = it * 10
            }
        )
        root.addView(
            positionSlider("Posição da mira - vertical", { MacroSettings.aimYPm / 10 }) {
                MacroSettings.aimYPm = it * 10
            }
        )
        root.addView(text("Mais fácil: marque a mira direto na tela, em Painel de mira > 2 Mira."))

        // 6) Bolinha flutuante
        root.addView(heading("6. Bolinha flutuante", 17f))
        root.addView(
            switch("Mostrar a bolinha durante o uso (vale ao iniciar)", MacroSettings.showBubble) {
                MacroSettings.showBubble = it
            }
        )
        root.addView(
            text(
                "Arraste a bolinha para mover. Toque nela para pausar/retomar, ligar ou " +
                    "desligar o arrasto, abrir o Painel de mira ou parar. Verde = rodando, " +
                    "amarela = pausado. Deixe-a longe do ponto de partida do dedo. Com o " +
                    "painel aberto o macro não arrasta."
            )
        )

        // Iniciar / parar
        btnStart = button("INICIAR") { onStartStop() }
        btnStart.textSize = 18f
        root.addView(btnStart)

        return ScrollView(this).apply { addView(root) }
    }

    // ------------------------------------------------------------------ lista de imagens

    private fun rebuildRows() {
        rowsBox.removeAllViews()
        val list = TemplateStore.list()
        list.forEachIndexed { i, t -> rowsBox.addView(templateRow(i, list.size, t)) }
    }

    private fun templateRow(index: Int, total: Int, t: Tpl): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundColor(Color.parseColor("#33888888"))
            setImageBitmap(TemplateStore.thumb(t.id))
        }
        row.addView(img)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(6), 0)
        }
        mid.addView(TextView(this).apply {
            text = "Imagem ${index + 1}"
            setTypeface(typeface, Typeface.BOLD)
        })

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
            setOnClickListener { showColorDialog(t) }
        }
        colorRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(24))
            setBackgroundColor(t.color ?: MacroSettings.color)
        })
        colorRow.addView(TextView(this).apply {
            text = if (t.color == null) "  cor padrão (toque)" else "  cor própria (toque)"
        })
        mid.addView(colorRow)
        mid.addView(switch("Arrastar até ela", t.drag) { TemplateStore.setDrag(t.id, it) })
        row.addView(mid)

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(smallButton("↑", index > 0) {
            TemplateStore.move(t.id, -1)
            rebuildRows()
        })
        right.addView(smallButton("↓", index < total - 1) {
            TemplateStore.move(t.id, 1)
            rebuildRows()
        })
        right.addView(smallButton("✕", true) {
            TemplateStore.remove(t.id)
            refresh()
        })
        row.addView(right)
        return row
    }

    private fun showColorDialog(t: Tpl) {
        val start = t.color ?: MacroSettings.color
        val rgb = intArrayOf(Color.red(start), Color.green(start), Color.blue(start))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
            setBackgroundColor(start)
        }
        box.addView(preview)
        val names = arrayOf("Vermelho", "Verde", "Azul")
        for (i in 0..2) {
            box.addView(slider(names[i], 0, 255, rgb[i], "") { v ->
                rgb[i] = v
                preview.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Cor desta imagem")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                TemplateStore.setColor(t.id, Color.rgb(rgb[0], rgb[1], rgb[2]))
                rebuildRows()
            }
            .setNeutralButton("Usar cor padrão") { _, _ ->
                TemplateStore.setColor(t.id, null)
                rebuildRows()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmClear() {
        if (TemplateStore.count() == 0) return
        AlertDialog.Builder(this)
            .setTitle("Apagar todas as imagens?")
            .setPositiveButton("Apagar") { _, _ ->
                TemplateStore.clear()
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ iniciar / parar

    private fun onStartStop() {
        if (CaptureService.running) {
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            )
            ui.postDelayed({ refresh() }, 700)
            return
        }
        if (TemplateStore.count() == 0) {
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
        tvTemplates.text = "Imagens de referência: ${TemplateStore.count()}"
        rebuildRows()
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
        // A bolinha também liga/desliga o arrasto: mantém a chave igual ao estado atual.
        swDrag.isChecked = MacroSettings.dragEnabled
        swAim.isChecked = MacroSettings.aimMode
        syncs.forEach { it() }
        btnStart.text = if (CaptureService.running) "PARAR" else "INICIAR"
    }

    // ------------------------------------------------------------------ helpers de UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun heading(t: String, size: Float) = TextView(this).apply {
        text = t
        textSize = size
        setPadding(0, dp(18), 0, dp(6))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun text(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, enabled: Boolean, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isEnabled = enabled
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { onClick() }
        }

    private fun switch(label: String, start: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(this).apply {
            text = label
            isChecked = start
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    /** Slider de posição na tela (0 a 100 %), que se atualiza ao voltar para o app. */
    private fun positionSlider(label: String, get: () -> Int, set: (Int) -> Unit): View {
        val box = slider(label, 0, 100, get(), "%") { set(it) } as LinearLayout
        syncs.add { (box.getChildAt(1) as SeekBar).progress = get().coerceIn(0, 100) }
        return box
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
