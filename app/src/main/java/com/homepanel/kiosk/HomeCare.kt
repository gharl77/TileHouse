package com.homepanel.kiosk

import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Petits écrans de confort : nettoyage de l'écran, bannière d'anniversaire, résumé « Bonne nuit ». */
object HomeCare {
    const val CLEAN_SECONDS = 30
    const val HOLD_MS = 3000L

    private fun plain(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * Texte de la bannière pour un événement du calendrier des anniversaires : un titre qui parle déjà d'anniversaire
     * (« Anniversaire de Léa », « Joyeux anniversaire! ») est gardé tel quel ; un simple prénom devient « Bonne fête, Léa ».
     */
    fun birthdayText(title: String): String {
        val t = title.trim()
        val p = plain(t)
        return if (listOf("anniversaire", "fete", "birthday", "bday").any { it in p }) t else tr("Bonne fête,") + " " + t
    }

    /** Calendrier des anniversaires : celui choisi, sinon le premier calendrier de l'agenda dont le nom en parle ; "" si aucun. */
    fun birthdayCalendar(choice: String, calendars: List<String>): String = when (choice) {
        "none" -> ""
        "" -> calendars.firstOrNull { c -> listOf("anniversaire", "birthday", "fete").any { it in plain(c) } } ?: ""
        else -> choice
    }

    /** Le raccourci [id] (nommé [name]) déclenche-t-il le résumé du soir ? "" = automatique (« Bonne nuit », « Good night »), "none" = jamais. */
    fun isNightShortcut(choice: String, id: String, name: String): Boolean = when (choice) {
        "none" -> false
        // « Bonne nuit » en entier : « Soirée cinéma » (scene.movie_night) n'est pas un coucher.
        "" -> plain("$id $name").replace('_', ' ').let { t -> listOf("bonne nuit", "good night", "goodnight", "dodo", "coucher").any { it in t } }
        else -> choice == id
    }

    /** Ce qu'il reste à régler avant la nuit : lumières allumées, serrures déverrouillées, portes et fenêtres ouvertes. */
    class Night(val lights: List<RoomEntity>, val locks: List<RoomEntity>, val openings: List<RoomEntity>) {
        val clear get() = lights.isEmpty() && locks.isEmpty() && openings.isEmpty()
        val fixable get() = lights.isNotEmpty() || locks.isNotEmpty()
    }

    fun night(room: Room, states: Map<String, EntityState>): Night {
        val lights = ArrayList<RoomEntity>(); val locks = ArrayList<RoomEntity>(); val open = ArrayList<RoomEntity>()
        for (re in room.entities) {
            val e = states[re.id] ?: continue
            if (e.state == "unavailable" || e.state == "unknown") continue
            when (Present.chipKind(re.id, e)) {
                "light" -> if (e.state == "on") lights.add(re)
                "lock" -> if (Present.bulkNeeded(re.id, e)) locks.add(re)
                "door", "window" -> if (e.state == "on") open.add(re)
            }
        }
        return Night(lights, locks, open)
    }
}

// ------------------------------------------------------------ nettoyage de l'écran

/** Gèle le tactile pendant [HomeCare.CLEAN_SECONDS] secondes pour essuyer l'écran. Lancé par un appui long sur l'heure. */
internal fun MainActivity.startCleaning() {
    if (cleanBox != null) return
    val veil = FrameLayout(this).apply { setBackgroundColor(0xF0000000.toInt()); isClickable = true }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = roundRect(0xFF1C1C1E.toInt(), dpf(28))
        setPadding(dp(48), dp(34), dp(48), dp(34))
    }
    panel.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_drop)
        imageTintList = android.content.res.ColorStateList.valueOf(0xFF64D2FF.toInt())
    }, LinearLayout.LayoutParams(dp(52), dp(52)))
    panel.addView(label(26f, Color.WHITE, true, tr("Écran verrouillé")).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
    val count = label(96f, Color.WHITE, text = HomeCare.CLEAN_SECONDS.toString()).apply { typeface = Skin.light; gravity = Gravity.CENTER; includeFontPadding = false }
    panel.addView(count)
    panel.addView(label(17f, 0xB3FFFFFF.toInt(), text = tr("Essuyez sans crainte.") + "\n" + tr("Maintenez 3 secondes ici pour finir plus tôt.")).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    // Largeur fixe : en WRAP_CONTENT, les textes en pleine largeur faisaient s'effondrer le panneau.
    veil.addView(panel, FrameLayout.LayoutParams(dp(560), WRAP_CONTENT, Gravity.CENTER))
    root.addView(veil, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    cleanBox = panel; cleanVeil = veil; cleanLeft = HomeCare.CLEAN_SECONDS
    val tick = object : Runnable {
        override fun run() {
            if (cleanBox !== panel) return
            if (--cleanLeft <= 0) { stopCleaning(); return }
            count.text = cleanLeft.toString()
            lastTouch = SystemClock.uptimeMillis() // pas de veille pendant le nettoyage
            handler.postDelayed(this, 1000)
        }
    }
    handler.postDelayed(tick, 1000)
    root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

internal fun MainActivity.stopCleaning() {
    handler.removeCallbacks(cleanHold)
    cleanVeil?.let { root.removeView(it) }
    cleanBox = null; cleanVeil = null
    lastTouch = SystemClock.uptimeMillis()
}

/** Pendant le nettoyage, aucun toucher n'atteint le tableau de bord ; seul un doigt posé 3 secondes sur le panneau l'arrête. */
internal fun MainActivity.cleanTouch(ev: MotionEvent) {
    val panel = cleanBox ?: return
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val loc = IntArray(2); panel.getLocationOnScreen(loc)
            cleanDownX = ev.rawX; cleanDownY = ev.rawY
            handler.removeCallbacks(cleanHold)
            if (ev.rawX >= loc[0] && ev.rawX <= loc[0] + panel.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + panel.height) handler.postDelayed(cleanHold, HomeCare.HOLD_MS)
        }
        // Un chiffon qui passe bouge : seul un doigt immobile compte.
        MotionEvent.ACTION_MOVE -> if (abs(ev.rawX - cleanDownX) > dp(30) || abs(ev.rawY - cleanDownY) > dp(30)) handler.removeCallbacks(cleanHold)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> handler.removeCallbacks(cleanHold)
    }
}

// ------------------------------------------------------------ anniversaires

/** Bannière « Bonne fête » tout en haut de l'accueil, le jour d'un événement du calendrier des anniversaires. */
internal fun MainActivity.addBirthdayBanner() {
    birthdayBox = null
    if (!cfg.shows("birthdays")) return
    birthdayBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    updateBirthdayBanner()
}

internal fun MainActivity.updateBirthdayBanner() {
    val box = birthdayBox ?: return
    box.removeAllViews()
    for (title in birthdays.take(3)) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = if (Skin.gauges) Skin.chip(this@updateBirthdayBanner, 1).first else roundRect(0xFFFBEAF0.toInt(), dpf(16))
            setPadding(dp(18), dp(11), dp(22), dp(11))
        }
        val ink = if (Skin.gauges) Skin.accent else 0xFF4B1528.toInt()
        row.addView(label(24f, ink, text = "🎂"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) })
        row.addView(label(19f, ink, true, HomeCare.birthdayText(title)))
        box.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(10) })
    }
}

