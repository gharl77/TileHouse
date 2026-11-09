package com.homepanel.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Téléchargement des snapshots (camera_proxy), décodés en basse résolution (RGB_565). */
class Snapshots(private val app: App) {
    private val main = Handler(Looper.getMainLooper())
    private val http by lazy {
        app.ha.http.newBuilder().readTimeout(20, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build()
    }

    /**
     * Charge le snapshot de la caméra. Si HA ne peut pas en fournir (caméras sur batterie,
     * ex. Eufy : erreur 500), on se rabat sur une image de l'appareil (ex. dernier événement).
     */
    fun load(entityId: String, targetWidth: Int, cb: (Bitmap?) -> Unit) {
        if (app.ha.demo) { main.post { cb(Demo.cameraImage(entityId, targetWidth)) }; return }
        fetch("/api/camera_proxy/$entityId", targetWidth) { bmp ->
            if (bmp != null) { cb(bmp); return@fetch }
            // Repli : image de l'appareil de la caméra (ex. dernier événement), trouvée dans HA.
            CameraResolver.resolve(app, entityId) { info ->
                val img = info.images.firstOrNull()
                if (img == null) cb(null) else fetch("/api/image_proxy/$img", targetWidth, cb)
            }
        }
    }

    private fun fetch(path: String, targetWidth: Int, cb: (Bitmap?) -> Unit) {
        val cfg = app.config
        if (!cfg.isConfigured) { cb(null); return }
        val req = try {
            Request.Builder()
                .url(cfg.url.trimEnd('/') + path + "?t=" + System.currentTimeMillis())
                .header("Authorization", "Bearer ${cfg.token}")
                .build()
        } catch (_: IllegalArgumentException) { cb(null); return }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.w("TileHouse", "image $path : ${e.message}")
                main.post { cb(null) }
            }
            override fun onResponse(call: Call, response: Response) {
                val bytes = response.use {
                    if (!it.isSuccessful) android.util.Log.w("TileHouse", "image $path : HTTP ${it.code}")
                    if (it.isSuccessful) it.body?.bytes() else null
                }
                val bmp = bytes?.let { decode(it, targetWidth) }
                if (bmp != null) android.util.Log.i("TileHouse", "image $path : ${bmp.width}x${bmp.height} (${bytes.size / 1024} Ko)")
                main.post { cb(bmp) }
            }
        })
    }

    /** GET REST authentifié, réponse texte (null en cas d'échec). */
    fun restGet(path: String, cb: (String?) -> Unit) {
        if (app.ha.demo) { val r = Demo.rest(path); main.post { cb(r) }; return }
        val cfg = app.config
        val req = try {
            Request.Builder().url(cfg.url.trimEnd('/') + path).header("Authorization", "Bearer ${cfg.token}").build()
        } catch (_: IllegalArgumentException) { cb(null); return }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { cb(null) } }
            override fun onResponse(call: Call, response: Response) {
                val text = response.use { if (it.isSuccessful) it.body?.string() else null }
                main.post { cb(text) }
            }
        })
    }

    /** État d'une entité via l'API REST (utilisé pour l'URL RTSP fournie par l'intégration). */
    fun getState(entityId: String, cb: (String?) -> Unit) {
        if (app.ha.demo) { main.post { cb(null) }; return }
        val cfg = app.config
        val req = try {
            Request.Builder().url(cfg.url.trimEnd('/') + "/api/states/" + entityId)
                .header("Authorization", "Bearer ${cfg.token}").build()
        } catch (_: IllegalArgumentException) { cb(null); return }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { cb(null) } }
            override fun onResponse(call: Call, response: Response) {
                val text = response.use { if (it.isSuccessful) it.body?.string() else null }
                val state = try { text?.let { org.json.JSONObject(it).optString("state") } } catch (_: Exception) { null }
                main.post { cb(state) }
            }
        })
    }

    private fun decode(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val d = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d) } catch (_: OutOfMemoryError) { null }
    }
}

