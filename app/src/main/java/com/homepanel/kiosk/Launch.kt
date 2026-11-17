package com.homepanel.kiosk

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Séquence de lancement : après un toucher sur une scène ou un script, un court plein écran liste les appareils
 * concernés et suit leur état réel, puis « Prêt ».
 *
 * Pour un script, on lit son contenu dans Home Assistant et on en tire chaque action : les appareils nommés, mais aussi
 * ceux d'une zone, d'un appareil, d'une étiquette ou d'un palier, les membres d'un groupe, les appareils d'une scène
 * appelée et le contenu d'un script appelé. Home Assistant résout le tout dans un seul modèle, relu pendant la séquence.
 * Quand l'action dit l'état voulu (éteindre, verrouiller, fermer…), la ligne passe à « OK » dès que l'appareil y est,
 * qu'il vienne d'y arriver ou qu'il y fût déjà ; sinon elle s'allume quand l'appareil change.
 * Ce qui est écrit avec un modèle (cible calculée) ne peut pas être deviné et n'est pas listé.
 */
object Launch {
    const val STEP_MS = 280L
    /** Lignes affichées au plus, sur deux colonnes. */
    const val MAX_ROWS = 12
    /** Attente maximale (une serrure met plusieurs secondes), durée minimale d'affichage et rythme de relecture. */
    const val WAIT_MS = 10_000L
    const val MIN_MS = 1400L
    const val POLL_MS = 700L
    private const val LEAD_MS = 350L
    private const val HOLD_MS = 750L
    private val ENTITY = Regex("^[a-z_]+\\.[a-z0-9_]+$")
    private val SLUG = Regex("^[a-z0-9_]+$")

    fun applies(id: String) = Present.domain(id) == "scene" || Present.domain(id) == "script"

