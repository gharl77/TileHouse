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
    w.isClickable = true
    w.setOnClickListener { showHourlyForecast() }
    w.update(app.ha.states[cfg.weatherEntity].takeIf { cfg.weatherEntity.isNotEmpty() })
    w.updateForecast(forecast)
    val c = CalendarCard(this).also { calendarCard = it }
    c.onEvent = { ev -> showEventDetail(c, ev) }
    c.setEvents(if (cfg.calendars.isEmpty()) emptyList() else events)
    cards.addView(w, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(12) })
    cards.addView(c, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
    content.addView(cards, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

    // Raccourcis : scripts, scènes, boutons
    addShortcuts(Shortcuts.forHome(cfg), dp(14))

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

/** Rangée de raccourcis (scripts, scènes, boutons) : sur l'accueil, ou en haut de la pièce à laquelle ils sont associés. */
internal fun MainActivity.addShortcuts(ids: List<String>, top: Int) {
    if (ids.isNotEmpty()) {
        val row = FlowLayout(this)
        for (id in ids) row.addView(HomeExtras.shortcut(this, id, app.ha.states[id]) { v ->
            if (haOffline()) return@shortcut
            if (Present.tap(app.ha, id, app.ha.states[id])) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                v.animate().alpha(0.5f).setDuration(120).withEndAction { v.animate().alpha(1f).setDuration(300).start() }.start()
            }
        })
        content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top })
    }
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

/**
 * Carte « En lecture », en haut d'une pièce : un lecteur de cette pièce qui joue (ou qu'on vient de mettre en pause,
 * pour pouvoir reprendre). Titre, lecteur, précédent / pause / suivant et volume. Deux lecteurs au plus ; rien si
 * tout est arrêté. L'accueil ne la montre pas.
 */
