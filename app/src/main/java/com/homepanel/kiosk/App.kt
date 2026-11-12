package com.homepanel.kiosk

import android.app.Application
import android.content.ComponentCallbacks2

class App : Application() {
    lateinit var config: ConfigStore
        private set
    lateinit var ha: HaClient
        private set

    lateinit var web: WebServer
        private set

    /** Liste complète des entités HA, chargée seulement dans les réglages. */
    var catalog: Catalog? = null

    /** Prévenus (thread principal) quand la configuration change depuis la page web. */
    val configListeners = ArrayList<() -> Unit>()

    /**
     * Filet de sécurité : une course connue d'OkHttp (écriture WebSocket pendant une fermeture) lève une
     * NullPointerException dans son propre fil. Sans ceci, elle fermait toute l'application (un kiosque
     * ne doit pas disparaître de l'écran). On l'ignore seulement dans ce cas précis ; la connexion se
     * rétablit d'elle-même. Tout autre plantage suit le chemin normal.
     */
    private fun guardNetworkThreads() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val okThreads = okhttp3.OkHttpClient::class.java.name.removePrefix("okhttp3.").removeSuffix("Client") + " TaskRunner"
        val okioBuffer = okio.Buffer::class.java.name
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val libraryRace = t.name.startsWith(okThreads) && e is NullPointerException && e.stackTrace.firstOrNull()?.className == okioBuffer
            if (libraryRace) { CrashLog.countIgnored(); android.util.Log.w("TileHouse", "OkHttp : écriture interrompue par une fermeture, ignorée", e) }
            else {
                // On garde la trace avant de fermer l'app : elle sera visible après le redémarrage.
                val before = CrashLog.last(this)?.time ?: 0L
                CrashLog.record(this, t, e, try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" })
                // Un kiosque ne doit pas rester figé derrière la fenêtre « l'application s'est arrêtée » d'Android : on ferme
                // l'app nous-mêmes et on la fait rouvrir deux secondes plus tard. Si elle vient déjà de planter (moins de
                // deux minutes), on laisse faire Android, pour ne pas redémarrer en boucle.
                if (System.currentTimeMillis() - before > 120_000L && scheduleRestart()) {
                    android.util.Log.e("TileHouse", "Erreur fatale, redémarrage de l'application", e)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(10)
                } else previous?.uncaughtException(t, e)
            }
        }
    }

    /** Demande à Android de rouvrir l'app dans deux secondes. Faux si ce n'est pas possible. */
    private fun scheduleRestart(): Boolean = try {
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getActivity(this, 77, android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2000, pi)
        true
    } catch (_: Throwable) { false }

    override fun onCreate() {
        super.onCreate()
        instance = this
        guardNetworkThreads()
        KitchenTimers.onDone = { timerDone(it) }
        config = ConfigStore(this)
        config.migrateSecrets()
        I18n.apply(config.language)
        ha = HaClient()
        ha.setEntities(config.allEntityIds())
        if (config.demo) ha.configureDemo() else if (config.isConfigured) ha.configure(config.url, config.token)
        web = WebServer(this)
        updateWebServer()
        mqtt = MqttDevice(this)
        KitchenTimers.listeners.add { mqtt.publishTimers() }
        updater = Updater(this)
        alerts = PersonAlerts(this)
        mqtt.start()
        updater.start()
        // À chaque (re)connexion à HA, on renvoie l'état de la tablette.
        ha.addListener(object : HaClient.Listener {
            override fun onHaStatus(status: HaClient.Status) {
                // Reconnexion : Home Assistant a pu redémarrer ou changer, la liste des entités gardée en mémoire est à refaire.
                if (status == HaClient.Status.CONNECTED) { catalog = null; refreshFloors(); refreshShortcutAreas() }
            }
        })
    }

    /** Alertes « personne détectée » des caméras. */
    lateinit var alerts: PersonAlerts
        private set

    /** Mises à jour par Wi-Fi (GitHub ou APK envoyé par la page web). */
    lateinit var updater: Updater
        private set
    val updaterOrNull get() = if (::updater.isInitialized) updater else null

    /** Notifications non lues (centre de notifications), conservées entre les redémarrages. */
    val notices by lazy { NoticeStore(this).also { it.listeners.add { if (::mqtt.isInitialized) mqtt.publishNotices() } } }
    /** Affichage d'une nouvelle notification (tableau de bord) ; null quand il n'est pas à l'écran. */
    var onNotice: ((StoredNotice) -> Unit)? = null
    /** Appelé quand HA efface une notification (étiquette) : ferme l'encadré s'il l'affiche. */
    var onNoticeCleared: ((String) -> Unit)? = null
    /** Notification arrivée pendant qu'un autre écran était affiché : montrée au retour. */
    var pendingNotice: Long? = null

    // ---- Minuterie terminée : la sonnerie appartient à l'application, pas à un écran. Elle continue donc
    // si l'écran est reconstruit, et l'écran principal revient devant pour montrer le bouton d'arrêt.
    private val timerAlarm by lazy { TimerAlarm(this) }
    /** Minuterie dont la fin n'a pas encore été confirmée (la carte doit être à l'écran). */
    var ringingTimer: KitchenTimer? = null
        private set
    /** Défini par l'écran principal tant qu'il est visible. */
    var onTimerDone: ((KitchenTimer) -> Unit)? = null

    private fun timerDone(t: KitchenTimer) {
        ringingTimer = t
        timerAlarm.start()
        if (::mqtt.isInitialized) mqtt.timerFinished(t)
        onTimerDone?.invoke(t) ?: startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun stopTimerAlarm() { ringingTimer = null; timerAlarm.stop() }

    /**
     * Essai du journal de plantage : provoque une vraie erreur fatale. Sert à vérifier que le plantage est noté,
     * que l'app se rouvre toute seule et que l'information remonte dans Home Assistant.
     */
    fun testCrash() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread({ throw IllegalStateException("Essai du journal de plantage (demandé depuis les réglages)") }, "essai-plantage").start()
        }, 600)
    }

    // ---- Fond d'écran « Photo Immich du jour » : une nouvelle photo de l'album du cadre photo chaque jour.
    private var immichBusy = false
    private var immichTriedAt = 0L

    /** Va chercher la photo du jour si le fond d'écran est « Photo Immich du jour » et qu'elle n'est pas encore là. [force] : une autre photo, maintenant. */
    fun refreshImmichWallpaper(force: Boolean = false) {
        val cfg = config
        if (immichBusy || cfg.demo || cfg.themeMode || cfg.wallpaper != PhotoWallpaper.IMMICH || !PhotoFrame.configured || cfg.photoSource.isEmpty()) return
        val c = java.util.Calendar.getInstance()
        val today = c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        if (!force && cfg.immichDay == today && PhotoWallpaper.exists(this, PhotoWallpaper.IMMICH)) return
        // Immich injoignable : on réessaie au plus toutes les 10 minutes, sans insister.
        if (!force && System.currentTimeMillis() - immichTriedAt < 10 * 60_000L) return
        immichBusy = true; immichTriedAt = System.currentTimeMillis()
        PhotoFrame.next(2048, 1280) { bmp ->
            if (bmp == null) { immichBusy = false; return@next }
            Thread {
                val ok = PhotoWallpaper.saveBitmap(this, bmp, PhotoWallpaper.IMMICH)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    immichBusy = false
                    if (ok) { cfg.immichDay = today; cfg.wallpaperRev = System.currentTimeMillis(); notifyConfigChanged() }
                }
            }.start()
        }
    }

    /** Point d'entrée de toutes les notifications (entité notify de HA, page web). */
    fun receiveNotice(raw: String) {
        if (raw.isBlank()) return
        val parsed = Notice.parse(raw)
        if (parsed.isClear) {
            notices.clearTag(parsed.tag)
            onNoticeCleared?.invoke(parsed.tag)
            return
        }
        val n = notices.add(raw)
        onNotice?.invoke(n) ?: run { pendingNotice = n.id }
    }

    /** La tablette comme appareil Home Assistant (MQTT). */
    lateinit var mqtt: MqttDevice
        private set

    /** Prévenus quand l'état d'une connexion change (HA, MQTT) : écrans de réglages. */
    val statusListeners = ArrayList<() -> Unit>()
    fun notifyStatusChanged() { for (l in statusListeners.toList()) l() }

    val snapshots by lazy { Snapshots(this) }

    /** Paliers et zones de HA (petites listes), gardés en mémoire pour l'accueil. */
    private fun refreshFloors() {
        ha.request("config/floor_registry/list") { fr ->
            ha.request("config/area_registry/list") { ar ->
                val floorsArr = fr.optJSONArray("result") ?: return@request
                val areasArr = ar.optJSONArray("result") ?: return@request
                val byFloor = HashMap<String, ArrayList<String>>()
                for (i in 0 until areasArr.length()) {
                    val a = areasArr.getJSONObject(i)
                    val f = a.str("floor_id")
                    if (f.isNotEmpty()) byFloor.getOrPut(f) { ArrayList() }.add(a.getString("area_id"))
                }
                val list = (0 until floorsArr.length()).map { i ->
                    val f = floorsArr.getJSONObject(i)
                    val id = f.getString("floor_id")
                    Floor(id, f.str("name").ifEmpty { id }, byFloor[id] ?: emptyList())
                }
                if (config.mergeFloors(list)) notifyConfigChanged()
            }
        }
    }

    /** Zone Home Assistant de chaque raccourci : décide dans quelle pièce il s'affiche (voir [Shortcuts]). */
    fun refreshShortcutAreas() {
        if (config.demo || ha.status != HaClient.Status.CONNECTED) return
        val ids = config.shortcuts
        Shortcuts.loadAreas(ha, ids) { areas ->
            if (config.demo || ids != config.shortcuts) return@loadAreas
            if (areas.toString() != config.shortcutAreas.toString()) { config.shortcutAreas = areas; notifyConfigChanged() }
        }
    }

    /** Active ou quitte la maison de démonstration (la vraie configuration est conservée). */
    fun setDemo(on: Boolean) {
        if (config.demo == on) return
        config.demo = on
        catalog = null
        ha.setEntities(config.allEntityIds())
        if (on) ha.configureDemo() else ha.configure(config.url, config.token)
        notifyConfigChanged()
    }

    fun updateWebServer() {
        if (config.webEnabled) web.start() else web.stop()
    }

    fun notifyConfigChanged() {
        // Changement de langue : on republie l'appareil MQTT avec les noms traduits
        val wasEnglish = I18n.english
        I18n.apply(config.language)
        if (wasEnglish != I18n.english && ::mqtt.isInitialized) mqtt.restart()
        if (wasEnglish != I18n.english && config.demo) { config.resetDemo(); catalog = null; ha.configureDemo() }
        for (l in configListeners.toList()) l()
        refreshImmichWallpaper()
        if (::alerts.isInitialized) alerts.rescan()
        mqtt.publishSettings()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) catalog = null
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
