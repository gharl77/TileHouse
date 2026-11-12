package com.homepanel.kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Minuterie de cuisson. [endAt] en temps « elapsedRealtime » ; [left] = temps restant quand elle est en pause. */
class KitchenTimer(val id: Int, var label: String, val totalMs: Long, /** Nom donné par un favori (« Pâtes »), sinon vide. */ val name: String = "") {
    /** Ce qu'on affiche pour la reconnaître : son nom, sinon « Minuterie de 10:00 ». */
    val title get() = name.ifEmpty { tr("Minuterie de") + " " + label }
    var endAt = SystemClock.elapsedRealtime() + totalMs
    var left: Long? = null
    val paused get() = left != null
    fun remaining(): Long = left ?: maxOf(0L, endAt - SystemClock.elapsedRealtime())
}

/** Minuteries en cours (gardées par l'application, même si l'écran change). */
object KitchenTimers {
    val list = ArrayList<KitchenTimer>()
    val listeners = ArrayList<() -> Unit>()
    /** Appelé quand une minuterie arrive à zéro. */
    var onDone: ((KitchenTimer) -> Unit)? = null
    private var nextId = 1
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val done = list.filter { !it.paused && it.remaining() <= 0 }
            for (t in done) { list.remove(t); onDone?.invoke(t) }
            changed()
            if (list.isNotEmpty()) main.postDelayed(this, 1000)
        }
    }

    fun start(minutes: Int, seconds: Int = 0, name: String = "") {
        val ms = (minutes * 60L + seconds) * 1000L
        if (ms <= 0) return
        list.add(KitchenTimer(nextId++, format(ms), ms, name.trim()))
        list.sortBy { it.remaining() }
        restart()
    }

    fun add(t: KitchenTimer, ms: Long) { if (t.paused) t.left = t.left!! + ms else t.endAt += ms; restart() }
    fun toggle(t: KitchenTimer) {
        if (t.paused) { t.endAt = SystemClock.elapsedRealtime() + t.left!!; t.left = null } else t.left = t.remaining()
        restart()
    }
    fun cancel(t: KitchenTimer) { list.remove(t); restart() }

    private fun restart() { main.removeCallbacks(tick); changed(); if (list.isNotEmpty()) main.postDelayed(tick, 1000) }
    private fun changed() { for (l in listeners.toList()) l() }

    fun format(ms: Long): String {
        val s = (ms + 999) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}

