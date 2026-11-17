package com.homepanel.kiosk

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Séquence de lancement : après un toucher sur une scène, un court plein écran liste les appareils qu'elle règle et suit
 * leur état réel, puis « Prêt ». Seules les scènes ont une séquence : une scène dit elle-même quels appareils elle règle
 * (son attribut `entity_id`), ce qu'un script ne fait pas.
 *
 * Une scène ne dit pas l'état qu'elle veut : une ligne passe à « OK » quand l'appareil change après le toucher, et
 * « déjà fait » s'il n'a pas bougé (il était déjà comme la scène le veut). Les membres d'un groupe sont listés un à un.
 */
object Launch {
    /** Dans les exceptions ([ConfigStore.launchSkip]) : pas de séquence pour les scènes ajoutées comme tuiles dans les pièces. */
    const val TILES = "tiles"
    const val STEP_MS = 280L
    /** Lignes affichées au plus, sur deux colonnes. */
    const val MAX_ROWS = 12
    /** Attente maximale d'un changement, durée minimale d'affichage et rythme de relecture. */
    const val WAIT_MS = 6000L
    const val MIN_MS = 1400L
    const val POLL_MS = 700L
    private const val LEAD_MS = 350L
    private const val HOLD_MS = 750L
    private val ENTITY = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    fun applies(id: String) = Present.domain(id) == "scene"

