package com.homepanel.kiosk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * La tablette comme appareil Home Assistant via MQTT (découverte automatique),
 * comme les autres appareils maison : un seul appareil regroupant réglages,
 * commandes et capteurs. Le bouton « Visiter » de HA ouvre la page de configuration.
 */
class MqttDevice(private val app: App) : MqttClient.Listener {
    private val cfg get() = app.config
    private val main = Handler(Looper.getMainLooper())
    val client = MqttClient(this)

    /**
     * Identifiant de l'appareil dans HA. Créé au premier lancement à partir de l'identifiant
     * Android, puis conservé dans la configuration (et les sauvegardes) : il ne change pas
     * si l'app est réinstallée ou signée avec une autre clé.
     */
    val id: String
        @SuppressLint("HardwareIds") get() {
            val c = cfg
            if (c.mqttDeviceId.isEmpty()) c.mqttDeviceId = "homepanel_" +
                (Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "tablette").takeLast(6)
            return c.mqttDeviceId
        }
    private val base get() = "homepanel/$id"

    // Commandes reçues de HA, traitées par l'écran principal
    var onScreenOff: ((Boolean) -> Unit)? = null
    var onHome: (() -> Unit)? = null


    // État publié
    var screenOff = false
        private set
    var currentScreen = tr("Accueil")
        private set

