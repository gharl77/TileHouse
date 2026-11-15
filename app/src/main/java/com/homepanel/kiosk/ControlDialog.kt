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

private const val CURRENT_GREEN = 0xFF34C759.toInt()

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
    private var currentView: TextView? = null
    private var modeIcon: ImageView? = null
    private var dial: ThermostatDial? = null
    private var cover: CoverView? = null
    /** Dernier mode vu en marche, pour le bouton marche/arrêt du thermostat. */
    private var lastMode: String? = null
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
            // Papier : les contrôles sont posés sur une feuille (texte foncé) au lieu de flotter sur le voile sombre.
            // Thermostat, store et rideau : toujours une carte, comme un appareil qu'on tient devant soi.
            if (Skin.dialogOnSheet || domain == "climate" || domain == "cover") { background = Skin.panelBg(act, act.dpf(26)); setPadding(act.dp(44), act.dp(30), act.dp(44), act.dp(34)) }
        }
        box.addView(act.label(26f, Skin.ink, true, title).apply { gravity = Gravity.CENTER })
        statusView = act.label(16f, Skin.fg(0xCC)).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(4), 0, 0) }
        box.addView(statusView)

        val e = ha.states[id]
        when (domain) {
            "light" -> buildSlider(box, Present.supportsBrightness(e))
            "fan" -> buildSlider(box, e?.attrs?.has("percentage") == true)
            "cover" -> buildCover(box, e)
            "climate" -> buildClimate(box, e)
            "media_player" -> buildMedia(box, e)
            "vacuum" -> buildVacuum(box)
        }
        setContentView(box)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setDimAmount(Skin.dialogDim)
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
                    "light" -> Present.setBrightness(ha, id, v)
                    "fan" -> if (v <= 0) ha.callService("fan", "turn_off", id)
                    else ha.callService("fan", "set_percentage", id, JSONObject().put("percentage", v))
                }
            }
            box.addView(s)
            slider = s
        }
        val r = row()
        powerBtn = act.roundIcon(R.drawable.ic_power, if (withSlider) 64 else 120, Skin.soft, Skin.ink) {
            ha.callService(domain, "toggle", id)
        }
        r.addView(powerBtn)
        box.addView(r)
    }

    private fun sliderText(v: Int) = when (domain) {
        "cover" -> if (v <= 0) tr("Fermé") else if (v >= 100) tr("Ouvert") else "${tr("Ouvert à")} $v %"
        else -> if (v <= 0) tr("Éteint") else "$v %"
    }

    /** Store ou rideau : la fenêtre dessinée à sa position réelle, qu'on règle du doigt, et les boutons du bon sens. */
    private fun buildCover(box: LinearLayout, e: EntityState?) {
        val curtain = Present.isCurtain(id, e)
        val v = CoverView(act, curtain)
        v.draggable = Present.coverCan(e, 4) && e?.attrs?.has("current_position") == true
        v.onChange = { statusView.text = sliderText(it) }
        v.onRelease = { ha.callService("cover", "set_cover_position", id, JSONObject().put("position", it)) }
        box.addView(v, LinearLayout.LayoutParams(act.dp(if (curtain) 360 else 300), act.dp(270)).apply { topMargin = act.dp(22) })
        cover = v
        val r = row()
        r.addView(act.roundIcon(if (curtain) R.drawable.ic_curtain_open else R.drawable.ic_up, 64, Skin.soft, Skin.ink) { ha.callService("cover", "open_cover", id) })
        if (Present.coverCan(e, 8)) r.addView(act.roundIcon(R.drawable.ic_stop, 64, Skin.soft, Skin.ink) { ha.callService("cover", "stop_cover", id) })
        r.addView(act.roundIcon(if (curtain) R.drawable.ic_curtain_close else R.drawable.ic_down, 64, Skin.soft, Skin.ink) { ha.callService("cover", "close_cover", id) })
        box.addView(r)
    }

    /**
     * Thermostat : à gauche la consigne en grand dans son arc (flamme au-dessus, marche/arrêt dessous),
     * à droite la température actuelle entre les flèches qui montent et descendent la consigne.
     */
    private fun buildClimate(box: LinearLayout, e: EntityState?) {
        val r = row()
        val dialBox = android.widget.FrameLayout(act)
        val d = ThermostatDial(act)
        d.onChange = { f -> range()?.let { (min, max, step) -> showTarget(ControlMath.snap(min + (max - min) * f, step, min, max)) } }
        d.onRelease = { handler.removeCallbacks(sendTarget); sendTarget.run() }
        dialBox.addView(d, android.widget.FrameLayout.LayoutParams(act.dp(300), act.dp(300)))
        dial = d
        val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        modeIcon = ImageView(act).apply { setImageResource(R.drawable.ic_flame) }
        mid.addView(modeIcon, LinearLayout.LayoutParams(act.dp(34), act.dp(34)))
        val big = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        targetView = act.label(84f, Skin.ink).apply { typeface = Skin.light; includeFontPadding = false }
        big.addView(targetView)
        big.addView(act.label(24f, Skin.ink, text = unit(e)).apply { setPadding(act.dp(4), act.dp(10), 0, 0) })
        mid.addView(big, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(6); bottomMargin = act.dp(10) })
        powerBtn = act.roundIcon(R.drawable.ic_power, 56, Skin.soft, Skin.ink) { togglePower() }
        mid.addView(powerBtn)
        dialBox.addView(mid, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = act.dp(34) })
        r.addView(dialBox)

        val side = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun arrow(icon: Int, dir: Int) = ImageView(act).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(Skin.ink)
            setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14)); isClickable = true
            setOnClickListener { adjust(dir) }
        }
        side.addView(arrow(R.drawable.ic_up, 1), LinearLayout.LayoutParams(act.dp(76), act.dp(76)))
        currentView = act.label(40f, CURRENT_GREEN).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        side.addView(currentView)
        side.addView(act.label(15f, Skin.fg(0xCC), text = tr("Température actuelle")).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(6), 0, 0) },
            LinearLayout.LayoutParams(act.dp(150), ViewGroup.LayoutParams.WRAP_CONTENT))
        side.addView(arrow(R.drawable.ic_down, -1), LinearLayout.LayoutParams(act.dp(76), act.dp(76)))
        r.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = act.dp(8) })
        box.addView(r)

        // Les modes ne s'affichent que s'il y a un vrai choix (thermopompe) : marche/arrêt suffit pour une plinthe.
        val modes = e?.attrs?.optJSONArray("hvac_modes") ?: return
        if ((0 until modes.length()).count { modes.optString(it) != "off" } < 2) return
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
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(20) })
    }

    private fun unit(e: EntityState?) = Present.climateUnit(e)

    /** Limites et pas de la consigne : (minimum, maximum, pas). */
    private fun range(): Triple<Double, Double, Double>? {
        val a = ha.states[id]?.attrs ?: return null
        val min = a.dbl("min_temp") ?: 5.0
        val max = a.dbl("max_temp") ?: 35.0
        if (max <= min) return null
        return Triple(min, max, a.dbl("target_temp_step") ?: if (unit(ha.states[id]) == "°F") 1.0 else 0.5)
    }

    private fun showTarget(v: Double) {
        pendingTarget = v
        targetText(Present.num(v))
    }

    /** Consigne en grand ; un peu moins grand avec une décimale, pour rester dans l'arc. */
    private fun targetText(t: String) {
        targetView?.let { it.text = t; it.textSize = (if (t.length > 2) 62f else 84f) * Skin.textScale }
    }

    /** Arrêt, ou remise en marche dans le dernier mode utilisé (sinon chauffage, sinon le premier mode offert). */
    private fun togglePower() {
        val e = ha.states[id] ?: return
        val mode = if (e.state != "off") "off" else {
            val modes = e.attrs.optJSONArray("hvac_modes")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty().filter { it != "off" }
            lastMode?.takeIf { it in modes } ?: modes.firstOrNull { it == "heat" } ?: modes.firstOrNull() ?: return
        }
        ha.callService("climate", "set_hvac_mode", id, JSONObject().put("hvac_mode", mode))
    }

    private fun adjust(dir: Int) {
        val (min, max, step) = range() ?: return
        val cur = pendingTarget ?: ha.states[id]?.attrs?.dbl("temperature") ?: return
        val v = ControlMath.snap(cur + dir * step, step, min, max)
        showTarget(v)
        dial?.fraction = ((v - min) / (max - min)).toFloat()
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
        r.addView(act.roundIcon(R.drawable.ic_prev, 64, Skin.soft, Skin.ink) { ha.callService("media_player", "media_previous_track", id) })
        playBtn = act.roundIcon(R.drawable.ic_play, 80, Skin.primary, Skin.onPrimary) { ha.callService("media_player", "media_play_pause", id) }
        r.addView(playBtn)
        r.addView(act.roundIcon(R.drawable.ic_next, 64, Skin.soft, Skin.ink) { ha.callService("media_player", "media_next_track", id) })
        r.addView(act.roundIcon(R.drawable.ic_power, 64, Skin.soft, Skin.ink) { ha.callService("media_player", "toggle", id) })
        box.addView(r)
    }

    private fun buildVacuum(box: LinearLayout) {
        val r = row()
        fun button(icon: Int, label: String, service: String): Pair<ImageView, TextView> {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(act.dp(10), 0, act.dp(10), 0)
            }
            val b = act.roundIcon(icon, 76, Skin.soft, Skin.ink) {
                val e = ha.states[id]
                // « Démarrer » reprend le nettoyage s'il est en pause.
                ha.callService("vacuum", if (service == "start" && e?.state == "paused") "start" else service, id)
            }
            col.addView(b)
            val t = act.label(15f, Skin.fg(0xCC), text = label).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(8), 0, 0) }
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

    private fun refreshClimate(e: EntityState?) {
        val a = e?.attrs
        val on = e != null && e.state != "off" && !Present.unavailable(e)
        if (on) lastMode = e?.state
        val target = a?.dbl("temperature")
        if (pendingTarget == null && dial?.dragging != true) {
            targetText(target?.let { Present.num(it) } ?: "—")
            val rg = range()
            dial?.fraction = if (target != null && rg != null) ((target - rg.first) / (rg.second - rg.first)).toFloat() else null
        }
        currentView?.text = a?.dbl("current_temperature")?.let { Present.num(it) + unit(e) } ?: "—"
        val action = a?.str("hvac_action").orEmpty()
        val cool = e?.state == "cool" || action == "cooling"
        dial?.let { d ->
            d.low = if (cool) ThermostatDial.COOL_LOW else ThermostatDial.HEAT_LOW
            d.high = if (cool) ThermostatDial.COOL_HIGH else ThermostatDial.HEAT_HIGH
            d.lit = on
        }
        // Flamme (ou flocon) vive quand l'appareil chauffe ou refroidit vraiment, estompée au repos.
        modeIcon?.let { i ->
            i.setImageResource(if (cool) R.drawable.ic_snow else R.drawable.ic_flame)
            i.imageTintList = android.content.res.ColorStateList.valueOf(when {
                action == "heating" -> 0xFFFF7A00.toInt()
                action == "cooling" -> 0xFF4DB8FF.toInt()
                else -> Skin.fg(if (on) 0x80 else 0x40)
            })
        }
    }

    private fun refresh() {
        val e = ha.states[id]
        statusView.text = Present.status(e)
        slider?.let { s ->
            if (!s.dragging) {
                s.value = when (domain) {
                    "light" -> (e?.let { Present.brightnessPct(it) } ?: 0).toFloat()
                    "fan" -> if (e?.state == "on") (e.attrs.int("percentage") ?: 100).toFloat() else 0f
                    else -> 0f
                }
            }
        }
        powerBtn?.background = oval(if (Present.active(e)) Present.accent(e) else Skin.soft)
        volume?.let { v -> if (!v.dragging) v.value = ((e?.attrs?.dbl("volume_level") ?: 0.0) * 100).toFloat() }
        playBtn?.setImageResource(if (e?.state == "playing") R.drawable.ic_pause else R.drawable.ic_play)
        cover?.let { v -> if (!v.dragging) v.value = Present.coverPosition(e).toFloat() }
        if (domain == "climate") refreshClimate(e)
        for ((m, p) in modePills) stylePill(p, e?.state == m)
        if (vacButtons.isNotEmpty()) {
            val st = e?.state
            val on = when (st) { "cleaning" -> "start"; "paused" -> "pause"; "idle" -> "stop"; "returning", "docked" -> "return_to_base"; else -> "" }
            for ((b, svc) in vacButtons) {
                val sel = svc == on
                b.background = oval(if (sel) Present.accent(e) else Skin.soft)
            }
            vacStartLabel?.text = if (st == "paused") tr("Reprendre") else tr("Démarrer")
        }
    }
}
