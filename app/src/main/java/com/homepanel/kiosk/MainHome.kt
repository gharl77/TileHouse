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
 * Écran d'accueil : cartes météo et agenda, raccourcis, pièces par palier, pastilles d'information (collectes, pointes, alertes météo) et « Ce matin ».
 * Fonctions de [MainActivity] regroupées par fonctionnalité ; l'état reste dans la classe.
 */

internal fun MainActivity.showHome() {
    clearContent()
    backBtn.visibility = View.GONE
    subtitleView.visibility = View.VISIBLE
    updateHomeHeader()

    // Météo + agenda côte à côte
    val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val w = WeatherCard(this).also { weatherCard = it }
    w.update(app.ha.states[cfg.weatherEntity].takeIf { cfg.weatherEntity.isNotEmpty() })
    w.updateForecast(forecast)
    val c = CalendarCard(this).also { calendarCard = it }
    c.setEvents(if (cfg.calendars.isEmpty()) emptyList() else events)
    cards.addView(w, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(12) })
    cards.addView(c, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
    content.addView(cards, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

    // Raccourcis : scripts, scènes, boutons
    if (cfg.shortcuts.isNotEmpty()) {
        val row = FlowLayout(this)
        for (id in cfg.shortcuts) row.addView(HomeExtras.shortcut(this, id, app.ha.states[id]) { v ->
            if (haOffline()) return@shortcut
            if (Present.tap(app.ha, id, app.ha.states[id])) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                v.animate().alpha(0.5f).setDuration(120).withEndAction { v.animate().alpha(1f).setDuration(300).start() }.start()
            }
        })
        content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    // Pièces regroupées par palier (étage) de Home Assistant
    val placed = HashSet<Int>()
    for (floor in cfg.floors) {
        val idx = cfg.rooms.indices.filter { cfg.rooms[it].areaId != null && cfg.rooms[it].areaId in floor.areas }
        if (idx.isEmpty()) continue
        addFloorSection(floor.name, idx)
        placed.addAll(idx)
    }
    val rest = cfg.rooms.indices.filter { it !in placed }
    if (rest.isNotEmpty()) addFloorSection(if (placed.isEmpty()) tr("Pièces") else tr("Autres pièces"), rest)

    if (System.currentTimeMillis() - homeDataAt > 10 * 60_000L) loadHomeData()
}

internal fun MainActivity.addFloorSection(title: String, indexes: List<Int>) {
    content.addView(label(17f, Skin.fg(0xE6), true, title).apply { setPadding(dp(2), dp(22), 0, dp(10)) })
    val grid = TileGrid(this)
    for (i in indexes) {
        val t = TileView(this)
        bindRoomTile(t, cfg.rooms[i])
        t.setOnClickListener { openRoom(i) }
        grid.addView(t)
        roomTiles[i] = t
    }
    content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/** Tuile de pièce sobre : icône de la pièce et son nom. */
internal fun MainActivity.bindRoomTile(t: TileView, room: Room) {
    t.bind(room.name, "", false, false, Skin.accent, HomeText.roomIcon(room.name))
}

internal fun MainActivity.updateHomeHeader() {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    titleView.text = Themes.greeting(cfg, h)
    val p = HomeText.presence(cfg.persons, app.ha.states)
    subtitleView.text = p
    subtitleView.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
    // Vue d'ensemble : seulement ce qui est allumé, déverrouillé ou ouvert.
    val st = app.ha.states
    updateChips(wholeHouse(), only = setOf("light", "lock", "door", "window"), activeOnly = true,
        extra = HomeExtras.peakChips(peaks) + HomeExtras.weatherChips(cfg.weatherAlerts, st) + collectChips + HomeExtras.customChips(cfg.customChips, st))
}

/** Prévisions (weather.get_forecasts) et agenda (API calendrier), rafraîchis toutes les 15 min. */
internal fun MainActivity.loadHomeData() {
    if (app.ha.status != HaClient.Status.CONNECTED) return
    homeDataAt = System.currentTimeMillis()
    val we = cfg.weatherEntity
    if (we.isNotEmpty()) {
        val msg = JSONObject().put("type", "call_service").put("domain", "weather").put("service", "get_forecasts")
            .put("target", JSONObject().put("entity_id", we))
            .put("service_data", JSONObject().put("type", "daily"))
            .put("return_response", true)
        app.ha.request(msg) { res ->
            forecast = res.optJSONObject("result")?.optJSONObject("response")?.optJSONObject(we)?.optJSONArray("forecast")
            weatherCard?.updateForecast(forecast)
        }
    }
    loadCollections()
    loadPeaks()
    val cals = cfg.calendars
    if (cals.isEmpty()) { events = emptyList(); calendarCard?.setEvents(events); return }
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val startCal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val start = iso.format(startCal.time)
    val end = iso.format(Date(startCal.timeInMillis + 5 * 86_400_000L))
    val collected = ArrayList<CalendarCard.Event>()
    var remaining = cals.size
    cals.forEachIndexed { ci, cal ->
        app.snapshots.restGet("/api/calendars/$cal?start=${Uri.encode(start)}&end=${Uri.encode(end)}") { text ->
            parseEvents(text, CALENDAR_COLORS[ci % CALENDAR_COLORS.size], collected)
            if (--remaining == 0) {
                val now = System.currentTimeMillis()
                // Garde les événements à venir (et ceux de la journée entière d'aujourd'hui)
                events = collected.filter { it.allDay || it.start.time > now - 3_600_000L }.sortedBy { it.start }
                calendarCard?.setEvents(events)
            }
        }
    }
}

/** Icône « Ce matin » : pendant la plage horaire s'il y a du contenu ; point bleu tant que ce contenu n'a pas été ouvert. */
internal fun MainActivity.updateMorningButton() {
    if (!morningReady) return
    val visible = cfg.isMorningNow() && HomeExtras.hasMorning(cfg.morningSources, app.ha.states)
    // Flux de nouvelles relus au besoin (gardés 20 min) : le point revient quand il y a du nouveau.
    if (visible) for (i in 0 until cfg.morningSources.length()) cfg.morningSources.optJSONObject(i)?.takeIf { HomeExtras.isFeed(it) }?.let { s ->
        val url = s.optString("url")
        val c = Feeds.cached(url)
        if (url.isNotEmpty() && (c == null || System.currentTimeMillis() - c.at > 20 * 60_000L)) Feeds.load(url) { refreshMorningDot() }
    }
    refreshMorningDot()
}

internal fun MainActivity.refreshMorningDot() {
    val visible = cfg.isMorningNow() && HomeExtras.hasMorning(cfg.morningSources, app.ha.states)
    val read = getSharedPreferences("notices", android.content.Context.MODE_PRIVATE).getInt("morning_read", 0)
    morningButton.set(visible, visible && read != HomeExtras.morningHash(cfg.morningSources, app.ha.states))
}

/** Pointes d'électricité des 2 prochains jours (calendrier choisi). */
internal fun MainActivity.loadPeaks() {
    val cal = cfg.peakCalendar
    if (cal.isEmpty() || app.ha.demo && cal.isEmpty()) { peaks = emptyList(); return }
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val day0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val start = iso.format(day0.time); val end = iso.format(Date(day0.timeInMillis + 2 * 86_400_000L))
    app.snapshots.restGet("/api/calendars/$cal?start=${Uri.encode(start)}&end=${Uri.encode(end)}") { text ->
        val arr = try { JSONArray(text ?: "[]") } catch (_: Exception) { JSONArray() }
        peaks = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val s0 = o.optJSONObject("start")?.let { parseTime(it) } ?: return@mapNotNull null
            val e0 = o.optJSONObject("end")?.let { parseTime(it) } ?: (s0 + 4 * 3_600_000L)
            HomeExtras.Peak(s0, e0)
        }
        if (roomIndex == MainActivity.HOME) updateHomeHeader()
    }
}

/** Rappel avant une pointe : une notification (centre de notifications), une seule fois par pointe. */
internal fun MainActivity.checkPeakReminder() {
    val lead = cfg.peakReminder * 60_000L
    if (lead <= 0) return
    val now = System.currentTimeMillis()
    for (p in peaks) if (p.start > now && p.start - now <= lead && reminded.add(p.start)) {
        val f = SimpleDateFormat("HH:mm", I18n.locale)
        app.receiveNotice(JSONObject().put("title", tr("Pointe d'électricité à") + " " + f.format(Date(p.start)))
            .put("message", tr("De") + " " + f.format(Date(p.start)) + " " + tr("à") + " " + f.format(Date(p.end)) + ". " +
                tr("Baissez le chauffage et évitez les gros appareils pendant la pointe."))
            .put("icon", "⚡").put("color", "orange").put("sound", true).put("tag", "pointe_electricite").toString())
    }
}

internal fun MainActivity.showWeatherAlerts() {
    val text = HomeExtras.weatherDetails(cfg.weatherAlerts, app.ha.states)
    @Suppress("DEPRECATION")
    showReader(tr("Alertes météo"), if (text.isEmpty()) tr("Aucune alerte en vigueur.") else android.text.Html.fromHtml(text))
}

internal fun MainActivity.openMorning() {
    val p = morningPanel ?: MorningPanel(this) { markMorningRead() }.also {
        root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        morningPanel = it
    }
    p.show()
    markMorningRead()
}

/** Contenu affiché = lu (rappelé quand un flux finit de charger pendant la lecture). */
internal fun MainActivity.markMorningRead() {
    getSharedPreferences("notices", android.content.Context.MODE_PRIVATE).edit().putInt("morning_read", HomeExtras.morningHash(cfg.morningSources, app.ha.states)).apply()
    refreshMorningDot()
}

internal fun MainActivity.loadCollections() {
    val rc = cfg.recycleCalendar; val wc = cfg.wasteCalendar
    val cals = listOf(rc, wc).filter { it.isNotEmpty() }.distinct()
    if (cals.isEmpty()) { collectChips = emptyList(); return }
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val day0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val start = iso.format(day0.time)
    val end = iso.format(Date(day0.timeInMillis + 2 * 86_400_000L))
    // (type, jour 0 = aujourd'hui / 1 = demain)
    val found = HashMap<String, Int>()
    var left = cals.size
    for (cal in cals) app.snapshots.restGet("/api/calendars/$cal?start=${Uri.encode(start)}&end=${Uri.encode(end)}") { text ->
        val arr = try { JSONArray(text ?: "[]") } catch (_: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optJSONObject("start") ?: continue
            val en = o.optJSONObject("end")
            val startT = parseTime(st) ?: continue
            val endT = en?.let { parseTime(it) } ?: (startT + 3_600_000L)
            if (endT <= now) continue // collecte déjà passée aujourd'hui
            val dayIdx = ((startT - day0.timeInMillis) / 86_400_000L).toInt().coerceAtLeast(0)
            if (dayIdx > 1) continue
            // Le titre doit contenir le mot clé du type (vide = tous les événements du calendrier).
            val summary = norm(o.optString("summary"))
            for ((type, c, kw) in listOf(Triple("recycle", rc, cfg.recycleKeyword), Triple("waste", wc, cfg.wasteKeyword))) {
                if (cal == c && (kw.isEmpty() || norm(kw) in summary)) found[type] = minOf(found[type] ?: 9, dayIdx)
            }
        }
        if (--left == 0) {
            // Bac bleu pour le recyclage, bac vert pour les ordures.
            collectChips = listOf("recycle", "waste").mapNotNull { t ->
                val d = found[t] ?: return@mapNotNull null
                val what = if (t == "recycle") tr("Recyclage") else tr("Ordures")
                Present.Chip(R.drawable.ic_bin, what + " " + if (d == 0) tr("aujourd'hui") else tr("demain"), 0, "collect",
                    if (t == "recycle") 0xFF2F8CFF.toInt() else 0xFF34C759.toInt())
            }
            if (roomIndex == MainActivity.HOME) updateHomeHeader()
        }
    }
}

/** Minuscules sans accents (« Matières Recyclables » → « matieres recyclables »). */
internal fun MainActivity.norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

internal fun MainActivity.parseTime(o: JSONObject): Long? = try {
    if (o.has("dateTime")) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(o.getString("dateTime").replace(Regex("\\.\\d+"), ""))?.time
    else SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(o.getString("date"))?.time
} catch (_: Exception) { null }

internal fun MainActivity.parseEvents(text: String?, color: Int, out: MutableList<CalendarCard.Event>) {
    val arr = try { JSONArray(text ?: return) } catch (_: Exception) { return }
    val dt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val s = o.optJSONObject("start") ?: continue
        val allDay = s.has("date") && !s.has("dateTime")
        val date = try {
            if (allDay) d.parse(s.getString("date")) else dt.parse(s.getString("dateTime").replace(Regex("\\.\\d+"), ""))
        } catch (_: Exception) { null } ?: continue
        out.add(CalendarCard.Event(o.str("summary").ifEmpty { tr("(sans titre)") }, date, allDay, color))
    }
}
