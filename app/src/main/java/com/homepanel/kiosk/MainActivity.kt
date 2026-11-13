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
        internal const val HOME = -1
        internal const val AUTO_HOME_MS = 3 * 60_000L
    }

    internal val app get() = App.instance
    internal val cfg get() = app.config

    internal lateinit var root: FrameLayout
    internal lateinit var backBtn: ImageView
    internal lateinit var titleView: TextView
    internal lateinit var subtitleView: TextView
    internal lateinit var chipsView: FlowLayout
    internal var lastChips: List<Present.Chip> = emptyList()
    internal lateinit var clockView: TextView
    internal lateinit var dateView: TextView
    internal lateinit var batteryView: BatteryView
    internal val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) = onBattery(i)
    }

    internal fun onBattery(i: android.content.Intent) {
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        batteryView.set(lvl, st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL)
    }
    internal lateinit var statusView: TextView
    internal lateinit var scroll: ScrollView
    internal lateinit var content: LinearLayout
    internal lateinit var demoPill: TextView
    internal lateinit var emptyBox: LinearLayout
    internal lateinit var emptyText: TextView
    internal lateinit var dimView: DimScreen

    internal val tiles = HashMap<String, Pair<TileView, RoomEntity>>()
    internal val roomTiles = HashMap<Int, TileView>()
    internal var weatherCard: WeatherCard? = null
    internal var calendarCard: CalendarCard? = null
    internal var forecast: JSONArray? = null
    internal var events: List<CalendarCard.Event>? = null
    internal var homeDataAt = 0L
    internal var roomIndex = HOME
    internal var dialog: ControlDialog? = null

    internal val handler = Handler(Looper.getMainLooper())
    internal var lastTouch = 0L
    internal var dimmed = false
    internal var swallow = false
    internal var swiping = false
    internal var downX = 0f
    internal var downY = 0f

    internal val timeFmt get() = SimpleDateFormat("HH:mm", I18n.locale)
    internal val dateFmt get() = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale)
    internal val tick = object : Runnable {
        override fun run() {
            updateClock()
            checkDim()
            // Retour automatique à l'accueil après quelques minutes sans toucher.
            if (roomIndex != HOME && !dimmed && dialog?.isShowing != true &&
                SystemClock.uptimeMillis() - lastTouch > AUTO_HOME_MS) goHome()
            if (roomIndex == HOME && System.currentTimeMillis() - homeDataAt > 15 * 60_000L) loadHomeData()
            // Thème automatique : à minuit le fond et la salutation suivent la nouvelle date. Photo Immich du jour : vérifiée ici aussi.
            if (cfg.theme == Themes.AUTO && cfg.activeTheme != shownTheme) {
                shownTheme = cfg.activeTheme
                root.background = Themes.background(this@MainActivity, cfg)
                if (roomIndex == HOME) updateHomeHeader()
                app.mqtt.publishSettings()
            }
            app.refreshImmichWallpaper()
            // Skin de nuit : on change de dessin à l'heure dite, mais pas sous le doigt ni pendant une fenêtre ouverte.
            if (Skin.signatureOf(cfg) != Skin.signature && dialog?.isShowing != true && notice?.showing != true &&
                SystemClock.uptimeMillis() - lastTouch > 15_000) { recreate(); return }
            // Icône « Ce matin » : apparaît / disparaît à l'heure choisie ; pastille de pointe (« en cours ») à jour.
            updateMorningButton()
            if (roomIndex == HOME && peaks.isNotEmpty()) updateHomeHeader()
            checkPeakReminder()
            checkReminders()
            app.backupTick()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Skin.load(cfg)
        buildUi()
    }

    internal fun buildUi() {
        root = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(26), dp(28), 0)
        }

        // En-tête : [‹] titre + sous-titre + pastilles, heure, menu
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        backBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Skin.ink)
            background = oval(Skin.soft)
            setPadding(dp(8), dp(8), dp(10), dp(8))
            setOnClickListener { goHome() }
            visibility = View.GONE
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16); topMargin = dp(2) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = label(34f, Skin.ink, true)
        subtitleView = label(16f, Skin.fg(0xCC))
        chipsView = FlowLayout(this)
        titles.addView(titleView)
        titles.addView(subtitleView)
        titles.addView(chipsView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clockView = label(30f, Skin.ink).apply { typeface = Skin.light }
        dateView = label(14f, Skin.fg(0xB3)).apply { gravity = Gravity.END }
        // Heure, avec la batterie de la tablette à sa gauche (selon les réglages)
        batteryView = BatteryView(this)
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        // Minuteries : icône toujours visible, temps restant de la plus proche quand une tourne.
        timerButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_timer)
                imageTintList = android.content.res.ColorStateList.valueOf(Skin.fg(0xE6))
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            timerText = label(17f, Skin.ink, true).apply { setPadding(dp(6), 0, 0, 0); visibility = View.GONE }
            addView(timerText)
            isClickable = true
            setOnClickListener { openTimers() }
        }
        timeRow.addView(timerButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        todoButton = TodoButton(this).apply { setOnClickListener { openTodos() } }
        timeRow.addView(todoButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        musicButton = MusicButton(this).apply { setOnClickListener { openMusic() } }
        timeRow.addView(musicButton, LinearLayout.LayoutParams(dp(42), dp(40)).apply { marginEnd = dp(8) })
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
            imageTintList = android.content.res.ColorStateList.valueOf(Skin.fg(0xB3))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { openSettings() }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(12) })
        col.addView(header)

        statusView = label(14f, Skin.ink).apply {
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
        emptyText = label(20f, Skin.ink).apply { gravity = Gravity.CENTER }
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
    internal val onConfigChanged: () -> Unit = {
        // Nouvelle skin : on reconstruit tout l'écran avec le nouveau dessin (le nouvel écran fera le reste).
        if (Skin.signatureOf(cfg) != Skin.signature) recreate()
        else {
            homeDataAt = 0L // météo/calendriers peuvent avoir changé : on recharge
            if (!dimmed) applyBrightness() else { shownDimStyle = ""; updateDimScreen() }
            updateMotion()
            root.background = Themes.background(this, cfg); shownTheme = cfg.activeTheme
            rebuild()
            updateStatus(app.ha.status)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Skin.signatureOf(cfg) != Skin.signature) { recreate(); return }
        hideSystemUi()
        root.background = Themes.background(this, cfg); shownTheme = cfg.activeTheme
        app.ha.setEntities(cfg.allEntityIds())
        app.ha.addListener(this)
        app.configListeners.add(onConfigChanged)
        app.mqtt.onScreenOff = { off -> if (off && !dimmed) dim() else if (!off && dimmed) undim() }
        app.mqtt.onHome = { goHome() }
        app.onNotice = { showNotice(it) }
        app.onNoticeCleared = { tag -> notice?.let { if (it.showing && tag.isNotEmpty() && it.shownTag == tag) it.close() } }
        if (onNoticesChanged !in app.notices.listeners) app.notices.listeners.add(onNoticesChanged)
        if (onTimers !in KitchenTimers.listeners) KitchenTimers.listeners.add(onTimers)
        app.onTimerDone = { showTimerDone(it) }
        // Minuterie finie pendant que cet écran était caché ou reconstruit : la carte (et son bouton d'arrêt) revient.
        app.ringingTimer?.let { t -> handler.post { if (notice?.showing != true) showTimerDone(t) } }
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

    internal var motion: MotionWake? = null

    /** Démarre ou arrête la caméra avant selon le réglage (et l'autorisation). */
    internal fun updateMotion() {
        val want = cfg.motionWake && MotionWake.hasFrontCamera(this) && MotionWake.hasPermission(this) && !app.ha.demo
        if (want && motion == null) motion = MotionWake(this) { onMotion() }.also { it.start() }
        else if (!want) { motion?.stop(); motion = null }
    }

    internal fun onMotion() {
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
        app.onTimerDone = null
        app.onNoticeCleared = null
        app.notices.listeners.remove(onNoticesChanged)
        // Sans ceci, chaque écran reconstruit (changement de skin) restait en mémoire et recevait encore les minuteries.
        KitchenTimers.listeners.remove(onTimers)
        handler.removeCallbacks(headerUpdate)
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
        if (todoPanel?.showing == true) { todoPanel?.hide(); return }
        if (musicPanel?.showing == true) { musicPanel?.hide(); return }
        if (morningPanel?.showing == true) { morningPanel?.hide(); return }
        if (center?.showing == true) { center?.hide(); return }
        if (roomIndex != HOME) goHome()
    }

    @Suppress("DEPRECATION")
    internal fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ------------------------------------------------------------ navigation

    internal fun rebuild() {
        houseCache = null
        updateTodoButton()
        updateMusicButton()
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

    internal fun clearContent() {
        content.removeAllViews()
        tiles.clear()
        roomTiles.clear()
        cameraTiles.clear()
        weatherCard = null
        calendarCard = null
        nowPlayingBox = null
        handler.removeCallbacks(snapshotTick)
    }

    internal fun openRoom(i: Int) {
        roomIndex = i
        app.mqtt.setCurrentScreen(cfg.rooms[i].name)
        showRoom()
        scroll.scrollTo(0, 0)
    }

    internal fun goHome() {
        if (roomIndex == HOME) return
        roomIndex = HOME
        app.mqtt.setCurrentScreen(null)
        showHome()
        scroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------ accueil

    /** Pièce virtuelle regroupant toutes les entités, pour la vue d'ensemble. */
    /** Thème affiché lors du dernier dessin du fond (pour repérer le changement de date en mode automatique). */
    internal var shownTheme = ""
    internal val headerUpdate = Runnable { if (roomIndex == HOME) updateHomeHeader() }
    internal var houseCache: Room? = null

    /** Toutes les entités de la maison, une seule fois chacune (recalculé quand la configuration change). */
    internal fun wholeHouse(): Room = houseCache ?: Room("__home", tr("Maison")).also { r ->
        val seen = HashSet<String>()
        for (room in cfg.rooms) for (e in room.entities) if (seen.add(e.id)) r.entities.add(e)
        houseCache = r
    }

    /**
     * La maison pour les pastilles de l'accueil : sans les appareils décochés dans les réglages (« Vue d'ensemble »),
     * ni ceux décochés d'office (porte du réfrigérateur…) tant qu'on ne les a pas recochés.
     */
    internal fun homeRoom(): Room = Room("__home", tr("Maison")).also { r ->
        val hidden = cfg.summaryHidden.toSet(); val shown = cfg.summaryShown.toSet()
        for (e in wholeHouse().entities) {
            val st = app.ha.states[e.id]
            if (!Choices.hidden(e.id, hidden, shown, Choices.summaryHiddenByDefault(e.id, st?.name ?: "", Present.chipKind(e.id, st)))) r.entities.add(e)
        }
    }

    /** Consommation d'aujourd'hui du premier capteur d'énergie, quand c'est un compteur cumulatif (pastille de l'accueil). */
    internal var energyToday: Energy.Recent? = null

    /** Pastilles « Recyclage / Ordures aujourd'hui ou demain », d'après les calendriers et mots clés choisis. */
    internal var collectChips: List<Present.Chip> = emptyList()

    internal var peaks: List<HomeExtras.Peak> = emptyList()
    internal lateinit var todoButton: TodoButton
    internal var todoPanel: TodoPanel? = null
    internal lateinit var musicButton: MusicButton
    internal var musicPanel: MusicPanel? = null
    /** Carte(s) « En lecture » de la pièce affichée, et ce qu'elles montrent (pour ne redessiner que si ça change). */
    internal var nowPlayingBox: LinearLayout? = null
    internal var nowPlayingKey = ""
    internal val lastPlaying = HashMap<String, Long>()
    internal lateinit var morningButton: HomeExtras.MorningButton
    internal val morningReady get() = ::morningButton.isInitialized

    internal val reminded = HashSet<Long>()

    internal var morningPanel: MorningPanel? = null

    // ------------------------------------------------------------ pièce

    // ------------------------------------------------------------ caméras

    internal val cameraTiles = HashMap<String, CameraTileView>()

    /** Rafraîchit les snapshots de la pièce affichée toutes les 60 s (pas en veille). */
    internal val snapshotTick = object : Runnable {
        override fun run() {
            if (!dimmed) refreshSnapshots()
            handler.postDelayed(this, 60_000)
        }
    }

    internal val reshowRoom = Runnable { if (roomIndex != HOME && roomIndex in cfg.rooms.indices) showRoom() }

    // ------------------------------------------------------------ pastilles

    /** Rafraîchit la fenêtre de détail d'une pastille ouverte (null si aucune). */
    internal var chipDetailRefresh: (() -> Unit)? = null

    internal var chipsRoom: Room? = null

    // ------------------------------------------------------------ tuiles d'appareils

    // ------------------------------------------------------------ mises à jour HA

    override fun onHaEntities(ids: Set<String>) {
        chipDetailRefresh?.invoke()
        // On note quand un lecteur joue, même si sa pièce n'est pas à l'écran : la carte « En lecture » sait ainsi
        // qu'une pause est récente quand on ouvre la pièce ensuite.
        for (id in ids) if (id.startsWith("media_player.") && app.ha.states[id]?.state == "playing") lastPlaying[id] = SystemClock.uptimeMillis()
        if (cfg.todoLists.any { it in ids }) { updateTodoButton(); todoPanel?.onEntities(ids) }
        if (cfg.musicPlayers.any { it in ids }) { updateMusicButton(); musicPanel?.onEntities(ids) }
        // Une source de « Ce matin » a changé (ex. résumé publié à 5 h) : l'icône réapparaît avec son point.
        val ms = cfg.morningSources
        if ((0 until ms.length()).any { ms.optJSONObject(it)?.optString("entity") in ids }) updateMorningButton()
        if (roomIndex == HOME) {
            if (ids.contains(cfg.weatherEntity)) weatherCard?.update(app.ha.states[cfg.weatherEntity])
            // Plusieurs capteurs changent souvent en rafale : un seul recalcul de l'en-tête pour le lot.
            handler.removeCallbacks(headerUpdate)
            handler.postDelayed(headerUpdate, 120)
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
        if (ids.any { it.startsWith("media_player.") }) updateNowPlaying()
    }

    override fun onHaStatus(status: HaClient.Status) {
        updateStatus(status)
        if (status == HaClient.Status.CONNECTED && roomIndex == HOME) loadHomeData()
        val room = cfg.rooms.getOrNull(roomIndex) ?: return
        for ((t, re) in tiles.values) bindTile(t, re, room)
    }

    internal fun updateStatus(s: HaClient.Status) {
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

    internal fun updateClock() {
        val now = Date()
        val t = timeFmt.format(now)
        clockView.text = t
        dateView.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
        if (dimmed) updateDimScreen()
        if (roomIndex == HOME) titleView.text = Themes.greeting(cfg, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    internal fun checkDim() {
        val m = cfg.dimMinutes
        if (m <= 0 || dimmed || dialog?.isShowing == true) return
        if (SystemClock.uptimeMillis() - lastTouch > m * 60_000L) dim()
    }

    internal fun dim() {
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
    internal fun applyDimBrightness() {
        val lit = cfg.effectiveDimStyle != "black"
        window.attributes = window.attributes.apply { screenBrightness = if (lit) cfg.dimBrightness / 100f else 0.01f }
    }

    internal var shownDimStyle = ""

    /** Écran de veille ; passe tout seul au noir au début des heures calmes (et revient après). */
    internal fun updateDimScreen() {
        val style = cfg.effectiveDimStyle
        if (style != shownDimStyle) { shownDimStyle = style; applyDimBrightness() }
        dimView.update(style, app.ha.states[cfg.weatherEntity].takeIf { cfg.weatherEntity.isNotEmpty() })
    }

    /** Luminosité normale : réglage de l'app (HA / page web), sinon celle du système. */
    internal fun applyBrightness() {
        val b = cfg.brightness
        window.attributes = window.attributes.apply {
            screenBrightness = if (b > 0) b / 100f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    internal fun undim() {
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

    internal var notice: NotificationCard? = null
    internal var center: NotificationCenter? = null

    // ------------------------------------------------------------ minuteries

    internal lateinit var timerButton: LinearLayout
    internal lateinit var timerText: TextView
    internal var timerPanel: TimerPanel? = null

    internal val onTimers: () -> Unit = {
        val next = KitchenTimers.list.minByOrNull { it.remaining() }
        timerText.visibility = if (next == null) View.GONE else View.VISIBLE
        timerText.text = next?.let { KitchenTimers.format(it.remaining()) + if (KitchenTimers.list.size > 1) "  +${KitchenTimers.list.size - 1}" else "" } ?: ""
        timerButton.background = if (next == null) null else roundRect(0x33FFB300, dpf(18))
    }

    internal lateinit var bellView: BellView
    internal val onNoticesChanged: () -> Unit = { bellView.setCount(app.notices.count, app.notices.historyCount > 0) }

    // ------------------------------------------------------------ réglages

    internal fun openSettings() {
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