    /** Appareils réglés par une scène (son attribut `entity_id`) ; vide pour un script. */
    fun targets(e: EntityState?): List<String> {
        val a = e?.attrs?.optJSONArray("entity_id") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    /** Une action d'un script : le service appelé et ce qu'il vise. */
    class Call(val domain: String, val service: String, val entities: List<String>, val areas: List<String> = emptyList(),
               val devices: List<String> = emptyList(), val labels: List<String> = emptyList(), val floors: List<String> = emptyList()) {
        val empty get() = entities.isEmpty() && areas.isEmpty() && devices.isEmpty() && labels.isEmpty() && floors.isEmpty()
        /** Scripts lancés par cette action (`script.turn_on` sur un script, ou l'action `script.nom`). */
        val scripts: List<String> get() = when {
            domain != "script" -> emptyList()
            service == "turn_on" -> entities.filter { it.startsWith("script.") }
            service in setOf("turn_off", "toggle", "reload") -> emptyList()
            else -> listOf("script.$service")
        }
        val scenes: List<String> get() = if (domain == "scene" && service == "turn_on") entities.filter { it.startsWith("scene.") } else emptyList()
    }

    private fun strings(v: Any?, ok: Regex): List<String> = when (v) {
        is String -> v.split(',').map { it.trim() }.filter { ok.matches(it) }
        is org.json.JSONArray -> (0 until v.length()).flatMap { strings(v.opt(it), ok) }
        else -> emptyList()
    }

    /** Toutes les actions du contenu d'un script, à tout niveau (si/alors, choix, répétitions, en parallèle). Les cibles calculées par un modèle sont ignorées. */
    fun calls(config: Any?, out: ArrayList<Call> = ArrayList()): List<Call> {
        when (config) {
            is org.json.JSONArray -> for (i in 0 until config.length()) calls(config.opt(i), out)
            is org.json.JSONObject -> {
                val name = config.optString("action").ifEmpty { config.optString("service") }
                if ('.' in name && SLUG.matches(name.substringBefore('.')) && SLUG.matches(name.substringAfter('.'))) {
                    val places = listOfNotNull(config, config.optJSONObject("target"), config.optJSONObject("data"), config.optJSONObject("service_data"))
                    fun all(key: String, ok: Regex) = places.flatMap { strings(it.opt(key), ok) }.distinct()
                    out.add(Call(name.substringBefore('.'), name.substringAfter('.'), all("entity_id", ENTITY), all("area_id", SLUG), all("device_id", SLUG), all("label_id", SLUG), all("floor_id", SLUG)))
                }
                for (k in config.keys()) if (k != "data" && k != "service_data" && k != "target") calls(config.opt(k), out)
            }
        }
        return out
    }

    /**
     * État voulu après un service, ou "" si l'action ne le dit pas (basculer, régler une température, lancer une scène) :
     * "on" = allumé ou actif, "off" = éteint, sinon l'état exact.
     */
    fun expected(domain: String, service: String): String = when (service) {
        "turn_on" -> if (domain == "scene" || domain == "script") "" else "on"
        "turn_off" -> if (domain == "script") "" else "off"
        "lock" -> "locked"; "unlock" -> "unlocked"
        "open_cover", "open_valve" -> "open"; "close_cover", "close_valve" -> "closed"
        else -> ""
    }

    /** L'appareil est-il dans l'état voulu ? */
    fun reached(expect: String, state: String): Boolean = when (expect) {
        "" -> false
        "off" -> state == "off" || state == "standby"
        "on" -> state !in setOf("off", "standby", "unavailable", "unknown", "")
        else -> state == expect
    }

    private fun lit(items: List<String>) = org.json.JSONArray(items).toString()

    /**
     * Modèle Home Assistant qui résout les cibles des [calls] (zones, appareils, étiquettes, paliers, groupes, scènes)
     * et rend [[entité, nom, état, empreinte, état voulu], …]. Vide s'il n'y a rien à résoudre.
     */
    fun template(calls: List<Call>): String {
        val groups = calls.filter { !it.empty && it.domain != "script" }.map { c ->
            // Une action « lumière » sur une zone ne touche que les lumières de la zone.
            val match = if (c.domain in setOf("homeassistant", "scene", "group")) "." else "^" + c.domain + "\\."
            val scenes = c.scenes
            "{'e':${lit(c.entities - scenes.toSet())},'s':${lit(scenes)},'a':${lit(c.areas)},'d':${lit(c.devices)},'l':${lit(c.labels)},'f':${lit(c.floors)}," +
                "'m':${org.json.JSONObject.quote(if (scenes.isNotEmpty()) "." else match)},'x':'${expected(c.domain, c.service)}'}"
        }
        if (groups.isEmpty()) return ""
        return "{% set ns = namespace(r=[], ids=[]) %}{% for g in [" + groups.joinToString(",") + "] %}" +
            "{% set ns.ids = g.e + (g.a|map('area_entities')|sum(start=[])) + (g.d|map('device_entities')|sum(start=[])) + (g.l|map('label_entities')|sum(start=[]))" +
            " + (g.f|map('floor_areas')|sum(start=[])|map('area_entities')|sum(start=[])) %}" +
            "{% for sc in g.s %}{% set ns.ids = ns.ids + ((state_attr(sc, 'entity_id') or [])|list) %}{% endfor %}" +
            "{% for s in expand(ns.ids) if s.entity_id is match(g.m) %}" +
            "{% set ns.r = ns.r + [[s.entity_id, s.name, s.state, (s.attributes.get('brightness')|string) ~ '/' ~ (s.attributes.get('current_position')|string) ~ '/' ~ (s.attributes.get('temperature')|string), g.x]] %}" +
            "{% endfor %}{% endfor %}{{ ns.r | tojson }}"
    }

    /** Un appareil suivi : son état du moment, son empreinte (état et réglages) et l'état voulu ("" si inconnu). */
    class Row(val id: String, val name: String, val state: String, val print: String, val expect: String)

    /**
     * Réponse du modèle. Un appareil visé par plusieurs actions n'a qu'une ligne ; si elles ne veulent pas le même état
     * (branches d'un choix), l'état voulu est inconnu.
     */
    fun parseRows(text: String?): List<Row> {
        val a = try { org.json.JSONArray(text ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        val out = LinkedHashMap<String, Row>()
        for (i in 0 until a.length()) {
            val r = a.optJSONArray(i) ?: continue
            val id = r.optString(0); if (id.isEmpty()) continue
            val expect = r.optString(4)
            val old = out[id]
            out[id] = Row(id, r.optString(1).ifEmpty { id }, r.optString(2), r.optString(2) + "#" + r.optString(3), if (old != null && old.expect != expect) "" else expect)
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

internal fun MainActivity.maybeLaunch(id: String) {
    if (!cfg.launchSequence || !Launch.applies(id) || id in cfg.launchSkip || isFinishing) return
    val e = app.ha.states[id]
    // Point de départ des appareils que la tablette suit déjà, pris tout de suite, avant qu'ils ne réagissent.
    val before = HashMap<String, String>()
    for ((k, v) in app.ha.states) before[k] = Launch.print(v)
    val started = android.os.SystemClock.uptimeMillis()

    val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    val root = FrameLayout(this).apply { setBackgroundColor((Skin.panel and 0x00FFFFFF) or 0xF2000000.toInt()); isClickable = true; setOnClickListener { d.dismiss() } }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    box.addView(label(15f, Skin.mute, true, tr("Lancement").uppercase(I18n.locale)).apply { letterSpacing = 0.18f; gravity = Gravity.CENTER })
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
    /** Appareils arrivés : dans l'état voulu, ou changés depuis le toucher quand l'état voulu est inconnu. */
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
        // À la fin de l'attente : ce qui n'a pas bougé sans état voulu connu était déjà en place ; le reste est encore en route.
        val byId = last.associateBy { it.id }
        var waiting = 0
        for (l in lines) if (!l.lit && l.entity != null) {
            val r = byId[l.entity]
            if (r != null && r.expect.isEmpty() && r.state != "unavailable" && r.state != "unknown") { l.mark.text = tr("déjà fait"); l.mark.setTextColor(Skin.fg(0xCC)); l.name.setTextColor(Skin.fg(0xCC)) }
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
            if (if (r.expect.isNotEmpty()) Launch.reached(r.expect, r.state) else r.print != start) done.add(r.id)
        }
        for (l in lines) if (l.entity != null && l.entity in done) light(l)
        // La ligne « + N autres » s'allume quand tous les appareils qu'elle résume sont arrivés.
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
    fill.animate().scaleX(1f).setDuration(if (app.ha.demo) 1500L else Launch.WAIT_MS).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()

    if (app.ha.demo) {
        // Maison fictive : rien ne change vraiment, les lignes s'allument à la cadence prévue.
        val known = Launch.targets(e)
        build(Launch.rows(known.map { t -> app.ha.states[t]?.name ?: t }), known.take(Launch.MAX_ROWS))
        for ((i, l) in lines.withIndex()) handler.postDelayed({ if (d.isShowing) light(l) }, Launch.litAt(i))
        handler.postDelayed({ if (d.isShowing && !ready) { ready = true; status.text = tr("Prêt"); status.setTextColor(Skin.accent); handler.postDelayed({ if (d.isShowing && !isFinishing) d.dismiss() }, 900) } },
            if (lines.isEmpty()) 1500L else Launch.total(lines.size) - 700L)
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
        // Rien de résolu après deux secondes (modèle refusé, cibles toutes calculées) : on n'attend pas dix secondes pour rien.
        handler.postDelayed({ if (!built) finish() }, 2000)
    }

    if (Present.domain(id) == "scene") { watch(Launch.template(listOf(Launch.Call("scene", "turn_on", listOf(id))))); return }
    // Script : son contenu, puis celui des scripts qu'il lance (deux niveaux au plus, huit scripts au plus).
    val calls = ArrayList<Launch.Call>()
    val seen = HashSet<String>().apply { add(id) }
    var pending = 0
    fun load(script: String, depth: Int) {
        pending++
        app.ha.request(org.json.JSONObject().put("type", "script/config").put("entity_id", script)) { res ->
            runOnUiThread {
                val found = Launch.calls(res.optJSONObject("result")?.opt("config"))
                calls.addAll(found)
                if (depth < 2) for (n in found.flatMap { it.scripts }) if (seen.size < 8 && seen.add(n)) load(n, depth + 1)
                if (--pending == 0) watch(Launch.template(calls))
            }
        }
    }
    load(id, 0)
    // Home Assistant ne répond pas : la séquence se ferme quand même.
    handler.postDelayed({ if (pending > 0 && !built) finish() }, 3000)
}
