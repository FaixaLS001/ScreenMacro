package com.macro.visionpaint

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bolinha flutuante sobre a tela: arraste para mover; toque para abrir o menu
 * (pausar/retomar, ligar/desligar o arrasto, painel de mira, parar).
 *
 * O "Painel de mira" abre deitado, ao lado da bolinha, com quatro botões:
 *  1 Toque  - marca na tela onde o dedo pressiona para arrastar;
 *  2 Mira   - marca na tela onde fica a mira do jogo (o marcador fica quase invisível);
 *  3 Imagem - escolhe a(s) imagem(ns) em que a mira deve se fixar;
 *  Travar   - liga/desliga o modo mira.
 */
class FloatingControls(
    private val ctx: Context,
    private val markers: AimMarkers,
    private val isPaused: () -> Boolean,
    private val onTogglePause: () -> Unit,
    private val onToggleDrag: () -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density

    private val green = Color.parseColor("#1F9D63")
    private val amber = Color.parseColor("#E8890C")

    private val bubbleBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(green)
        setStroke(dp(2), Color.parseColor("#80FFFFFF"))
    }

    /** A bolinha mostra a mira quando está rodando e o símbolo de pausa quando está pausada. */
    private val bubble = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_bubble_running)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = bubbleBg
        alpha = 0.92f
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
    }

    // ---- menu vertical (abre ao tocar na bolinha)

    private val btnPause: TextView = menuItem()
    private val btnDrag: TextView = menuItem()
    private val btnPanel: TextView = menuItem().apply { text = "Painel de mira" }
    private val btnStop: TextView = menuItem().apply {
        text = "Parar"
        setTextColor(Color.parseColor("#FF8FA3"))
    }

    private val menu = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = panelBg()
        addView(btnPause)
        addView(btnDrag)
        addView(btnPanel)
        addView(btnStop)
    }

    // ---- painel de mira (deitado, ao lado da bolinha)

    private val btnPress: TextView = trayButton()
    private val btnAim: TextView = trayButton()
    private val btnImage: TextView = trayButton()
    private val btnLock: TextView = trayButton()
    private val btnClose: TextView = trayButton().apply { text = "✕" }

    private val tray = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        background = panelBg()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) }
        addView(btnPress)
        addView(btnAim)
        addView(btnImage)
        addView(btnLock)
        addView(btnClose)
    }

    /** Linha de miniaturas para escolher em que imagem(ns) a mira se fixa. */
    private val pickerRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private val picker = HorizontalScrollView(ctx).apply {
        visibility = View.GONE
        isHorizontalScrollBarEnabled = false
        background = panelBg()
        addView(pickerRow)
    }

    private val topRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(bubble)
        addView(tray)
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(topRow)
        addView(menu)
        addView(picker)
    }

    private var lp: WindowManager.LayoutParams? = null
    private var added = false

    @Volatile
    private var menuOpen = false

    @Volatile
    private var trayOpen = false

    private var pickerOpen = false
    private var posSaved = false
    private var savedX = 0
    private var savedY = 0

    private val expanded: Boolean get() = menuOpen || trayOpen

    /** true enquanto o menu ou o painel de mira estão abertos (o macro não arrasta nesse tempo). */
    val isOpen: Boolean get() = menuOpen || trayOpen

    init {
        btnPause.setOnClickListener {
            onTogglePause()
            refresh()
            closeAll()
        }
        btnDrag.setOnClickListener {
            onToggleDrag()
            refresh()
            closeAll()
        }
        btnPanel.setOnClickListener { openTray() }
        btnStop.setOnClickListener { onStop() }

        btnPress.setOnClickListener { togglePlacing(AimMarkers.PRESS) }
        btnAim.setOnClickListener { togglePlacing(AimMarkers.AIM) }
        btnImage.setOnClickListener {
            markers.stopPlacing()
            pickerOpen = !pickerOpen
            if (pickerOpen) rebuildPicker()
            refresh()
            applyLayout()
        }
        btnLock.setOnClickListener {
            if (MacroSettings.aimMode && MacroSettings.dragEnabled) {
                MacroSettings.dragEnabled = false
                refresh()
            } else {
                MacroSettings.aimMode = true
                MacroSettings.dragEnabled = true
                // Fecha o painel para o macro poder agir (ele não arrasta com o painel aberto).
                closeAll()
            }
        }
        btnClose.setOnClickListener { closeAll() }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val l = lp ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = l.x
                        startY = l.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > 8 * d || abs(dy) > 8 * d)) moved = true
                        if (moved && !expanded) {
                            l.x = (startX + dx).toInt()
                            l.y = (startY + dy).toInt()
                            wm.updateViewLayout(root, l)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            if (!expanded) savePosition(l)
                        } else if (trayOpen) {
                            closeAll()
                        } else {
                            toggleMenu()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        setCornerRadius(16f * d)
        setColor(Color.parseColor("#F2171433"))
        setStroke(dp(1), Color.parseColor("#4D6C5CFF"))
    }

    /** Fundo transparente com efeito de toque (ondulação) de cantos arredondados. */
    private fun rippleBg(): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
        null,
        GradientDrawable().apply {
            setCornerRadius(10f * d)
            setColor(Color.WHITE)
        }
    )

    private fun menuItem(): TextView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = rippleBg()
    }

    private fun trayButton(): TextView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(9), dp(10), dp(9), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
    }

    private fun style(btn: TextView, active: Boolean, activeColor: Int = green) {
        btn.background = GradientDrawable().apply {
            setCornerRadius(10f * d)
            setColor(if (active) activeColor else Color.parseColor("#2EFFFFFF"))
        }
    }

    fun show() {
        if (added) return
        val size = ScreenInfo.realSize(ctx)
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        l.gravity = Gravity.TOP or Gravity.START

        val bs = dp(46)
        val sx = MacroSettings.bubbleX
        val sy = MacroSettings.bubbleY
        l.x = (if (sx >= 0) sx else dp(8)).coerceIn(0, max(0, size.first - bs))
        l.y = (if (sy >= 0) sy else size.second / 4).coerceIn(0, max(0, size.second - bs))

        lp = l
        wm.addView(root, l)
        added = true
        refresh()
    }

    fun hide() {
        if (!added) return
        val l = lp
        if (l != null && !expanded) savePosition(l)
        try {
            wm.removeView(root)
        } catch (e: Exception) {
        }
        added = false
        menuOpen = false
        trayOpen = false
        pickerOpen = false
        posSaved = false
    }

    /** Atualiza cores e textos conforme o estado atual (pausado, arrasto ligado, painel de mira). */
    fun refresh() {
        val paused = isPaused()
        bubbleBg.setColor(if (paused) amber else green)
        bubble.setImageResource(if (paused) R.drawable.ic_bubble_paused else R.drawable.ic_bubble_running)
        btnPause.text = if (paused) "Retomar" else "Pausar"
        btnDrag.text = if (MacroSettings.dragEnabled) "Arrasto: ligado" else "Arrasto: desligado"

        val placing = markers.placing
        btnPress.text = if (placing == AimMarkers.PRESS) "OK" else "1 Toque"
        style(btnPress, placing == AimMarkers.PRESS, amber)
        btnAim.text = if (placing == AimMarkers.AIM) "OK" else "2 Mira"
        style(btnAim, placing == AimMarkers.AIM, amber)
        btnImage.text = "3 Imagem"
        style(btnImage, pickerOpen)
        val lockOn = MacroSettings.aimMode && MacroSettings.dragEnabled
        btnLock.text = if (lockOn) "Travar ✔" else "Travar"
        style(btnLock, lockOn)
    }

    private fun savePosition(l: WindowManager.LayoutParams) {
        MacroSettings.bubbleX = l.x
        MacroSettings.bubbleY = l.y
    }

    // ---------------------------------------------------------------- abrir / fechar

    private fun toggleMenu() {
        menuOpen = !menuOpen
        if (menuOpen) {
            trayOpen = false
            pickerOpen = false
            markers.stopPlacing()
            refresh()
        }
        applyLayout()
    }

    private fun openTray() {
        menuOpen = false
        trayOpen = true
        refresh()
        applyLayout()
    }

    /** Fecha menu, painel e lista de imagens, e termina qualquer posicionamento. */
    private fun closeAll() {
        menuOpen = false
        trayOpen = false
        pickerOpen = false
        markers.stopPlacing()
        refresh()
        applyLayout()
    }

    private fun togglePlacing(which: Int) {
        if (markers.placing == which) markers.stopPlacing() else markers.startPlacing(which)
        pickerOpen = false
        refresh()
        applyLayout()
    }

    /**
     * Mostra/esconde as partes conforme o estado e mantém tudo dentro da tela.
     * Ao fechar tudo, a bolinha volta para onde estava antes de abrir.
     */
    private fun applyLayout() {
        val l = lp ?: return
        if (!added) return

        val want = menuOpen || trayOpen
        if (want && !posSaved) {
            savedX = l.x
            savedY = l.y
            posSaved = true
        }
        val showPicker = trayOpen && pickerOpen
        menu.visibility = if (menuOpen) View.VISIBLE else View.GONE
        tray.visibility = if (trayOpen) View.VISIBLE else View.GONE
        picker.visibility = if (showPicker) View.VISIBLE else View.GONE

        val size = ScreenInfo.realSize(ctx)
        if (showPicker) {
            picker.layoutParams = LinearLayout.LayoutParams(
                min(dp(330), size.first - dp(12)), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        if (want) {
            root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            l.x = l.x.coerceIn(0, max(0, size.first - root.measuredWidth))
            l.y = l.y.coerceIn(0, max(0, size.second - root.measuredHeight))
        } else if (posSaved) {
            l.x = savedX
            l.y = savedY
            posSaved = false
        }
        wm.updateViewLayout(root, l)
    }

    // ---------------------------------------------------------------- escolha da imagem

    /** Miniaturas das imagens; tocar liga/desliga "mirar nesta imagem". */
    private fun rebuildPicker() {
        pickerRow.removeAllViews()
        val label = TextView(ctx).apply {
            text = "Mirar em:"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(4), 0, dp(8), 0)
        }
        pickerRow.addView(label)

        val list = TemplateStore.list()
        if (list.isEmpty()) {
            label.text = "Sem imagens: adicione no app."
            return
        }
        for (t in list) {
            val selected = t.drag
            val img = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setImageBitmap(TemplateStore.thumb(t.id, 112))
                alpha = if (selected) 1f else 0.5f
                background = GradientDrawable().apply {
                    setCornerRadius(6f * d)
                    setColor(Color.parseColor(if (selected) "#44FFFFFF" else "#22000000"))
                    if (selected) {
                        setStroke(dp(3), green)
                    } else {
                        setStroke(dp(1), Color.parseColor("#55FFFFFF"))
                    }
                }
                setOnClickListener {
                    val now = TemplateStore.info(t.id)?.drag ?: false
                    TemplateStore.setDrag(t.id, !now)
                    rebuildPicker()
                }
            }
            pickerRow.addView(img)
        }
    }
}
