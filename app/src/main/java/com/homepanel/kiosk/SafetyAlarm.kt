package com.homepanel.kiosk

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.sin

/**
 * Alarme de sécurité : quand un détecteur de fumée, de monoxyde de carbone ou de fuite d'eau des pièces se déclenche,
 * la tablette sort de veille, sonne et affiche un écran plein écran (rouge pour la fumée, bleu pour l'eau) jusqu'à ce
 * qu'on touche « Arrêter la sonnerie ». Elle appartient à l'application (pas à un écran) : elle sonne même si les
 * réglages ou une caméra sont ouverts, et pendant les heures calmes. La pastille rouge reste tant que le détecteur est en alerte.
 */
class SafetyAlarm(private val app: App) : HaClient.Listener {
    /** [kind] : "smoke", "co" ou "leak". */
    class Alert(val id: String, val kind: String, val name: String, val room: String, val at: Long) {
        val leak get() = kind == LEAK
    }

    companion object {
        const val TEST = "__test"
        const val SMOKE = "smoke"; const val CO = "co"; const val LEAK = "leak"

        /** Type d'alarme d'une entité : fumée, monoxyde ou gaz, fuite d'eau ; null si elle n'est pas surveillée. */
        fun kind(id: String, deviceClass: String): String? = if (!id.startsWith("binary_sensor.")) null else when (deviceClass) {
            "smoke" -> SMOKE
            "carbon_monoxide", "gas" -> CO
            "moisture" -> LEAK
            else -> null
        }

        /**
         * Suite d'un changement d'état : [ringing] et [silenced] sont les détecteurs qui sonnent et ceux déjà confirmés.
         * Retourne vrai si [id] doit se mettre à sonner. Un détecteur qui revient au calme est oublié des deux listes,
         * pour sonner de nouveau à la prochaine alerte.
         */
        fun step(id: String, on: Boolean, ringing: MutableSet<String>, silenced: MutableSet<String>): Boolean {
            if (!on) { ringing.remove(id); silenced.remove(id); return false }
            if (id in ringing || id in silenced) return false
            ringing.add(id)
            return true
        }
    }

    private val ringing = LinkedHashSet<String>()
    private val silenced = HashSet<String>()
    private val alerts = HashMap<String, Alert>()
    private val siren = Siren(app)

    /** Défini par l'écran principal tant qu'il est à l'écran. */
    var onChange: (() -> Unit)? = null

    /** Alertes qui sonnent encore (à confirmer). */
    val active: List<Alert> get() = ringing.mapNotNull { alerts[it] }

    init { app.ha.addListener(this) }

    private fun enabled(kind: String) = if (kind == LEAK) app.config.leakAlarm else app.config.smokeAlarm

    override fun onHaEntities(ids: Set<String>) {
        var changed = false
        for (id in ids) {
            if (!id.startsWith("binary_sensor.")) continue
            val e = app.ha.states[id]
            val kind = kind(id, e?.attrs?.str("device_class").orEmpty())
            if (e == null || kind == null) { if (id in ringing || id in silenced) { step(id, false, ringing, silenced); alerts.remove(id); changed = true }; continue }
            val wasRinging = id in ringing
            if (step(id, e.state == "on", ringing, silenced)) {
                val room = app.config.rooms.firstOrNull { it.find(id) != null }
                // Un détecteur qui n'est dans aucune pièce, ou dont l'alarme est désactivée, n'est pas surveillé.
                if (room == null || !enabled(kind)) { ringing.remove(id); continue }
                val re = room.find(id)!!
                alerts[id] = Alert(id, kind, Present.displayName(re, e, room.name), room.name, System.currentTimeMillis())
                record(alerts[id]!!)
                changed = true
            } else if (wasRinging != (id in ringing)) { alerts.remove(id); changed = true }
        }
        if (changed) apply()
    }

