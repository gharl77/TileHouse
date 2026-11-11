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
 * Écran principal, en deux modes :
 *  - accueil (roomIndex = -1) : salutation, vue d'ensemble, météo, agenda, pièces par palier ;
 *  - pièce : appareils de la pièce, flèche ‹ pour revenir à l'accueil.
 */
class MainActivity : Activity(), HaClient.Listener {
    companion object {
        private const val HOME = -1
        private const val AUTO_HOME_MS = 3 * 60_000L
    }

    private val app get() = App.instance
    private val cfg get() = app.config

    private lateinit var root: FrameLayout
    private lateinit var backBtn: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var chipsView: FlowLayout
    private var lastChips: List<Present.Chip> = emptyList()
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var batteryView: BatteryView
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) = onBattery(i)
    }

    private fun onBattery(i: android.content.Intent) {
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        batteryView.set(lvl, st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL)
    }
    private lateinit var statusView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var demoPill: TextView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var dimView: DimScreen

    private val tiles = HashMap<String, Pair<TileView, RoomEntity>>()
    private val roomTiles = HashMap<Int, TileView>()
    private var weatherCard: WeatherCard? = null
    private var calendarCard: CalendarCard? = null
    private var forecast: JSONArray? = null
    private var events: List<CalendarCard.Event>? = null
    private var homeDataAt = 0L
    private var roomIndex = HOME
    private var dialog: ControlDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastTouch = 0L
    private var dimmed = false
    private var swallow = false
    private var swiping = false
    private var downX = 0f
    private var downY = 0f

    private val timeFmt get() = SimpleDateFormat("HH:mm", I18n.locale)
    private val dateFmt get() = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale)
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            checkDim()
            // Retour automatique à l'accueil après quelques minutes sans toucher.
            if (roomIndex != HOME && !dimmed && dialog?.isShowing != true &&
                SystemClock.uptimeMillis() - lastTouch > AUTO_HOME_MS) goHome()
            if (roomIndex == HOME && System.currentTimeMillis() - homeDataAt > 15 * 60_000L) loadHomeData()
            // Icône « Ce matin » : apparaît / disparaît à l'heure choisie ; pastille de pointe (« en cours ») à jour.
            updateMorningButton()
            if (roomIndex == HOME && peaks.isNotEmpty()) updateHomeHeader()
            checkPeakReminder()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    private fun buildUi() {
        root = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(26), dp(28), 0)
        }

        // En-tête : [‹] titre + sous-titre + pastilles, heure, menu
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        backBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = oval(GLASS_LIGHT)
            setPadding(dp(8), dp(8), dp(10), dp(8))
            setOnClickListener { goHome() }
            visibility = View.GONE
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16); topMargin = dp(2) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = label(34f, Color.WHITE, true)
        subtitleView = label(16f, 0xCCFFFFFF.toInt())
        chipsView = FlowLayout(this)
        titles.addView(titleView)
        titles.addView(subtitleView)
        titles.addView(chipsView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clockView = label(30f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        dateView = label(14f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.END }
        // Heure, avec la batterie de la tablette à sa gauche (selon les réglages)
        batteryView = BatteryView(this)
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        // Minuteries : icône toujours visible, temps restant de la plus proche quand une tourne.
        timerButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_timer)
                imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            timerText = label(17f, Color.WHITE, true).apply { setPadding(dp(6), 0, 0, 0); visibility = View.GONE }
            addView(timerText)
            isClickable = true
            setOnClickListener { openTimers() }
        }
        timeRow.addView(timerButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        morningButton = HomeExtras.MorningButton(this).apply { setOnClickListener { openMorning() } }
        timeRow.addView(morningButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        bellView = BellView(this).apply { setOnClickListener { openCenter() } }
        timeRow.addView(bellView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) })
        timeRow.addView(batteryView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(14) })
        timeRow.addView(clockView)
        clockBox.addView(timeRow)
        clockBox.addView(dateView)
        header.addView(clockBox)

        val menu = ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = android.content.res.ColorStateList.valueOf(0xB3FFFFFF.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { openSettings() }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(12) })
        col.addView(header)

        statusView = label(14f, Color.WHITE).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
        }
        col.addView(statusView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(28)) }
        scroll.addView(content)
        col.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        emptyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        emptyText = label(20f, Color.WHITE).apply { gravity = Gravity.CENTER }
        emptyBox.addView(emptyText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        emptyBox.addView(pill(tr("Ouvrir les réglages"), true) { openSettings() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(20); marginEnd = 0 }
        })
        demoPill = pill(tr("Essayer la démo"), false) { app.setDemo(true) }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(12); marginEnd = 0 }
        }
        emptyBox.addView(demoPill)
        root.addView(emptyBox, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        dimView = DimScreen(this)
        root.addView(dimView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    /** Appelé quand la configuration change (page web, paliers HA). */
    private val onConfigChanged: () -> Unit = {
        homeDataAt = 0L // météo/calendriers peuvent avoir changé : on recharge
        if (!dimmed) applyBrightness() else { shownDimStyle = ""; updateDimScreen() }
        updateMotion()
        root.background = Themes.background(this, cfg)
        rebuild()
        updateStatus(app.ha.status)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        root.background = Themes.background(this, cfg)
        app.ha.setEntities(cfg.allEntityIds())
        app.ha.addListener(this)
        app.configListeners.add(onConfigChanged)
        app.mqtt.onScreenOff = { off -> if (off && !dimmed) dim() else if (!off && dimmed) undim() }
        app.mqtt.onHome = { goHome() }
        app.onNotice = { showNotice(it) }
        app.onNoticeCleared = { tag -> notice?.let { if (it.showing && tag.isNotEmpty() && it.shownTag == tag) it.close() } }
        if (onNoticesChanged !in app.notices.listeners) app.notices.listeners.add(onNoticesChanged)
        if (onTimers !in KitchenTimers.listeners) KitchenTimers.listeners.add(onTimers)
        KitchenTimers.onDone = { onTimerDone(it) }
        onTimers()
        onNoticesChanged()
        app.pendingNotice?.let { id -> app.pendingNotice = null; app.notices.find(id)?.let { n -> handler.post { showNotice(n) } } }
        app.ha.ensureConnected()
        lastTouch = SystemClock.uptimeMillis()
        undim()
        rebuild()
        updateStatus(app.ha.status)
        handler.removeCallbacks(tick)
        handler.post(tick)
        updateMotion()
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { onBattery(it) }
    }

    // ------------------------------------------------------------ réveil par mouvement

    private var motion: MotionWake? = null

    /** Démarre ou arrête la caméra avant selon le réglage (et l'autorisation). */
    private fun updateMotion() {
        val want = cfg.motionWake && MotionWake.hasFrontCamera(this) && MotionWake.hasPermission(this) && !app.ha.demo
        if (want && motion == null) motion = MotionWake(this) { onMotion() }.also { it.start() }
        else if (!want) { motion?.stop(); motion = null }
    }

    private fun onMotion() {
        lastTouch = SystemClock.uptimeMillis()
        if (dimmed) undim()
    }

    override fun onPause() {
        super.onPause()
        motion?.stop(); motion = null
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        app.ha.removeListener(this)
        app.configListeners.remove(onConfigChanged)
        app.mqtt.onScreenOff = null
        app.mqtt.onHome = null
        app.onNotice = null
        app.onNoticeCleared = null
        app.notices.listeners.remove(onNoticesChanged)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(snapshotTick)
        dialog?.dismiss()
        dialog = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Kiosque : le bouton retour ramène à l'accueil")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (roomIndex != HOME) goHome()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ------------------------------------------------------------ navigation

    private fun rebuild() {
        clockView.visibility = if (cfg.showClock) View.VISIBLE else View.GONE
        dateView.visibility = clockView.visibility
        batteryView.visibility = if (cfg.showBattery) View.VISIBLE else View.GONE
        if (!cfg.isConfigured || cfg.rooms.isEmpty()) {
            emptyBox.visibility = View.VISIBLE
            demoPill.visibility = if (cfg.isConfigured) View.GONE else View.VISIBLE
            val msg = if (!cfg.isConfigured) tr("Bienvenue !\nConnectez Home Assistant dans les réglages.")
            else tr("Aucune pièce pour l'instant.\nAjoutez des pièces et des appareils dans les réglages.")
            val web = app.web.url()
            emptyText.text = if (web != null) "$msg\n\n" + tr("Ou, plus simple, depuis un ordinateur ou un téléphone :") + "\n$web" else msg
            titleView.text = tr("Maison")
            subtitleView.visibility = View.GONE
            backBtn.visibility = View.GONE
            updateChips(null)
            clearContent()
            return
        }
        emptyBox.visibility = View.GONE
        if (roomIndex != HOME && roomIndex !in cfg.rooms.indices) roomIndex = HOME
        if (roomIndex == HOME) showHome() else showRoom()
    }

    private fun clearContent() {
        content.removeAllViews()
        tiles.clear()
        roomTiles.clear()
        cameraTiles.clear()
        weatherCard = null
        calendarCard = null
        handler.removeCallbacks(snapshotTick)
    }

    private fun openRoom(i: Int) {
        roomIndex = i
        app.mqtt.setCurrentScreen(cfg.rooms[i].name)
        showRoom()
        scroll.scrollTo(0, 0)
    }

    private fun goHome() {
        if (roomIndex == HOME) return
        roomIndex = HOME
        app.mqtt.setCurrentScreen(tr("Accueil"))
        showHome()
        scroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------ accueil

    /** Pièce virtuelle regroupant toutes les entités, pour la vue d'ensemble. */
    private fun wholeHouse(): Room {
        val r = Room("__home", tr("Maison"))
        val seen = HashSet<String>()
        for (room in cfg.rooms) for (e in room.entities) if (seen.add(e.id)) r.entities.add(e)
        return r
    }

    private fun showHome() {
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

    private fun addFloorSection(title: String, indexes: List<Int>) {
        content.addView(label(17f, 0xE6FFFFFF.toInt(), true, title).apply { setPadding(dp(2), dp(22), 0, dp(10)) })
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
    private fun bindRoomTile(t: TileView, room: Room) {
        t.bind(room.name, "", false, false, 0xFFFFB300.toInt(), HomeText.roomIcon(room.name))
    }

    private fun updateHomeHeader() {
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
    private fun loadHomeData() {
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

    /** Pastilles « Recyclage / Ordures aujourd'hui ou demain », d'après les calendriers et mots clés choisis. */
    private var collectChips: List<Present.Chip> = emptyList()

    private var peaks: List<HomeExtras.Peak> = emptyList()
    private lateinit var morningButton: HomeExtras.MorningButton

    /** Icône « Ce matin » : pendant la plage horaire s'il y a du contenu ; point bleu tant que ce contenu n'a pas été ouvert. */
    private fun updateMorningButton() {
        if (!::morningButton.isInitialized) return
        val visible = cfg.isMorningNow() && HomeExtras.hasMorning(cfg.morningSources, app.ha.states)
        val read = getSharedPreferences("notices", MODE_PRIVATE).getInt("morning_read", 0)
        morningButton.set(visible, visible && read != HomeExtras.morningHash(cfg.morningSources, app.ha.states))
    }
    private val reminded = HashSet<Long>()

    /** Pointes d'électricité des 2 prochains jours (calendrier choisi). */
    private fun loadPeaks() {
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
            if (roomIndex == HOME) updateHomeHeader()
        }
    }

    /** Rappel avant une pointe : une notification (centre de notifications), une seule fois par pointe. */
    private fun checkPeakReminder() {
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

    private fun showWeatherAlerts() {
        val text = HomeExtras.weatherDetails(cfg.weatherAlerts, app.ha.states)
        @Suppress("DEPRECATION")
        showReader(tr("Alertes météo"), if (text.isEmpty()) tr("Aucune alerte en vigueur.") else android.text.Html.fromHtml(text))
    }

    private fun openMorning() {
        showReader("☀️  " + tr("Ce matin"), HomeExtras.morningFull(cfg.morningSources, app.ha.states))
        getSharedPreferences("notices", MODE_PRIVATE).edit().putInt("morning_read", HomeExtras.morningHash(cfg.morningSources, app.ha.states)).apply()
        updateMorningButton()
    }

    /** Fenêtre de lecture (texte long, défilant). */
    private fun showReader(title: String, body: CharSequence) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(24), dp(30), dp(24))
            background = roundRect(0xF21C1C1E.toInt(), dpf(22))
        }
        box.addView(label(22f, Color.WHITE, true, title).apply { setPadding(0, 0, 0, dp(12)) })
        box.addView(ScrollView(this).apply {
            addView(label(16f, 0xE6FFFFFF.toInt()).apply { text = body; setLineSpacing(0f, 1.15f) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7f).toInt()))
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

    private fun loadCollections() {
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
                if (roomIndex == HOME) updateHomeHeader()
            }
        }
    }

    /** Minuscules sans accents (« Matières Recyclables » → « matieres recyclables »). */
    private fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun parseTime(o: JSONObject): Long? = try {
        if (o.has("dateTime")) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(o.getString("dateTime").replace(Regex("\\.\\d+"), ""))?.time
        else SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(o.getString("date"))?.time
    } catch (_: Exception) { null }

    private fun parseEvents(text: String?, color: Int, out: MutableList<CalendarCard.Event>) {
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

    // ------------------------------------------------------------ pièce

    private fun showRoom() {
        clearContent()
        val room = cfg.rooms[roomIndex]
        backBtn.visibility = View.VISIBLE
        subtitleView.visibility = View.GONE
        titleView.text = room.name

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
        // Les capteurs résumés dans l'en-tête (température, portes, mouvement…) n'ont pas de tuile.
        val others = room.entities.filter {
            Present.domain(it.id) != "camera" && !Present.isHeaderSensor(it.id, app.ha.states[it.id])
        }
        val groups: List<Pair<String?, List<RoomEntity>>> =
            if (cfg.groupByCategory) others.groupBy { Present.category(it.id) }.toSortedMap().map { Present.CATEGORIES[it.key] to it.value }
            else if (others.isEmpty()) emptyList() else listOf(null to others)

        for ((title, list) in groups) {
            if (title != null && (groups.size > 1 || cams.isNotEmpty())) {
                content.addView(label(17f, 0xE6FFFFFF.toInt(), true, title).apply { setPadding(dp(2), dp(16), 0, dp(10)) })
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
            content.addView(label(16f, 0xB3FFFFFF.toInt(), text = tr("Cette pièce est vide. Ajoutez des appareils dans les réglages.")).apply { setPadding(0, dp(24), 0, 0) })
        }
        updateChips(room)
    }

    // ------------------------------------------------------------ caméras

    private val cameraTiles = HashMap<String, CameraTileView>()

    /** Rafraîchit les snapshots de la pièce affichée toutes les 60 s (pas en veille). */
    private val snapshotTick = object : Runnable {
        override fun run() {
            if (!dimmed) refreshSnapshots()
            handler.postDelayed(this, 60_000)
        }
    }

    private fun refreshSnapshots() {
        for ((id, v) in cameraTiles) {
            val w = if (v.width > 0) v.width else dp(400)
            app.snapshots.load(id, w) { bmp ->
                // La pièce a pu changer entre-temps : on libère l'image si la vignette n'est plus affichée.
                if (bmp != null && cameraTiles[id] === v && v.isAttachedToWindow) v.setBitmap(bmp) else bmp?.recycle()
            }
        }
    }

    private fun openLive(re: RoomEntity, room: Room) {
        startActivity(Intent(this, LiveActivity::class.java)
            .putExtra(LiveActivity.EXTRA_ID, re.id)
            .putExtra(LiveActivity.EXTRA_NAME, Present.displayName(re, app.ha.states[re.id], room.name)))
    }

    private val reshowRoom = Runnable { if (roomIndex != HOME && roomIndex in cfg.rooms.indices) showRoom() }

    // ------------------------------------------------------------ pastilles

    /** Rafraîchit la fenêtre de détail d'une pastille ouverte (null si aucune). */
    private var chipDetailRefresh: (() -> Unit)? = null

    /**
     * Détail d'une pastille : appareils/capteurs correspondants et leur état.
     * Sur l'accueil, seulement ceux qui sont ouverts / allumés. Une lumière ou une serrure
     * se commande directement depuis sa ligne ; la liste suit l'état en direct.
     */
    private fun showChipDetail(room: Room, chip: Present.Chip) {
        val home = room.id == "__home"
        val all = room.entities.filter { Present.chipKind(it.id, app.ha.states[it.id]) == chip.kind }
        if (all.isEmpty()) return
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = roundRect(0xF21C1C1E.toInt(), dpf(22))
            minimumWidth = dp(460)
        }
        box.addView(label(20f, Color.WHITE, true, room.name).apply { setPadding(0, 0, 0, dp(12)) })
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
                rows.addView(label(16f, 0xB3FFFFFF.toInt(), text = tr("Tout est fermé")).apply { setPadding(0, dp(8), 0, dp(8)) })
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
                    imageTintList = android.content.res.ColorStateList.valueOf(if (on) Present.accent(e) else 0xB3FFFFFF.toInt())
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
                val name = Present.displayName(re, e, src.name) + if (home) "  ·  ${src.name}" else ""
                row.addView(label(16f, Color.WHITE, text = name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(label(16f, 0xCCFFFFFF.toInt(), text = Present.status(e)).apply { setPadding(dp(16), 0, 0, 0) })
                val action = Present.quickAction(re.id, e)
                if (action != null) {
                    row.addView(label(15f, if (on) 0xFF1C1C1E.toInt() else Color.WHITE, true, action).apply {
                        background = roundRect(if (on) 0xFFFFB300.toInt() else 0x33FFFFFF, dpf(16))
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
    private fun updateChips(room: Room?, only: Set<String>? = null, activeOnly: Boolean = false, extra: List<Present.Chip> = emptyList()) {
        val chips = (if (room == null) emptyList() else Present.chips(room, app.ha.states)
            .filter { (only == null || it.kind in only) && (!activeOnly || it.level > 0 || it.kind == "light") }) + extra
        if (chips == lastChips && chipsView.childCount == chips.size) return
        lastChips = chips
        chipsView.removeAllViews()
        for (c in chips) chipsView.addView(chipView(c).apply {
            isClickable = true
            setOnClickListener { if (c.kind == "wx") showWeatherAlerts() else room?.let { showChipDetail(it, c) } }
        })
    }

    // ------------------------------------------------------------ tuiles d'appareils

    private fun bindTile(t: TileView, re: RoomEntity, room: Room) {
        val e = app.ha.states[re.id]
        val status = when {
            e != null -> Present.status(e)
            app.ha.status == HaClient.Status.CONNECTED -> "Introuvable"
            else -> "…"
        }
        t.bind(Present.displayName(re, e, room.name), status, Present.active(e), Present.unavailable(e), Present.accent(e), Present.icon(re.id, e))
    }

    /** HA injoignable : on le dit plutôt que d'ignorer le toucher sans rien montrer. */
    private fun haOffline(): Boolean {
        if (app.ha.demo || app.ha.status == HaClient.Status.CONNECTED) return false
        Toast.makeText(this, tr("Home Assistant ne répond pas : commande impossible pour le moment."), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun onTileTap(t: TileView, re: RoomEntity, room: Room) {
        if (Present.domain(re.id) != "camera" && haOffline()) return
        val e = app.ha.states[re.id]
        if (Present.domain(re.id) == "climate") { openDetail(re, room); return }
        if (Present.tap(app.ha, re.id, e)) t.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        else if (Present.hasDetail(re.id, e)) openDetail(re, room)
    }

    private fun openDetail(re: RoomEntity, room: Room): Boolean {
        val e = app.ha.states[re.id]
        if (!Present.hasDetail(re.id, e)) return false
        dialog?.dismiss()
        dialog = ControlDialog(this, re.id, Present.displayName(re, e, room.name)).also { it.showImmersive() }
        return true
    }

    // ------------------------------------------------------------ mises à jour HA

    override fun onHaEntities(ids: Set<String>) {
        chipDetailRefresh?.invoke()
        // Une source de « Ce matin » a changé (ex. résumé publié à 5 h) : l'icône réapparaît avec son point.
        val ms = cfg.morningSources
        if ((0 until ms.length()).any { ms.optJSONObject(it)?.optString("entity") in ids }) updateMorningButton()
        if (roomIndex == HOME) {
            if (ids.contains(cfg.weatherEntity)) weatherCard?.update(app.ha.states[cfg.weatherEntity])
            updateHomeHeader()
            return
        }
        val room = cfg.rooms.getOrNull(roomIndex) ?: return
        var touched = false
        // Un capteur d'en-tête dont l'état vient d'arriver ne doit pas rester en tuile.
        if (ids.any { tiles.containsKey(it) && Present.isHeaderSensor(it, app.ha.states[it]) }) {
            handler.removeCallbacks(reshowRoom)
            handler.post(reshowRoom)
        }
        for (id in ids) tiles[id]?.let { (t, re) -> bindTile(t, re, room); touched = true }
        for (id in ids) cameraTiles[id]?.let { v -> room.find(id)?.let { v.name = Present.displayName(it, app.ha.states[id], room.name) } }
        if (touched || ids.any { room.find(it) != null }) updateChips(room)
    }

    override fun onHaStatus(status: HaClient.Status) {
        updateStatus(status)
        if (status == HaClient.Status.CONNECTED && roomIndex == HOME) loadHomeData()
        val room = cfg.rooms.getOrNull(roomIndex) ?: return
        for ((t, re) in tiles.values) bindTile(t, re, room)
    }

    private fun updateStatus(s: HaClient.Status) {
        if (!cfg.isConfigured) { statusView.visibility = View.GONE; return }
        // Hors ligne : les tuiles montrent le dernier état connu, en grisé pour ne pas faire croire qu'il est à jour.
        content.alpha = if (s == HaClient.Status.CONNECTED || s == HaClient.Status.IDLE || app.ha.demo) 1f else 0.55f
        val (text, color) = when (s) {
            HaClient.Status.CONNECTED, HaClient.Status.IDLE -> { statusView.visibility = View.GONE; return }
            HaClient.Status.CONNECTING -> tr("Connexion à Home Assistant…") to 0x99FF9F0A.toInt()
            HaClient.Status.DISCONNECTED -> tr("Hors ligne · nouvelle tentative…") to 0xB3FF3B30.toInt()
            HaClient.Status.AUTH_FAILED -> tr("Jeton refusé · vérifiez les réglages") to 0xB3FF3B30.toInt()
        }
        statusView.text = text
        statusView.background = roundRect(color, dpf(20))
        statusView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ gestes, veille

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (dimmed) { undim(); swallow = true; return true }
            swallow = false
            swiping = false
            downX = ev.rawX
            downY = ev.rawY
        }
        if (swallow) return true
        // Dans une pièce, balayer à gauche/droite passe à la pièce suivante/précédente.
        if (action == MotionEvent.ACTION_MOVE && !swiping && roomIndex != HOME && cfg.rooms.size > 1) {
            val dx = ev.rawX - downX
            val dy = ev.rawY - downY
            if (abs(dx) > dp(48) && abs(dx) > abs(dy) * 1.8f) {
                swiping = true
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (swiping) {
            if (action == MotionEvent.ACTION_UP) {
                val n = cfg.rooms.size
                openRoom(if (ev.rawX < downX) (roomIndex + 1) % n else (roomIndex - 1 + n) % n)
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun updateClock() {
        val now = Date()
        val t = timeFmt.format(now)
        clockView.text = t
        dateView.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
        if (dimmed) updateDimScreen()
        if (roomIndex == HOME) titleView.text = Themes.greeting(cfg, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    private fun checkDim() {
        val m = cfg.dimMinutes
        if (m <= 0 || dimmed || dialog?.isShowing == true) return
        if (SystemClock.uptimeMillis() - lastTouch > m * 60_000L) dim()
    }

    private fun dim() {
        dimmed = true
        dimView.visibility = View.VISIBLE
        dimView.bringToFront()
        shownDimStyle = ""
        updateDimScreen()
        dimView.onShown(true)
        motion?.rebaseline() // l'écran qui s'assombrit change l'éclairage de la pièce
        updateClock()
        app.mqtt.setScreenOff(true)
    }

    /** Écran de veille : noir (luminosité minimale) ou heure + météo (luminosité choisie). */
    private fun applyDimBrightness() {
        val lit = cfg.effectiveDimStyle != "black"
        window.attributes = window.attributes.apply { screenBrightness = if (lit) cfg.dimBrightness / 100f else 0.01f }
    }

    private var shownDimStyle = ""

    /** Écran de veille ; passe tout seul au noir au début des heures calmes (et revient après). */
    private fun updateDimScreen() {
        val style = cfg.effectiveDimStyle
        if (style != shownDimStyle) { shownDimStyle = style; applyDimBrightness() }
        dimView.update(style, app.ha.states[cfg.weatherEntity].takeIf { cfg.weatherEntity.isNotEmpty() })
    }

    /** Luminosité normale : réglage de l'app (HA / page web), sinon celle du système. */
    private fun applyBrightness() {
        val b = cfg.brightness
        window.attributes = window.attributes.apply {
            screenBrightness = if (b > 0) b / 100f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun undim() {
        val was = dimmed
        dimmed = false
        applyBrightness()
        if (was) app.mqtt.setScreenOff(false)
        dimView.visibility = View.GONE
        dimView.onShown(false)
        if (was) motion?.rebaseline()
        if (cameraTiles.isNotEmpty()) refreshSnapshots()
    }

    // ------------------------------------------------------------ messages de HA

    private var notice: NotificationCard? = null
    private var center: NotificationCenter? = null

    // ------------------------------------------------------------ minuteries

    private lateinit var timerButton: LinearLayout
    private lateinit var timerText: TextView
    private var timerPanel: TimerPanel? = null
    private val timerAlarm by lazy { TimerAlarm(this) }

    private val onTimers: () -> Unit = {
        val next = KitchenTimers.list.minByOrNull { it.remaining() }
        timerText.visibility = if (next == null) View.GONE else View.VISIBLE
        timerText.text = next?.let { KitchenTimers.format(it.remaining()) + if (KitchenTimers.list.size > 1) "  +${KitchenTimers.list.size - 1}" else "" } ?: ""
        timerButton.background = if (next == null) null else roundRect(0x33FFB300, dpf(18))
    }

    private fun openTimers() {
        val p = timerPanel ?: TimerPanel(this).also {
            root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            timerPanel = it
        }
        p.show()
    }

    /** Fin d'une minuterie : sonnerie en boucle et grand encadré jusqu'à « Arrêter ». */
    private fun onTimerDone(t: KitchenTimer) {
        if (dimmed) undim()
        lastTouch = SystemClock.uptimeMillis()
        timerAlarm.start()
        timerPanel?.hide()
        noticeCard().show(Notice(tr("Minuterie terminée !"), tr("Minuterie de") + " " + t.label, "⏰", 0, 0xFFFF9F0A.toInt(), null, false),
            0) { timerAlarm.stop() }
    }
    private lateinit var bellView: BellView
    private val onNoticesChanged: () -> Unit = { bellView.setCount(app.notices.count) }

    private fun noticeCard(): NotificationCard = notice ?: NotificationCard(this).also {
        root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        it.onClosed = { lastTouch = SystemClock.uptimeMillis(); timerAlarm.stop() }
        notice = it
    }

    /**
     * Nouvelle notification de HA : encadré centré au plus [Config.noticeSeconds] secondes
     * (moins si l'automatisation le demande). OK = lue ; sinon elle reste dans la clochette.
     */
    private fun showNotice(s: StoredNotice) {
        if (dimmed) undim()
        lastTouch = SystemClock.uptimeMillis()
        val max = cfg.noticeSeconds
        val d = s.notice.duration
        noticeCard().show(s.notice, if (d in 1 until max) d else max) { app.notices.markRead(s.id) }
    }

    /** Ouverte depuis le centre de notifications : elle devient lue tout de suite. */
    private fun openNotice(s: StoredNotice) {
        lastTouch = SystemClock.uptimeMillis()
        // Afficher d'abord : marquer comme lue supprime la capture enregistrée de l'alerte caméra.
        noticeCard().show(s.notice.copyNoSound(), cfg.noticeSeconds)
        app.notices.markRead(s.id)
    }

    private fun openCenter() {
        val c = center ?: NotificationCenter(this, app.notices) { openNotice(it) }.also {
            root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            center = it
        }
        c.show()
    }

    // ------------------------------------------------------------ réglages

    private fun openSettings() {
        val pin = cfg.pin
        if (pin.isEmpty()) { startActivity(Intent(this, SettingsActivity::class.java)); return }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = tr("Code PIN")
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(tr("Réglages"))
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == pin) startActivity(Intent(this, SettingsActivity::class.java))
                else Toast.makeText(this, tr("Code incorrect"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(tr("Annuler"), null)
            .show()
    }
}