    /** Appareils réglés par une scène (son attribut `entity_id`). */
    fun targets(e: EntityState?): List<String> {
        val a = e?.attrs?.optJSONArray("entity_id") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    /**
     * Modèle Home Assistant qui rend les appareils de la scène [id] (membres des groupes compris) sous la forme
     * [[entité, nom, état, empreinte], …] ; "" si l'identifiant n'est pas celui d'une scène.
     */
    fun template(id: String): String {
        if (!applies(id) || !ENTITY.matches(id)) return ""
        return "{% set ns = namespace(r=[]) %}{% for s in expand((state_attr('$id', 'entity_id') or [])|list) %}" +
            "{% set ns.r = ns.r + [[s.entity_id, s.name, s.state, (s.attributes.get('brightness')|string) ~ '/' ~ (s.attributes.get('current_position')|string) ~ '/' ~ (s.attributes.get('temperature')|string)]] %}" +
            "{% endfor %}{{ ns.r | tojson }}"
    }

    /** Un appareil suivi : son état du moment et son empreinte (état et réglages). */
    class Row(val id: String, val name: String, val state: String, val print: String)

    /** Réponse du modèle ; un appareil n'a qu'une ligne. Tolère une réponse vide ou abîmée. */
    fun parseRows(text: String?): List<Row> {
        val a = try { org.json.JSONArray(text ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        val out = LinkedHashMap<String, Row>()
        for (i in 0 until a.length()) {
            val r = a.optJSONArray(i) ?: continue
            val id = r.optString(0); if (id.isEmpty()) continue
            out[id] = Row(id, r.optString(1).ifEmpty { id }, r.optString(2), r.optString(2) + "#" + r.optString(3))
        }
        return out.values.toList()
    }

    /** Empreinte, au même format que le modèle, d'un appareil déjà suivi par la tablette : sert de point de départ pris au toucher. */
    fun print(e: EntityState): String {
        fun attr(k: String) = e.attrs.opt(k)?.takeIf { it != org.json.JSONObject.NULL }?.toString() ?: "None"
        return e.state + "#" + attr("brightness") + "/" + attr("current_position") + "/" + attr("temperature")
    }

    /** Lignes affichées : au plus [MAX_ROWS], la dernière résumant le reste (« + 3 autres »). */
    fun rows(names: List<String>): List<String> =
        if (names.size <= MAX_ROWS) names
        else names.take(MAX_ROWS - 1) + ("+ " + (names.size - MAX_ROWS + 1) + " " + tr("autres"))

    /** Mode démo (aucun vrai appareil) : moment où la ligne [index] s'allume, et durée totale pour [count] lignes. */
    fun litAt(index: Int) = LEAD_MS + index * STEP_MS
    fun total(count: Int) = LEAD_MS + maxOf(count, 2) * STEP_MS + HOLD_MS
}

/**
 * [tile] : la scène a été touchée comme appareil d'une pièce, pas comme raccourci.
 * [preview] : aperçu demandé depuis la page web ; la scène n'est pas lancée, les lignes s'allument à la cadence prévue.
 */
internal fun MainActivity.maybeLaunch(id: String, tile: Boolean = false, preview: Boolean = false) {
    if (!Launch.applies(id) || isFinishing) return
    if (!preview && (!cfg.launchSequence || (if (tile) Launch.TILES else id) in cfg.launchSkip)) return
    val e = app.ha.states[id]
    // Point de départ des appareils que la tablette suit déjà, pris tout de suite, avant qu'ils ne réagissent.
    val before = HashMap<String, String>()
    for ((k, v) in app.ha.states) before[k] = Launch.print(v)
    val started = android.os.SystemClock.uptimeMillis()

    val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    val root = FrameLayout(this).apply { setBackgroundColor((Skin.panel and 0x00FFFFFF) or 0xF2000000.toInt()); isClickable = true; setOnClickListener { d.dismiss() } }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    box.addView(label(15f, Skin.mute, true, tr(if (preview) "Aperçu" else "Lancement").uppercase(I18n.locale)).apply { letterSpacing = 0.18f; gravity = Gravity.CENTER })
    box.addView(label(40f, Skin.ink, true, e?.name ?: id).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(22)) })
    val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    box.addView(columns)
    val fill = View(this).apply { setBackgroundColor(Skin.accent); pivotX = 0f; scaleX = 0f }
    box.addView(FrameLayout(this).apply { setBackgroundColor(Skin.fg(0x26)); addView(fill, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) },
        LinearLayout.LayoutParams(dp(460), dp(4)).apply { topMargin = dp(26) })
    val status = label(17f, Skin.mute, text = " ").apply { typeface = Skin.light; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
    box.addView(status)
    root.addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    d.setContentView(root)

    /** Une ligne : l'appareil suivi (null pour « + N autres »), sa rangée et sa marque. */
    class Line(val entity: String?, val row: LinearLayout, val name: TextView, val mark: TextView) { var lit = false }
    val lines = ArrayList<Line>()
    var built = false
    var ready = false
    /** Appareils qui ont changé depuis le toucher. */
    val done = HashSet<String>()
    var last: List<Launch.Row> = emptyList()
    val first = HashMap<String, String>()

    fun build(names: List<String>, ids: List<String?>) {
        if (built || !d.isShowing) return
        built = true
        // Jusqu'à six lignes : une colonne ; au-delà, deux colonnes.
        val perColumn = if (names.size <= 6) names.size else (names.size + 1) / 2
        var column: LinearLayout? = null
        for ((i, n) in names.withIndex()) {
            if (i % maxOf(1, perColumn) == 0) {
                column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                columns.addView(column, LinearLayout.LayoutParams(dp(if (names.size <= 6) 460 else 400), WRAP_CONTENT).apply { if (i > 0) marginStart = dp(12) })
            }
            val name = label(19f, Skin.fg(0x99), text = n).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val mark = label(16f, Skin.fg(0x66), text = "· · ·").apply { typeface = Skin.light; setPadding(dp(10), 0, 0, 0) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Skin.softRect(this@maybeLaunch, dpf(14)); setPadding(dp(20), dp(12), dp(20), dp(12))
                addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(mark)
            }
            column?.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            lines.add(Line(ids.getOrNull(i), row, name, mark))
        }
    }
    fun light(l: Line) {
        if (l.lit) return
        l.lit = true
        val (bg, fg) = Skin.pill(this, true)
        l.row.background = bg; l.name.setTextColor(fg); l.mark.setTextColor(fg); l.mark.text = "OK"
        status.text = "${lines.count { it.lit }} / ${lines.size}"
    }
    fun finish() {
        if (ready || !d.isShowing) return
        ready = true
        // À la fin de l'attente : ce qui n'a pas bougé était déjà comme la scène le veut ; un appareil injoignable reste en attente.
        val byId = last.associateBy { it.id }
        var waiting = 0
        for (l in lines) if (!l.lit && l.entity != null) {
            val r = byId[l.entity]
            if (r != null && r.state != "unavailable" && r.state != "unknown") { l.mark.text = tr("déjà fait"); l.mark.setTextColor(Skin.fg(0xCC)); l.name.setTextColor(Skin.fg(0xCC)) }
            else { l.mark.text = tr("en attente"); l.mark.setTextColor(Skin.accent); waiting++ }
        }
        fill.animate().cancel(); fill.scaleX = 1f
        status.text = if (waiting == 0) tr("Prêt") else "$waiting " + tr("en attente"); status.setTextColor(Skin.accent)
        handler.postDelayed({ if (d.isShowing && !isFinishing) d.dismiss() }, if (waiting == 0) 900 else 2200)
    }
    fun update(rows: List<Launch.Row>) {
        if (!d.isShowing || ready) return
        last = rows
        if (!built) {
            if (rows.isEmpty()) return
            val shown = if (rows.size <= Launch.MAX_ROWS) rows else rows.take(Launch.MAX_ROWS - 1)
            build(Launch.rows(rows.map { it.name }), shown.map { it.id })
        }
        for (r in rows) {
            val start = before[r.id] ?: first.getOrPut(r.id) { r.print }
            if (r.print != start) done.add(r.id)
        }
        for (l in lines) if (l.entity != null && l.entity in done) light(l)
        // La ligne « + N autres » s'allume quand tous les appareils qu'elle résume ont changé.
        lines.firstOrNull { it.entity == null }?.let { more -> if (rows.drop(lines.size - 1).all { it.id in done }) light(more) }
        val now = android.os.SystemClock.uptimeMillis() - started
        if (now >= Launch.MIN_MS && lines.all { it.lit }) finish()
    }

    // Même mode plein écran que l'app : les barres du système ne réapparaissent pas.
    d.window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    d.show()
    @Suppress("DEPRECATION")
    d.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    d.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    fill.animate().scaleX(1f).setDuration(if (app.ha.demo || preview) 1500L else Launch.WAIT_MS).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()

    /** Sans vrai changement à suivre (maison fictive, aperçu) : les lignes s'allument à la cadence prévue. */
    fun play(names: List<String>, ids: List<String>) {
        build(Launch.rows(names), ids.take(Launch.MAX_ROWS))
        // La barre avance au rythme des lignes.
        val length = if (lines.isEmpty()) 1500L else Launch.total(lines.size) - 700L
        fill.animate().cancel(); fill.scaleX = 0f
        fill.animate().scaleX(1f).setDuration(length).setInterpolator(android.view.animation.LinearInterpolator()).start()
        for ((i, l) in lines.withIndex()) handler.postDelayed({ if (d.isShowing) light(l) }, Launch.litAt(i))
        handler.postDelayed({ if (d.isShowing && !ready) { ready = true; fill.animate().cancel(); fill.scaleX = 1f; status.text = tr("Prêt"); status.setTextColor(Skin.accent); handler.postDelayed({ if (d.isShowing && !isFinishing) d.dismiss() }, 900) } },
            length)
    }
    val known = Launch.targets(e)
    if (app.ha.demo) { play(known.map { t -> app.ha.states[t]?.name ?: t }, known); return }
    if (preview) {
        // Aperçu : Home Assistant donne les noms (les appareils de la scène ne sont pas tous suivis par la tablette) ; sans réponse, les identifiants.
        var shown = false
        fun show(rows: List<Launch.Row>) { if (shown) return; shown = true; if (rows.isNotEmpty()) play(rows.map { it.name }, rows.map { it.id }) else play(known.map { t -> app.ha.states[t]?.name ?: t }, known) }
        app.ha.renderTemplate(Launch.template(id)) { text -> runOnUiThread { show(Launch.parseRows(text)) } }
        handler.postDelayed({ show(emptyList()) }, 1500)
        return
    }

    /** Relit l'état des appareils tant que la séquence est ouverte. Sans rien à suivre, elle se ferme d'elle-même. */
    fun watch(template: String) {
        if (template.isEmpty()) { handler.postDelayed({ finish() }, Launch.MIN_MS); return }
        val poll = object : Runnable {
            override fun run() {
                if (!d.isShowing || ready) return
                if (android.os.SystemClock.uptimeMillis() - started >= Launch.WAIT_MS) { finish(); return }
                app.ha.renderTemplate(template) { text -> runOnUiThread { update(Launch.parseRows(text)) } }
                handler.postDelayed(this, Launch.POLL_MS)
            }
        }
        poll.run()
        // Rien de résolu après deux secondes (scène vide, Home Assistant muet) : on n'attend pas pour rien.
        handler.postDelayed({ if (!built) finish() }, 2000)
    }

    watch(Launch.template(id))
}