/** Grande vignette caméra 16:9 avec le dernier snapshot. */
class CameraTileView(ctx: Context) : View(ctx) {
    var name = ""
        set(v) { field = v; invalidate() }
    private var bitmap: Bitmap? = null
    private var loadedAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = ctx.dpf(15); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = ctx.dpf(12) }
    private val icon = ctx.getDrawable(R.drawable.ic_camera)?.mutate()?.apply { setTint(0x80FFFFFF.toInt()) }
    private val rect = RectF()
    private val m = Matrix()
    private val radius = ctx.dpf(18)

    init { isClickable = true }

    fun setBitmap(b: Bitmap?) {
        if (b == null) return
        val old = bitmap
        bitmap = b
        loadedAt = SystemClock.elapsedRealtime()
        paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        updateMatrix()
        invalidate()
        if (old != null && old !== b) old.recycle()
    }

    private fun updateMatrix() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        // Recadrage centré (centerCrop)
        val s = max(width.toFloat() / b.width, height.toFloat() / b.height)
        m.setScale(s, s)
        m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        paint.shader?.setLocalMatrix(m)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = updateMatrix()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        paint.shader = null
        bitmap?.recycle()
        bitmap = null
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed == isPressed) { super.setPressed(pressed); return }
        super.setPressed(pressed)
        val s = if (pressed) 0.97f else 1f
        animate().scaleX(s).scaleY(s).setDuration(90).start()
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (bitmap != null) c.drawRoundRect(rect, radius, radius, paint)
        else {
            c.drawRoundRect(rect, radius, radius, bg)
            icon?.let {
                val s = context.dp(40)
                val l = (width - s) / 2; val t = (height - s) / 2
                it.setBounds(l, t, l + s, t + s); it.draw(c)
            }
        }
        // Étiquette : nom + âge du snapshot
        val pad = context.dpf(10)
        val age = if (loadedAt == 0L) "" else {
            val min = (SystemClock.elapsedRealtime() - loadedAt) / 60_000
            if (min < 1) tr("à l'instant") else if (I18n.english) "$min min ago" else "il y a $min min"
        }
        val label = TextUtils.ellipsize(name, text, width * 0.7f, TextUtils.TruncateAt.END).toString()
        val lw = max(text.measureText(label), small.measureText(age))
        val lh = -text.ascent() + text.descent() + (if (age.isEmpty()) 0f else -small.ascent() + small.descent())
        val bx = pad; val by = height - pad - lh - pad * 1.2f
        c.drawRoundRect(bx, by, bx + lw + pad * 2, height - pad, context.dpf(10), context.dpf(10), labelBg)
        c.drawText(label, bx + pad, by + pad * 0.6f - text.ascent(), text)
        if (age.isNotEmpty()) c.drawText(age, bx + pad, by + pad * 0.6f - text.ascent() + text.descent() - small.ascent(), small)
    }
}

/** Grille des caméras : vignettes 16:9, colonnes selon la largeur. */
class CameraGrid(ctx: Context) : ViewGroup(ctx) {
    private val minW = ctx.dp(340)
    private val gap = ctx.dp(12)
    private var cols = 1
    private var tileW = 0
    private var tileH = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cols = max(1, (w + gap) / (minW + gap))
        tileW = (w - gap * (cols - 1)) / cols
        tileH = tileW * 9 / 16
        val cw = MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        val rows = (childCount + cols - 1) / cols
        setMeasuredDimension(w, if (rows == 0) 0 else rows * tileH + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = (i % cols) * (tileW + gap)
            val y = (i / cols) * (tileH + gap)
            getChildAt(i).layout(x, y, x + tileW, y + tileH)
        }
    }
}

