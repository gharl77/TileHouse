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

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        mqtt.start()
        updater.start()
        // À chaque (re)connexion à HA, on renvoie l'état de la tablette.
        ha.addListener(object : HaClient.Listener {
            override fun onHaStatus(status: HaClient.Status) {
                if (status == HaClient.Status.CONNECTED) refreshFloors()
            }
        })
    }

    /** Mises à jour par Wi-Fi (GitHub ou APK envoyé par la page web). */
    lateinit var updater: Updater
        private set
    val updaterOrNull get() = if (::updater.isInitialized) updater else null

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
        mqtt.publishAll()
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
