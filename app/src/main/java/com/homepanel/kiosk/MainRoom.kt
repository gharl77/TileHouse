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
 * Écran d'une pièce : tuiles d'appareils par catégorie, vignettes des caméras, toucher et appui long.
 * Fonctions de [MainActivity] regroupées par fonctionnalité ; l'état reste dans la classe.
 */

internal fun MainActivity.showRoom() {
    clearContent()
    val room = cfg.rooms[roomIndex]
    // Navigation latérale : le rail remplace la flèche de retour.
    backBtn.visibility = if (cfg.sideNav) View.GONE else View.VISIBLE
    updateRail()
    subtitleView.visibility = View.GONE
    titleView.text = room.name

    // Ordre d'une pièce : les caméras d'abord, puis les raccourcis, puis la carte « En lecture », puis les tuiles.
    // Les caméras ont toujours leur propre grille de grandes vignettes, en haut.
    val cams = room.entities.filter { Present.domain(it.id) == "camera" }
    // Navigation latérale : la caméra à gauche, les lecteurs à droite, sur la même rangée.
    playersBesideCamera = cfg.sideNav && cams.isNotEmpty() && room.entities.any { hasPlayerCard(it.id) }
    if (cams.isNotEmpty()) {
        val grid = CameraGrid(this)
        for (re in cams) {
            val v = CameraTileView(this)
            v.name = Present.displayName(re, app.ha.states[re.id], room.name)
            v.setOnClickListener { openLive(re, room) }
            grid.addView(v)
            cameraTiles[re.id] = v
        }
        if (playersBesideCamera) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(grid, LinearLayout.LayoutParams(dp(400), WRAP_CONTENT))
            nowPlayingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(nowPlayingBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        } else content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        handler.post(snapshotTick)
    }
    // Raccourcis associés à cette pièce (ceux sans pièce restent sur l'accueil).
    addShortcuts(Shortcuts.forRoom(cfg, room), dp(if (cams.isEmpty()) 4 else 12))

    // Cartes des lecteurs choisis dans les réglages : toujours là, sobres quand le lecteur est éteint.
    if (!playersBesideCamera) nowPlayingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { content.addView(it) }
    nowPlayingKey = "?"
    updateNowPlaying()

    // Les capteurs résumés dans l'en-tête (température, portes, mouvement…) n'ont pas de tuile.
    val others = room.entities.filter {
        // Un lecteur qui a sa carte n'a pas de tuile : c'est l'un ou l'autre.
        Present.domain(it.id) != "camera" && !Present.isHeaderSensor(it.id, app.ha.states[it.id]) && !hasPlayerCard(it.id)
    }
    val groups: List<Pair<String?, List<RoomEntity>>> =
        if (cfg.groupByCategory) others.groupBy { Present.category(it.id) }.toSortedMap().map { Present.CATEGORIES[it.key] to it.value }
        else if (others.isEmpty()) emptyList() else listOf(null to others)

    for ((title, list) in groups) {
        if (title != null && (groups.size > 1 || cams.isNotEmpty())) {
            content.addView(label(17f, Skin.fg(0xE6), true, title).apply { setPadding(dp(2), dp(16), 0, dp(10)) })
        }
        val grid = TileGrid(this)
        for (re in list) grid.addView(deviceTile(re, room))
        content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }
    if (room.entities.isEmpty()) {
        content.addView(label(16f, Skin.fg(0xB3), text = tr("Cette pièce est vide. Ajoutez des appareils dans les réglages.")).apply { setPadding(0, dp(24), 0, 0) })
    }
    updateChips(room)
}

/** Tuile d'un appareil, avec son toucher, son appui long et son glissement ; elle est suivie dans [MainActivity.tiles]. */
internal fun MainActivity.deviceTile(re: RoomEntity, room: Room): TileView {
    val t = TileView(this)
    bindTile(t, re, room)
    t.setOnClickListener { onTileTap(t, re, room) }
    // Toujours consommer l'appui long : sinon il déclencherait un toucher (allumage) par erreur.
    t.setOnLongClickListener { openDetail(re, room); true }
    // Lumière à intensité variable : glisser à gauche ou à droite sur la tuile règle la luminosité.
    if (Present.domain(re.id) == "light") {
        t.slideFrom = { app.ha.states[re.id]?.takeIf { Present.supportsBrightness(it) && !Present.unavailable(it) }?.let { Present.brightnessPct(it) ?: 0 } }
        t.onSlide = { v -> if (!haOffline()) { Present.setBrightness(app.ha, re.id, v); t.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }
    }
    tiles[re.id] = t to re
    return t
}

internal fun MainActivity.refreshSnapshots() {
    for ((id, v) in cameraTiles) {
        val w = if (v.width > 0) v.width else dp(400)
        app.snapshots.load(id, w) { bmp ->
            // La pièce a pu changer entre-temps : on libère l'image si la vignette n'est plus affichée.
            if (bmp != null && cameraTiles[id] === v && v.isAttachedToWindow) v.setBitmap(bmp) else bmp?.recycle()
        }
    }
}

internal fun MainActivity.openLive(re: RoomEntity, room: Room) {
    startActivity(Intent(this, LiveActivity::class.java)
        .putExtra(LiveActivity.EXTRA_ID, re.id)
        .putExtra(LiveActivity.EXTRA_NAME, Present.displayName(re, app.ha.states[re.id], room.name)))
}

internal fun MainActivity.bindTile(t: TileView, re: RoomEntity, room: Room) {
    val e = app.ha.states[re.id]
    val status = when {
        e != null -> Present.status(e)
        app.ha.status == HaClient.Status.CONNECTED -> tr("Introuvable")
        else -> "…"
    }
    // Lumière à intensité variable allumée : son niveau remplit la tuile.
    val level = e?.takeIf { it.domain == "light" && Present.supportsBrightness(it) }?.let { Present.brightnessPct(it) }
    t.bind(Present.displayName(re, e, room.name), status, Present.active(e), Present.unavailable(e), Present.accent(e), Present.icon(re.id, e), level)
}

/** HA injoignable : on le dit plutôt que d'ignorer le toucher sans rien montrer. */
internal fun MainActivity.haOffline(): Boolean {
    if (app.ha.demo || app.ha.status == HaClient.Status.CONNECTED) return false
    Toast.makeText(this, tr("Home Assistant ne répond pas : commande impossible pour le moment."), Toast.LENGTH_SHORT).show()
    return true
}

internal fun MainActivity.onTileTap(t: TileView, re: RoomEntity, room: Room) {
    if (Present.domain(re.id) != "camera" && haOffline()) return
    val e = app.ha.states[re.id]
    if (Present.domain(re.id) == "climate") { openDetail(re, room); return }
    // Capteur de puissance ou d'énergie : sa tuile ouvre la fenêtre Électricité, avec sa courbe des dernières 24 heures.
    if (Present.domain(re.id) == "sensor" && e?.attrs?.str("device_class").let { it == "power" || it == "energy" }) { showEnergy(re.id); return }
    if (Present.tap(app.ha, re.id, e)) t.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    else if (Present.hasDetail(re.id, e)) openDetail(re, room)
}

internal fun MainActivity.openDetail(re: RoomEntity, room: Room): Boolean {
    val e = app.ha.states[re.id]
    if (!Present.hasDetail(re.id, e)) return false
    dialog?.dismiss()
    dialog = ControlDialog(this, re.id, Present.displayName(re, e, room.name)).also { it.showImmersive() }
    return true
}