/** Panneau des minuteries : choix rapide de la durée et minuteries en cours. */
class TimerPanel(private val act: MainActivity) : FrameLayout(act) {
    private val running = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    /** Favoris nommés (« Pâtes · 10 min »), réglés sur la page web. */
    private val favorites = FlowLayout(act)
    private val customView = act.label(44f, Skin.ink).apply { gravity = Gravity.CENTER; typeface = Skin.light }
    private var customMin = 5
    /** Avertissement : le volume d'alarme de la tablette est à zéro, la sonnerie de fin ne s'entendrait pas. */
    private val muted = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = roundRect(0x33FF9F0A, act.dpf(14))
        setPadding(act.dp(16), act.dp(10), act.dp(10), act.dp(10))
        addView(act.label(15f, Skin.ink, text = "🔇  " + tr("Le volume d'alarme de la tablette est à zéro : la sonnerie de fin ne s'entendra pas.")), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(act.label(15f, Skin.onPrimary, true, tr("Monter le volume")).apply {
            background = roundRect(Skin.accent, act.dpf(12))
            setPadding(act.dp(14), act.dp(9), act.dp(14), act.dp(9))
            setOnClickListener { TimerAlarm.raiseVolume(act); checkVolume() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = act.dp(10) })
    }
    private fun checkVolume() { muted.visibility = if (TimerAlarm.silent(act)) View.VISIBLE else View.GONE }
    private val onChange: () -> Unit = { if (showing) renderRunning() }
    val showing get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xB3000000.toInt())
        isClickable = true
        setOnClickListener { hide() }
        elevation = act.dpf(28)
        visibility = View.GONE

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Skin.panel); cornerRadius = act.dpf(26); setStroke(act.dp(1), Skin.fg(0x26)) }
            setPadding(act.dp(28), act.dp(22), act.dp(28), act.dp(24))
            isClickable = true
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(act.label(26f, Skin.ink, true, "⏱  " + tr("Minuteries")), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(act.roundIcon(R.drawable.ic_close, 44, Skin.fg(0x1F), Skin.ink) { hide() }.apply {
            layoutParams = LinearLayout.LayoutParams(act.dp(44), act.dp(44))
        })
        panel.addView(head)
        panel.addView(muted, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(12) })
        panel.addView(favorites, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(14); marginStart = act.dp(5) })

        // Durées rapides
        val presets = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, act.dp(16), 0, 0) }
        presets.chunked(5).forEach { row ->
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in row) r.addView(chip(if (m < 60) "$m min" else "1 h") { KitchenTimers.start(m) },
                LinearLayout.LayoutParams(0, act.dp(58), 1f).apply { setMargins(act.dp(5), act.dp(5), act.dp(5), act.dp(5)) })
            repeat(5 - row.size) { r.addView(View(act), LinearLayout.LayoutParams(0, act.dp(58), 1f).apply { setMargins(act.dp(5), act.dp(5), act.dp(5), act.dp(5)) }) }
            grid.addView(r)
        }
        panel.addView(grid)

        // Durée personnalisée : − 5 min +, puis Démarrer
        val custom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(10), 0, act.dp(6)) }
        custom.addView(act.roundIcon(R.drawable.ic_minus, 56, Skin.fg(0x24), Skin.ink) { customMin = maxOf(1, customMin - if (customMin > 10) 5 else 1); renderCustom() })
        custom.addView(customView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        custom.addView(act.roundIcon(R.drawable.ic_plus, 56, Skin.fg(0x24), Skin.ink) { customMin = minOf(240, customMin + if (customMin >= 10) 5 else 1); renderCustom() })
        custom.addView(act.label(18f, Skin.onPrimary, true, tr("Démarrer")).apply {
            gravity = Gravity.CENTER
            background = roundRect(Skin.accent, act.dpf(16))
            setPadding(act.dp(26), act.dp(14), act.dp(26), act.dp(14))
            setOnClickListener { KitchenTimers.start(customMin) }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = act.dp(12) })
        panel.addView(custom)

        panel.addView(View(act).apply { setBackgroundColor(Skin.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = act.dp(10) })
        panel.addView(ScrollView(act).apply { addView(running) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val dm = act.resources.displayMetrics
        addView(panel, LayoutParams(minOf(dm.widthPixels - act.dp(64), act.dp(720)), (dm.heightPixels * 0.84f).toInt(), Gravity.CENTER))
        renderCustom()
    }

    private fun chip(text: String, onClick: () -> Unit) = act.label(19f, Skin.ink).apply {
        this.text = text; gravity = Gravity.CENTER
        typeface = Skin.medium
        background = roundRect(Skin.fg(0x24), act.dpf(16))
        setOnClickListener { onClick() }
    }

    private fun renderCustom() { customView.text = if (customMin < 60) "$customMin min" else "${customMin / 60} h ${"%02d".format(customMin % 60)}" }

    fun show() {
        if (onChange !in KitchenTimers.listeners) KitchenTimers.listeners.add(onChange)
        built = false
        checkVolume()
        renderFavorites()
        renderRunning()
        visibility = View.VISIBLE; bringToFront()
        alpha = 0f; animate().alpha(1f).setDuration(180).start()
    }

    fun hide() {
        if (!showing) return
        KitchenTimers.listeners.remove(onChange)
        animate().alpha(0f).setDuration(150).withEndAction { visibility = View.GONE }.start()
    }

    private fun renderFavorites() {
        favorites.removeAllViews()
        val presets = App.instance.config.timerPresets
        favorites.visibility = if (presets.length() == 0) View.GONE else View.VISIBLE
        for (i in 0 until presets.length()) {
            val p = presets.optJSONObject(i) ?: continue
            val name = p.optString("name").trim(); val min = p.optInt("minutes")
            if (name.isEmpty() || min <= 0) continue
            favorites.addView(act.label(17f, Skin.onPrimary).apply {
                text = "$name · " + (if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60}" else "")
                typeface = Skin.medium
                background = roundRect(Skin.accent, act.dpf(16))
                setPadding(act.dp(18), act.dp(12), act.dp(18), act.dp(12))
                setOnClickListener { KitchenTimers.start(min, name = name) }
            })
        }
    }

    private val rows = HashMap<Int, Pair<View, TextView>>()
    private var built = false

    private fun renderRunning() {
        val list = KitchenTimers.list
        // Mise à jour légère chaque seconde : on ne reconstruit que si la liste a changé.
        if (!built || rows.keys != list.map { it.id }.toSet()) {
            built = true
            running.removeAllViews(); rows.clear()
            if (list.isEmpty()) running.addView(act.label(16f, Skin.mute, text = tr("Aucune minuterie en cours. Touchez une durée pour en démarrer une.")).apply {
                gravity = Gravity.CENTER; setPadding(0, act.dp(24), 0, act.dp(8))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            for (t in list) {
                val time = act.label(40f, Skin.ink).apply { typeface = Skin.light }
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(18), act.dp(10), act.dp(12), act.dp(10))
                    background = roundRect(Skin.fg(0x14), act.dpf(18))
                }
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                col.addView(time)
                col.addView(act.label(if (t.name.isEmpty()) 14f else 16f, if (t.name.isEmpty()) Skin.mute else Skin.ink, t.name.isNotEmpty(),
                    if (t.name.isEmpty()) t.title else t.name + " · " + t.label))
                r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                r.addView(chip("+1 min") { KitchenTimers.add(t, 60_000L) }, LinearLayout.LayoutParams(act.dp(96), act.dp(52)).apply { marginEnd = act.dp(8) })
                r.addView(act.roundIcon(if (t.paused) R.drawable.ic_play else R.drawable.ic_pause, 52, Skin.fg(0x24), Skin.ink) { KitchenTimers.toggle(t); rows.clear(); renderRunning() })
                r.addView(act.roundIcon(R.drawable.ic_close, 52, 0x40FF453A, Skin.ink) { KitchenTimers.cancel(t) })
                running.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(10) })
                rows[t.id] = r to time
            }
        }
        for (t in list) rows[t.id]?.second?.apply {
            text = KitchenTimers.format(t.remaining())
            alpha = if (t.paused) 0.5f else 1f
        }
    }
}

/** Alarme de fin de minuterie : sonnerie en boucle (max 2 min) jusqu'au toucher de « Arrêter ». */
class TimerAlarm(private val ctx: Context) {
    companion object {
        private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        /** Vrai si la sonnerie de fin serait inaudible (volume d'alarme à zéro). */
        fun silent(ctx: Context) = try { audio(ctx).getStreamVolume(android.media.AudioManager.STREAM_ALARM) == 0 } catch (_: Exception) { false }
        /** Remet le volume d'alarme aux deux tiers. */
        fun raiseVolume(ctx: Context) {
            try {
                val a = audio(ctx)
                a.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxOf(1, a.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM) * 2 / 3), 0)
            } catch (_: Exception) {}
        }
    }

    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    fun start() {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare(); start()
            }
        } catch (_: Exception) {}
        main.postDelayed(autoStop, 120_000)
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
    }
}
