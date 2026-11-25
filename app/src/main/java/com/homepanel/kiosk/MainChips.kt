package com.homepanel.kiosk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Pastilles de l'en-tête et leurs fenêtres : détail d'une pastille, liste complète (« +N »), fenêtre de lecture.
 * Fonctions de [MainActivity] regroupées par fonctionnalité ; l'état reste dans la classe.
 */

/**
 * Détail d'une pastille : appareils/capteurs correspondants et leur état.
 * Sur l'accueil, seulement ceux qui sont ouverts / allumés. Une lumière ou une serrure
 * se commande directement depuis sa ligne ; la liste suit l'état en direct.
 */
internal fun MainActivity.showChipDetail(room: Room, chip: Present.Chip) {
    // Température et humidité : la courbe des dernières 24 heures plutôt qu'une liste d'un seul capteur.
    if (chip.kind == "temp" || chip.kind == "hum") { showSensorHistory(room, chip.kind); return }
    val home = room.id == "__home"
    val all = room.entities.filter { Present.chipKind(it.id, app.ha.states[it.id]) == chip.kind }
    if (all.isEmpty()) return
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(22), dp(28), dp(22))
        background = roundRect(Skin.panel, dpf(22))
        minimumWidth = dp(460)
    }
    box.addView(label(20f, Skin.ink, true, room.name).apply { setPadding(0, 0, 0, dp(12)) })
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // Nom de la pièce d'origine, utile dans la vue d'ensemble
    val roomOf = HashMap<String, Room>()
    for (r in cfg.rooms) for (e in r.entities) roomOf.putIfAbsent(e.id, r)
    // Sur l'accueil, la liste est figée à l'ouverture : une lumière qu'on vient d'éteindre
    // reste affichée (on peut la rallumer) au lieu de disparaître sous le doigt.
    // Les détecteurs de fumée se montrent tous, même au calme : on veut voir qu'ils répondent.
    val list = if (home && chip.kind != "smoke") all.filter { Present.active(app.ha.states[it.id]) } else all
    fun build() {
        rows.removeAllViews()
        if (list.isEmpty()) {
            rows.addView(label(16f, Skin.fg(0xB3), text = tr("Tout est fermé")).apply { setPadding(0, dp(8), 0, dp(8)) })
            return
        }
        for (re in list) {
            val e = app.ha.states[re.id]
            val src = roomOf[re.id] ?: room
            val on = Present.active(e)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                minimumHeight = dp(52)
            }
            row.addView(ImageView(this).apply {
                setImageResource(Present.icon(re.id, e))
                imageTintList = android.content.res.ColorStateList.valueOf(if (on) Present.accent(e) else Skin.fg(0xB3))
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
            val name = Present.displayName(re, e, src.name) + if (home) "  ·  ${src.name}" else ""
            row.addView(label(16f, Skin.ink, text = name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(label(16f, Skin.fg(0xCC), text = Present.status(e)).apply { setPadding(dp(16), 0, 0, 0) })
            val action = Present.quickAction(re.id, e)
            if (action != null) {
                row.addView(label(15f, if (on) Skin.onPrimary else Skin.ink, true, action).apply {
                    background = roundRect(if (on) Skin.accent else Skin.fg(0x33), dpf(16))
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
                row.isClickable = true
                row.setOnClickListener {
                    if (haOffline()) return@setOnClickListener
                    val now = app.ha.states[re.id]
                    if (cfg.confirmUnlock && Present.wouldUnlock(re.id, now)) {
                        confirmUnlock(Present.displayName(re, now, src.name)) { if (!haOffline()) Present.tap(app.ha, re.id, app.ha.states[re.id]) }
                        return@setOnClickListener
                    }
                    if (Present.tap(app.ha, re.id, now)) row.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            rows.addView(row)
        }
    }
    // Lumières, stores et serrures : un bouton pour tout éteindre, fermer ou verrouiller d'un coup.
    val bulkText = Present.bulkLabel(chip.kind)
    val bulk = if (bulkText == null) null else label(16f, Skin.onPrimary, true, bulkText).apply {
        gravity = Gravity.CENTER
        background = roundRect(Skin.accent, dpf(18))
        setPadding(dp(20), dp(13), dp(20), dp(13))
        isClickable = true
        setOnClickListener {
            if (haOffline()) return@setOnClickListener
            var n = 0
            for (re in list) if (Present.bulkOff(app.ha, re.id, app.ha.states[re.id])) n++
            if (n > 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    val buildRows = { build(); bulk?.visibility = if (list.any { Present.bulkNeeded(it.id, app.ha.states[it.id]) }) View.VISIBLE else View.GONE }
    buildRows()
    // Liste longue (vue d'ensemble) : hauteur limitée, défilante
    val listH = if (list.size > 8) resources.displayMetrics.heightPixels * 6 / 10 else WRAP_CONTENT
    box.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(MATCH_PARENT, listH))
    bulk?.let { box.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }) }
    d.setContentView(box)
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setDimAmount(0.6f)
    d.setCanceledOnTouchOutside(true)
    val refresh = { buildRows() }
    chipDetailRefresh = refresh
    d.setOnDismissListener { if (chipDetailRefresh === refresh) chipDetailRefresh = null }
    d.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    d.show()
    @Suppress("DEPRECATION")
    d.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    d.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/** Met à jour les pastilles seulement si leur contenu a changé. */
internal fun MainActivity.updateChips(room: Room?, only: Set<String>? = null, activeOnly: Boolean = false, extra: List<Present.Chip> = emptyList()) {
    val chips = ((if (room == null) emptyList() else Present.chips(counted(room), app.ha.states, cfg.normalOn.toSet())
        .filter { (only == null || it.kind in only) && (!activeOnly || it.level > 0 || it.kind == "light" || it.kind == "cover" || it.kind == "smoke") }) + extra)
        // Tableau de bord, sur l'accueil : les voyants des systèmes de la maison restent en place, éteints quand tout va bien.
        .let { list -> if (Skin.gauges && activeOnly && room != null) Present.standing(list, room.entities.mapNotNull { Present.chipKind(it.id, app.ha.states[it.id]) }.toSet()) else list }
    // Tableau de bord : les alertes rouges de la maison allument le voyant d'alerte principal.
    if (Skin.gauges && activeOnly && room != null) updateCaution(chips.filter { it.level == 2 }.map { it.text }.toSet())
    // Accueil par priorité : les pastilles de la maison deviennent la zone « À voir maintenant » et la ligne d'information.
    val split = priorityHome && activeOnly && room != null && attentionBox != null
    chipsView.visibility = if (split) View.GONE else View.VISIBLE
    if (chips == lastChips && chipsRoom === room && !split) return
    lastChips = chips; chipsRoom = room
    chipsView.removeAllViews()
    chipsView.maxRows = Skin.chipRows
    // Rangées limitées (Grand confort) : les alertes d'abord, pour qu'elles ne soient jamais cachées.
    val shown = if (Skin.chipRows == Int.MAX_VALUE) chips else chips.sortedByDescending { it.level }
    fun chip(c: Present.Chip, before: () -> Unit = {}) = chipView(c).apply {
        isClickable = true
        setOnClickListener { before(); if (c.entity != null) room?.find(c.entity)?.let { re -> if (!haOffline()) openDetail(re, room) } else if (c.kind == "wx") showWeatherAlerts() else if (c.kind == "wear") showWear() else if (c.kind == "tracker") Unit else if (c.kind == "battery") showBatteries() else if (c.kind == "offline") showOffline() else if (c.kind == "energy" || c.kind == "peak") showEnergy() else room?.let { showChipDetail(it, c) } }
    }
    if (split) { updatePriorityHeader(chips) { c, close -> chip(c, close) }; return }
    for (c in shown) chipsView.addView(chip(c))
    if (Skin.chipRows != Int.MAX_VALUE) {
        val more = chipView(Present.Chip(0, "+9", 0, "more")).apply {
            isClickable = true
            setOnClickListener { showAllChips(shown) { c, close -> chip(c, close) } }
        }
        chipsView.moreView = more
        chipsView.onOverflow = { n -> more.text = "+$n" }
        chipsView.addView(more)
    } else chipsView.moreView = null
    chipsView.resetOverflow()
}

/** Toutes les pastilles de l'en-tête, quand elles ne tiennent pas sur la rangée (pastille « +N »). */
internal fun MainActivity.showAllChips(chips: List<Present.Chip>, make: (Present.Chip, () -> Unit) -> View) {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(30), dp(24), dp(30), dp(28))
        background = Skin.panelBg(this@showAllChips, dpf(22))
    }
    box.addView(label(22f, Skin.ink, true, titleView.text.toString()).apply { setPadding(0, 0, 0, dp(16)) })
    val flow = FlowLayout(this)
    for (c in chips) flow.addView(make(c) { d.dismiss() })
    box.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.7f).toInt()).apply { addView(flow) })
    showSheet(d, box)
}

/** Affiche une fenêtre centrée sans faire réapparaître la barre de navigation. */
internal fun MainActivity.showSheet(d: android.app.Dialog, box: View) {
    d.setContentView(box)
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(80), dp(900)), WindowManager.LayoutParams.WRAP_CONTENT)
    d.window?.setDimAmount(0.7f)
    d.setCanceledOnTouchOutside(true)
    d.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    d.show()
    @Suppress("DEPRECATION")
    d.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    d.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/** Fenêtre de lecture (texte long, défilant). */
internal fun MainActivity.showReader(title: String, body: CharSequence, footer: ((android.app.Dialog) -> View)? = null) {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(30), dp(24), dp(30), dp(24))
        background = Skin.panelBg(this@showReader, dpf(22))
    }
    box.addView(label(22f, Skin.ink, true, title).apply { setPadding(0, 0, 0, dp(12)) })
    // La fenêtre prend la hauteur du texte (jusqu'à 70 % de l'écran), au lieu d'une grande fenêtre presque vide.
    box.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.7f).toInt()).apply {
        addView(label(16f, Skin.fg(0xE6)).apply { text = body; setLineSpacing(0f, 1.15f) })
    })
    footer?.let { box.addView(it(d), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }) }
    showSheet(d, box)
}

