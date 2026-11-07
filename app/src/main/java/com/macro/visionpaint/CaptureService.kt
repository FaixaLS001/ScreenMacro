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

/**
 * Serviço principal: captura a tela (compartilhamento de tela), procura as imagens,
 * pinta os alvos no overlay e, se ligado, manda o arrasto para o serviço de acessibilidade.
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
    private var displayListener: DisplayManager.DisplayListener? = null

    private var screenW = 0
    private var screenH = 0
    private var scale = 0.5f
    private var lastProcess = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MacroSettings.init(this)
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
            .setSmallIcon(android.R.drawable.ic_menu_view)
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

        val files = TemplateStore.list(this)
        scale = MacroSettings.scalePct / 100f
        val det = Detector(scale)
        det.loadTemplates(files)
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

        addOverlay()
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
        val ox = screenW * MacroSettings.originXPct / 100f
        val oy = screenH * MacroSettings.originYPct / 100f
        val nearest = targets.minByOrNull { hypot(it.cx - ox, it.cy - oy) }

        val color = MacroSettings.color
        val alpha = MacroSettings.paintAlphaPct
        val showRoute = MacroSettings.showRoute
        main.post {
            overlay?.update(targets, nearest, ox, oy, showRoute, color, alpha)
        }

        if (MacroSettings.dragEnabled && nearest != null) {
            maybeDrag(ox, oy, nearest)
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
