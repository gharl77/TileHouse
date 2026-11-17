package com.homepanel.kiosk

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Messages pour la famille : de courtes notes épinglées en haut de l'accueil (« Souper chez mamie à 18 h »),
 * écrites sur la tablette ou depuis la page web. Elles restent sur la tablette et s'effacent d'elles-mêmes
 * à l'échéance choisie (ce soir à minuit, demain soir, ou jamais).
 */
object FamilyNotes {
    /** [until] : heure d'expiration en millisecondes, 0 = jusqu'à ce qu'on l'efface. */
    class Note(val id: Long, val text: String, val author: String, val at: Long, val until: Long)

    const val MAX = 6
    const val MAX_LENGTH = 120
    /** Durées proposées : jusqu'à minuit, jusqu'à demain soir, sans fin. */
    val KEEP = listOf("today", "tomorrow", "forever")

    val listeners = ArrayList<() -> Unit>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("family_notes", Context.MODE_PRIVATE)

    fun parse(text: String?): List<Note> {
        val arr = try { JSONArray(text ?: "[]") } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("text").trim()
            if (t.isEmpty()) null else Note(o.optLong("id"), t, o.optString("author"), o.optLong("at"), o.optLong("until"))
        }
    }

    fun toJson(list: List<Note>): JSONArray = JSONArray().apply {
        for (n in list) put(JSONObject().put("id", n.id).put("text", n.text).put("author", n.author).put("at", n.at).put("until", n.until))
    }

    /** Fin de validité d'une note écrite à [now] : minuit ce soir, minuit demain soir, ou 0 (sans fin). */
    fun until(keep: String, now: Long): Long {
        if (keep == "forever") return 0L
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        c.add(Calendar.DAY_OF_YEAR, if (keep == "tomorrow") 2 else 1)
        return c.timeInMillis
    }

    /** Notes encore valables à [now], les plus récentes d'abord. */
    fun valid(list: List<Note>, now: Long) = list.filter { it.until == 0L || it.until > now }.sortedByDescending { it.at }

    /** Texte nettoyé : une seule ligne, longueur limitée. */
    fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH)

    private var demoNotes: List<Note>? = null

    fun all(ctx: Context): List<Note> {
        val now = System.currentTimeMillis()
        if (App.instance.config.demo) return valid(demoNotes ?: demo(now).also { demoNotes = it }, now)
        return valid(parse(prefs(ctx).getString("list", "[]")), now)
    }

    private fun demo(now: Long) = listOf(
        Note(2, if (I18n.english) "Dinner at grandma's at 6" else "Souper chez grand-maman à 18 h", "Alex", now - 80 * 60_000L, until("today", now)),
        Note(1, if (I18n.english) "Sam: take out the recycling" else "Sam : sortir le recyclage", "Riley", now - 20 * 3_600_000L, 0L))

    private fun save(ctx: Context, list: List<Note>) {
        if (App.instance.config.demo) demoNotes = list else prefs(ctx).edit().putString("list", toJson(list).toString()).apply()
        for (l in listeners.toList()) l()
    }

    /** Ajoute une note (la plus ancienne sort au-delà de [MAX]). Retourne false si le texte est vide. */
    fun add(ctx: Context, text: String, author: String, keep: String): Boolean {
        val t = clean(text)
        if (t.isEmpty()) return false
        val now = System.currentTimeMillis()
        val list = all(ctx)
        save(ctx, (listOf(Note(maxOf(now, (list.maxOfOrNull { it.id } ?: 0L) + 1), t, clean(author).take(24), now, until(if (keep in KEEP) keep else "today", now))) + list).take(MAX))
        return true
    }

    fun remove(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    /** Retire les notes échues ; vrai s'il y en avait (l'accueil est alors à redessiner). */
    fun prune(ctx: Context): Boolean {
        if (App.instance.config.demo) return false
        val stored = parse(prefs(ctx).getString("list", "[]"))
        val keep = valid(stored, System.currentTimeMillis())
        if (keep.size == stored.size) return false
        save(ctx, keep)
        return true
    }

    /** « 08:12 », « hier », ou la date pour une note plus ancienne. */
    fun whenText(at: Long, now: Long): String {
        fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        return when (Math.round((day(now) - day(at)) / 86_400_000.0).toInt()) {
            0 -> SimpleDateFormat("HH:mm", I18n.locale).format(Date(at))
            1 -> tr("hier")
            else -> SimpleDateFormat(if (I18n.english) "MMM d" else "d MMM", I18n.locale).format(Date(at))
        }
    }

    /** Prénoms proposés pour signer : les personnes choisies pour « Qui est à la maison ». */
    fun authors(cfg: ConfigStore, states: Map<String, EntityState>) = cfg.persons.mapNotNull { states[it] }.map { HomeText.personName(it) }.filter { it.isNotBlank() }.distinct()
}

