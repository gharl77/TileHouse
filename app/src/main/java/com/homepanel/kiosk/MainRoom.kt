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
    backBtn.visibility = View.VISIBLE
    subtitleView.visibility = View.GONE
    titleView.text = room.name

    // Ordre d'une pièce : les caméras d'abord, puis les raccourcis, puis la carte « En lecture », puis les tuiles.
    // Les caméras ont toujours leur propre grille de grandes vignettes, en haut.
    val cams = room.entities.filter { Present.domain(it.id) == "camera" }
    if (cams.isNotEmpty()) {
        val grid = CameraGrid(this)
        for (re in cams) {
            val v = CameraTileView(this)
            v.name = Present.displayName(re, app.ha.states[re.id], room.name)
            v.setOnClickListener { openLive(re, room) }
            grid.addView(v)
            cameraTiles[re.id] = v
        }
        content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        handler.post(snapshotTick)
    }
    // Raccourcis associés à cette pièce (ceux sans pièce restent sur l'accueil).
    addShortcuts(Shortcuts.forRoom(cfg, room), dp(if (cams.isEmpty()) 4 else 12))

    // Carte « En lecture » : seulement quand un lecteur choisi de cette pièce joue.
    nowPlayingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { content.addView(it) }
    nowPlayingKey = "?"
    updateNowPlaying()

    // Les capteurs résumés dans l'en-tête (température, portes, mouvement…) n'ont pas de tuile.
    val others = room.entities.filter {
        Present.domain(it.id) != "camera" && !Present.isHeaderSensor(it.id, app.ha.states[it.id])
    }
    val groups: List<Pair<String?, List<RoomEntity>>> =
        if (cfg.groupByCategory) others.groupBy { Present.category(it.id) }.toSortedMap().map { Present.CATEGORIES[it.key] to it.value }
        else if (others.isEmpty()) emptyList() else listOf(null to others)

    for ((title, list) in groups) {
        if (title != null && (groups.size > 1 || cams.isNotEmpty())) {
            content.addView(label(17f, Skin.fg(0xE6), true, title).apply { setPadding(dp(2), dp(16), 0, dp(10)) })
        }
        val grid = TileGrid(this)
        for (re in list) {
            val t = TileView(this)
            bindTile(t, re, room)
            t.setOnClickListener { onTileTap(t, re, room) }
            // Toujours consommer l'appui long : sinon il déclencherait un toucher (allumage) par erreur.
            t.setOnLongClickListener { openDetail(re, room); true }
            grid.addView(t)
            tiles[re.id] = t to re
        }
        content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }
    if (room.entities.isEmpty()) {
        content.addView(label(16f, Skin.fg(0xB3), text = tr("Cette pièce est vide. Ajoutez des appareils dans les réglages.")).apply { setPadding(0, dp(24), 0, 0) })
    }
    updateChips(room)
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
    t.bind(Present.displayName(re, e, room.name), status, Present.active(e), Present.unavailable(e), Present.accent(e), Present.icon(re.id, e))
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
