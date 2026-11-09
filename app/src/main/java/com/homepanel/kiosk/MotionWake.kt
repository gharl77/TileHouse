package com.homepanel.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size

/**
 * Réveil par mouvement : la caméra avant de la tablette regarde la pièce en très basse
 * résolution et signale un mouvement (quelqu'un passe devant) pour annuler la mise en veille.
 * Les images sont analysées en mémoire puis jetées : rien n'est enregistré ni envoyé.
 */
class MotionWake(private val ctx: Context, private val onMotion: () -> Unit) {
    companion object {
        private const val TAG = "TileHouse"
        private const val GRID = 8          // un pixel sur 8 dans chaque direction
        private const val PIXEL_DIFF = 28   // écart de luminance d'un point « changé »
        private const val CHANGED_RATIO = 0.04f // 4 % des points changés = mouvement
        private const val EVERY_MS = 400L

        fun hasFrontCamera(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previous: IntArray? = null
    private var lastFrame = 0L
    /** Ignore les images jusqu'à cet instant (ex. juste après un changement de luminosité de l'écran). */
    @Volatile private var muteUntil = 0L
    val running get() = device != null

    /** Les prochaines images servent de nouvelle référence (l'éclairage de l'écran vient de changer). */
    fun rebaseline(ms: Long = 2500) { muteUntil = SystemClock.uptimeMillis() + ms; previous = null }

    @SuppressLint("MissingPermission")
    fun start() {
        if (device != null || !hasFrontCamera(ctx) || !hasPermission(ctx)) return
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        } catch (e: Exception) { null } ?: return
        val chars = cm.getCameraCharacteristics(id)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888) ?: return
        // La plus petite taille d'au moins 160 px de large (moins de calcul et de mémoire).
        val size: Size = sizes.filter { it.width >= 160 }.minByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.minByOrNull { it.upper }
        val t = HandlerThread("motion").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        bg = h
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastFrame < EVERY_MS || now < muteUntil) return@setOnImageAvailableListener
                lastFrame = now
                val plane = img.planes[0]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val w = img.width / GRID; val hh = img.height / GRID
                val cur = IntArray(w * hh)
                var k = 0
                for (y in 0 until hh) {
                    val row = y * GRID * rowStride
                    for (x in 0 until w) cur[k++] = buf.get(row + x * GRID).toInt() and 0xFF
                }
                val prev = previous
                previous = cur
                if (prev == null || prev.size != cur.size) return@setOnImageAvailableListener
                var changed = 0
                for (i in cur.indices) if (kotlin.math.abs(cur[i] - prev[i]) > PIXEL_DIFF) changed++
                if (changed > cur.size * CHANGED_RATIO) main.post(onMotion)
            } catch (_: Exception) {
            } finally { img.close() }
        }, h)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.lower, fps.upper))
                        }
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try { s.setRepeatingRequest(req.build(), null, h) } catch (e: Exception) { Log.w(TAG, "motion: $e") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "motion: session failed") }
                        }, h)
                    } catch (e: Exception) { Log.w(TAG, "motion: $e") }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); if (device === cam) device = null }
                override fun onError(cam: CameraDevice, error: Int) { Log.w(TAG, "motion: camera error $error"); cam.close(); if (device === cam) device = null }
            }, h)
            Log.i(TAG, "motion: front camera ${size.width}x${size.height}")
        } catch (e: Exception) { Log.w(TAG, "motion: $e"); stop() }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null; previous = null
        thread?.quitSafely(); thread = null; bg = null
    }
}
