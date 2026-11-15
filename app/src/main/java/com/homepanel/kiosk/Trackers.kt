package com.homepanel.kiosk

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Appareils suivis : imprimante 3D, laveuse, four… Chacun est décrit par l'entité qui donne son état, et au besoin
 * celles de l'avancement, du temps restant, du nom de la tâche et d'une image. Un appareil rattaché à une pièce y a
 * sa carte (toujours visible) ; sans pièce, il fait une pastille sur l'accueil pendant qu'il travaille.
 */
object Trackers {
    class Tracker(val name: String, val icon: String, val entity: String, val progress: String, val remaining: String,
                  val title: String, val image: String, val room: String, val notify: Boolean) {
        val ids get() = listOf(entity, progress, remaining, title).filter { it.isNotEmpty() }
    }

    fun parse(arr: JSONArray?): List<Tracker> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        val entity = o.optString("entity").trim()
        if (entity.isEmpty()) null else Tracker(o.optString("name").trim().ifEmpty { entity.substringAfter('.').replace('_', ' ') }, o.optString("icon").trim(),
            entity, o.optString("progress").trim(), o.optString("remaining").trim(), o.optString("title").trim(), o.optString("image").trim(),
            o.optString("room").trim(), o.optBoolean("notify"))
    }

    /** États qui veulent dire « ne travaille pas » (imprimante, électroménagers LG, capteurs maison…). */
    private val IDLE = setOf("", "idle", "inactive", "inactif", "off", "initial", "finish", "finished", "done", "end", "ended", "completed", "complete",
        "standby", "stopped", "failed", "offline", "power_off", "none", "unknown", "unavailable", "ready", "docked", "cleaning_is_done", "terminé", "termine", "arrêt", "arret", "éteint")

    fun active(state: String?) = state != null && state.trim().lowercase() !in IDLE

    private val LABELS = mapOf("running" to "En cours", "printing" to "Impression", "pause" to "En pause", "paused" to "En pause", "prepare" to "Préparation",
        "slicing" to "Préparation", "init" to "Démarrage", "preheating" to "Préchauffage", "cooking_in_progress" to "Cuisson", "cooling" to "Refroidissement",
        "cleaning" to "Nettoyage", "washing" to "Lavage", "rinsing" to "Rinçage", "spinning" to "Essorage", "drying" to "Séchage",
        "finish" to "Terminé", "finished" to "Terminé", "done" to "Terminé", "cleaning_is_done" to "Nettoyage terminé", "failed" to "Échec", "idle" to "Inactif",
        "inactive" to "Inactif", "initial" to "Inactif", "offline" to "Hors ligne", "unavailable" to "Non disponible", "unknown" to "Inconnu")

    /** État lisible : les états courants sont traduits, les autres nettoyés (« cooking_in_progress » → « Cooking in progress »). */
    fun stateText(state: String?): String {
        val s = state?.trim().orEmpty()
        LABELS[s.lowercase()]?.let { return tr(it) }
        return s.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Minutes restantes d'après un capteur de durée (h, min, s) ou une heure de fin ; null si inconnu ou écoulé. */
    fun remainingMinutes(e: EntityState?, now: Long): Int? {
        e ?: return null
        val v = e.state.toDoubleOrNull()
        val min = if (v != null) when (e.attrs.str("unit_of_measurement").lowercase()) {
            "h" -> v * 60
            "s" -> v / 60
            "d" -> v * 1440
            else -> v
        } else {
            val t = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(e.state.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00"))?.time
            } catch (_: Exception) { null } ?: return null
            (t - now) / 60_000.0
        }
        return Math.round(min).toInt().takeIf { it > 0 }
    }

    /** « 25 min », « 1 h 15 ». */
    fun remainingText(min: Int) = if (min < 60) "$min min" else "${min / 60} h" + (if (min % 60 > 0) " %02d".format(min % 60) else "")

    fun progress(e: EntityState?): Int? = e?.state?.toDoubleOrNull()?.let { Math.round(it).toInt().coerceIn(0, 100) }

    /** Ligne d'état d'un appareil qui travaille : « Impression · 50 % · reste 1 h 15 ». */
    fun statusLine(t: Tracker, states: Map<String, EntityState>, now: Long): String {
        val parts = ArrayList<String>()
        parts.add(stateText(states[t.entity]?.state))
        if (active(states[t.entity]?.state)) {
            progress(states[t.progress])?.let { parts.add("$it %") }
            remainingMinutes(states[t.remaining], now)?.let { parts.add(tr("reste") + " " + remainingText(it)) }
        }
        return parts.filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    /** Pastilles de l'accueil : les appareils sans pièce qui travaillent en ce moment. */
    fun chips(list: List<Tracker>, states: Map<String, EntityState>, now: Long): List<Present.Chip> = list.filter { it.room.isEmpty() && active(states[it.entity]?.state) }.map { t ->
        Present.Chip(0, (if (t.icon.isEmpty()) "" else t.icon + " ") + t.name + " : " + statusLine(t, states, now).replace("  ·  ", " · "), 0, "tracker", color = 0xFF30B0C7.toInt())
    }
}

/** Cartes des appareils suivis rattachés à cette pièce, sous les lecteurs. */
internal fun MainActivity.addTrackerCards(room: Room) {
    trackerViews.clear()
    val list = Trackers.parse(cfg.trackers).filter { it.room == room.id }
    if (list.isEmpty()) return
    for (t in list) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Skin.card(this@addTrackerCards, dpf(22))
            setPadding(dp(14), dp(14), dp(22), dp(14))
        }
        var img: ImageView? = null
        if (t.image.isNotEmpty()) {
            img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = roundRect(Skin.fg(0x14), dpf(14)); clipToOutline = true }
            card.addView(img, LinearLayout.LayoutParams(dp(200), dp(130)).apply { marginEnd = dp(18) })
            if (t.image.startsWith("camera.")) img.setOnClickListener {
                startActivity(android.content.Intent(this, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_ID, t.image).putExtra(LiveActivity.EXTRA_NAME, t.name))
            }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(14f, Skin.fg(0x99), text = (if (t.icon.isEmpty()) "" else t.icon + "  ") + t.name))
        val title = label(21f, Skin.ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, dp(2)) }
        val status = label(16f, Skin.fg(0xCC))
        val bar = FrameLayout(this).apply { background = roundRect(Skin.fg(0x1F), dpf(5)) }
        val fill = View(this).apply { background = roundRect(Skin.accent, dpf(5)) }
        bar.addView(fill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        col.addView(title); col.addView(status)
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(10)).apply { topMargin = dp(12) })
        card.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        content.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        trackerViews.add(TrackerView(t, title, status, bar, fill, img))
    }
    updateTrackerCards(images = true)
}