    private fun apply(raise: Boolean = true) {
        val list = active
        if (list.isEmpty()) siren.stop() else {
            // Une fuite seule sonne plus doucement ; la fumée ou le monoxyde prennent toujours la sirène.
            siren.start(raise, soft = list.all { it.leak })
            // Un autre écran est devant (réglages, caméra) : le tableau de bord revient pour montrer l'alarme.
            if (onChange == null) try { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        onChange?.invoke()
    }

    /** « Arrêter la sonnerie » : le son s'arrête ; ces détecteurs ne resonneront qu'après être revenus au calme. */
    fun silence() {
        silenced.addAll(ringing)
        silenced.remove(TEST)
        ringing.clear()
        alerts.remove(TEST)
        siren.stop()
        onChange?.invoke()
    }

    /**
     * Essai depuis les réglages : même écran et même sonnerie qu'une vraie alerte, sans toucher aux détecteurs.
     * [keepVolume] : ne remonte pas le volume d'alarme s'il est à zéro (essai discret, depuis l'API).
     */
    fun test(keepVolume: Boolean = false, leak: Boolean = false) {
        alerts[TEST] = Alert(TEST, if (leak) LEAK else SMOKE, tr("Essai de l'alarme"), tr("Aucun détecteur n'est en alerte"), System.currentTimeMillis())
        ringing.add(TEST)
        apply(raise = !keepVolume)
    }

    /** Trace dans le centre de notifications (sans encadré ni son : l'écran d'alarme s'en charge). */
    private fun record(a: Alert) {
        val title = when (a.kind) { CO -> tr("Monoxyde de carbone détecté"); LEAK -> tr("Fuite d'eau détectée"); else -> tr("Fumée détectée") }
        app.notices.add(JSONObject().put("title", title).put("message", a.name + " · " + a.room)
            .put("icon", if (a.leak) "💧" else "🚨").put("color", if (a.leak) "blue" else "red").put("tag", "securite_" + a.id).toString())
    }
}

/**
 * Sirène, en boucle, sur le canal des alarmes ; différente de la sonnerie des minuteries. Fumée : trois bips aigus puis
 * une pause. Fuite d'eau ([soft]) : deux bips plus graves et plus espacés.
 */
class Siren(private val ctx: Context) {
    private var track: AudioTrack? = null
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    private var playingSoft = false

    fun start(raise: Boolean = true, soft: Boolean = false) {
        // Déjà en route : on ne change que pour passer de la sonnerie douce à la sirène.
        if (track != null) { if (playingSoft && !soft) stop() else return }
        playingSoft = soft
        // Sonnerie inaudible (volume d'alarme à zéro) : une alarme de sécurité doit s'entendre.
        if (raise && TimerAlarm.silent(ctx)) TimerAlarm.raiseVolume(ctx)
        try {
            val rate = 22050
            val pcm = if (soft) softPattern(rate) else pattern(rate)
            val t = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.play()
            track = t
        } catch (_: Exception) { track = null }
        // Personne à la maison pour l'arrêter : elle se tait après dix minutes (l'écran rouge reste).
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, 10 * 60_000L)
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    /** Trois bips de 0,5 s à 3 kHz séparés de 0,5 s, puis 1,5 s de silence (le rythme des avertisseurs de fumée). */
    private fun pattern(rate: Int): ShortArray {
        val beep = rate / 2
        val out = ShortArray(rate * 4)
        for (b in 0 until 3) {
            val from = b * rate
            for (i in 0 until beep) {
                // Montée et descente courtes : pas de claquement au début et à la fin du bip.
                val env = minOf(1.0, i / 200.0, (beep - i) / 200.0)
                out[from + i] = (sin(2 * PI * 3000 * i / rate) * env * Short.MAX_VALUE * 0.9).toInt().toShort()
            }
        }
        return out
    }

    /** Deux bips de 0,3 s à 1,2 kHz, puis 2,4 s de silence. */
    private fun softPattern(rate: Int): ShortArray {
        val beep = rate * 3 / 10
        val out = ShortArray(rate * 3)
        for (b in 0 until 2) {
            val from = b * rate / 2
            for (i in 0 until beep) {
                val env = minOf(1.0, i / 300.0, (beep - i) / 300.0)
                out[from + i] = (sin(2 * PI * 1200 * i / rate) * env * Short.MAX_VALUE * 0.7).toInt().toShort()
            }
        }
        return out
    }
}

/** Écran rouge de l'alarme, par-dessus tout le reste (y compris une fenêtre déjà ouverte). */
internal fun MainActivity.syncSafety() {
    val list = app.safety.active
    if (list.isEmpty()) { safetyDialog?.dismiss(); safetyDialog = null; return }
    if (dimmed) undim()
    lastTouch = android.os.SystemClock.uptimeMillis()
    safetyDialog?.dismiss()
    val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val leakOnly = list.all { it.leak }
    val test = list.all { it.id == SafetyAlarm.TEST }
    val paper = if (leakOnly) 0xFF0B5CAD.toInt() else 0xFFC1121F.toInt()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setBackgroundColor(paper)
        setPadding(dp(60), dp(40), dp(60), dp(40))
    }
    box.addView(ImageView(this).apply {
        setImageResource(if (leakOnly) R.drawable.ic_drop else R.drawable.ic_smoke)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
    }, LinearLayout.LayoutParams(dp(120), dp(120)))
    box.addView(label(58f, Color.WHITE, true, when {
        test -> tr("Essai de l'alarme")
        leakOnly -> tr("Fuite d'eau détectée")
        list.none { it.kind == SafetyAlarm.SMOKE } && list.any { it.kind == SafetyAlarm.CO } -> tr("Monoxyde de carbone détecté")
        else -> tr("Fumée détectée")
    }).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(14)) })
    val f = SimpleDateFormat("HH:mm", I18n.locale)
    for (a in list) {
        val what = if (a.id == SafetyAlarm.TEST) a.room else a.name + "  ·  " + a.room + "  ·  " + f.format(Date(a.at))
        box.addView(label(26f, 0xF2FFFFFF.toInt(), text = what).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) })
    }
    if (!test) box.addView(label(20f, 0xD9FFFFFF.toInt(), text = if (leakOnly) tr("Allez voir, et fermez l'eau si vous le pouvez.") else tr("Si vous voyez de la fumée ou si vous vous sentez mal, sortez de la maison.")).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
    box.addView(label(26f, paper, true, tr("Arrêter la sonnerie")).apply {
        gravity = Gravity.CENTER
        background = roundRect(Color.WHITE, dpf(40))
        setPadding(dp(46), dp(22), dp(46), dp(22))
        isClickable = true
        setOnClickListener { app.safety.silence() }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(40) })
    d.setContentView(box, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    d.setCancelable(false)
    d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    d.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    d.show()
    @Suppress("DEPRECATION")
    d.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    d.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    // Pleine luminosité : l'écran doit se voir de loin.
    d.window?.attributes = d.window?.attributes?.apply { screenBrightness = 1f }
    safetyDialog = d
}