/** Événements d'aujourd'hui du calendrier des anniversaires (rechargés avec les autres données de l'accueil). */
internal fun MainActivity.loadBirthdays() {
    val cal = if (cfg.demo) "calendar.birthdays" else HomeCare.birthdayCalendar(cfg.birthdayCalendar, cfg.calendars)
    if (cal.isEmpty() || !cfg.shows("birthdays")) { if (birthdays.isNotEmpty()) { birthdays = emptyList(); updateBirthdayBanner() }; return }
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val day0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val start = iso.format(day0.time); val end = iso.format(Date(day0.timeInMillis + 86_400_000L - 1000))
    app.snapshots.restGet("/api/calendars/$cal?start=${Uri.encode(start)}&end=${Uri.encode(end)}") { text ->
        val arr = try { JSONArray(text ?: "[]") } catch (_: Exception) { JSONArray() }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day0.time)
        birthdays = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // Un événement d'une journée entière déborde sur la veille selon le fuseau : on ne garde que celui qui commence aujourd'hui.
            val s = o.optJSONObject("start")
            val day = s?.optString("date").orEmpty().ifEmpty { s?.optString("dateTime").orEmpty().take(10) }
            o.optString("summary").trim().takeIf { it.isNotEmpty() && day == today }
        }.distinct()
        updateBirthdayBanner()
    }
}

// ------------------------------------------------------------ résumé « Bonne nuit »