/**
 * Ce que l'appareil d'une caméra offre dans HA, trouvé de façon générique (toutes marques) :
 * images de secours (image.*), flux RTSP publiés en capteurs (…stream_url / …rtsp_url),
 * boutons PTZ (pan/tilt/ptz/move + gauche/droite/haut/bas), et PTZ ONVIF.
 * Les noms Eufy (image.<cam>_last_event, sensor.<cam>_stream_url, button.<cam>_pan_left…)
 * sont aussi essayés directement en repli.
 */
class CameraInfo(
    val images: List<String>,
    val streamSensors: List<String>,
    val ptzButtons: Map<String, String>,
    val onvif: Boolean,
    /** Interrupteur qui démarre le flux RTSP (Eufy : switch.<cam>_rtsp_stream), éteint par défaut. */
    val streamSwitch: String? = null,
    /** Détecteurs « personne » de l'appareil (Eufy : binary_sensor.<cam>_person). */
    val personSensors: List<String> = emptyList(),
) {
    val hasPtz get() = onvif || ptzButtons.isNotEmpty()
}

object CameraResolver {
    private val cache = HashMap<String, CameraInfo>()

    fun cached(cameraId: String) = cache[cameraId]

    fun resolve(app: App, cameraId: String, cb: (CameraInfo) -> Unit) {
        cache[cameraId]?.let { cb(it); return }
        val ha = app.ha
        ha.request(JSONObject().put("type", "config/entity_registry/get").put("entity_id", cameraId)) { reg ->
            val r = reg.optJSONObject("result")
            val platform = r?.str("platform") ?: ""
            val deviceId = r?.str("device_id") ?: ""
            if (deviceId.isEmpty()) { done(cameraId, emptyList(), platform, cb); return@request }
            ha.request(JSONObject().put("type", "search/related").put("item_type", "device").put("item_id", deviceId)) { rel ->
                val arr = rel.optJSONObject("result")?.optJSONArray("entity")
                val ids = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
                done(cameraId, ids, platform, cb)
            }
        }
    }

    private fun done(cameraId: String, related: List<String>, platform: String, cb: (CameraInfo) -> Unit) {
        val obj = cameraId.substringAfter('.')
        val images = related.filter { it.startsWith("image.") }.sortedBy { if ("last" in it || "event" in it) 0 else 1 }
            .ifEmpty { listOf("image.${obj}_last_event") }
        val streams = related.filter { it.startsWith("sensor.") && ("stream_url" in it || "rtsp_url" in it || "rtsp" in it) }
            .sortedBy { if ("stream_url" in it) 0 else 1 }
            .ifEmpty { listOf("sensor.${obj}_stream_url", "sensor.${obj}_rtsp_url") }
        val ptz = HashMap<String, String>()
        for (b in related.filter { it.startsWith("button.") }) {
            val t = b.substringAfter('.').split('_', '-').toSet()
            val isPtz = t.any { it in setOf("pan", "tilt", "ptz", "move", "rotate", "turn") }
            val dir = when {
                "left" in t || "gauche" in t -> "left"
                "right" in t || "droite" in t -> "right"
                ("up" in t && isPtz) || "haut" in t -> "up"
                ("down" in t && isPtz) || "bas" in t -> "down"
                else -> null
            }
            if (dir != null && (isPtz || t.any { it in setOf("gauche", "droite", "haut", "bas") })) ptz.putIfAbsent(dir, b)
        }
        val switch = related.firstOrNull { it.startsWith("switch.") && ("rtsp" in it || it.endsWith("_stream") || "stream" in it.substringAfter('.').split('_')) }
        val persons = related.filter { it.startsWith("binary_sensor.") && it.substringAfter('.').split('_').any { t -> t == "person" || t == "personne" || t == "human" } }
            .ifEmpty { if (related.isEmpty()) listOf("binary_sensor.${obj}_person") else emptyList() }
        val info = CameraInfo(images, streams, ptz, platform == "onvif", switch, persons)
        cache[cameraId] = info
        cb(info)
    }
}
