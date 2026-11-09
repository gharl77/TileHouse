package com.homepanel.kiosk

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Fenêtre de contrôle détaillé (appui long) : luminosité, position, thermostat, média. */
class ControlDialog(private val act: Activity, private val id: String, title: String) : Dialog(act), HaClient.Listener {
    private val ha = App.instance.ha
    private val domain = Present.domain(id)
    private val statusView: TextView
    private var slider: VerticalSlider? = null
    private var powerBtn: ImageView? = null
    private var playBtn: ImageView? = null
    private var volume: VerticalSlider? = null
    private var targetView: TextView? = null
    private val modePills = ArrayList<Pair<String, TextView>>()
    /** Boutons d'aspirateur : (vue ronde, service) pour mettre en évidence l'action en cours. */
    private val vacButtons = ArrayList<Pair<ImageView, String>>()
    private var vacStart: ImageView? = null
    private var vacStartLabel: TextView? = null
    private var pendingTarget: Double? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sendTarget = Runnable {
        pendingTarget?.let { ha.callService("climate", "set_temperature", id, JSONObject().put("temperature", it)) }
        pendingTarget = null
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(32), act.dp(24), act.dp(32), act.dp(24))
        }
        box.addView(act.label(26f, Color.WHITE, true, title).apply { gravity = Gravity.CENTER })
        statusView = act.label(16f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(4), 0, 0) }
        box.addView(statusView)

        val e = ha.states[id]
        when (domain) {
            "light" -> buildSlider(box, Present.supportsBrightness(e))
            "fan" -> buildSlider(box, e?.attrs?.has("percentage") == true)
            "cover" -> buildSlider(box, e?.attrs?.has("current_position") == true)
            "climate" -> buildClimate(box, e)
            "media_player" -> buildMedia(box, e)
            "vacuum" -> buildVacuum(box)
        }
        setContentView(box)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setDimAmount(0.78f)
        setCanceledOnTouchOutside(true)
        setOnDismissListener {
            ha.removeListener(this)
            handler.removeCallbacks(sendTarget)
            sendTarget.run()
        }
        refresh()
    }

    fun showImmersive() {
        // Évite que la barre de navigation réapparaisse à l'ouverture.
        window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        show()
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = act.window.decorView.systemUiVisibility
        window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        ha.addListener(this)
    }

    override fun onHaEntities(ids: Set<String>) { if (id in ids) refresh() }

    private fun row(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(24) }
    }

    private fun buildSlider(box: LinearLayout, withSlider: Boolean) {
        if (withSlider) {
            val s = VerticalSlider(act)
            s.layoutParams = LinearLayout.LayoutParams(act.dp(140), act.dp(340)).apply { topMargin = act.dp(24) }
            s.onChange = { v -> statusView.text = sliderText(v) }
            s.onRelease = { v ->
                when (domain) {
                    "light" -> if (v <= 0) ha.callService("light", "turn_off", id)
                    else ha.callService("light", "turn_on", id, JSONObject().put("brightness_pct", v))
                    "fan" -> if (v <= 0) ha.callService("fan", "turn_off", id)
                    else ha.callService("fan", "set_percentage", id, JSONObject().put("percentage", v))
                    "cover" -> ha.callService("cover", "set_cover_position", id, JSONObject().put("position", v))
                }
            }
            box.addView(s)
            slider = s
        }
        val r = row()
        if (domain == "cover") {
            r.addView(act.roundIcon(R.drawable.ic_up, 64, GLASS_LIGHT, Color.WHITE) { ha.callService("cover", "open_cover", id) })
            r.addView(act.roundIcon(R.drawable.ic_stop, 64, GLASS_LIGHT, Color.WHITE) { ha.callService("cover", "stop_cover", id) })
            r.addView(act.roundIcon(R.drawable.ic_down, 64, GLASS_LIGHT, Color.WHITE) { ha.callService("cover", "close_cover", id) })
        } else {
            powerBtn = act.roundIcon(R.drawable.ic_power, if (withSlider) 64 else 120, GLASS_LIGHT, Color.WHITE) {
                ha.callService(domain, "toggle", id)
            }
            r.addView(powerBtn)
        }
        box.addView(r)
    }

    private fun sliderText(v: Int) = when (domain) {
        "cover" -> if (v <= 0) tr("Fermé") else if (v >= 100) tr("Ouvert") else "${tr("Ouvert à")} $v %"
        else -> if (v <= 0) tr("Éteint") else "$v %"
    }

    private fun buildClimate(box: LinearLayout, e: EntityState?) {
        val r = row()
        r.addView(act.roundIcon(R.drawable.ic_minus, 64, GLASS_LIGHT, Color.WHITE) { adjust(-1) })
        targetView = act.label(72f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minWidth = act.dp(200)
        }
        r.addView(targetView)
        r.addView(act.roundIcon(R.drawable.ic_plus, 64, GLASS_LIGHT, Color.WHITE) { adjust(1) })
        box.addView(r)

        val modes = e?.attrs?.optJSONArray("hvac_modes") ?: return
        val mr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until modes.length()) {
            val m = modes.optString(i)
            val p = act.pill(Present.hvacName(m), false) {
                ha.callService("climate", "set_hvac_mode", id, JSONObject().put("hvac_mode", m))
            }
            modePills.add(m to p)
            mr.addView(p)
        }
        val sc = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(mr) }
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(28) })
    }

    private fun adjust(dir: Int) {
        val e = ha.states[id] ?: return
        val a = e.attrs
        val step = a.dbl("target_temp_step") ?: if (a.str("temperature_unit") == "°F") 1.0 else 0.5
        val cur = pendingTarget ?: a.dbl("temperature") ?: return
        val min = a.dbl("min_temp") ?: 5.0
        val max = a.dbl("max_temp") ?: 35.0
        val v = (Math.round((cur + dir * step) / step) * step).coerceIn(min, max)
        pendingTarget = v
        targetView?.text = Present.num(v) + "°"
        handler.removeCallbacks(sendTarget)
        handler.postDelayed(sendTarget, 900)
    }

    private fun buildMedia(box: LinearLayout, e: EntityState?) {
        if (e?.attrs?.has("volume_level") == true) {
            val v = VerticalSlider(act)
            v.layoutParams = LinearLayout.LayoutParams(act.dp(110), act.dp(260)).apply { topMargin = act.dp(24) }
            v.onChange = { statusView.text = "Volume $it %" }
            v.onRelease = { ha.callService("media_player", "volume_set", id, JSONObject().put("volume_level", it / 100.0)) }
            box.addView(v)
            volume = v
        }
        val r = row()
        r.addView(act.roundIcon(R.drawable.ic_prev, 64, GLASS_LIGHT, Color.WHITE) { ha.callService("media_player", "media_previous_track", id) })
        playBtn = act.roundIcon(R.drawable.ic_play, 80, WHITE_TILE, 0xFF1C1C1E.toInt()) { ha.callService("media_player", "media_play_pause", id) }
        r.addView(playBtn)
        r.addView(act.roundIcon(R.drawable.ic_next, 64, GLASS_LIGHT, Color.WHITE) { ha.callService("media_player", "media_next_track", id) })
        r.addView(act.roundIcon(R.drawable.ic_power, 64, GLASS_LIGHT, Color.WHITE) { ha.callService("media_player", "toggle", id) })
        box.addView(r)
    }

    private fun buildVacuum(box: LinearLayout) {
        val r = row()
        fun button(icon: Int, label: String, service: String): Pair<ImageView, TextView> {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(act.dp(10), 0, act.dp(10), 0)
            }
            val b = act.roundIcon(icon, 76, GLASS_LIGHT, Color.WHITE) {
                val e = ha.states[id]
                // « Démarrer » reprend le nettoyage s'il est en pause.
                ha.callService("vacuum", if (service == "start" && e?.state == "paused") "start" else service, id)
            }
            col.addView(b)
            val t = act.label(15f, 0xCCFFFFFF.toInt(), text = label).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(8), 0, 0) }
            col.addView(t)
            r.addView(col)
            vacButtons.add(b to service)
            return b to t
        }
        val (s, sl) = button(R.drawable.ic_play, tr("Démarrer"), "start")
        vacStart = s; vacStartLabel = sl
        button(R.drawable.ic_pause, tr("Pause"), "pause")
        button(R.drawable.ic_stop, tr("Arrêter"), "stop")
        button(R.drawable.ic_home, tr("Base"), "return_to_base")
        box.addView(r)
    }

    private fun refresh() {
        val e = ha.states[id]
        statusView.text = Present.status(e)
        slider?.let { s ->
            if (!s.dragging) {
                s.value = when (domain) {
                    "light" -> (e?.let { Present.brightnessPct(it) } ?: 0).toFloat()
                    "fan" -> if (e?.state == "on") (e.attrs.int("percentage") ?: 100).toFloat() else 0f
                    else -> (e?.attrs?.int("current_position") ?: if (e?.state == "open") 100 else 0).toFloat()
                }
            }
        }
        powerBtn?.background = oval(if (Present.active(e)) Present.accent(e) else GLASS_LIGHT)
        volume?.let { v -> if (!v.dragging) v.value = ((e?.attrs?.dbl("volume_level") ?: 0.0) * 100).toFloat() }
        playBtn?.setImageResource(if (e?.state == "playing") R.drawable.ic_pause else R.drawable.ic_play)
        if (targetView != null && pendingTarget == null) {
            targetView?.text = e?.attrs?.dbl("temperature")?.let { Present.num(it) + "°" } ?: "—"
        }
        for ((m, p) in modePills) stylePill(p, e?.state == m)
        if (vacButtons.isNotEmpty()) {
            val st = e?.state
            val on = when (st) { "cleaning" -> "start"; "paused" -> "pause"; "idle" -> "stop"; "returning", "docked" -> "return_to_base"; else -> "" }
            for ((b, svc) in vacButtons) {
                val sel = svc == on
                b.background = oval(if (sel) Present.accent(e) else GLASS_LIGHT)
            }
            vacStartLabel?.text = if (st == "paused") tr("Reprendre") else tr("Démarrer")
        }
    }
}
