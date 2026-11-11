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
            if (libraryRace) android.util.Log.w("TileHouse", "OkHttp : écriture interrompue par une fermeture, ignorée", e)
            else previous?.uncaughtException(t, e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        guardNetworkThreads()
        config = ConfigStore(this)
        config.migrateSecrets()
        I18n.apply(config.language)
        ha = HaClient()
        ha.setEntities(config.allEntityIds())
        if (config.demo) ha.configureDemo() else if (config.isConfigured) ha.configure(config.url, config.token)
        web = WebServer(this)
        updateWebServer()
        mqtt = MqttDevice(this)
        updater = Updater(this)
        alerts = PersonAlerts(this)
        mqtt.start()
        updater.start()
        // À chaque (re)connexion à HA, on renvoie l'état de la tablette.
        ha.addListener(object : HaClient.Listener {
            override fun onHaStatus(status: HaClient.Status) {
                if (status == HaClient.Status.CONNECTED) refreshFloors()
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
