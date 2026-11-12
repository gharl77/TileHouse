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
    val list = if (home) all.filter { Present.active(app.ha.states[it.id]) } else all
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
                    if (Present.tap(app.ha, re.id, app.ha.states[re.id])) row.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            rows.addView(row)
        }
    }
    build()
    // Liste longue (vue d'ensemble) : hauteur limitée, défilante
    val listH = if (list.size > 8) resources.displayMetrics.heightPixels * 6 / 10 else WRAP_CONTENT
    box.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(MATCH_PARENT, listH))
    d.setContentView(box)
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setDimAmount(0.6f)
    d.setCanceledOnTouchOutside(true)
    val refresh = { build() }
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
    val chips = (if (room == null) emptyList() else Present.chips(room, app.ha.states)
        .filter { (only == null || it.kind in only) && (!activeOnly || it.level > 0 || it.kind == "light") }) + extra
    if (chips == lastChips && chipsRoom === room) return
    lastChips = chips; chipsRoom = room
    chipsView.removeAllViews()
    chipsView.maxRows = Skin.chipRows
    // Rangées limitées (Grand confort) : les alertes d'abord, pour qu'elles ne soient jamais cachées.
    val shown = if (Skin.chipRows == Int.MAX_VALUE) chips else chips.sortedByDescending { it.level }
    fun chip(c: Present.Chip, before: () -> Unit = {}) = chipView(c).apply {
        isClickable = true
        setOnClickListener { before(); if (c.kind == "wx") showWeatherAlerts() else if (c.kind == "energy" || c.kind == "peak") showEnergy() else room?.let { showChipDetail(it, c) } }
    }
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
