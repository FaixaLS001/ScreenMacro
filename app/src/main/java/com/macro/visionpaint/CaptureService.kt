package com.macro.visionpaint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Serviço principal: captura a tela (compartilhamento de tela), procura as imagens,
 * pinta os alvos no overlay e, se ligado, manda o arrasto para o serviço de acessibilidade.
 * No modo mira, em vez de arrastar até o alvo, leva a mira do jogo até o centro da imagem
 * e a mantém fixa ali.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.macro.visionpaint.START"
        const val ACTION_STOP = "com.macro.visionpaint.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "macro_capture"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false

        /** true enquanto o macro está pausado pela bolinha flutuante. */
        @Volatile
        var paused = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var detector: Detector? = null

    private var wm: WindowManager? = null
    private var overlay: OverlayView? = null

    // Lidos também pela thread da análise.
    @Volatile
    private var controls: FloatingControls? = null

    @Volatile
    private var markers: AimMarkers? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    // Modo mira (só a thread da análise mexe nestes).
    private val aim = AimController()
    private var stickyTpl: String? = null
    private var stickyX = 0f
    private var stickyY = 0f
    private var aimLastSeen = 0L
    private var lastHintToast = 0L

    private var screenW = 0
    private var screenH = 0
    private var scale = 0.5f
    private var lastProcess = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MacroSettings.init(this)
        TemplateStore.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
            }
            ACTION_START -> {
                startForegroundCompat()
                if (running) return START_NOT_STICKY

                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null || !startCapture(code, data)) {
                    stopAll()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Captura de tela", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macro)
            .setColor(0xFF7C5CFF.toInt())
            .setContentTitle("Vision Paint Macro")
            .setContentText("Analisando a tela...")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, "Parar", stopPi)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun startCapture(code: Int, data: Intent): Boolean {
        if (!OpenCVLoader.initLocal()) {
            toast("Não foi possível iniciar o OpenCV.")
            return false
        }

        val items = TemplateStore.list().map { it.id to TemplateStore.file(it.id) }
        scale = MacroSettings.scalePct / 100f
        val det = Detector(scale)
        det.loadTemplates(items)
        if (det.templateCount() == 0) {
            toast("Adicione ao menos uma imagem válida primeiro.")
            det.release()
            return false
        }
        detector = det

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        } ?: return false
        projection = proj

        thread = HandlerThread("vision").also { it.start() }
        handler = Handler(thread!!.looper)

        // Obrigatório no Android 14+: registrar o callback antes de criar o display virtual.
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopAll() }
            }
        }
        projectionCallback = cb
        proj.registerCallback(cb, main)

        val size = ScreenInfo.realSize(this)
        screenW = size.first
        screenH = size.second
        val capW = max(16, (screenW * scale).toInt())
        val capH = max(16, (screenH * scale).toInt())

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        reader = r

        virtualDisplay = proj.createVirtualDisplay(
            "vision", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )

        paused = false
        addOverlay()
        addMarkers()
        addControls()
        watchRotation()
        running = true
        return true
    }

    // ---------------------------------------------------------------- overlay

    private fun addOverlay() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = OverlayView(this)
        manager.addView(v, lp)
        overlay = v
    }

    // ---------------------------------------------------------------- marcadores da mira

    /** Camada com os marcadores "onde o dedo pressiona" e "onde fica a mira" (quase invisíveis). */
    private fun addMarkers() {
        val m = AimMarkers(this)
        m.show()
        markers = m
    }

    // ---------------------------------------------------------------- botão flutuante

    private fun addControls() {
        if (!MacroSettings.showBubble) return
        val m = markers ?: return
        val c = FloatingControls(
            this,
            markers = m,
            isPaused = { paused },
            onTogglePause = { togglePause() },
            onToggleDrag = { MacroSettings.dragEnabled = !MacroSettings.dragEnabled },
            onStop = { main.post { stopAll() } }
        )
        c.show()
        controls = c
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            // Some com a pintura e a rota na hora.
            overlay?.update(emptyList(), null, 0f, 0f, false, MacroSettings.paintAlphaPct)
            markers?.setLocked(false)
        }
    }

    // ---------------------------------------------------------------- rotação

    private fun watchRotation() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                // Roda na mesma thread dos quadros para não misturar com a análise.
                handler?.post { reconfigure() }
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, main)
    }

    private fun reconfigure() {
        if (stopping) return
        val size = ScreenInfo.realSize(this)
        if (size.first == screenW && size.second == screenH) return
        screenW = size.first
        screenH = size.second
        val capW = max(16, (screenW * scale).toInt())
        val capH = max(16, (screenH * scale).toInt())
        try {
            val old = reader
            old?.setOnImageAvailableListener(null, null)
            val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener({ onFrame(it) }, handler)
            virtualDisplay?.resize(capW, capH, resources.displayMetrics.densityDpi)
            virtualDisplay?.surface = r.surface
            reader = r
            old?.close()
        } catch (e: Exception) {
            // Se falhar, continua com o que estava funcionando.
        }
    }

    // ---------------------------------------------------------------- análise

    private fun onFrame(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastProcess < 60) return
            lastProcess = now
            if (paused) return

            val det = detector ?: return
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width

            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)

            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()

            val cropped = rgba.submat(0, image.height, 0, image.width)
            val rgb = Mat()
            Imgproc.cvtColor(cropped, rgb, Imgproc.COLOR_RGBA2RGB)
            cropped.release()
            rgba.release()

            val targets = det.detect(rgb, MacroSettings.threshold)
            rgb.release()
            publish(targets)
        } catch (e: Throwable) {
            // Quadro inválido (ex.: durante a rotação): ignora e espera o próximo.
        } finally {
            image.close()
        }
    }

    private fun publish(targets: List<Target>) {
        val aimMode = MacroSettings.aimMode

        // Ponto onde o dedo pressiona (partida do arrasto) e posição da mira do jogo.
        val ox = screenW * MacroSettings.originXPm / 1000f
        val oy = screenH * MacroSettings.originYPm / 1000f
        val mx = screenW * MacroSettings.aimXPm / 1000f
        val my = screenH * MacroSettings.aimYPm / 1000f

        // Cada alvo é pintado na cor da sua imagem (ou na cor padrão, se não tiver uma).
        val defaultColor = MacroSettings.color
        val painted = targets.map { t ->
            Painted(t, TemplateStore.info(t.tplId)?.color ?: defaultColor)
        }

        // Só as imagens marcadas com "Arrastar até ela" (ou escolhidas em "3 Imagem" no painel
        // de mira) entram na escolha do alvo.
        val candidates = painted.filter { TemplateStore.info(it.target.tplId)?.drag == true }
        val chosen = if (aimMode) chooseAim(candidates, mx, my) else choose(candidates, ox, oy)

        val alpha = MacroSettings.paintAlphaPct
        val showRoute = MacroSettings.showRoute

        var locked = false
        if (aimMode) {
            locked = handleAim(chosen?.target, ox, oy, mx, my)
        } else if (MacroSettings.dragEnabled && chosen != null) {
            maybeDrag(ox, oy, chosen.target)
        }

        // No modo mira a rota sai da mira (e não do ponto de pressão) até o centro da imagem.
        val rx = if (aimMode) mx else ox
        val ry = if (aimMode) my else oy
        main.post {
            if (!paused) {
                overlay?.update(painted, chosen, rx, ry, showRoute, alpha)
                markers?.setLocked(aimMode && locked)
            }
        }
    }

    // ---------------------------------------------------------------- modo mira

    /**
     * Escolhe a imagem em que a mira vai se fixar. Enquanto a imagem que já vinha sendo seguida
     * continua visível (por perto de onde estava), segue ela e não troca por outra.
     * Se não, vale a regra da tela principal, medida a partir da mira.
     */
    private fun chooseAim(c: List<Painted>, mx: Float, my: Float): Painted? {
        if (c.isEmpty()) return null

        val st = stickyTpl
        if (st != null) {
            val radius = 0.3f * min(screenW, screenH)
            val same = c
                .filter {
                    it.target.tplId == st &&
                        hypot(it.target.cx - stickyX, it.target.cy - stickyY) <= radius
                }
                .minByOrNull { hypot(it.target.cx - stickyX, it.target.cy - stickyY) }
            if (same != null) {
                stickyX = same.target.cx
                stickyY = same.target.cy
                return same
            }
        }

        val pick = choose(c, mx, my)
        if (pick != null) {
            stickyTpl = pick.target.tplId
            stickyX = pick.target.cx
            stickyY = pick.target.cy
        }
        return pick
    }

    /**
     * Um passo do modo mira: mede o erro entre a mira e o centro da imagem e, se estiver fora da
     * zona de travamento, manda um arrasto de dedo a partir do ponto de pressão para corrigir.
     * Devolve true quando a mira está fixa no centro.
     */
    private fun handleAim(t: Target?, ox: Float, oy: Float, mx: Float, my: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        if (t == null) {
            // A detecção pode falhar em um quadro: só solta a mira se a imagem sumir de vez.
            if (now - aimLastSeen > 300L) {
                aim.lost()
                stickyTpl = null
            }
            return aim.locked
        }
        aimLastSeen = now

        aim.tolerance = MacroSettings.aimTolPx.toFloat()
        aim.setGain(MacroSettings.aimGainPct / 100f)
        aim.learn = MacroSettings.aimLearn
        aim.invertX = MacroSettings.aimInvertX
        aim.invertY = MacroSettings.aimInvertY

        // Não arrasta se: arrasto desligado, serviço de acessibilidade fora do ar, outro arrasto
        // em andamento, pausa entre correções, ou painel/posicionamento abertos (o dedo
        // automático poderia tocar no painel).
        val svc = MacroAccessibilityService.instance
        val canAct = MacroSettings.dragEnabled &&
            svc != null && !svc.busy &&
            now - svc.lastEnd >= MacroSettings.aimCooldownMs &&
            controls?.isOpen != true &&
            (markers?.placing ?: AimMarkers.NONE) == AimMarkers.NONE

        val w = screenW.toFloat()
        val h = screenH.toFloat()
        val maxStep = 0.5f * min(w, h)

        val step = aim.update(t.cx - mx, t.cy - my, t.tplId, now, canAct, maxStep)

        if (aim.takeHint() && now - lastHintToast > 15000L) {
            lastHintToast = now
            toast("A mira parece se afastar da imagem. Tente \"Inverter horizontal/vertical\" nos ajustes da mira.")
        }

        if (step.swipe && svc != null) {
            val margin = 8f
            val fitted = AimMath.fit(ox, oy, step.dx, step.dy, w, h, margin)
            val planned = hypot(step.dx, step.dy)
            val sent = hypot(fitted[0], fitted[1])
            // Se o arrasto foi encurtado para caber na tela, o controle precisa saber.
            if (planned > 0f && sent < planned) aim.shrinkPending(sent / planned)
            // Ponto de pressão colado na borda e arrasto apontando para fora: não há para onde
            // ir. Não manda um toque parado (que se repetiria a cada pausa).
            if (sent < 0.5f) return aim.locked

            val pts = AimMath.path(
                ox, oy, fitted[0], fitted[1],
                MacroSettings.aimMinTravelPx.toFloat(), w, h, margin
            )
            val durationMs = (AimMath.length(pts) / MacroSettings.dragSpeed * 1000f)
                .toLong().coerceIn(40L, 2000L)
            svc.swipe(pts, durationMs)
        }
        return aim.locked
    }

    /** Escolhe qual alvo o dedo vai seguir, conforme o modo escolhido na tela principal. */
    private fun choose(c: List<Painted>, ox: Float, oy: Float): Painted? {
        if (c.isEmpty()) return null
        return when (MacroSettings.targetMode) {
            1 -> c.maxByOrNull { it.target.score }
            2 -> c.minWithOrNull(
                compareBy<Painted>(
                    { TemplateStore.orderOf(it.target.tplId) },
                    { hypot(it.target.cx - ox, it.target.cy - oy) }
                )
            )
            else -> c.minByOrNull { hypot(it.target.cx - ox, it.target.cy - oy) }
        }
    }

    /** Calcula distância e duração do arrasto e manda o serviço de acessibilidade executar. */
    private fun maybeDrag(ox: Float, oy: Float, t: Target) {
        val svc = MacroAccessibilityService.instance ?: return
        if (svc.busy) return
        if (SystemClock.uptimeMillis() - svc.lastEnd < MacroSettings.cooldownMs) return

        val dist = hypot(t.cx - ox, t.cy - oy)
        if (dist < 12f) return

        val durationMs = (dist / MacroSettings.dragSpeed * 1000f).toLong().coerceIn(40L, 2000L)
        svc.drag(ox, oy, t.cx, t.cy, durationMs)
    }

    // ---------------------------------------------------------------- encerrar

    private fun stopAll() {
        if (stopping) return
        stopping = true
        running = false
        paused = false

        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayListener?.let { dm.unregisterDisplayListener(it) }
        } catch (e: Exception) {
        }
        displayListener = null

        reader?.setOnImageAvailableListener(null, null)
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null

        thread?.quitSafely()
        try {
            thread?.join(500)
        } catch (e: InterruptedException) {
        }
        thread = null
        handler = null

        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null

        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null

        controls?.hide()
        controls = null

        markers?.hide()
        markers = null

        overlay?.let {
            try {
                wm?.removeView(it)
            } catch (e: Exception) {
            }
        }
        overlay = null

        detector?.release()
        detector = null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