/**
 * Courbe des dernières 24 heures du capteur de température ou d'humidité d'une pièce (celui de sa pastille),
 * avec la valeur actuelle, le minimum et le maximum. Ouverte en touchant la pastille.
 */
internal fun MainActivity.showSensorHistory(room: Room, kind: String) {
    val re = room.entities.firstOrNull { Present.chipKind(it.id, app.ha.states[it.id]) == kind && app.ha.states[it.id]?.state?.toDoubleOrNull() != null } ?: return
    val e = app.ha.states[re.id] ?: return
    val unit = e.attrs.str("unit_of_measurement").ifEmpty { if (kind == "hum") "%" else "°" }
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(30), dp(24), dp(30), dp(24))
        background = Skin.panelBg(this@showSensorHistory, dpf(22))
    }
    box.addView(label(15f, Skin.fg(0x99), text = room.name + "  ·  " + if (kind == "hum") tr("Humidité") else tr("Température")))
    box.addView(label(44f, Skin.ink, text = Present.num(e.state.toDouble()) + " " + unit).apply { typeface = Skin.light; setPadding(0, dp(2), 0, dp(2)) })
    val caption = label(14f, Skin.fg(0xB3), text = tr("Dernières 24 heures") + " …").apply { setPadding(0, 0, 0, dp(10)) }
    box.addView(caption)
    val chart = ChartView(this)
    box.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, dp(220)))
    val to = System.currentTimeMillis(); val from = to - 86_400_000L
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    app.snapshots.restGet("/api/history/period/${Uri.encode(iso.format(Date(from)))}?filter_entity_id=${re.id}&end_time=${Uri.encode(iso.format(Date(to)))}&minimal_response&no_attributes") { text ->
        val pts = Energy.series(text)
        if (pts.isEmpty()) { caption.text = tr("Dernières 24 heures") + " · " + tr("pas d'historique"); return@restGet }
        val (lo, hi) = Energy.range(pts)
        caption.text = tr("Dernières 24 heures") + "  ·  " + tr("min") + " " + Present.num(lo) + " " + unit + "  ·  " + tr("max") + " " + Present.num(hi) + " " + unit
        val (axisLo, axisHi) = Energy.axis(lo, hi)
        chart.set(pts, false, unit, from, to, floor = axisLo, ceil = axisHi)
    }
    showSheet(d, box)
}

/**
 * La pièce telle que ses pastilles la comptent : sans les appareils décochés dans « Appareils comptés » (ni ceux décochés
 * d'office, comme la porte du réfrigérateur). L'accueil arrive ici déjà filtré ([MainActivity.homeRoom]).
 */
internal fun MainActivity.counted(room: Room): Room {
    if (room.id == "__home") return room
    val hidden = cfg.summaryHidden.toSet(); val shown = cfg.summaryShown.toSet()
    val keep = room.entities.filter { e ->
        val st = app.ha.states[e.id]
        !Choices.hidden(e.id, hidden, shown, Choices.summaryHiddenByDefault(e.id, st?.name ?: "", Present.chipKind(e.id, st)))
    }
    return if (keep.size == room.entities.size) room else Room(room.id, room.name, room.areaId).also { it.entities.addAll(keep) }
}
