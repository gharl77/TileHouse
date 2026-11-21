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

    /** Durée d'affichage de la fenêtre de vérification quand personne n'y touche. */
    const val SUMMARY_SECONDS = 60
    const val GREEN = 0xFF34C759.toInt()
    const val RED = 0xFFFF6B5E.toInt()

    /** Bilan de l'en-tête : « 3 choses à régler · 1 réglée » ; « Tout est réglé » quand il ne reste rien. */
    fun summaryHeader(open: Int, done: Int): String {
        if (open == 0) return tr("Tout est réglé")
        val left = if (open == 1) tr("1 chose à régler") else "$open " + tr("choses à régler")
        val fixed = if (done == 0) "" else if (done == 1) tr("1 réglée") else "$done " + tr("réglées")
        return listOf(left, fixed).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Rappel de ce qui s'est réglé pendant que la fenêtre était ouverte : « 2 lumières éteintes · 1 serrure verrouillée ». */
    fun doneText(lights: Int, locks: Int, openings: Int): String = listOfNotNull(
        if (lights > 0) Present.plural(lights, tr("1 lumière éteinte"), tr("lumières éteintes")) else null,
        if (locks > 0) Present.plural(locks, tr("1 serrure verrouillée"), tr("serrures verrouillées")) else null,
        if (openings > 0) Present.plural(openings, tr("1 ouverture fermée"), tr("ouvertures fermées")) else null).joinToString(" · ")

    /**
     * Marque de la dernière activation d'un raccourci : l'état d'une scène ou d'un bouton est l'heure de son dernier
     * lancement ; pour un script, c'est son attribut `last_triggered`. "" si elle est inconnue.
     */
    fun activation(id: String, e: EntityState): String {
        val mark = if (Present.domain(id) == "script") e.attrs.str("last_triggered") else e.state
        return if (mark == "unknown" || mark == "unavailable" || mark == "null") "" else mark
    }

    /** Le raccourci vient-il d'être lancé ? Oui si sa marque a changé depuis la dernière connue ([prev] null = première lecture, qui ne compte pas). */
    fun activated(prev: String?, now: String) = prev != null && now.isNotEmpty() && prev != now

    /**
     * La vérification suit-elle le raccourci [id] ? Oui s'il est activé à la main ([on]), non s'il est coupé à la main ([off]) ;
     * sinon selon les anciens réglages : le raccourci de départ choisi ([depart]), ou « Bonne nuit » reconnu par son nom ([night]).
     */
    fun checkAfter(on: List<String>, off: List<String>, night: String, depart: String, id: String, name: String): Boolean = when (id) {
        in on -> true
        in off -> false
        else -> (depart.isNotEmpty() && id == depart) || isNightShortcut(night, id, name)
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
/** Anneau de compte à rebours : il se vide à mesure que le temps passe et affiche les secondes restantes. Sert aussi de bouton pour fermer. */
class CountdownRing(ctx: android.content.Context) : View(ctx) {
    private var left = 0
    private var total = 1
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val oval = android.graphics.RectF()
    fun set(left: Int, total: Int) { this.left = left; this.total = maxOf(1, total); contentDescription = tr("Fermer") + " · $left s"; invalidate() }
    override fun onDraw(c: android.graphics.Canvas) {
        val w = context.dpf(3.5f)
        val r = minOf(width, height) / 2f - w
        oval.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        paint.strokeWidth = w; paint.color = Skin.fg(0x26); c.drawOval(oval, paint)
        paint.color = Skin.accent; c.drawArc(oval, -90f, 360f * left / total, false, paint)
        text.color = Skin.ink; text.typeface = Skin.light; text.textSize = r * 0.72f
        c.drawText(left.toString(), width / 2f, height / 2f + text.textSize * 0.36f, text)
    }
}

/**
 * Fenêtre de vérification après un raccourci : ce qui reste allumé, déverrouillé ou ouvert, pièce par pièce.
 * Ce que la tablette peut régler a son bouton ; une porte ou une fenêtre ouverte est dite « à fermer à la main ».
 * Ce qui se règle pendant que la fenêtre est ouverte passe au vert ; quand plus rien ne reste, « La maison est prête ».
 * Sans toucher, elle se referme après [HomeCare.SUMMARY_SECONDS] secondes (anneau en haut à droite).
 */
internal fun MainActivity.showNightSummary(title: String = "") {
    if (isFinishing || cleanBox != null) return
    var left = HomeCare.SUMMARY_SECONDS
    val d = object : android.app.Dialog(this) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) left = HomeCare.SUMMARY_SECONDS
            return super.dispatchTouchEvent(ev)
        }
    }
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(26), dp(22), dp(26), dp(22))
        background = Skin.panelBg(this@showNightSummary, dpf(26))
    }
    // En-tête : pastille, titre, bilan, anneau de compte à rebours.
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_moon); imageTintList = android.content.res.ColorStateList.valueOf(Skin.accent)
        background = oval(Skin.fg(0x1A)); setPadding(dp(11), dp(11), dp(11), dp(11))
    }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) })
    val subtitle = label(15f, Skin.mute)
    head.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(22f, Skin.ink, true, title.ifEmpty { tr("Bonne nuit") }))
        addView(subtitle)
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    val ring = CountdownRing(this).apply { isClickable = true; setOnClickListener { d.dismiss() } }
    head.addView(ring, LinearLayout.LayoutParams(dp(46), dp(46)))
    box.addView(head)

    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.52f).toInt()).apply { addView(rows); isVerticalScrollBarEnabled = false },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val later = label(17f, Skin.fg(0xCC), true, tr("Plus tard")).apply {
        gravity = Gravity.CENTER; setPadding(dp(26), dp(14), dp(26), dp(14)); isClickable = true
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dpf(26); setColor(Color.TRANSPARENT); setStroke(dp(1), Skin.fg(0x40)) }
        setOnClickListener { d.dismiss() }
    }
    val fix = label(17f, Skin.onPrimary, true, tr("Tout régler")).apply {
        gravity = Gravity.CENTER; setPadding(dp(26), dp(14), dp(26), dp(14)); background = roundRect(Skin.accent, dpf(26)); isClickable = true
    }
    buttons.addView(later)
    buttons.addView(fix, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    box.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

    val roomOf = HashMap<String, Room>()
    for (r in cfg.rooms) for (e in r.entities) roomOf.putIfAbsent(e.id, r)
    fun nameOf(re: RoomEntity) = Present.displayName(re, app.ha.states[re.id], roomOf[re.id]?.name ?: "")
    fun roomTitle(name: String) = rows.addView(label(12.5f, Skin.mute, true, name.uppercase(I18n.locale)).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(12), 0, dp(6)) })
    /** Une carte : pastille colorée, texte, précision dessous, et le bouton de ce que la tablette peut faire. */
    fun card(icon: Int, tint: Int, text: String, detail: String, detailColor: Int, action: String?, onTap: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(62)
            background = roundRect(Skin.fg(0x12), dpf(16)); setPadding(dp(14), dp(8), dp(12), dp(8))
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(tint)
            background = oval((tint and 0x00FFFFFF) or 0x33000000); setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(17f, Skin.ink, text = text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (detail.isNotEmpty()) addView(label(14f, detailColor, text = detail).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (action != null && onTap != null) row.addView(label(15f, Skin.ink, true, action).apply {
            background = roundRect(Skin.fg(0x26), dpf(18)); setPadding(dp(16), dp(9), dp(16), dp(9)); isClickable = true
            setOnClickListener { if (!haOffline()) { onTap(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }
    fun doneLine(text: String) = rows.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = roundRect(0x2434C759, dpf(14)); setPadding(dp(14), dp(8), dp(14), dp(8))
        addView(label(15f, HomeCare.GREEN, true, "✓").apply { setPadding(0, 0, dp(10), 0) })
        addView(label(15f, HomeCare.GREEN, text = text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })

    // Ce qui était à régler à l'ouverture et ne l'est plus : gardé à l'écran, en vert, jusqu'à la fermeture.
    val seen = LinkedHashMap<String, String>()
    val done = LinkedHashMap<String, String>()
    /** Rappel de ce qui est réglé, compté d'après la liste verte du moment (une lumière rallumée n'y est plus). */
    fun doneSummary() = HomeCare.doneText(done.keys.count { seen[it] == "light" }, done.keys.count { seen[it] == "lock" }, done.keys.count { seen[it] == "open" })
    val closeSoon = Runnable { if (d.isShowing) d.dismiss() }
    fun build() {
        rows.removeAllViews()
        val n = HomeCare.night(homeRoom(), app.ha.states)
        val now = HashSet<String>()
        for (re in n.lights) { now.add(re.id); seen[re.id] = "light" }
        for (re in n.locks) { now.add(re.id); seen[re.id] = "lock" }
        for (re in n.openings) { now.add(re.id); seen[re.id] = "open" }
        for ((id, kind) in seen) if (id !in now && id !in done) {
            val re = roomOf[id]?.find(id) ?: RoomEntity(id)
            val room = roomOf[id]?.name.orEmpty()
            done[id] = listOf(room, nameOf(re)).filter { it.isNotEmpty() }.joinToString(" · ") + " · " + tr(when (kind) { "light" -> "éteinte"; "lock" -> "verrouillée"; else -> "fermée" })
        }
        // Ce qui redevient à régler (une lumière rallumée) quitte la liste verte.
        for (id in now) done.remove(id)
        val open = n.lights.size + n.locks.size + n.openings.size
        subtitle.text = HomeCare.summaryHeader(open, done.size)
        val fixable = n.lights.size + n.locks.size
        fix.visibility = if (fixable > 0) View.VISIBLE else View.GONE
        fix.text = tr("Tout régler") + " ($fixable)"
        fix.setOnClickListener {
            if (haOffline()) return@setOnClickListener
            for (re in n.lights + n.locks) Present.bulkOff(app.ha, re.id, app.ha.states[re.id])
            fix.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        if (n.clear) {
            // Plus rien à régler : un écran de fin, puis la fenêtre se referme d'elle-même.
            buttons.visibility = View.GONE
            rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(18), 0, dp(10))
                addView(label(44f, 0xFF0B0E13.toInt(), true, "✓").apply { gravity = Gravity.CENTER; background = oval(HomeCare.GREEN); includeFontPadding = false },
                    LinearLayout.LayoutParams(dp(92), dp(92)))
                addView(label(22f, Skin.ink, true, tr("La maison est prête")).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(4)) })
                addView(label(15f, Skin.mute, text = tr("Rien d'allumé, d'ouvert ni de déverrouillé")).apply { gravity = Gravity.CENTER })
                doneSummary().takeIf { it.isNotEmpty() }?.let { t ->
                    addView(label(14f, Skin.fg(0xCC), text = t).apply { gravity = Gravity.CENTER; background = roundRect(Skin.fg(0x14), dpf(14)); setPadding(dp(14), dp(7), dp(14), dp(7)) },
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
                }
            })
            handler.removeCallbacks(closeSoon); handler.postDelayed(closeSoon, 6000)
            return
        }
        buttons.visibility = View.VISIBLE
        handler.removeCallbacks(closeSoon)
        // Pièce par pièce, dans l'ordre des pièces de la tablette.
        val byRoom = LinkedHashMap<String, Triple<ArrayList<RoomEntity>, ArrayList<RoomEntity>, ArrayList<RoomEntity>>>()
        fun bucket(re: RoomEntity) = byRoom.getOrPut(roomOf[re.id]?.name ?: tr("Maison")) { Triple(ArrayList(), ArrayList(), ArrayList()) }
        for (r in cfg.rooms) for (e in r.entities) when {
            n.lights.any { it.id == e.id } && roomOf[e.id] === r -> bucket(e).first.add(e)
            n.locks.any { it.id == e.id } && roomOf[e.id] === r -> bucket(e).second.add(e)
            n.openings.any { it.id == e.id } && roomOf[e.id] === r -> bucket(e).third.add(e)
        }
        for ((room, items) in byRoom) {
            roomTitle(room)
            val (lights, locks, openings) = items
            if (lights.size == 1) card(R.drawable.ic_light, 0xFFFFD60A.toInt(), nameOf(lights[0]), tr("Allumée"), Skin.mute, tr("Éteindre")) { Present.bulkOff(app.ha, lights[0].id, app.ha.states[lights[0].id]) }
            else if (lights.isNotEmpty()) card(R.drawable.ic_light, 0xFFFFD60A.toInt(), "${lights.size} " + tr("lumières allumées"), lights.joinToString(", ") { nameOf(it) }, Skin.mute, tr("Éteindre")) {
                for (re in lights) Present.bulkOff(app.ha, re.id, app.ha.states[re.id])
            }
            for (re in locks) card(R.drawable.ic_lock_open, 0xFFFF9F0A.toInt(), nameOf(re), tr("Déverrouillée"), Skin.mute, tr("Verrouiller")) { Present.bulkOff(app.ha, re.id, app.ha.states[re.id]) }
            for (re in openings) card(Present.icon(re.id, app.ha.states[re.id]), HomeCare.RED, nameOf(re), tr("Ouverte") + " · " + tr("à fermer à la main"), HomeCare.RED, null, null)
        }
        // Jusqu'à trois lignes vertes, une par appareil réglé ; au-delà, une seule ligne qui les résume.
        if (done.isNotEmpty()) {
            rows.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
            if (done.size <= 3) for (t in done.values) doneLine(t) else doneLine(doneSummary())
        }
    }
    build()
    val refresh = { build() }
    chipDetailRefresh = refresh
    // L'anneau se vide en une minute ; un toucher dans la fenêtre repart pour une minute.
    val countdown = object : Runnable {
        override fun run() {
            if (!d.isShowing) return
            if (left <= 0) { d.dismiss(); return }
            ring.set(left, HomeCare.SUMMARY_SECONDS)
            left--
            handler.postDelayed(this, 1000)
        }
    }
    d.setOnDismissListener { handler.removeCallbacks(closeSoon); handler.removeCallbacks(countdown); if (chipDetailRefresh === refresh) chipDetailRefresh = null }
    showSheet(d, box)
    d.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(80), dp(680)), WindowManager.LayoutParams.WRAP_CONTENT)
    countdown.run()
    if (dimmed) undim()
    lastTouch = SystemClock.uptimeMillis()
}