    // Batterie
    private var level = -1
    private var charging = false
    private var plugType = ""
    private var temperature = 0.0

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }
    private val periodic = object : Runnable {
        override fun run() {
            publishDiagnostics()
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    fun start() {
        app.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { onBattery(it) }
        restart()
    }

    /** (Re)connexion au broker avec les réglages actuels. */
    fun restart() {
        val c = cfg
        if (!c.mqttEnabled || c.mqttHost.isBlank()) { client.stop(); return }
        client.start(c.mqttHost, c.mqttPort, c.mqttUser, c.mqttPass, id, "$base/status")
    }

    val status: String
        get() = when {
            !cfg.mqttEnabled -> tr("désactivé")
            cfg.mqttHost.isBlank() -> tr("non configuré")
            client.connected -> tr("connecté")
            else -> tr("déconnecté") + (client.lastError?.let { " ($it)" } ?: "")
        }

    // ------------------------------------------------------------ MqttClient.Listener

    override fun onMqttConnected() {
        publishDiscovery()
        client.publish("$base/status", "online", retain = true)
        client.subscribe("$base/+/set")
        publishAll()
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, 5 * 60_000L)
        app.notifyStatusChanged()
    }

    override fun onMqttDisconnected(error: String?) {
        main.removeCallbacks(periodic)
        app.notifyStatusChanged()
    }

    override fun onMqttMessage(topic: String, payload: String) {
        val key = topic.removePrefix("$base/").removeSuffix("/set")
        val v = payload.trim()
        var configChanged = false
        when (key) {
            "theme" -> {
                val k = if (v == tr("Aucun")) "" else Themes.all.firstOrNull { it.name == v }?.key
                if (k != null && k != cfg.theme) { cfg.theme = k; configChanged = true }
            }
            "fond" -> {
                val i = Wallpapers.names.indexOf(v)
                if (i >= 0) { cfg.wallpaper = Wallpapers.keys[i]; configChanged = true }
            }
            "veille" -> v.toDoubleOrNull()?.toInt()?.let { cfg.dimMinutes = it.coerceIn(0, 240); configChanged = true }
            "veille_style" -> { cfg.dimStyle = if (v == tr("Écran noir")) "black" else "clock"; configChanged = true }
            "veille_luminosite" -> v.toDoubleOrNull()?.toInt()?.let { cfg.dimBrightness = it.coerceIn(1, 100); configChanged = true }
            "luminosite" -> v.toDoubleOrNull()?.toInt()?.let { cfg.brightness = it.coerceIn(5, 100); configChanged = true }
            "regrouper" -> { cfg.groupByCategory = v == "ON"; configChanged = true }
            "horloge" -> { cfg.showClock = v == "ON"; configChanged = true }
            "afficher_batterie" -> { cfg.showBattery = v == "ON"; configChanged = true }
            "ecran_veille" -> onScreenOff?.invoke(v == "ON")
            "accueil" -> onHome?.invoke()
            "message" -> app.receiveNotice(v)
            "duree_notifications" -> v.toDoubleOrNull()?.toInt()?.let { cfg.noticeSeconds = it; configChanged = true }
            "update" -> if (v == "install") app.updater.installLatest()
        }
        if (configChanged) app.notifyConfigChanged() else publishAll()
    }

    // ------------------------------------------------------------ publication

    /** Publie l'état des réglages (appelé à chaque changement de configuration). */
    fun publishAll() {
        if (!client.connected) return
        val c = cfg
        st("theme", Themes.get(c.theme)?.name ?: tr("Aucun"))
        st("fond", Wallpapers.names.getOrNull(Wallpapers.keys.indexOf(c.wallpaper)) ?: Wallpapers.names[0])
        st("veille", c.dimMinutes.toString())
        st("luminosite", effectiveBrightness().toString())
        st("veille_style", if (c.dimStyle == "black") tr("Écran noir") else tr("Heure et météo"))
        st("veille_luminosite", c.dimBrightness.toString())
        st("regrouper", if (c.groupByCategory) "ON" else "OFF")
        st("horloge", if (c.showClock) "ON" else "OFF")
        st("afficher_batterie", if (c.showBattery) "ON" else "OFF")
        st("duree_notifications", c.noticeSeconds.toString())
        publishNotices()
        st("ecran_veille", if (screenOff) "ON" else "OFF")
        st("ecran", currentScreen)
        publishBattery()
        publishDiagnostics()
        publishUpdate()
    }

    /** Entité « update » de HA : version installée, dernière version, bouton Installer. */
    fun publishUpdate() {
        val u = app.updaterOrNull ?: return
        if (!client.connected) return
        client.publish("$base/update", u.mqttState().toString(), retain = true)
    }

    /** Nombre de notifications non lues, pour Home Assistant. */
    fun publishNotices() { if (client.connected) st("notifications_non_lues", app.notices.count.toString()) }

    fun setScreenOff(off: Boolean) { screenOff = off; st("ecran_veille", if (off) "ON" else "OFF") }
    fun setCurrentScreen(name: String) { currentScreen = name; st("ecran", name) }

    private fun st(key: String, value: String) = client.publish("$base/$key", value, retain = true)

    /** Luminosité appliquée : réglage de l'app, sinon celle du système. */
    fun effectiveBrightness(): Int {
        if (cfg.brightness > 0) return cfg.brightness
        val sys = try { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 200 }
        return (sys * 100 / 255).coerceIn(5, 100)
    }

    private fun onBattery(i: Intent) {
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val newLevel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val newCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        plugType = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> tr("secteur")
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> tr("sans fil")
            else -> tr("débranché")
        }
        temperature = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val changed = newLevel != level || newCharging != charging
        level = newLevel
        charging = newCharging
        if (changed) publishBattery()
    }

    private fun publishBattery() {
        if (!client.connected || level < 0) return
        st("batterie", level.toString())
        st("en_charge", if (charging) "ON" else "OFF")
        st("alimentation", plugType)
    }

    private fun publishDiagnostics() {
        if (!client.connected) return
        st("temperature_batterie", String.format(java.util.Locale.US, "%.1f", temperature))
        @Suppress("DEPRECATION")
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        wifi?.connectionInfo?.rssi?.let { if (it > -127 && it < 0) st("wifi", it.toString()) }
        WebServer.localIp()?.let { st("ip", it) }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.let { st("memoire", (it.totalPss / 1024).toString()) }
    }

    // ------------------------------------------------------------ découverte HA

    private fun device(): JSONObject {
        val d = JSONObject()
            .put("identifiers", JSONArray().put(id))
            .put("name", cfg.deviceName)
            .put("manufacturer", Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            .put("model", "${Build.MODEL} · TileHouse")
            .put("sw_version", try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "1" })
        app.web.url()?.let { d.put("configuration_url", it) }
        return d
    }

    private fun entity(component: String, key: String, name: String, icon: String?, extra: JSONObject.() -> Unit) {
        val o = JSONObject()
            .put("name", name)
            .put("unique_id", "${id}_$key")
            .put("availability_topic", "$base/status")
            .put("device", device())
        if (icon != null) o.put("icon", icon)
        o.extra()
        client.publish("homeassistant/$component/$id/$key/config", o.toString(), retain = true)
    }

    private fun publishDiscovery() {
        // Réglages
        entity("select", "theme", tr("Thème"), "mdi:palette") {
            put("state_topic", "$base/theme"); put("command_topic", "$base/theme/set")
            put("options", JSONArray(listOf(tr("Aucun")) + Themes.all.map { it.name }))
        }
        entity("select", "fond", tr("Fond d'écran"), "mdi:image-filter-hdr") {
            put("state_topic", "$base/fond"); put("command_topic", "$base/fond/set")
            put("options", JSONArray(Wallpapers.names))
        }
        entity("number", "veille", tr("Veille après"), "mdi:timer-sand") {
            put("state_topic", "$base/veille"); put("command_topic", "$base/veille/set")
            put("min", 0); put("max", 60); put("step", 1); put("unit_of_measurement", "min"); put("mode", "slider")
        }
        entity("number", "luminosite", tr("Luminosité de l'écran"), "mdi:brightness-6") {
            put("state_topic", "$base/luminosite"); put("command_topic", "$base/luminosite/set")
            put("min", 5); put("max", 100); put("step", 5); put("unit_of_measurement", "%"); put("mode", "slider")
        }
        entity("select", "veille_style", tr("Écran de veille"), "mdi:sleep") {
            put("state_topic", "$base/veille_style"); put("command_topic", "$base/veille_style/set")
            put("options", JSONArray(listOf(tr("Heure et météo"), tr("Écran noir"))))
        }
        entity("number", "veille_luminosite", tr("Luminosité en veille"), "mdi:brightness-4") {
            put("state_topic", "$base/veille_luminosite"); put("command_topic", "$base/veille_luminosite/set")
            put("min", 1); put("max", 60); put("step", 1); put("unit_of_measurement", "%"); put("mode", "slider")
        }
        entity("switch", "regrouper", tr("Regrouper par catégorie"), "mdi:format-list-group") {
            put("state_topic", "$base/regrouper"); put("command_topic", "$base/regrouper/set"); put("entity_category", "config")
        }
        entity("switch", "horloge", tr("Afficher l'heure"), "mdi:clock-outline") {
            put("state_topic", "$base/horloge"); put("command_topic", "$base/horloge/set"); put("entity_category", "config")
        }
        entity("sensor", "notifications_non_lues", tr("Notifications non lues"), "mdi:bell-badge") {
            put("state_topic", "$base/notifications_non_lues"); put("state_class", "measurement")
        }
        entity("number", "duree_notifications", tr("Durée des notifications"), "mdi:timer-outline") {
            put("state_topic", "$base/duree_notifications"); put("command_topic", "$base/duree_notifications/set")
            put("min", 5); put("max", 300); put("step", 5); put("unit_of_measurement", "s"); put("mode", "box"); put("entity_category", "config")
        }
        entity("switch", "afficher_batterie", tr("Afficher la batterie"), "mdi:battery-outline") {
            put("state_topic", "$base/afficher_batterie"); put("command_topic", "$base/afficher_batterie/set"); put("entity_category", "config")
        }
        // Commandes
        entity("switch", "ecran_veille", tr("Écran en veille"), "mdi:monitor-off") {
            put("state_topic", "$base/ecran_veille"); put("command_topic", "$base/ecran_veille/set")
        }
        entity("button", "accueil", tr("Retour à l'accueil"), "mdi:home") {
            put("command_topic", "$base/accueil/set")
        }
        entity("notify", "message", "Message", "mdi:message-text") {
            put("command_topic", "$base/message/set")
        }
        entity("sensor", "ecran", tr("Écran affiché"), "mdi:view-dashboard") {
            put("state_topic", "$base/ecran")
        }
        // Batterie
        entity("sensor", "batterie", tr("Batterie"), null) {
            put("state_topic", "$base/batterie"); put("device_class", "battery"); put("unit_of_measurement", "%"); put("state_class", "measurement")
        }
        entity("binary_sensor", "en_charge", tr("En charge"), null) {
            put("state_topic", "$base/en_charge"); put("device_class", "battery_charging")
        }
        entity("sensor", "alimentation", tr("Alimentation"), "mdi:power-plug") {
            put("state_topic", "$base/alimentation")
        }
        // Diagnostic
        entity("sensor", "temperature_batterie", tr("Température batterie"), null) {
            put("state_topic", "$base/temperature_batterie"); put("device_class", "temperature"); put("unit_of_measurement", "°C")
            put("state_class", "measurement"); put("entity_category", "diagnostic")
        }
        entity("sensor", "wifi", tr("Signal Wi-Fi"), null) {
            put("state_topic", "$base/wifi"); put("device_class", "signal_strength"); put("unit_of_measurement", "dBm")
            put("state_class", "measurement"); put("entity_category", "diagnostic")
        }
        entity("sensor", "ip", tr("Adresse IP"), "mdi:ip-network") {
            put("state_topic", "$base/ip"); put("entity_category", "diagnostic")
        }
        entity("update", "update", "TileHouse", null) {
            put("state_topic", "$base/update"); put("command_topic", "$base/update/set"); put("payload_install", "install")
            put("device_class", "firmware"); put("entity_category", "config")
        }
        entity("sensor", "memoire", tr("Mémoire utilisée"), "mdi:memory") {
            put("state_topic", "$base/memoire"); put("unit_of_measurement", "Mo"); put("state_class", "measurement"); put("entity_category", "diagnostic")
        }
    }
}