private const val NOTE_PAPER = 0xFFFAEEDA.toInt()
private const val NOTE_INK = 0xFF412402.toInt()
private const val NOTE_MUTE = 0xFF854F0B.toInt()

/** Rangée des notes en haut de l'accueil : jusqu'à trois par ligne, et un « + » pour en écrire une. */
internal fun MainActivity.addFamilyNotes() {
    notesBox = null
    if (!cfg.shows("notes")) return
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    notesBox = box
    updateFamilyNotes()
}

internal fun MainActivity.updateFamilyNotes() {
    val box = notesBox ?: return
    box.removeAllViews()
    val notes = FamilyNotes.all(this)
    val now = System.currentTimeMillis()
    fun plus(wide: Boolean) = label(if (wide) 15f else 26f, Skin.fg(0xB3), text = if (wide) "＋  " + tr("Message pour la famille") else "＋").apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dpf(16); setColor(Color.TRANSPARENT); setStroke(dp(1), Skin.fg(0x55), dpf(6), dpf(5)) }
        if (wide) setPadding(dp(16), dp(9), dp(18), dp(9))
        isClickable = true
        contentDescription = tr("Écrire un message")
        setOnClickListener { composeFamilyNote() }
    }
    if (notes.isEmpty()) {
        box.addView(plus(true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(10) })
        return
    }
    notes.chunked(3).forEachIndexed { r, chunk ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chunk.forEachIndexed { i, n ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (Skin.gauges) Skin.card(this@updateFamilyNotes, dpf(16)) else roundRect(NOTE_PAPER, dpf(16))
                setPadding(dp(18), dp(12), dp(18), dp(12))
                isClickable = true
                setOnClickListener { showFamilyNote(n) }
            }
            card.addView(label(18f, if (Skin.gauges) Skin.accent else NOTE_INK, true, n.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            card.addView(label(13.5f, if (Skin.gauges) Skin.mute else NOTE_MUTE, text = listOf(n.author, FamilyNotes.whenText(n.at, now)).filter { it.isNotEmpty() }.joinToString("  ·  ")).apply { setPadding(0, dp(3), 0, 0) })
            // Hauteur selon le contenu : MATCH_PARENT ferait s'effondrer la rangée (voir les aperçus de l'accueil).
            row.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(12) })
        }
        if (r == 0) row.addView(plus(false), LinearLayout.LayoutParams(dp(56), MATCH_PARENT).apply { marginStart = dp(12) })
        box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(if (r == 0) 4 else 10) })
    }
    box.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
}

/** Une note en grand, avec le bouton pour l'effacer. */
internal fun MainActivity.showFamilyNote(n: FamilyNotes.Note) {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(NOTE_PAPER, dpf(22))
        setPadding(dp(34), dp(28), dp(34), dp(24))
    }
    box.addView(label(30f, NOTE_INK, true, n.text))
    val f = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d, HH:mm" else "EEEE d MMMM, HH:mm", I18n.locale)
    val ends = when {
        n.until == 0L -> tr("Reste jusqu'à ce qu'on l'efface")
        n.until <= FamilyNotes.until("today", System.currentTimeMillis()) -> tr("S'efface ce soir à minuit")
        n.until <= FamilyNotes.until("tomorrow", System.currentTimeMillis()) -> tr("S'efface demain soir à minuit")
        else -> tr("S'efface") + " " + f.format(Date(n.until - 60_000L)).substringBeforeLast(',') + " " + tr("à minuit")
    }
    box.addView(label(15f, NOTE_MUTE, text = listOf(n.author, f.format(Date(n.at)).replaceFirstChar { it.uppercase() }).filter { it.isNotEmpty() }.joinToString("  ·  ") + "\n" + ends).apply { setPadding(0, dp(12), 0, dp(20)) })
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(label(17f, NOTE_INK, true, tr("Effacer")).apply {
        setPadding(dp(22), dp(12), dp(22), dp(12)); background = roundRect(0x22412402, dpf(22)); isClickable = true
        setOnClickListener { FamilyNotes.remove(this@showFamilyNote, n.id); d.dismiss() }
    })
    buttons.addView(label(17f, NOTE_PAPER, true, tr("Fermer")).apply {
        setPadding(dp(22), dp(12), dp(22), dp(12)); background = roundRect(NOTE_INK, dpf(22)); isClickable = true
        setOnClickListener { d.dismiss() }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
    box.addView(buttons)
    showSheet(d, box)
    d.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(80), dp(640)), WindowManager.LayoutParams.WRAP_CONTENT)
}

