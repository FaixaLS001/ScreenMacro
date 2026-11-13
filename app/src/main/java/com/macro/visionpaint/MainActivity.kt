package com.macro.visionpaint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : AppCompatActivity() {

    /** Estilos de botão: cheio (principal), suave (secundário), perigo (suave vermelho) e parar (cheio vermelho). */
    private enum class Kind { PRIMARY, TONAL, DANGER, STOP }

    private lateinit var tvTemplates: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvAccess: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var statusDot: View
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
        applyBarStyle()

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

    /** Ícones da barra de status e de navegação escuros no tema claro e claros no escuro. */
    private fun applyBarStyle() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.isAppearanceLightStatusBars = !night
        controller?.isAppearanceLightNavigationBars = !night
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(R.color.vp_bg))
        }
        page.addView(header())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        content.addView(
            note(
                "Envie imagens de referência. Quando uma delas aparecer na tela, " +
                    "o app pinta o local e (se ligado) arrasta o dedo até ela ou leva a mira " +
                    "do jogo até o centro dela (modo mira)."
            ),
            lp(0)
        )

        // 1) Permissões
        val c1 = card(1, "Permissões")
        content.addView(c1)
        tvOverlay = chip()
        c1.addView(
            permRow("Sobreposição na tela", tvOverlay, "Permitir sobreposição na tela") {
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        )
        tvAccess = chip()
        c1.addView(
            permRow("Acessibilidade (só para o arrasto)", tvAccess, "Ativar serviço de acessibilidade") {
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        c1.addView(
            note(
                "Se o Android bloquear a ativação (app instalado fora da loja): " +
                    "Configurações > Apps > Vision Paint Macro > menu ⋮ > " +
                    "\"Permitir configurações restritas\"."
            )
        )

        // 2) Imagens
        val c2 = card(2, "Imagens de referência")
        content.addView(c2)
        tvTemplates = note("")
        c2.addView(tvTemplates)
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c2.addView(rowsBox)
        c2.addView(button("Adicionar imagens", Kind.PRIMARY) {
            pickImages.launch(arrayOf("image/*"))
        }, lp(8))
        c2.addView(button("Apagar todas as imagens", Kind.DANGER) { confirmClear() }, lp(8))
        c2.addView(
            note(
                "Toque na cor para trocá-la só nessa imagem. \"Arrastar até ela\" liga o arrasto " +
                    "(ou a mira, no modo mira) apenas para ela. Use ↑ e ↓ para mudar a ordem da lista.\n" +
                    "Use recortes com detalhes (ícones, personagens). Cores lisas sem detalhe " +
                    "não são reconhecidas."
            )
        )

        // 3) Pintura
        val c3 = card(3, "Cor padrão e pintura")
        content.addView(c3)
        c3.addView(note("A cor padrão vale para as imagens que não têm cor própria."))
        swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(40)).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
            background = rounded(MacroSettings.color, 12f, themeColor(R.color.vp_stroke))
        }
        c3.addView(swatch)
        val c = MacroSettings.color
        val rgb = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
        val names = arrayOf("Vermelho", "Verde", "Azul")
        val tints = intArrayOf(
            Color.parseColor("#E5484D"),
            Color.parseColor("#30A46C"),
            Color.parseColor("#3E63DD")
        )
        for (i in 0..2) {
            c3.addView(slider(names[i], 0, 255, rgb[i], "", tints[i]) { v ->
                rgb[i] = v
                val col = Color.rgb(rgb[0], rgb[1], rgb[2])
                MacroSettings.color = col
                swatch.background = rounded(col, 12f, themeColor(R.color.vp_stroke))
            })
        }
        c3.addView(slider("Opacidade da pintura", 15, 75, MacroSettings.paintAlphaPct, "%") {
            MacroSettings.paintAlphaPct = it
        })
        c3.addView(slider("Sensibilidade", 50, 98, (MacroSettings.threshold * 100).toInt(), "%") {
            MacroSettings.threshold = it / 100f
        })
        c3.addView(
            slider("Qualidade da captura (vale ao iniciar)", 30, 100, MacroSettings.scalePct, "%") {
                MacroSettings.scalePct = it
            }
        )

        // 4) Arrasto
        val c4 = card(4, "Arrasto do dedo")
        content.addView(c4)
        swDrag = switch("Arrastar automaticamente até o alvo", MacroSettings.dragEnabled) {
            MacroSettings.dragEnabled = it
        }
        c4.addView(swDrag)
        c4.addView(switch("Mostrar a rota na tela", MacroSettings.showRoute) {
            MacroSettings.showRoute = it
        })

        c4.addView(note("Qual alvo seguir (entre as imagens com \"Arrastar até ela\"):"), lp(6))
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
                textSize = 14f
                setTextColor(themeColor(R.color.vp_text))
                buttonTintList = ColorStateList.valueOf(themeColor(R.color.vp_accent))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            group.addView(rb)
            if (i == MacroSettings.targetMode) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val rb = g.findViewById<RadioButton>(checkedId)
            if (rb != null) MacroSettings.targetMode = rb.tag as Int
        }
        c4.addView(group)

        c4.addView(
            note(
                "Ponto de partida = onde o dedo pressiona para arrastar. Também dá para " +
                    "marcá-lo direto na tela: bolinha > Painel de mira > 1 Toque."
            ),
            lp(6)
        )
        c4.addView(
            positionSlider("Ponto de partida - horizontal", { MacroSettings.originXPct }) {
                MacroSettings.originXPct = it
            }
        )
        c4.addView(
            positionSlider("Ponto de partida - vertical", { MacroSettings.originYPct }) {
                MacroSettings.originYPct = it
            }
        )
        c4.addView(slider("Velocidade do arrasto", 300, 6000, MacroSettings.dragSpeed, " px/s") {
            MacroSettings.dragSpeed = it
        })
        c4.addView(slider("Pausa entre arrastos", 0, 3000, MacroSettings.cooldownMs, " ms") {
            MacroSettings.cooldownMs = it
        })

        // 5) Modo mira
        val c5 = card(5, "Modo mira (travar a mira na imagem)")
        content.addView(c5)
        swAim = switch("Modo mira: levar a mira até o centro da imagem", MacroSettings.aimMode) {
            MacroSettings.aimMode = it
        }
        c5.addView(swAim)
        c5.addView(
            note(
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
        c5.addView(
            slider("Sensibilidade da mira", 10, 600, MacroSettings.aimGainPct, "%") {
                MacroSettings.aimGainPct = it
            }
        )
        c5.addView(
            note(
                "Quanto o dedo anda para cada pixel de distância até a imagem. Se a mira passa " +
                    "do centro, baixe; se fica curta, suba. Com \"Aprender sozinho\" ligado, o app " +
                    "corrige esse valor medindo como o jogo responde."
            )
        )
        c5.addView(switch("Aprender a sensibilidade sozinho", MacroSettings.aimLearn) {
            MacroSettings.aimLearn = it
        })
        c5.addView(slider("Zona de travamento (raio)", 4, 80, MacroSettings.aimTolPx, " px") {
            MacroSettings.aimTolPx = it
        })
        c5.addView(
            slider("Pausa entre correções", 0, 1000, MacroSettings.aimCooldownMs, " ms") {
                MacroSettings.aimCooldownMs = it
            }
        )
        c5.addView(
            slider("Arrasto mínimo", 0, 80, MacroSettings.aimMinTravelPx, " px") {
                MacroSettings.aimMinTravelPx = it
            }
        )
        c5.addView(
            note(
                "Alguns jogos e navegadores ignoram arrastos muito curtos. Correções menores " +
                    "que o arrasto mínimo fazem o dedo ir além e voltar, terminando no mesmo " +
                    "lugar. Use 0 para desligar."
            )
        )
        c5.addView(switch("Inverter horizontal", MacroSettings.aimInvertX) {
            MacroSettings.aimInvertX = it
        })
        c5.addView(switch("Inverter vertical", MacroSettings.aimInvertY) {
            MacroSettings.aimInvertY = it
        })
        c5.addView(
            note("Se a mira se afasta da imagem em vez de chegar nela, ligue a inversão do eixo.")
        )
        c5.addView(
            positionSlider("Posição da mira - horizontal", { MacroSettings.aimXPm / 10 }) {
                MacroSettings.aimXPm = it * 10
            }
        )
        c5.addView(
            positionSlider("Posição da mira - vertical", { MacroSettings.aimYPm / 10 }) {
                MacroSettings.aimYPm = it * 10
            }
        )
        c5.addView(note("Mais fácil: marque a mira direto na tela, em Painel de mira > 2 Mira."))

        // 6) Bolinha flutuante
        val c6 = card(6, "Bolinha flutuante")
        content.addView(c6)
        c6.addView(
            switch("Mostrar a bolinha durante o uso (vale ao iniciar)", MacroSettings.showBubble) {
                MacroSettings.showBubble = it
            }
        )
        c6.addView(
            note(
                "Arraste a bolinha para mover. Toque nela para pausar/retomar, ligar ou " +
                    "desligar o arrasto, abrir o Painel de mira ou parar. Verde = rodando, " +
                    "amarela = pausado. Deixe-a longe do ponto de partida do dedo. Com o " +
                    "painel aberto o macro não arrasta."
            )
        )

        page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        page.addView(bottomBar())
        return page
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#4C35CC"), Color.parseColor("#271A6B"))
            ).apply { cornerRadius = dpf(14f) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) }
        }
        titles.addView(TextView(this).apply {
            text = "Vision Paint Macro"
            textSize = 20f
            setTextColor(themeColor(R.color.vp_text))
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "Reconhece imagens na tela, pinta e mira"
            textSize = 13f
            setTextColor(themeColor(R.color.vp_text_secondary))
        })
        row.addView(titles)
        return row
    }

    private fun bottomBar(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply {
            setBackgroundColor(themeColor(R.color.vp_stroke))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
        })
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(themeColor(R.color.vp_surface))
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        bar.addView(statusDot)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        tvStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(themeColor(R.color.vp_text))
            setTypeface(typeface, Typeface.BOLD)
        }
        tvStatusSub = TextView(this).apply {
            textSize = 12f
            setTextColor(themeColor(R.color.vp_text_secondary))
        }
        texts.addView(tvStatus)
        texts.addView(tvStatusSub)
        bar.addView(texts)
        btnStart = button("INICIAR", Kind.PRIMARY) { onStartStop() }
        btnStart.minimumWidth = dp(120)
        bar.addView(btnStart, LinearLayout.LayoutParams(WRAP, WRAP))
        wrap.addView(bar)
        return wrap
    }

    // ------------------------------------------------------------------ lista de imagens

    private fun rebuildRows() {
        rowsBox.removeAllViews()
        val list = TemplateStore.list()
        if (list.isEmpty()) {
            rowsBox.addView(TextView(this).apply {
                text = "Nenhuma imagem ainda. Toque em \"Adicionar imagens\"."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(themeColor(R.color.vp_text_secondary))
                setPadding(dp(12), dp(18), dp(12), dp(18))
                background = rounded(themeColor(R.color.vp_surface_variant), 14f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
            })
            return
        }
        list.forEachIndexed { i, t -> rowsBox.addView(templateRow(i, list.size, t)) }
    }

    private fun templateRow(index: Int, total: Int, t: Tpl): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(themeColor(R.color.vp_surface_variant), 14f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = rounded(themeColor(R.color.vp_surface), 10f)
            setImageBitmap(TemplateStore.thumb(t.id))
        })

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(dp(10), 0, dp(6), 0)
        }
        mid.addView(TextView(this).apply {
            text = "Imagem ${index + 1}"
            textSize = 15f
            setTextColor(themeColor(R.color.vp_text))
            setTypeface(typeface, Typeface.BOLD)
        })

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            setOnClickListener { showColorDialog(t) }
        }
        colorRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(22))
            background = rounded(t.color ?: MacroSettings.color, 6f, themeColor(R.color.vp_stroke))
        })
        colorRow.addView(TextView(this).apply {
            text = if (t.color == null) "  cor padrão (toque)" else "  cor própria (toque)"
            textSize = 13f
            setTextColor(themeColor(R.color.vp_text_secondary))
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
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(40))
            background = rounded(start, 12f, themeColor(R.color.vp_stroke))
        }
        box.addView(preview)
        val names = arrayOf("Vermelho", "Verde", "Azul")
        val tints = intArrayOf(
            Color.parseColor("#E5484D"),
            Color.parseColor("#30A46C"),
            Color.parseColor("#3E63DD")
        )
        for (i in 0..2) {
            box.addView(slider(names[i], 0, 255, rgb[i], "", tints[i]) { v ->
                rgb[i] = v
                preview.background =
                    rounded(Color.rgb(rgb[0], rgb[1], rgb[2]), 12f, themeColor(R.color.vp_stroke))
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
        val n = TemplateStore.count()
        tvTemplates.text = if (n == 1) "1 imagem de referência" else "$n imagens de referência"
        rebuildRows()
        setChip(tvOverlay, AndroidSettings.canDrawOverlays(this), "Permitida", "Pendente")
        setChip(tvAccess, MacroAccessibilityService.instance != null, "Ativa", "Desativada")
        // A bolinha também liga/desliga o arrasto: mantém a chave igual ao estado atual.
        swDrag.isChecked = MacroSettings.dragEnabled
        swAim.isChecked = MacroSettings.aimMode
        syncs.forEach { it() }

        val running = CaptureService.running
        btnStart.text = if (running) "PARAR" else "INICIAR"
        styleButton(btnStart, if (running) Kind.STOP else Kind.PRIMARY)
        tvStatus.text = if (running) "Rodando" else "Parado"
        tvStatusSub.text = if (running) {
            "Procurando as imagens na tela"
        } else if (n == 0) {
            "Adicione imagens para começar"
        } else {
            "Pronto para iniciar"
        }
        statusDot.background = oval(
            themeColor(if (running) R.color.vp_success else R.color.vp_text_secondary)
        )
    }

    // ------------------------------------------------------------------ helpers de UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun themeColor(id: Int): Int = ContextCompat.getColor(this, id)

    private fun lp(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun oval(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /** Cartão de seção: número, título e o conteúdo logo abaixo. */
    private fun card(n: Int, title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(themeColor(R.color.vp_surface), 18f, themeColor(R.color.vp_stroke))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        head.addView(TextView(this).apply {
            text = n.toString()
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.color.vp_on_accent))
            setTypeface(typeface, Typeface.BOLD)
            background = oval(themeColor(R.color.vp_accent))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) }
        })
        head.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(themeColor(R.color.vp_text))
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(head)
        return box
    }

    /** Texto de apoio (cinza, menor). */
    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(themeColor(R.color.vp_text_secondary))
        setLineSpacing(0f, 1.1f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun chip(): TextView = TextView(this).apply {
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun setChip(tv: TextView, ok: Boolean, okText: String, badText: String) {
        val tint = themeColor(if (ok) R.color.vp_success else R.color.vp_warning)
        tv.text = if (ok) "✔ $okText" else "✘ $badText"
        tv.setTextColor(tint)
        tv.background = rounded(ColorUtils.setAlphaComponent(tint, 0x26), 12f)
    }

    /** Linha de permissão: nome, estado (chip) e o botão que leva à configuração. */
    private fun permRow(title: String, chip: TextView, action: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(themeColor(R.color.vp_text))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        top.addView(chip)
        box.addView(top)
        box.addView(button(action, Kind.TONAL, onClick), lp(8))
        return box
    }

    private fun styleButton(b: Button, kind: Kind) {
        val accentText = themeColor(R.color.vp_accent_text)
        val danger = themeColor(R.color.vp_danger_fill)
        val white = themeColor(R.color.vp_on_accent)
        val (fill, fg, ripple) = when (kind) {
            Kind.PRIMARY -> Triple(themeColor(R.color.vp_accent), white, 0x33FFFFFF)
            Kind.TONAL -> Triple(
                themeColor(R.color.vp_surface_variant),
                accentText,
                ColorUtils.setAlphaComponent(accentText, 0x33)
            )
            Kind.DANGER -> Triple(
                ColorUtils.setAlphaComponent(danger, 0x1F),
                danger,
                ColorUtils.setAlphaComponent(danger, 0x33)
            )
            Kind.STOP -> Triple(danger, white, 0x33FFFFFF)
        }
        b.setTextColor(fg)
        b.background = RippleDrawable(
            ColorStateList.valueOf(ripple),
            rounded(fill, 12f),
            rounded(Color.WHITE, 12f)
        )
    }

    private fun button(label: String, kind: Kind, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = 0
            minimumHeight = dp(48)
            stateListAnimator = null
            styleButton(this, kind)
            setOnClickListener { onClick() }
        }

    private fun smallButton(label: String, enabled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(36)).apply { bottomMargin = dp(4) }
            styleButton(this, Kind.TONAL)
            setOnClickListener { onClick() }
        }

    private fun switch(label: String, start: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(this).apply {
            text = label
            textSize = 15f
            setTextColor(themeColor(R.color.vp_text))
            isChecked = start
            setPadding(0, dp(8), 0, dp(8))
            val on = themeColor(R.color.vp_accent)
            val off = themeColor(R.color.vp_text_secondary)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, off))
            trackTintList = ColorStateList(states, intArrayOf(on, ColorUtils.setAlphaComponent(off, 0x66)))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    /** Slider de posição na tela (0 a 100 %), que se atualiza ao voltar para o app. */
    private fun positionSlider(label: String, get: () -> Int, set: (Int) -> Unit): View {
        val (box, bar) = sliderPair(label, 0, 100, get(), "%", null) { set(it) }
        syncs.add { bar.progress = get().coerceIn(0, 100) }
        return box
    }

    private fun slider(
        label: String,
        lo: Int,
        hi: Int,
        start: Int,
        unit: String,
        tint: Int? = null,
        onChange: (Int) -> Unit
    ): View = sliderPair(label, lo, hi, start, unit, tint, onChange).first

    /** Nome à esquerda, valor à direita e a barra embaixo; devolve também a barra. */
    private fun sliderPair(
        label: String,
        lo: Int,
        hi: Int,
        start: Int,
        unit: String,
        tint: Int?,
        onChange: (Int) -> Unit
    ): Pair<View, SeekBar> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valueTv = TextView(this).apply {
            text = "$start$unit"
            textSize = 13f
            setTextColor(themeColor(R.color.vp_accent_text))
            setTypeface(typeface, Typeface.BOLD)
        }
        head.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(themeColor(R.color.vp_text))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        head.addView(valueTv)

        val barColor = tint ?: themeColor(R.color.vp_accent)
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = (start - lo).coerceIn(0, hi - lo)
            progressTintList = ColorStateList.valueOf(barColor)
            thumbTintList = ColorStateList.valueOf(barColor)
            progressBackgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(barColor, 0x44))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    valueTv.text = "$v$unit"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(head)
        box.addView(sb)
        return Pair(box, sb)
    }
}
