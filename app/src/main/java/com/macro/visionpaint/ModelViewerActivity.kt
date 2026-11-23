package com.macro.visionpaint

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mostra o modelo 3D (desenhado por software, sem OpenGL) para o usuário girar e escolher onde o app
 * deve mirar: toque no modelo para pôr o ponto de mira, ou use os atalhos (cabeça, peito, centro, pés).
 * O raio é o tamanho da área de mira. Também troca o eixo "para cima" quando o arquivo vem deitado.
 */
class ModelViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"

        /** Lado (px) da imagem desenhada e pixels por unidade do modelo (como no [BankBuilder]: metade do lado). */
        private const val SIZE = 420
        private const val PX = 0.5f * SIZE

        /** Quanto o toque entra no modelo (fração do raio), para o ponto ficar dentro do corpo e não na pele. */
        private const val PUSH_IN = 0.8f
    }

    private enum class Kind { PRIMARY, TONAL }

    /** Um quadro pronto: o desenho (sem marcador), a profundidade de cada pixel e a câmera usada. */
    private class Snap(val bmp: Bitmap, val depth: FloatArray, val cam: CamInfo)

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    @Volatile
    private var dirty = false

    @Volatile
    private var alive = true

    @Volatile
    private var model: Model3D? = null

    @Volatile
    private var yaw = 25f

    @Volatile
    private var pitch = 15f

    private var loadGen = 0
    private var saving = false
    private var snap: Snap? = null

    private lateinit var modelId: String
    private lateinit var info: ModelInfo
    private var up = UpAxis.Y
    private var fx = 0.5f
    private var fy = 0.5f
    private var fz = 0.5f
    private var radius = ModelStore.DEFAULT_RADIUS

    private lateinit var preview: Preview
    private lateinit var tvStatus: TextView
    private lateinit var tvAim: TextView
    private lateinit var btnSave: Button
    private lateinit var radiusBar: SeekBar
    private val upButtons = ArrayList<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModelStore.init(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val i0 = if (id != null) ModelStore.info(id) else null
        if (id == null || i0 == null) {
            finish()
            return
        }
        modelId = id
        info = i0
        up = i0.up
        fx = i0.fx
        fy = i0.fy
        fz = i0.fz
        radius = i0.radius

        setContentView(buildUi())
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.isAppearanceLightStatusBars = !night
        controller?.isAppearanceLightNavigationBars = !night

        updateAimText()
        loadModel()
    }

    override fun onDestroy() {
        alive = false
        exec.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ carregar e desenhar

    private fun loadModel() {
        val gen = ++loadGen
        model = null
        tvStatus.text = "Carregando o modelo…"
        tvStatus.visibility = View.VISIBLE
        btnSave.isEnabled = false
        btnSave.alpha = 0.5f
        val wanted = info.copy(up = up)
        try {
            exec.execute {
                try {
                    val m = ModelStore.loadModel(wanted)
                    runOnUiThread {
                        if (!alive || gen != loadGen) return@runOnUiThread
                        model = m
                        tvStatus.visibility = View.GONE
                        btnSave.isEnabled = true
                        btnSave.alpha = 1f
                        requestRender()
                    }
                } catch (e: ModelFormatException) {
                    fail(gen, e.message ?: "Não consegui abrir o modelo.")
                } catch (e: Throwable) {
                    fail(gen, "Não consegui abrir o modelo (${e.javaClass.simpleName}).")
                }
            }
        } catch (e: Exception) {
            // Tela já fechada.
        }
    }

    private fun fail(gen: Int, msg: String) {
        runOnUiThread {
            if (!alive || gen != loadGen) return@runOnUiThread
            tvStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Pede um desenho novo (só o mais recente conta: se vários pedidos chegam, os do meio são pulados). */
    private fun requestRender() {
        dirty = true
        if (model == null) return
        kick()
    }

    private fun kick() {
        if (busy.compareAndSet(false, true)) {
            try {
                exec.execute { renderLoop() }
            } catch (e: Exception) {
                busy.set(false)
            }
        }
    }

    private fun renderLoop() {
        try {
            val r = Render(SIZE, SIZE)
            while (dirty && alive) {
                dirty = false
                val m = model ?: break
                val cam = Rasterizer.camera(yaw, pitch, PX, SIZE / 2f, SIZE / 2f)
                Rasterizer.render(m, cam, r)
                val bmp = Bitmap.createBitmap(r.argb, SIZE, SIZE, Bitmap.Config.ARGB_8888)
                val s = Snap(bmp, r.depth.copyOf(), cam)
                runOnUiThread {
                    if (!alive) return@runOnUiThread
                    snap = s
                    preview.bitmap = bmp
                    updateMarker()
                }
            }
        } catch (e: Throwable) {
            // Sem memória ou modelo estranho: fica com o último desenho.
        } finally {
            busy.set(false)
            if (dirty && alive) kick()
        }
    }

    // ------------------------------------------------------------------ escolha da mira

    /** Posiciona o marcador (anel) na imagem a partir do ponto 3D escolhido. */
    private fun updateMarker() {
        val m = model ?: return
        val s = snap ?: return
        val p = m.fromFractions(fx, fy, fz)
        val sc = Rasterizer.project(s.cam, p)
        val z = sc[2]
        if (z <= 0.05f) {
            preview.setMarker(false, 0f, 0f, 0f, false)
            return
        }
        val ix = sc[0].toInt()
        val iy = sc[1].toInt()
        var visible = false
        if (ix in 0 until SIZE && iy in 0 until SIZE) {
            visible = s.depth[iy * SIZE + ix] >= z - (radius + 0.04f)
        }
        preview.setMarker(true, sc[0] * preview.scaleToView(), sc[1] * preview.scaleToView(),
            radius * s.cam.f / z * preview.scaleToView(), visible)
    }

    private fun updateAimText() {
        tvAim.text = "Mira a ${(fy * 100).roundToInt()}% da altura  ·  raio ${(radius * 100).roundToInt()}% do modelo"
    }

    private fun onTap(vx: Float, vy: Float) {
        val m = model ?: return
        val s = snap ?: return
        val k = SIZE.toFloat() / max(1, preview.width)
        val rx = (vx * k).toInt()
        val ry = (vy * k).toInt()
        var bx = -1
        var by = -1
        var bd = Int.MAX_VALUE
        // Se o toque pegou um pouco fora do contorno, usa o pixel do modelo mais perto.
        for (yy in max(0, ry - 8)..min(SIZE - 1, ry + 8)) {
            for (xx in max(0, rx - 8)..min(SIZE - 1, rx + 8)) {
                if (s.depth[yy * SIZE + xx].isInfinite()) continue
                val d = (xx - rx) * (xx - rx) + (yy - ry) * (yy - ry)
                if (d < bd) {
                    bd = d
                    bx = xx
                    by = yy
                }
            }
        }
        if (bx < 0) {
            Toast.makeText(this, "Toque em cima do modelo.", Toast.LENGTH_SHORT).show()
            return
        }
        // O toque acha a superfície; o ponto de mira fica um pouco para dentro, no corpo.
        val z = s.depth[by * SIZE + bx] + PUSH_IN * radius
        val p = Rasterizer.unproject(s.cam, bx + 0.5f, by + 0.5f, z)
        val f = m.toFractions(p)
        fx = f[0]
        fy = f[1]
        fz = f[2]
        updateAimText()
        updateMarker()
    }

    private fun preset(level: Float) {
        val m = model ?: return
        val f = m.toFractions(m.levelPoint(level))
        fx = f[0]
        fy = f[1]
        fz = f[2]
        updateAimText()
        updateMarker()
    }

    private fun setUpAxis(value: Int) {
        if (value == up) return
        up = value
        // Com outro "para cima" o modelo muda de posição: a mira volta para o centro.
        fx = 0.5f
        fy = 0.5f
        fz = 0.5f
        styleUpButtons()
        updateAimText()
        loadModel()
    }

    private fun styleUpButtons() {
        for ((i, b) in upButtons.withIndex()) style(b, if (i == up) Kind.PRIMARY else Kind.TONAL)
    }

    private fun save() {
        if (saving || model == null) return
        saving = true
        btnSave.isEnabled = false
        btnSave.alpha = 0.5f
        btnSave.text = "Preparando…"

        ModelStore.setUp(modelId, up)
        ModelStore.setAim(modelId, fx, fy, fz, radius)
        val s = snap
        if (s != null) {
            try {
                ModelStore.saveThumb(modelId, Bitmap.createScaledBitmap(s.bmp, 168, 168, true))
            } catch (e: Throwable) {
                // Sem miniatura: a lista mostra um espaço vazio.
            }
        }
        val saved = ModelStore.info(modelId)
        try {
            exec.execute {
                // Gera as vistas agora (leva alguns segundos em modelos pesados) para o início do macro ser rápido.
                var err: String? = null
                try {
                    if (saved != null) ModelStore.loadBank(saved)
                } catch (e: ModelFormatException) {
                    err = e.message
                } catch (e: Throwable) {
                    err = "Não consegui preparar o modelo (${e.javaClass.simpleName})."
                }
                runOnUiThread {
                    if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        } catch (e: Exception) {
            setResult(RESULT_OK)
            finish()
        }
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.vp_bg))
        }

        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
            addView(TextView(context).apply {
                text = info.name
                textSize = 18f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(R.color.vp_text))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "Arraste para girar. Toque no modelo para escolher onde mirar."
                textSize = 13f
                setTextColor(color(R.color.vp_text_secondary))
            })
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }

        preview = Preview()
        content.addView(preview, LinearLayout.LayoutParams(MATCH, WRAP))

        tvStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.vp_warning))
            setPadding(0, dp(6), 0, dp(2))
        }
        content.addView(tvStatus)

        tvAim = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.vp_accent_text))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        content.addView(tvAim)

        content.addView(label("Atalhos para o ponto de mira"))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = arrayOf("Cabeça", "Peito", "Centro", "Pés")
        val levels = floatArrayOf(0.92f, 0.72f, 0.5f, 0.04f)
        for (i in names.indices) {
            val b = button(names[i], Kind.TONAL) { preset(levels[i]) }
            presets.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
        content.addView(presets)

        content.addView(label("Tamanho da área de mira (raio)"))
        radiusBar = SeekBar(this).apply {
            max = 29
            progress = ((radius * 100f).roundToInt() - 1).coerceIn(0, 29)
            val c = color(R.color.vp_accent)
            progressTintList = ColorStateList.valueOf(c)
            thumbTintList = ColorStateList.valueOf(c)
            progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(c, 0x44))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    radius = ((p + 1) / 100f).coerceIn(ModelStore.MIN_RADIUS, ModelStore.MAX_RADIUS)
                    updateAimText()
                    updateMarker()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        content.addView(radiusBar)
        content.addView(note(
            "O anel mostra a área que conta como \"na mira\" (em % do maior lado do modelo). " +
                "Linha tracejada = o ponto está atrás de outra parte nesta vista."
        ))

        content.addView(label("Qual eixo do arquivo é \"para cima\""))
        val ups = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in UpAxis.names.indices) {
            val b = button(UpAxis.names[i], Kind.TONAL) { setUpAxis(i) }
            b.textSize = 13f
            upButtons.add(b)
            ups.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
        content.addView(ups)
        styleUpButtons()
        content.addView(note(
            "Se o modelo aparece deitado ou de cabeça para baixo, troque. Arquivos .stl e de " +
                "Blender costumam usar Z; a maioria dos .glb usa Y. Ao trocar, a mira volta ao centro."
        ))

        page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply {
            setBackgroundColor(color(R.color.vp_stroke))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
        })
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(color(R.color.vp_surface))
        }
        bar.addView(button("Cancelar", Kind.TONAL) { finish() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        btnSave = button("Salvar", Kind.PRIMARY) { save() }
        bar.addView(btnSave, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
        wrap.addView(bar)
        page.addView(wrap)
        return page
    }

    /** A imagem do modelo com o anel da mira por cima; recebe o arrastar (girar) e o toque (escolher). */
    private inner class Preview : View(this@ModelViewerActivity) {
        var bitmap: Bitmap? = null
            set(v) {
                field = v
                invalidate()
            }

        private var markerOn = false
        private var mx = 0f
        private var my = 0f
        private var mr = 0f
        private var mVisible = true

        private val dst = RectF()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(2.5f)
            color = Color.parseColor("#FF3B5C")
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FF3B5C")
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#33FF3B5C")
        }
        private val dash = DashPathEffect(floatArrayOf(dpf(8f), dpf(6f)), 0f)
        private val slop = ViewConfiguration.get(this@ModelViewerActivity).scaledTouchSlop.toFloat()

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false

        init {
            background = rounded(color(R.color.vp_surface_variant), 18f, color(R.color.vp_stroke))
        }

        /** Quantos pixels da tela por pixel da imagem desenhada. */
        fun scaleToView(): Float = if (width > 0) width.toFloat() / SIZE else 1f

        /** [x], [y], [r] já em pixels da tela. */
        fun setMarker(on: Boolean, x: Float, y: Float, r: Float, visible: Boolean) {
            markerOn = on
            mx = x
            my = y
            mr = r
            mVisible = visible
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val s = MeasureSpec.getSize(widthSpec)
            setMeasuredDimension(s, s)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            updateMarker()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = bitmap
            if (b != null) {
                dst.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(b, null, dst, bmpPaint)
            }
            if (markerOn) {
                val r = max(mr, dpf(6f))
                ring.alpha = if (mVisible) 255 else 140
                ring.pathEffect = if (mVisible) null else dash
                canvas.drawCircle(mx, my, r, halo)
                canvas.drawCircle(mx, my, r, ring)
                dot.alpha = if (mVisible) 255 else 140
                canvas.drawCircle(mx, my, dpf(3.5f), dot)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Dentro da rolagem: o arrastar aqui gira o modelo, não rola a tela.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.x
                    downY = e.y
                    lastX = e.x
                    lastY = e.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                    if (moved) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        yaw = (yaw - dx * 0.4f) % 360f
                        pitch = (pitch + dy * 0.3f).coerceIn(-60f, 80f)
                        requestRender()
                    }
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap(e.x, e.y)
                        performClick()
                    }
                }
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }

    // ------------------------------------------------------------------ helpers de UI

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(color(R.color.vp_text))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(color(R.color.vp_text_secondary))
        setLineSpacing(0f, 1.1f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun style(b: Button, kind: Kind) {
        val accentText = color(R.color.vp_accent_text)
        val white = color(R.color.vp_on_accent)
        val fill: Int
        val fg: Int
        val ripple: Int
        if (kind == Kind.PRIMARY) {
            fill = color(R.color.vp_accent)
            fg = white
            ripple = 0x33FFFFFF
        } else {
            fill = color(R.color.vp_surface_variant)
            fg = accentText
            ripple = ColorUtils.setAlphaComponent(accentText, 0x33)
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
            minimumHeight = dp(46)
            minWidth = 0
            minimumWidth = 0
            stateListAnimator = null
            style(this, kind)
            setOnClickListener { onClick() }
        }
}