/** Fenêtre « Nouveau message » : le texte au clavier, qui signe, et jusqu'à quand la note reste. Placée en haut, au-dessus du clavier. */
internal fun MainActivity.composeFamilyNote() {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Skin.panelBg(this@composeFamilyNote, dpf(22))
        setPadding(dp(28), dp(22), dp(28), dp(22))
    }
    box.addView(label(22f, Skin.ink, true, tr("Nouveau message")).apply { setPadding(0, 0, 0, dp(12)) })
    val input = EditText(this).apply {
        hint = tr("Souper chez mamie à 18 h")
        textSize = 20f
        setTextColor(Skin.ink); setHintTextColor(Skin.fg(0x66))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf(android.text.InputFilter.LengthFilter(FamilyNotes.MAX_LENGTH))
        maxLines = 1
        background = roundRect(Skin.fg(0x14), dpf(14))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    box.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    // Une rangée de pastilles à choix unique ; [none] permet de ne rien choisir (message non signé).
    fun pick(title: String, items: List<String>, first: Int, none: Boolean, onPick: (Int) -> Unit) {
        if (items.isEmpty()) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(14f, Skin.fg(0x99), text = title), LinearLayout.LayoutParams(dp(74), WRAP_CONTENT))
        var sel = first
        val pills = ArrayList<TextView>()
        items.forEachIndexed { i, name ->
            val p = pill(name, i == sel) {
                sel = if (none && sel == i) -1 else i
                pills.forEachIndexed { k, v -> stylePill(v, k == sel) }
                onPick(sel)
            }
            pills.add(p)
            row.addView(p, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }
    val names = FamilyNotes.authors(cfg, app.ha.states)
    var author = ""
    pick(tr("De"), names, -1, true) { author = names.getOrNull(it).orEmpty() }
    var keep = "today"
    pick(tr("Reste"), listOf(tr("Jusqu'à minuit"), tr("Jusqu'à demain soir"), tr("Jusqu'à ce qu'on l'efface")), 0, false) { keep = FamilyNotes.KEEP.getOrElse(it) { "today" } }

    fun hideKeyboard() = (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
    fun send() {
        if (!FamilyNotes.add(this, input.text.toString(), author, keep)) { input.hint = tr("Écrivez d'abord le message"); return }
        hideKeyboard(); d.dismiss()
    }
    input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { send(); true } else false }
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(label(17f, Skin.ink, true, tr("Annuler")).apply {
        setPadding(dp(22), dp(12), dp(22), dp(12)); background = roundRect(Skin.fg(0x1F), dpf(22)); isClickable = true
        setOnClickListener { hideKeyboard(); d.dismiss() }
    })
    buttons.addView(label(17f, Skin.onPrimary, true, tr("Épingler")).apply {
        setPadding(dp(24), dp(12), dp(24), dp(12)); background = roundRect(Skin.accent, dpf(22)); isClickable = true
        setOnClickListener { send() }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
    box.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

    showSheet(d, box)
    d.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(80), dp(760)), WindowManager.LayoutParams.WRAP_CONTENT)
    d.window?.attributes = d.window?.attributes?.apply { gravity = Gravity.TOP; y = dp(40) }
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    d.setOnDismissListener { hideSystemUi() }
    input.requestFocus()
    input.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(input, 0) }, 250)
}