internal class TrackerView(val t: Trackers.Tracker, val title: TextView, val status: TextView, val bar: FrameLayout, val fill: View, val image: ImageView?) {
    var bitmap: Bitmap? = null
}

internal fun MainActivity.updateTrackerCards(images: Boolean = false) {
    val st = app.ha.states
    val now = System.currentTimeMillis()
    for (v in trackerViews) {
        val on = Trackers.active(st[v.t.entity]?.state)
        val task = st[v.t.title]?.state?.takeIf { it.isNotBlank() && it != "unknown" && it != "unavailable" }
        v.title.text = if (on && task != null) task else Trackers.stateText(st[v.t.entity]?.state).ifEmpty { "…" }
        v.status.text = if (on) Trackers.statusLine(v.t, st, now) else if (task != null && st[v.t.entity]?.state?.lowercase() in setOf("finish", "finished", "done")) tr("Dernière tâche :") + " " + task else ""
        v.status.visibility = if (v.status.text.isEmpty()) View.GONE else View.VISIBLE
        val p = if (on) Trackers.progress(st[v.t.progress]) else null
        v.bar.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p != null) v.bar.post { v.fill.layoutParams = FrameLayout.LayoutParams(v.bar.width * p / 100, MATCH_PARENT); }
        val img = v.image
        if (images && img != null) app.snapshots.loadAny(v.t.image, dp(200)) { bmp ->
            if (bmp != null && img.isAttachedToWindow) { v.bitmap?.let { old -> if (old !== bmp && !old.isRecycled) old.recycle() }; v.bitmap = bmp; img.setImageBitmap(bmp) } else bmp?.recycle()
        }
    }
}

/** Fin d'une tâche (l'appareil travaillait, il ne travaille plus) : une notification, pour ceux qui l'ont demandée. */
internal fun MainActivity.checkTrackers(ids: Set<String>) {
    val list = Trackers.parse(cfg.trackers)
    if (list.none { t -> t.ids.any { it in ids } }) return
    for (t in list) if (t.entity in ids) {
        val state = app.ha.states[t.entity]?.state ?: continue
        if (state == "unavailable" || state == "unknown") continue
        val on = Trackers.active(state)
        val was = trackerActive.put(t.entity, on)
        if (was == true && !on && t.notify && state.lowercase() != "failed") app.receiveNotice(JSONObject()
            .put("title", t.name + " : " + tr("terminé")).put("message", app.ha.states[t.title]?.state?.takeIf { it.isNotBlank() && it != "unknown" }.orEmpty())
            .put("icon", t.icon.ifEmpty { "✅" }).put("color", "green").put("sound", true).put("tag", "suivi_" + t.entity).toString())
    }
    if (trackerViews.isNotEmpty()) updateTrackerCards(images = list.any { it.entity in ids })
    if (roomIndex == MainActivity.HOME) { handler.removeCallbacks(headerUpdate); handler.postDelayed(headerUpdate, 120) }
}
