package com.homepanel.kiosk

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.json.JSONObject
import kotlin.math.abs

/**
 * Vue live plein écran d'une caméra (HLS de Home Assistant).
 * Balayer gauche/droite/haut/bas oriente une caméra motorisée (boutons pan/tilt de HA).
 */
class LiveActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        /** Alerte : durée d'affichage en secondes (0 = live normal). */
        const val EXTRA_ALERT = "alert"
        const val EXTRA_REASON = "reason"
        /** Caméra affichée en ce moment (null si aucune vue live ouverte). */
        @Volatile var showing: String? = null
            private set
        private const val MAX_LIVE_MS = 5 * 60_000L // économise la batterie des caméras
    }

    private val app get() = App.instance
    private lateinit var entityId: String
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var still: ImageView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var arrow: TextView
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entityId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        alertSeconds = intent.getIntExtra(EXTRA_ALERT, 0)
        @Suppress("DEPRECATION")
        if (alertSeconds > 0) window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        showing = entityId

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        // Le snapshot reste par-dessus la vidéo jusqu'à la première image du flux.
        still = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        root.addView(still, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        root.addView(label(22f, Color.WHITE, true, intent.getStringExtra(EXTRA_NAME) ?: "").apply {
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(28), dp(24), 0, 0) })

        status = label(16f, Color.WHITE, text = tr("Connexion au flux…")).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundRect(0x99000000.toInt(), dpf(20))
        }
        root.addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })

        hint = label(14f, 0xCCFFFFFF.toInt(), text = tr("Balayez pour orienter la caméra")).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundRect(0x99000000.toInt(), dpf(20))
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })

        arrow = label(96f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(16f, 0f, 0f, Color.BLACK)
            alpha = 0f
        }
        root.addView(arrow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        root.addView(roundIcon(R.drawable.ic_close, 52, 0x99000000.toInt(), Color.WHITE) { finish() }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(24), 0) }
        })
        if (alertSeconds > 0) {
            alertPill = label(17f, Color.WHITE, true).apply {
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = roundRect(0xE6FF3B30.toInt(), dpf(22))
            }
            root.addView(alertPill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })
            alertLeft = alertSeconds
            alertPill?.text = alertReason
            // Le compte à rebours part à la première image (une sonnette sur batterie peut mettre 20 s à démarrer).
            handler.postDelayed(startAlertCountdown, 30_000)
        }
        setContentView(root)

        // Dernier snapshot affiché en attendant le flux
        app.snapshots.load(entityId, resources.displayMetrics.widthPixels / 2) { bmp ->
            if (isDestroyed) bmp?.recycle() else if (bmp != null) still.setImageBitmap(bmp)
        }
        if (app.ha.demo) {
            status.text = tr("Mode démo : la vidéo en direct n'est pas disponible.")
            if (entityId == "camera.driveway") showPtzHint()
        } else startStreams()
        handler.postDelayed({ finish() }, MAX_LIVE_MS)
    }

    // ------------------------------------------------------------ alerte

    private var alertSeconds = 0
    private var alertLeft = 0
    private var alertPill: TextView? = null
    private var alertKept = false
    private val alertReason by lazy { intent.getStringExtra(EXTRA_REASON) ?: ("🚶  " + tr("Personne détectée")) }

    /** Compte à rebours de l'alerte ; un toucher l'annule et garde le live ouvert. */
    private val alertTick = object : Runnable {
        override fun run() {
            if (alertLeft <= 0) { finish(); return }
            alertPill?.text = alertReason + "  ·  $alertLeft s"
            alertLeft--
            handler.postDelayed(this, 1000)
        }
    }

    private val startAlertCountdown = Runnable {
        if (!alertKept && !alertStarted) { alertStarted = true; handler.post(alertTick) }
    }
    private var alertStarted = false

    private fun keepOpen() {
        if (alertPill == null || alertKept) return
        alertKept = true
        handler.removeCallbacks(alertTick)
        handler.removeCallbacks(startAlertCountdown)
        alertPill?.text = alertReason
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) keepOpen()
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onStop() {
        super.onStop()
        // Kiosque : on ne garde jamais un flux vidéo ouvert en arrière-plan.
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (showing == entityId) showing = null
        // On rend l'interrupteur de flux dans l'état trouvé (économise la batterie et la bande passante).
        switchedOn?.let { StreamSwitches.release(app, it, entityId) }
        (still.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { still.setImageDrawable(null); it.recycle() }
    }

    // ------------------------------------------------------------ flux

    private var info: CameraInfo? = null

    /**
     * Ordre d'essai :
     *  - si l'appareil publie des flux RTSP (ex. Eufy) : ceux-ci d'abord (plus directs), puis HLS de HA ;
     *  - sinon : HLS de HA (cas standard, toutes marques).
     */
    private fun startStreams() {
        CameraResolver.resolve(app, entityId) { i ->
            if (isFinishing) return@resolve
            info = i
            if (i.hasPtz) showPtzHint()
            val sw = i.streamSwitch
            if (sw != null) { enableStream(i, sw); return@resolve }
            collectRtsp(i) {
                triedRtsp = true
                val first = rtspQueue.removeFirstOrNull()
                if (first != null) playRtsp(first) else requestStream()
            }
        }
    }

    /** Interrupteur de flux allumé par cette vue (à éteindre en sortant). */
    private var switchedOn: String? = null
    /** Flux qui vient d'être démarré : le serveur peut répondre 404 quelques secondes. */
    private var streamFresh = false

    /**
     * Eufy : sans flux actif, les capteurs d'URL sont « unknown ». On allume l'interrupteur
     * RTSP de la caméra, puis on attend que l'adresse du flux soit publiée (jusqu'à ~30 s).
     */
    private fun enableStream(i: CameraInfo, sw: String) {
        status.text = tr("Démarrage du flux de la caméra…")
        status.visibility = View.VISIBLE
        app.snapshots.getState(sw) { st ->
            if (isFinishing) return@getState
            // Interrupteur éteint : les adresses publiées sont périmées, on attend les nouvelles.
            if (st != "on") { StreamSwitches.turnOn(app, sw); switchedOn = sw; waitForUrl(i, 0, 3000) }
            else { if (sw in StreamSwitches.owned) switchedOn = sw; waitForUrl(i, 0, 0) }
        }
    }

    private fun waitForUrl(i: CameraInfo, n: Int, delay: Long = 2000) {
        handler.postDelayed({
            if (!isFinishing) collectRtsp(i) {
                val first = rtspQueue.removeFirstOrNull()
                when {
                    first != null -> { triedRtsp = true; streamFresh = switchedOn != null; playRtsp(first) }
                    n < 14 -> waitForUrl(i, n + 1)
                    else -> requestStream()
                }
            }
        }, 2000)
    }

    /** Lit les capteurs d'URL RTSP de l'appareil (les adresses internes de HA OS sont réécrites). */
    private fun collectRtsp(i: CameraInfo, then: () -> Unit) {
        val haHost = android.net.Uri.parse(app.config.url).host ?: ""
        var remaining = i.streamSensors.size
        if (remaining == 0) { then(); return }
        val found = arrayOfNulls<String>(remaining)
        i.streamSensors.forEachIndexed { k, sensor ->
            app.snapshots.getState(sensor) { u ->
                if (u != null && u.startsWith("rtsp://"))
                    found[k] = u.replace(Regex("://(172\\.30\\.\\d+\\.\\d+|127\\.0\\.0\\.1|localhost)"), "://$haHost")
                if (--remaining == 0) {
                    for (f in found) if (f != null && f !in rtspQueue) rtspQueue.add(f)
                    if (!isFinishing) then()
                }
            }
        }
    }

    private var triedHls = false

    private fun requestStream() {
        if (triedHls) { showFailure(tr("Aucun flux n'a pu être lu.")); return }
        triedHls = true
        status.text = tr("Demande du flux à Home Assistant…")
        status.visibility = View.VISIBLE
        // HA peut retenir la playlist très longtemps : on abandonne après 25 s.
        handler.postDelayed(hlsTimeout, 25_000)
        val msg = JSONObject().put("type", "camera/stream").put("entity_id", entityId).put("format", "hls")
        app.ha.request(msg) { res ->
            if (isFinishing) return@request
            val url = res.optJSONObject("result")?.str("url") ?: ""
            if (!res.optBoolean("success") || url.isEmpty()) {
                fallbackRtsp(tr("Home Assistant ne fournit pas de flux pour cette caméra."))
                return@request
            }
            playHls(if (url.startsWith("http")) url else app.config.url.trimEnd('/') + url)
        }
    }

    private var retries = 0

    private val hlsTimeout = Runnable {
        if (player?.isPlaying != true) fallbackRtsp(tr("Home Assistant ne répond pas pour ce flux."))
    }

    private var triedRtsp = false
    private val rtspQueue = ArrayList<String>()

    /** Source suivante après un échec : autre flux RTSP, puis HLS, puis message d'échec. */
    private fun fallbackRtsp(reason: String) {
        val next = rtspQueue.removeFirstOrNull()
        when {
            next != null -> playRtsp(next)
            !triedHls -> requestStream()
            else -> showFailure(reason)
        }
    }

    /** Dernière erreur RTSP lisible (ex. « la caméra répond 404 »). */
    private var rtspError: String? = null
    private var rtspTried = 0

    private fun showFailure(reason: String) {
        if (alertPill != null) { handler.removeCallbacks(startAlertCountdown); startAlertCountdown.run() }
        status.text = if (rtspTried == 0)
            "$reason " + tr("Live indisponible : activez le flux RTSP ou « stream » de la caméra dans son intégration.")
        else
            tr("Live indisponible : la caméra n'a fourni aucun flux lisible") +
                (rtspError?.let { " ($it)" } ?: "") + ". " + tr("Essayez de redémarrer la caméra ou son intégration.")
        status.visibility = View.VISIBLE
        still.visibility = View.VISIBLE
    }

    @OptIn(UnstableApi::class)
    private fun playHls(url: String) {
        // Certaines caméras (Eufy en P2P) mettent 10 à 30 s à démarrer : HA retient la playlist.
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(30_000).setReadTimeoutMs(30_000)
        play(HlsMediaSource.Factory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
            .createMediaSource(MediaItem.fromUri(url)))
    }

    @OptIn(UnstableApi::class)
    private fun playRtsp(url: String) {
        retries = 0
        rtspTried++
        status.text = tr("Connexion directe à la caméra…")
        play(RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(15_000)
            .createMediaSource(MediaItem.fromUri(url)))
    }

    @OptIn(UnstableApi::class)
    private fun play(source: MediaSource) {
        player?.release()
        // Petit tampon : moins de mémoire et moins de latence.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1500, 6000, 500, 1000).build()
        val p = ExoPlayer.Builder(this).setLoadControl(load).build()
        player = p
        p.setVideoSurfaceView(surface)
        p.volume = 0f
        // Live muet : on ignore la piste audio (sinon le lecteur peut attendre des données audio
        // qui n'arrivent jamais et rester bloqué sur « Chargement… »).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> status.visibility = View.GONE
                    Player.STATE_BUFFERING -> { status.text = tr("Chargement…"); status.visibility = View.VISIBLE }
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width == 0 || size.height == 0) return
                // Respecte le format de l'image (bandes noires si nécessaire)
                val vw = root.width.toFloat(); val vh = root.height.toFloat()
                val r = size.width * size.pixelWidthHeightRatio / size.height
                val (w, h) = if (vw / vh > r) (vh * r) to vh else vw to (vw / r)
                surface.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER)
            }

            override fun onRenderedFirstFrame() {
                still.visibility = View.GONE
                if (alertPill != null) { handler.removeCallbacks(startAlertCountdown); startAlertCountdown.run() }
                handler.removeCallbacks(hlsTimeout)
            }

            override fun onPlayerError(error: PlaybackException) {
                var c: Throwable? = error
                while (c != null) {
                    val m = c.message ?: ""
                    when {
                        m.contains("DESCRIBE 404") || m.contains(" 404") -> { rtspError = tr("le serveur de flux répond 404 : aucun flux disponible"); break }
                        m.contains("401") -> { rtspError = tr("accès refusé (identifiants RTSP)"); break }
                        m.contains("Decoder init failed") -> { rtspError = tr("résolution trop élevée pour la tablette : réglez la caméra en 1080p"); break }
                    }
                    c = c.cause
                }
                status.visibility = View.VISIBLE
                still.visibility = View.VISIBLE
                if (retries++ < (if (streamFresh) 5 else 1)) {
                    status.text = tr("Nouvelle tentative…")
                    handler.postDelayed({ player?.let { it.prepare(); it.playWhenReady = true } }, if (streamFresh) 3000 else 2000)
                } else fallbackRtsp(tr("Flux interrompu") + " (${error.errorCodeName}).")
            }
        })
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
    }

    // ------------------------------------------------------------ PTZ

    private fun showPtzHint() {
        hint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, 6000)
    }

    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (info?.hasPtz == true && maxOf(abs(dx), abs(dy)) > dp(70)) {
                    val dir = if (abs(dx) > abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
                    move(dir)
                }
            }
        }
        return true
    }

    private fun move(dir: String) {
        val i = info ?: return
        val button = i.ptzButtons[dir]
        when {
            button != null -> app.ha.callService("button", "press", button)
            i.onvif -> {
                // PTZ ONVIF : petit mouvement continu dans la direction du balayage
                val d = JSONObject().put("move_mode", "ContinuousMove").put("continuous_duration", 0.4).put("speed", 0.6)
                when (dir) { "left" -> d.put("pan", "LEFT"); "right" -> d.put("pan", "RIGHT"); "up" -> d.put("tilt", "UP"); else -> d.put("tilt", "DOWN") }
                app.ha.callService("onvif", "ptz", entityId, d)
            }
            else -> return
        }
        arrow.text = when (dir) { "left" -> "‹"; "right" -> "›"; "up" -> "˄"; else -> "˅" }
        arrow.animate().cancel()
        arrow.alpha = 0.9f
        arrow.animate().alpha(0f).setStartDelay(250).setDuration(400).start()
    }
}