/** Après le raccourci « Bonne nuit » : ce qui est encore allumé, déverrouillé ou ouvert, et un bouton pour tout régler. */
internal fun MainActivity.showNightSummary(leaving: Boolean = false) {
    if (isFinishing || cleanBox != null) return
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(30), dp(24), dp(30), dp(24))
        background = Skin.panelBg(this@showNightSummary, dpf(22))
    }
    box.addView(label(24f, Skin.ink, true, if (leaving) "🚪  " + tr("Avant de partir") else "🌙  " + tr("Bonne nuit")).apply { setPadding(0, 0, 0, dp(10)) })
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.55f).toInt()).apply { addView(rows) })
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    val fix = label(17f, Skin.onPrimary, true, tr("Tout régler")).apply {
        setPadding(dp(24), dp(13), dp(24), dp(13)); background = roundRect(Skin.accent, dpf(22)); isClickable = true
    }
    buttons.addView(label(17f, Skin.ink, true, tr("Fermer")).apply {
        setPadding(dp(24), dp(13), dp(24), dp(13)); background = roundRect(Skin.fg(0x1F), dpf(22)); isClickable = true
        setOnClickListener { d.dismiss() }
    })
    buttons.addView(fix, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
    box.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

    val roomOf = HashMap<String, Room>()
    for (r in cfg.rooms) for (e in r.entities) roomOf.putIfAbsent(e.id, r)
    fun name(re: RoomEntity): String {
        val room = roomOf[re.id]
        return Present.displayName(re, app.ha.states[re.id], room?.name ?: "") + (room?.let { "  ·  " + it.name } ?: "")
    }
    fun line(icon: Int, text: String, action: String?, tint: Int, onTap: (() -> Unit)?) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(50); setPadding(0, dp(5), 0, dp(5)) }
        row.addView(ImageView(this).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(tint) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
        row.addView(label(17f, Skin.ink, text = text), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (action != null && onTap != null) row.addView(label(15f, Skin.ink, true, action).apply {
            background = roundRect(Skin.fg(0x26), dpf(16)); setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true
            setOnClickListener { if (!haOffline()) { onTap(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
        else if (action != null) row.addView(label(15f, Skin.fg(0x99), text = action).apply { setPadding(dp(14), 0, 0, 0) })
        rows.addView(row)
    }
    val closeSoon = Runnable { if (d.isShowing) d.dismiss() }
    fun build() {
        rows.removeAllViews()
        val n = HomeCare.night(homeRoom(), app.ha.states)
        fix.visibility = if (n.fixable) View.VISIBLE else View.GONE
        fix.setOnClickListener {
            if (haOffline()) return@setOnClickListener
            for (re in n.lights + n.locks) Present.bulkOff(app.ha, re.id, app.ha.states[re.id])
            fix.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        if (n.clear) {
            line(R.drawable.ic_lock, tr("Tout est en ordre : rien d'allumé, d'ouvert ni de déverrouillé."), null, 0xFF34C759.toInt(), null)
            // Rien à faire : la fenêtre se referme d'elle-même.
            handler.removeCallbacks(closeSoon); handler.postDelayed(closeSoon, 6000)
            return
        }
        handler.removeCallbacks(closeSoon)
        if (n.lights.size == 1) line(R.drawable.ic_light, name(n.lights[0]), tr("Éteindre"), 0xFFFFD60A.toInt()) { Present.bulkOff(app.ha, n.lights[0].id, app.ha.states[n.lights[0].id]) }
        else if (n.lights.isNotEmpty()) line(R.drawable.ic_light, "${n.lights.size} " + tr("lumières allumées"), tr("Éteindre"), 0xFFFFD60A.toInt()) { for (re in n.lights) Present.bulkOff(app.ha, re.id, app.ha.states[re.id]) }
        for (re in n.locks) line(R.drawable.ic_lock_open, name(re), tr("Verrouiller"), 0xFFFF9F0A.toInt()) { Present.bulkOff(app.ha, re.id, app.ha.states[re.id]) }
        for (re in n.openings) line(Present.icon(re.id, app.ha.states[re.id]), name(re), tr("ouverte"), 0xFFFF9F0A.toInt(), null)
    }
    build()
    val refresh = { build() }
    chipDetailRefresh = refresh
    d.setOnDismissListener { handler.removeCallbacks(closeSoon); if (chipDetailRefresh === refresh) chipDetailRefresh = null }
    showSheet(d, box)
    d.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(80), dp(720)), WindowManager.LayoutParams.WRAP_CONTENT)
    if (dimmed) undim()
    lastTouch = SystemClock.uptimeMillis()
}