internal fun MainActivity.updateNowPlaying() {
    val box = nowPlayingBox ?: return
    val now = SystemClock.uptimeMillis()
    val states = app.ha.states
    val room = cfg.rooms.getOrNull(roomIndex) ?: return
    val hidden = cfg.nowPlayingHidden; val shown = cfg.nowPlayingShown
    // Après une pause : la carte reste un moment (10 min par défaut), tant que dure la pause (-1), ou pas du tout (0).
    val grace = cfg.nowPlayingPause
    val players = if (!cfg.showNowPlaying) emptyList() else room.entities.mapNotNull { states[it.id] }.filter { it.domain == "media_player" && !Choices.hidden(it.id, hidden, shown, Choices.playerHiddenByDefault(it.id, it.name)) }.filter { e ->
        if (e.state == "playing") { lastPlaying[e.id] = now; true }
        // En pause depuis moins de 10 minutes : la carte reste, pour reprendre la lecture.
        else e.state == "paused" && (grace < 0 || grace > 0 && (lastPlaying[e.id]?.let { now - it < grace * 60_000L } == true ||
            HomeText.pausedRecently(e.attrs.str("media_position_updated_at"), System.currentTimeMillis(), grace * 60_000L)))
    }.take(2)
    val key = players.joinToString("|") { it.id + ":" + it.state + ":" + it.attrs.str("media_title") + ":" + it.attrs.str("media_artist") + ":" + it.attrs.str("app_name") }
    if (key == nowPlayingKey) return
    nowPlayingKey = key
    box.removeAllViews()
    for (e in players) {
        val a = e.attrs
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Skin.card(this@updateNowPlaying, dpf(22))
            setPadding(dp(18), dp(12), dp(14), dp(12))
        }
        // Une émission ou un film : icône de télé ; de la musique, une radio : note.
        val video = a.str("media_content_type").lowercase() in setOf("video", "tvshow", "movie", "episode", "channel") || a.str("device_class") == "tv"
        card.addView(label(24f, text = if (video) "📺" else "🎵"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = a.str("media_title").ifEmpty { if (e.state == "playing") tr("En lecture") else tr("En pause") }
        col.addView(label(17f, Skin.ink, true, title).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(label(14f, Skin.fg(0xB3), text = listOf(a.str("media_artist").ifEmpty { a.str("media_series_title") }, a.str("app_name"), e.name).filter { it.isNotEmpty() }.distinct().joinToString("  ·  ")).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun btn(icon: Int, big: Boolean = false, action: () -> Unit) = card.addView(roundIcon(icon, if (big) 52 else 44, if (big) Skin.primary else Skin.soft, if (big) Skin.onPrimary else Skin.ink) {
            if (!haOffline()) action()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(if (big) 52 else 44), dp(if (big) 52 else 44)).apply { marginStart = dp(8) } })
        // Volume : par pas de 5 % quand le lecteur donne son niveau, sinon par ses propres pas.
        fun volume(up: Boolean) {
            val v = a.dbl("volume_level")
            if (v != null) app.ha.callService("media_player", "volume_set", e.id, JSONObject().put("volume_level", (v + if (up) 0.05 else -0.05).coerceIn(0.0, 1.0)))
            else app.ha.callService("media_player", if (up) "volume_up" else "volume_down", e.id)
        }
        btn(R.drawable.ic_minus) { volume(false) }
        btn(R.drawable.ic_plus) { volume(true) }
        btn(R.drawable.ic_prev) { app.ha.callService("media_player", "media_previous_track", e.id) }
        btn(if (e.state == "playing") R.drawable.ic_pause else R.drawable.ic_play, big = true) { app.ha.callService("media_player", "media_play_pause", e.id) }
        btn(R.drawable.ic_next) { app.ha.callService("media_player", "media_next_track", e.id) }
        box.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    }
}

internal fun MainActivity.updateHomeHeader() {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    titleView.text = Themes.greeting(cfg, h)
    val p = HomeText.presence(cfg.persons, app.ha.states)
    subtitleView.text = p
    subtitleView.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
    // Vue d'ensemble : seulement ce qui est allumé, déverrouillé ou ouvert.
    val st = app.ha.states
    updateChips(homeRoom(), only = setOf("light", "lock", "door", "window", "cover"), activeOnly = true,
        extra = Energy.chips(cfg.energySensors, st, energyToday) + HomeExtras.peakChips(peaks) + HomeExtras.weatherChips(cfg.weatherAlerts, st) + collectChips + HomeExtras.customChips(cfg.customChips, st))
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
    loadEnergyToday()
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
        val e = o.optJSONObject("end")
        val end = try {
            if (e == null) null else if (e.has("dateTime")) dt.parse(e.getString("dateTime").replace(Regex("\\.\\d+"), "")) else d.parse(e.getString("date"))
        } catch (_: Exception) { null }
        out.add(CalendarCard.Event(o.str("summary").ifEmpty { tr("(sans titre)") }, date, allDay, color,
            end, o.str("location").trim(), o.str("description").trim()))
    }
}

/** Détail d'un événement de l'agenda : moment (avec l'heure de fin), lieu et description. */
internal fun MainActivity.showEventDetail(card: CalendarCard, ev: CalendarCard.Event) {
    val sb = StringBuilder()
    sb.append("<b>").append(android.text.Html.escapeHtml(card.whenText(ev, withEnd = true))).append("</b>")
    if (ev.allDay && ev.end != null) {
        // Un événement d'une journée entière se termine « le lendemain à minuit » : on montre le dernier jour réel.
        val days = Math.round((ev.end.time - ev.start.time) / 86_400_000.0).toInt()
        if (days > 1) sb.append("<br>").append(days).append(" ").append(tr("jours"))
    }
    if (ev.location.isNotEmpty()) sb.append("<br><br>📍 ").append(android.text.Html.escapeHtml(ev.location))
    val desc = Feeds.plain(ev.description)
    if (desc.isNotEmpty()) sb.append("<br><br>").append(android.text.Html.escapeHtml(desc))
    if (ev.location.isEmpty() && desc.isEmpty()) sb.append("<br><br><i>").append(tr("Pas d'autre détail pour cet événement.")).append("</i>")
    @Suppress("DEPRECATION")
    showReader(ev.title, android.text.Html.fromHtml(sb.toString())) { d -> reminderRow(ev, d) }
}

/** Bas de la fenêtre d'un événement : demander un rappel (notification avec son), ou voir et annuler celui qui est prévu. */
internal fun MainActivity.reminderRow(ev: CalendarCard.Event, d: android.app.Dialog): View {
    val row = FlowLayout(this)
    val key = Reminders.keyOf(ev.title, ev.start.time)
    val now = System.currentTimeMillis()
    fun pill(text: String, strong: Boolean = false, onClick: () -> Unit) = label(15f, if (strong) Skin.onPrimary else Skin.ink).apply {
        this.text = text; typeface = Skin.medium
        background = roundRect(if (strong) Skin.accent else Skin.fg(0x1F), dpf(14))
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }
    val existing = Reminders.find(this, key)
    if (existing != null) {
        val f = SimpleDateFormat(if (I18n.english) "EEE d, HH:mm" else "EEE d 'à' HH:mm", I18n.locale)
        row.addView(label(15f, Skin.fg(0xCC), text = "🔔  " + tr("Rappel prévu") + " " + f.format(Date(existing.at))).apply { setPadding(0, dp(10), dp(12), dp(10)) })
        row.addView(pill(tr("Annuler le rappel")) { Reminders.cancel(this, key); calendarCard?.setEvents(events); d.dismiss() })
        return row
    }
    // Moments proposés : seulement ceux qui sont encore à venir.
    val choices = ArrayList<Pair<String, Long>>()
    if (ev.allDay) {
        val day = Calendar.getInstance().apply { time = ev.start }
        fun at(dayOffset: Int, hour: Int) = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        choices.add(tr("La veille à 19 h") to at(-1, 19))
        choices.add(tr("Le matin à 7 h") to at(0, 7))
    } else {
        choices.add(tr("15 min avant") to ev.start.time - 15 * 60_000L)
        choices.add(tr("1 h avant") to ev.start.time - 3_600_000L)
        choices.add(tr("La veille à 19 h") to Calendar.getInstance().apply { time = ev.start; add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis)
    }
    val open = choices.filter { it.second > now + 30_000L }
    if (open.isEmpty()) return row
    row.addView(label(15f, Skin.fg(0xB3), text = "🔔  " + tr("Me le rappeler")).apply { setPadding(0, dp(10), dp(12), dp(10)) })
    for ((text, at) in open) row.addView(pill(text) {
        Reminders.set(this, Reminders.Reminder(key, ev.title, at, ev.start.time, ev.allDay))
        calendarCard?.setEvents(events)
        Toast.makeText(this, tr("Rappel enregistré"), Toast.LENGTH_SHORT).show()
        d.dismiss()
    })
    return row
}

/** Rappels d'événements arrivés à l'heure : une notification avec son, dans le centre de notifications. */
internal fun MainActivity.checkReminders() {
    val due = Reminders.takeDue(this, System.currentTimeMillis())
    if (due.isNotEmpty() && roomIndex == MainActivity.HOME) calendarCard?.setEvents(events) // la cloche de l'agenda s'efface
    for (r in due) {
        val f = SimpleDateFormat("HH:mm", I18n.locale)
        val whenText = if (r.allDay) {
            val sameDay = Calendar.getInstance().let { c -> val a = c.get(Calendar.DAY_OF_YEAR); c.timeInMillis = r.eventStart; a == c.get(Calendar.DAY_OF_YEAR) }
            if (sameDay) tr("Aujourd'hui") else tr("Demain")
        } else tr("Début à") + " " + f.format(Date(r.eventStart))
        app.receiveNotice(JSONObject().put("title", r.title).put("message", whenText + " · " + tr("rappel de l'agenda"))
            .put("icon", "🔔").put("color", "blue").put("sound", true).toString())
    }
}

/** Prévisions heure par heure (weather.get_forecasts, type « hourly »), ouvertes en touchant la carte météo. */
internal fun MainActivity.showHourlyForecast() {
    val we = cfg.weatherEntity
    if (we.isEmpty() || haOffline()) return
    val msg = JSONObject().put("type", "call_service").put("domain", "weather").put("service", "get_forecasts")
        .put("target", JSONObject().put("entity_id", we))
        .put("service_data", JSONObject().put("type", "hourly"))
        .put("return_response", true)
    app.ha.request(msg) { res ->
        val list = res.optJSONObject("result")?.optJSONObject("response")?.optJSONObject(we)?.optJSONArray("forecast")
        if (list == null || list.length() == 0) {
            Toast.makeText(this, tr("Cette source météo ne fournit pas de prévisions heure par heure."), Toast.LENGTH_LONG).show()
            return@request
        }
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(24), dp(30), dp(26))
            background = Skin.panelBg(this@showHourlyForecast, dpf(22))
        }
        box.addView(label(22f, Skin.ink, true, tr("Prochaines heures")).apply { setPadding(0, 0, 0, dp(14)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val hourFmt = SimpleDateFormat(if (I18n.english) "ha" else "H 'h'", I18n.locale)
        val dayFmt = SimpleDateFormat("EEE", I18n.locale)
        var lastDay = -1
        val sun = app.ha.states["sun.sun"]?.attrs
        fun sunTime(k: String) = try { iso.parse(sun?.str(k).orEmpty().replace(Regex("\\.\\d+"), "").replace("Z", "+00:00"))?.time } catch (_: Exception) { null }
        val rising = sunTime("next_rising"); val setting = sunTime("next_setting")
        val windUnit = app.ha.states[we]?.attrs?.str("wind_speed_unit").orEmpty().ifEmpty { "km/h" }
        for (i in 0 until minOf(24, list.length())) {
            val f = list.optJSONObject(i) ?: continue
            val t = try { iso.parse(f.str("datetime").replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")) } catch (_: Exception) { null } ?: continue
            val cal = Calendar.getInstance().apply { time = t }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = roundRect(Skin.fg(0x0F), dpf(14))
            }
            // Le nom du jour apparaît à chaque changement de date, pour savoir où commence demain.
            val day = cal.get(Calendar.DAY_OF_YEAR)
            col.addView(label(12f, Skin.accent, true, if (day != lastDay) dayFmt.format(t).replaceFirstChar { it.uppercase() } else " "))
            lastDay = day
            col.addView(label(14f, Skin.fg(0xCC), text = hourFmt.format(t).lowercase(I18n.locale)))
            col.addView(label(26f, Skin.ink, text = HomeText.emoji(f.str("condition"), HomeText.isNight(t.time, rising, setting))).apply { setPadding(0, dp(6), 0, dp(4)) })
            val temp = f.dbl("temperature")
            col.addView(label(17f, Skin.ink, true, temp?.let { Present.num(Math.round(it).toDouble()) + "°" } ?: "—"))
            // Température ressentie, seulement quand elle s'écarte nettement de la température.
            val feels = f.dbl("apparent_temperature")
            col.addView(label(12f, Skin.fg(0xB3), text = if (feels != null && temp != null && abs(feels - temp) >= 2) tr("ress.") + " " + Present.num(Math.round(feels).toDouble()) + "°" else " "))
            // Risque de précipitation en %, sinon la quantité prévue.
            val prob = f.dbl("precipitation_probability")
            val amount = f.dbl("precipitation")
            val rain = when {
                prob != null && prob >= 5 -> "💧 " + Math.round(prob) + " %"
                amount != null && amount > 0 -> "💧 " + Present.num(amount) + " mm"
                else -> " "
            }
            col.addView(label(12.5f, Skin.fg(0xB3), text = rain).apply { setPadding(0, dp(4), 0, 0) })
            col.addView(label(12f, Skin.fg(0x99), text = f.dbl("wind_speed")?.let { "💨 " + Math.round(it) } ?: " ").apply { setPadding(0, dp(2), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(dp(74), WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        box.addView(label(12.5f, Skin.fg(0x99), text = "💨 " + tr("Vent en") + " " + windUnit + "  ·  " + tr("« ress. » : température ressentie")).apply { setPadding(0, dp(12), 0, 0) })
        showSheet(d, box)
    }
}

/**
 * Fenêtre « Électricité », ouverte par la pastille d'énergie ou de pointe : la pointe en cours ou à venir,
 * les capteurs choisis et la courbe des dernières 24 heures du premier capteur.
 */
internal fun MainActivity.showEnergy(first: String? = null) {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(30), dp(24), dp(30), dp(26))
        background = Skin.panelBg(this@showEnergy, dpf(22))
    }
    box.addView(label(22f, Skin.ink, true, "⚡  " + tr("Électricité")).apply { setPadding(0, 0, 0, dp(10)) })
    // Pointes : celle en cours ou la prochaine, puis les suivantes.
    val now = System.currentTimeMillis()
    val coming = peaks.filter { it.end > now }.sortedBy { it.start }
    val hm = SimpleDateFormat("HH:mm", I18n.locale)
    val dayFmt = SimpleDateFormat(if (I18n.english) "EEEE" else "EEEE", I18n.locale)
    if (cfg.peakCalendar.isNotEmpty()) {
        if (coming.isEmpty()) box.addView(label(16f, Skin.fg(0xB3), text = tr("Aucune pointe annoncée pour les deux prochains jours.")))
        for (p in coming) {
            val running = p.start <= now
            box.addView(label(16f, Skin.ink, running, (if (running) "🔴  " + tr("Pointe en cours") + " · " + tr("fin") + " " + hm.format(Date(p.end))
                else "🔶  " + tr("Pointe") + " " + dayFmt.format(Date(p.start)) + " " + hm.format(Date(p.start)) + "–" + hm.format(Date(p.end)))).apply { setPadding(0, dp(2), 0, dp(2)) })
        }
    }
    // Ouverte depuis la tuile d'un capteur de pièce : ce capteur vient en premier (c'est lui qu'on trace).
    val ids = (listOfNotNull(first) + cfg.energySensors).distinct()
    val states = app.ha.states
    if (ids.isEmpty()) box.addView(label(15f, Skin.fg(0x99), text = tr("Ajoutez des capteurs de puissance ou d'énergie dans la page web (Accueil → Électricité) pour voir ici la consommation.")).apply { setPadding(0, dp(12), 0, 0) })
    for (id in ids) {
        val e = states[id]
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        row.addView(label(17f, Skin.fg(0xCC), text = e?.name ?: id), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(label(26f, Skin.ink, true, Energy.value(e)))
        box.addView(row)
    }
    val first = ids.firstOrNull()
    if (first != null) {
        val bars = Energy.cumulative(states[first])
        val unit = states[first]?.attrs?.str("unit_of_measurement").orEmpty()
        val caption = label(13f, Skin.fg(0x99), text = (states[first]?.name ?: first) + " · " + (if (bars) tr("par heure, dernières 24 h") else tr("dernières 24 h")) + " …").apply { setPadding(0, dp(18), 0, dp(4)) }
        val chart = ChartView(this)
        box.addView(caption)
        box.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, dp(170)))
        val to = now; val from = now - 86_400_000L
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        // Pour un compteur, on part une heure plus tôt : la première barre a besoin de la valeur d'avant.
        val start = iso.format(Date(from - if (bars) 3_600_000L else 0L))
        app.snapshots.restGet("/api/history/period/${Uri.encode(start)}?filter_entity_id=$first&end_time=${Uri.encode(iso.format(Date(to)))}&minimal_response&no_attributes") { text ->
            val pts = Energy.series(text)
            caption.text = caption.text.toString().removeSuffix(" …") + if (pts.isEmpty()) " · " + tr("pas d'historique") else ""
            chart.set(if (bars) Energy.hourly(pts, from, to) else pts.filter { it.t >= from - 3_600_000L }, bars, unit, from, to)
            // Compteur relevé une ou deux fois par jour (Hydro-Québec) : 24 heures ne montrent qu'une barre.
            // On passe aux 14 derniers jours, une barre par jour, d'après les statistiques de Home Assistant.
            if (bars && Energy.distinct(pts) <= 3) {
                val day0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                val since = day0 - 13 * 86_400_000L
                app.ha.request(JSONObject().put("type", "recorder/statistics_during_period").put("start_time", iso.format(Date(since)))
                    .put("statistic_ids", JSONArray().put(first)).put("period", "day").put("types", JSONArray().put("change"))) { res ->
                    val perDay = Energy.daily(res.optJSONObject("result")?.optJSONArray(first))
                    if (perDay.size < 2) return@request
                    val mean = perDay.sumOf { it.v } / perDay.size
                    caption.text = (states[first]?.name ?: first) + " · " + tr("par jour") + " (" + perDay.size + " " + tr("jours") + ") · " + tr("moyenne") + " " + Energy.format(mean, unit)
                    chart.set(perDay, true, unit, perDay.first().t, perDay.last().t + 86_400_000L, days = true)
                }
            }
        }
    }
    showSheet(d, box)
}

/** Consommation récente du premier capteur d'énergie (pour la pastille de l'accueil, quand c'est un compteur cumulatif). */
internal fun MainActivity.loadEnergyToday() {
    // Demandé même si l'état du capteur n'est pas encore arrivé : la pastille décide ensuite s'il s'agit d'un compteur.
    val id = cfg.energySensors.firstOrNull()
    if (id == null) { energyToday = null; return }
    val midnight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val start = iso.format(Date(midnight - 2 * 86_400_000L))
    // Sans heure de fin, Home Assistant ne renvoie qu'une journée à partir du début.
    app.snapshots.restGet("/api/history/period/${Uri.encode(start)}?filter_entity_id=$id&end_time=${Uri.encode(iso.format(Date()))}&minimal_response&no_attributes") { text ->
        if (id != cfg.energySensors.firstOrNull()) return@restGet
        val recent = Energy.recent(Energy.series(text), midnight)
        energyToday = recent
        if (roomIndex == MainActivity.HOME) updateHomeHeader()
        // Compteur relevé une fois par jour : la consommation d'aujourd'hui reste à zéro, on affiche celle d'hier.
        if (recent?.sinceMidnight == true) return@restGet
        app.ha.request(JSONObject().put("type", "recorder/statistics_during_period").put("start_time", iso.format(Date(midnight - 86_400_000L)))
            .put("end_time", iso.format(Date(midnight))).put("statistic_ids", JSONArray().put(id)).put("period", "day").put("types", JSONArray().put("change"))) { res ->
            if (id != cfg.energySensors.firstOrNull()) return@request
            // Home Assistant inclut le jour qui commence à l'heure de fin : on ne garde que la journée d'hier.
            val day = Energy.daily(res.optJSONObject("result")?.optJSONArray(id)).lastOrNull { it.t < midnight } ?: return@request
            if (day.v <= 0) return@request
            energyToday = Energy.Recent(day.v, sinceMidnight = false, yesterday = true)
            if (roomIndex == MainActivity.HOME) updateHomeHeader()
        }
    }
}
