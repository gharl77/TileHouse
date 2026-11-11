package com.homepanel.kiosk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class RoomEntity(val id: String, var name: String? = null)

class Room(val id: String, var name: String, var areaId: String? = null) {
    val entities = ArrayList<RoomEntity>()
    fun find(entityId: String) = entities.firstOrNull { it.id == entityId }
}

/** Palier (étage) de Home Assistant et ses zones. */
class Floor(val id: String, val name: String, val areas: List<String>)

class ConfigStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v).apply()
    // Secrets : chiffrés au repos (Android Keystore), gardés déchiffrés en mémoire.
    private val secretCache = HashMap<String, String>()
    private fun secret(k: String): String = synchronized(secretCache) {
        secretCache.getOrPut(k) { SecretBox.decrypt(p.getString(k, "") ?: "") }
    }
    private fun setSecret(k: String, v: String) {
        synchronized(secretCache) { secretCache[k] = v }
        p.edit().putString(k, SecretBox.encrypt(v)).apply()
    }

    /** Chiffre les secrets encore stockés en clair (versions précédentes de l'app). */
    fun migrateSecrets() {
        for (k in listOf("token", "pin", "mqttPass")) {
            val raw = p.getString(k, "") ?: ""
            if (raw.isNotEmpty() && !SecretBox.isEncrypted(raw)) setSecret(k, raw)
        }
    }

    var token: String
        get() = secret("token")
        set(v) = setSecret("token", v)
    var pin: String
        get() = secret("pin")
        set(v) = setSecret("pin", v)
    var wallpaper: String
        get() = p.getString("wallpaper", "nuit") ?: "nuit"
        set(v) = p.edit().putString("wallpaper", v).apply()
    /** Thème de fête/saison activé manuellement ("" = aucun, on garde le fond d'écran). */
    var theme: String
        get() = p.getString("theme", "") ?: ""
        set(v) = p.edit().putString("theme", v).apply()
    /** Langue : "auto" (celle de la tablette), "fr" ou "en". */
    var language: String
        get() = p.getString("lang", "auto") ?: "auto"
        set(v) = p.edit().putString("lang", v).apply()
    var dimMinutes: Int
        get() = p.getInt("dim", 0)
        set(v) = p.edit().putInt("dim", v).apply()
    /** Écran de veille : "black" (noir), "clock" (heure + météo) ou "photos" (cadre photo). */
    var dimStyle: String
        get() = p.getString("dimStyle", "clock") ?: "clock"
        set(v) = p.edit().putString("dimStyle", if (v in setOf("black", "photos")) v else "clock").apply()
    /** Style réellement affiché : noir pendant les heures calmes. */
    val effectiveDimStyle: String get() = if (isQuietNow()) "black" else dimStyle
    /** Luminosité de l'écran de veille « heure + météo », en %. */
    var dimBrightness: Int
        get() = p.getInt("dimBrightness", 10)
        set(v) = p.edit().putInt("dimBrightness", v.coerceIn(1, 100)).apply()
    /** Réveil par mouvement devant la caméra avant (désactivé par défaut). */
    var motionWake: Boolean
        get() = p.getBoolean("motionWake", false)
        set(v) = p.edit().putBoolean("motionWake", v).apply()
    var groupByCategory: Boolean
        get() = p.getBoolean("group", true)
        set(v) = p.edit().putBoolean("group", v).apply()
    var showClock: Boolean
        get() = p.getBoolean("clock", true)
        set(v) = p.edit().putBoolean("clock", v).apply()
    /** Alerte : live plein écran quand une caméra détecte une personne. */
    var personAlert: Boolean
        get() = p.getBoolean("personAlert", true)
        set(v) = p.edit().putBoolean("personAlert", v).apply()
    /** Alerte quand quelqu'un sonne à une sonnette-caméra. */
    var doorbellAlert: Boolean
        get() = p.getBoolean("doorbellAlert", true)
        set(v) = p.edit().putBoolean("doorbellAlert", v).apply()
    /** Déclencheur de l'alerte : "person" (personne seulement) ou "motion" (tout mouvement). */
    var alertTrigger: String
        get() = p.getString("alertTrigger", "person") ?: "person"
        set(v) = p.edit().putString("alertTrigger", if (v == "motion") "motion" else "person").apply()
    /** Durée de l'alerte, en secondes. */
    var alertSeconds: Int
        get() = p.getInt("alertSeconds", 20)
        set(v) = p.edit().putInt("alertSeconds", v.coerceIn(5, 120)).apply()
    /** Sons des notifications ; désactivé = silence en tout temps (les minuteries sonnent quand même). */
    var soundsEnabled: Boolean
        get() = p.getBoolean("soundsEnabled", true)
        set(v) = p.edit().putBoolean("soundsEnabled", v).apply()
    /** Heures calmes : pas de son (sauf alertes rouges) et écran de veille noir. */
    var quietEnabled: Boolean
        get() = p.getBoolean("quietEnabled", true)
        set(v) = p.edit().putBoolean("quietEnabled", v).apply()
    /** Début et fin des heures calmes, en minutes depuis minuit (22:00 → 7:00 par défaut). */
    var quietStart: Int
        get() = p.getInt("quietStart", 22 * 60)
        set(v) = p.edit().putInt("quietStart", v.coerceIn(0, 1439)).apply()
    var quietEnd: Int
        get() = p.getInt("quietEnd", 7 * 60)
        set(v) = p.edit().putInt("quietEnd", v.coerceIn(0, 1439)).apply()
    /** Vrai pendant les heures calmes (la plage peut passer minuit). */
    fun isQuietNow(): Boolean {
        if (!quietEnabled) return false
        val c = java.util.Calendar.getInstance()
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val s = quietStart; val e = quietEnd
        return if (s == e) false else if (s < e) m in s until e else m >= s || m < e
    }

    /** Calendriers des collectes (vide = pas de pastille). */
    var recycleCalendar: String
        get() = if (demo) "calendar.collection" else p.getString("recycleCalendar", "") ?: ""
        set(v) = p.edit().putString("recycleCalendar", v).apply()
    /** Mots clés cherchés dans le titre des événements (sans tenir compte des majuscules ni des accents). */
    var recycleKeyword: String
        get() = if (demo) "recycl" else p.getString("recycleKeyword", "recycl") ?: "recycl"
        set(v) = p.edit().putString("recycleKeyword", v.trim()).apply()
    var wasteKeyword: String
        get() = if (demo) (if (I18n.english) "garbage" else "ordure") else p.getString("wasteKeyword", "ordure") ?: "ordure"
        set(v) = p.edit().putString("wasteKeyword", v.trim()).apply()
    var wasteCalendar: String
        get() = if (demo) "calendar.collection" else p.getString("wasteCalendar", "") ?: ""
        set(v) = p.edit().putString("wasteCalendar", v).apply()

    /** Serveur Immich du cadre photo (ex. http://192.168.1.20:2283) et clé API (chiffrée). */
    var immichUrl: String
        get() = p.getString("immichUrl", "") ?: ""
        set(v) = p.edit().putString("immichUrl", v.trim().trimEnd('/')).apply()
    var immichKey: String
        get() = secret("immichKey")
        set(v) = setSecret("immichKey", v.trim())

    // ------------------------------------------------------------ accueil : infos et raccourcis

    /** Calendrier des pointes d'électricité (Hydro-Québec), vide = pas de pastille. */
    var peakCalendar: String
        get() = if (demo) "calendar.peaks" else p.getString("peakCalendar", "") ?: ""
        set(v) = p.edit().putString("peakCalendar", v).apply()
    /** Rappel (notification) avant le début d'une pointe, en minutes ; 0 = aucun. */
    var peakReminder: Int
        get() = if (demo) 0 else p.getInt("peakReminder", 60)
        set(v) = p.edit().putInt("peakReminder", v.coerceIn(0, 720)).apply()
    /** Capteurs d'alertes météo (nombre d'alertes en vigueur), ex. Environnement Canada. */
    var weatherAlerts: List<String>
        get() = if (demo) listOf("sensor.weather_advisories") else csv("weatherAlerts")
        set(v) = p.edit().putString("weatherAlerts", v.joinToString(",")).apply()
    /** Raccourcis de l'accueil : scripts, scènes, boutons. */
    var shortcuts: List<String>
        get() = if (demo) listOf("scene.movie_night", "script.goodnight") else csv("shortcuts")
        set(v) = p.edit().putString("shortcuts", v.joinToString(",")).apply()
    /** Pastilles personnalisées : [{entity, states:[..], text, color}] (JSON). */
    var customChips: JSONArray
        get() = if (demo) JSONArray().put(JSONObject().put("entity", "input_select.washer").put("states", JSONArray().put(if (I18n.english) "To empty" else "À vider")).put("text", if (I18n.english) "🧺 Washer to empty" else "🧺 Laveuse à vider").put("color", "orange")) else try { JSONArray(p.getString("customChips", "[]")) } catch (_: Exception) { JSONArray() }
        set(v) = p.edit().putString("customChips", v.toString()).apply()
    /** Carte du matin : sources [{entity, attribute, title}] (JSON) et plage horaire (minutes). */
    var morningSources: JSONArray
        get() = if (demo) JSONArray().put(JSONObject().put("entity", "sensor.morning_summary").put("attribute", "weather").put("title", if (I18n.english) "Weather" else "Météo")).put(JSONObject().put("entity", "sensor.morning_summary").put("attribute", "commute").put("title", if (I18n.english) "Commute" else "Trajet")).put(JSONObject().put("entity", "sensor.local_news").put("attribute", "").put("title", if (I18n.english) "Local news" else "Nouvelles locales")) else try { JSONArray(p.getString("morningSources", "[]")) } catch (_: Exception) { JSONArray() }
        set(v) = p.edit().putString("morningSources", v.toString()).apply()
    var morningStart: Int
        get() = if (demo) 0 else p.getInt("morningStart", 5 * 60)
        set(v) = p.edit().putInt("morningStart", v.coerceIn(0, 1439)).apply()
    var morningEnd: Int
        get() = if (demo) 1439 else p.getInt("morningEnd", 10 * 60)
        set(v) = p.edit().putInt("morningEnd", v.coerceIn(0, 1439)).apply()
    fun isMorningNow(): Boolean {
        val c = java.util.Calendar.getInstance()
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        return if (morningStart <= morningEnd) m in morningStart until morningEnd else m >= morningStart || m < morningEnd
    }

    /** Cadre photo : "favorites" ou "album:<id>" (Immich), vide = désactivé. */
    var photoSource: String
        get() = p.getString("photoSource", "") ?: ""
        set(v) = p.edit().putString("photoSource", v).apply()
    var photoSourceName: String
        get() = p.getString("photoSourceName", "") ?: ""
        set(v) = p.edit().putString("photoSourceName", v).apply()
    /** Secondes entre deux photos. */
    var photoSeconds: Int
        get() = p.getInt("photoSeconds", 30)
        set(v) = p.edit().putInt("photoSeconds", v.coerceIn(5, 600)).apply()

    /** Durée maximale d'affichage d'une notification, en secondes (non lue ensuite → clochette). */
    var noticeSeconds: Int
        get() = p.getInt("noticeSeconds", 30)
        set(v) = p.edit().putInt("noticeSeconds", v.coerceIn(5, 300)).apply()
    /** Batterie de la tablette affichée à côté de l'heure. */
    var showBattery: Boolean
        get() = p.getBoolean("showBattery", true)
        set(v) = p.edit().putBoolean("showBattery", v).apply()
    var lastRoom: Int
        get() = p.getInt("lastRoom", 0)
        set(v) = p.edit().putInt("lastRoom", v).apply()

    var exposeEnabled: Boolean
        get() = p.getBoolean("expose", true)
        set(v) = p.edit().putBoolean("expose", v).apply()
    var deviceName: String
        get() = p.getString("deviceName", "Tablette TileHouse") ?: "Tablette TileHouse"
        set(v) = p.edit().putString("deviceName", v).apply()
    /** Webhook obtenu à l'enregistrement mobile_app, et l'adresse HA correspondante. */
    var webhookId: String
        get() = p.getString("webhook", "") ?: ""
        set(v) = p.edit().putString("webhook", v).apply()
    var webhookUrl: String
        get() = p.getString("webhookUrl", "") ?: ""
        set(v) = p.edit().putString("webhookUrl", v).apply()

    // --- Mode démo : maison fictive en mémoire, la vraie configuration n'est jamais modifiée.
    var demo: Boolean
        get() = p.getBoolean("demo", false)
        set(v) { p.edit().putBoolean("demo", v).apply(); resetDemo() }
    private var demoRooms: MutableList<Room>? = null
    private var demoFloors: List<Floor>? = null
    private var demoWeather = Demo.WEATHER
    private var demoCalendars = Demo.CALENDARS
    private var demoPersons = Demo.PERSONS
    /** Reconstruit la maison fictive (ex. après un changement de langue). */
    fun resetDemo() { demoRooms = null; demoFloors = null; demoWeather = Demo.WEATHER; demoCalendars = Demo.CALENDARS; demoPersons = Demo.PERSONS }

    // --- Page d'accueil
    var weatherEntity: String
        get() = if (demo) demoWeather else p.getString("weather", "") ?: ""
        set(v) { if (demo) demoWeather = v else p.edit().putString("weather", v).apply() }
    var calendars: List<String>
        get() = if (demo) demoCalendars else csv("calendars")
        set(v) { if (demo) demoCalendars = v else p.edit().putString("calendars", v.joinToString(",")).apply() }
    var persons: List<String>
        get() = if (demo) demoPersons else csv("persons")
        set(v) { if (demo) demoPersons = v else p.edit().putString("persons", v.joinToString(",")).apply() }

    private fun csv(k: String) = (p.getString(k, "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Paliers de HA, dans l'ordre choisi par l'utilisateur. */
    private var realFloors: List<Floor> = loadFloors()
    val floors: List<Floor>
        get() = if (demo) demoFloors ?: Demo.floors().also { demoFloors = it } else realFloors

    private fun loadFloors(): List<Floor> = try {
        val arr = JSONArray(p.getString("floors", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.optJSONArray("areas") ?: JSONArray()
            Floor(o.getString("id"), o.getString("name"), (0 until a.length()).map { a.getString(it) })
        }
    } catch (_: Exception) { emptyList() }

    private fun saveFloors(list: List<Floor>) {
        if (demo) { demoFloors = list; return }
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("id", f.id).put("name", f.name).put("areas", JSONArray(f.areas)))
        p.edit().putString("floors", arr.toString()).apply()
        realFloors = list
    }

    /** Met à jour les paliers depuis HA en gardant l'ordre choisi. Retourne true si quelque chose a changé. */
    fun mergeFloors(fresh: List<Floor>): Boolean {
        val order = floors.map { it.id }
        val sorted = fresh.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val same = sorted.size == floors.size && sorted.zip(floors).all { (a, b) -> a.id == b.id && a.name == b.name && a.areas == b.areas }
        if (!same) saveFloors(sorted)
        return !same
    }

    fun setFloorOrder(ids: List<String>) {
        saveFloors(floors.sortedBy { ids.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } })
    }

    // --- MQTT (la tablette comme appareil Home Assistant)
    var mqttEnabled: Boolean
        get() = p.getBoolean("mqttOn", true)
        set(v) = p.edit().putBoolean("mqttOn", v).apply()
    var mqttHost: String
        get() = p.getString("mqttHost", null) ?: android.net.Uri.parse(url).host ?: ""
        set(v) = p.edit().putString("mqttHost", v).apply()
    var mqttPort: Int
        get() = p.getInt("mqttPort", 1883)
        set(v) = p.edit().putInt("mqttPort", v).apply()
    var mqttUser: String
        get() = p.getString("mqttUser", "") ?: ""
        set(v) = p.edit().putString("mqttUser", v).apply()
    var mqttPass: String
        get() = secret("mqttPass")
        set(v) = setSecret("mqttPass", v)
    /** Identifiant de l'appareil MQTT dans HA : fixé au premier lancement, conservé dans les sauvegardes. */
    var mqttDeviceId: String
        get() = p.getString("mqttDeviceId", "") ?: ""
        set(v) = p.edit().putString("mqttDeviceId", v).apply()
    /** Luminosité de l'écran imposée par l'app (5–100 %), -1 = celle du système. */
    var brightness: Int
        get() = p.getInt("brightness", -1)
        set(v) = p.edit().putInt("brightness", v).apply()

    var webEnabled: Boolean
        get() = p.getBoolean("web", true)
        set(v) = p.edit().putBoolean("web", v).apply()

    val isConfigured get() = demo || (url.isNotBlank() && token.isNotBlank())

    private val realRooms: MutableList<Room> = loadRooms()
    val rooms: MutableList<Room>
        get() = if (demo) demoRooms ?: Demo.rooms().also { demoRooms = it } else realRooms

    private fun loadRooms(): MutableList<Room> {
        val raw = p.getString("rooms", null) ?: return ArrayList()
        return try { parseRooms(JSONArray(raw)) } catch (_: Exception) { ArrayList() }
    }

    /** Lève une exception si le JSON est invalide. */
    fun parseRooms(arr: JSONArray): MutableList<Room> {
        val list = ArrayList<Room>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val r = Room(o.str("id").ifEmpty { UUID.randomUUID().toString() }, o.getString("name"), o.str("area").ifEmpty { null })
            val ents = o.optJSONArray("entities") ?: JSONArray()
            val seen = HashSet<String>()
            for (j in 0 until ents.length()) {
                val e = ents.getJSONObject(j)
                val id = e.getString("id")
                if (seen.add(id)) r.entities.add(RoomEntity(id, e.str("name").trim().ifEmpty { null }))
            }
            list.add(r)
        }
        return list
    }

    fun roomsToJson(): JSONArray {
        val arr = JSONArray()
        for (r in rooms) {
            val ents = JSONArray()
            for (e in r.entities) {
                val o = JSONObject().put("id", e.id)
                e.name?.let { o.put("name", it) }
                ents.put(o)
            }
            val o = JSONObject().put("id", r.id).put("name", r.name).put("entities", ents)
            r.areaId?.let { o.put("area", it) }
            arr.put(o)
        }
        return arr
    }

    /** Numéro de version des pièces : évite qu'une page web périmée écrase des changements. */
    var roomsRev = (System.currentTimeMillis() / 1000 % 1_000_000_000).toInt()
        private set

    fun saveRooms() {
        roomsRev++
        if (!demo) p.edit().putString("rooms", roomsToJson().toString()).apply()
    }

    // ------------------------------------------------------------ sauvegarde

    /**
     * Configuration complète en JSON (pièces, noms, ordres, réglages).
     * Les secrets (jeton HA, mot de passe MQTT, PIN) ne sont inclus que sur demande explicite.
     */
    fun exportJson(withSecrets: Boolean): JSONObject {
        val o = JSONObject()
            .put("format", "homepanel-config")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("rooms", roomsToJson())
            .put("display", JSONObject()
                .put("theme", theme).put("wallpaper", wallpaper).put("dim", dimMinutes).put("motionWake", motionWake).put("dimStyle", dimStyle).put("dimBrightness", dimBrightness)
                .put("group", groupByCategory).put("clock", showClock).put("battery", showBattery).put("noticeSeconds", noticeSeconds)
                .put("quietEnabled", quietEnabled).put("quietStart", quietStart).put("quietEnd", quietEnd).put("soundsEnabled", soundsEnabled)
                .put("photoSource", photoSource).put("photoSourceName", photoSourceName).put("photoSeconds", photoSeconds)
                .put("immichUrl", immichUrl).put("brightness", brightness)
                .put("language", language).put("personAlert", personAlert).put("alertSeconds", alertSeconds).put("alertTrigger", alertTrigger).put("doorbellAlert", doorbellAlert))
            .put("home", JSONObject()
                .put("weather", weatherEntity).put("calendars", JSONArray(calendars))
                .put("persons", JSONArray(persons)).put("recycleCalendar", recycleCalendar).put("wasteCalendar", wasteCalendar)
                .put("peakCalendar", peakCalendar).put("peakReminder", peakReminder).put("weatherAlerts", JSONArray(weatherAlerts))
                .put("shortcuts", JSONArray(shortcuts)).put("customChips", customChips).put("morningSources", morningSources)
                .put("morningStart", morningStart).put("morningEnd", morningEnd)
                .put("recycleKeyword", recycleKeyword).put("wasteKeyword", wasteKeyword).put("floorOrder", JSONArray(floors.map { it.id })))
            .put("connection", JSONObject().put("url", url))
            .put("mqtt", JSONObject()
                .put("enabled", mqttEnabled).put("host", mqttHost).put("port", mqttPort)
                .put("user", mqttUser).put("deviceName", deviceName).put("deviceId", mqttDeviceId))
            .put("web", JSONObject().put("enabled", webEnabled))
        if (withSecrets) o.put("secrets", JSONObject().put("token", token).put("mqttPass", mqttPass).put("pin", pin).put("immichKey", immichKey))
        return o
    }

    /** Applique une sauvegarde. Lève une exception si le fichier n'est pas reconnu. */
    fun importJson(o: JSONObject) {
        require(o.optString("format") == "homepanel-config") { "Ce fichier n'est pas une sauvegarde de l'app." }
        o.optJSONArray("rooms")?.let { val r = parseRooms(it); rooms.clear(); rooms.addAll(r); saveRooms() }
        o.optJSONObject("display")?.let { d ->
            if (d.has("theme")) theme = d.str("theme")
            if (d.has("wallpaper")) wallpaper = d.str("wallpaper")
            if (d.has("dim")) dimMinutes = d.optInt("dim")
            if (d.has("motionWake")) motionWake = d.optBoolean("motionWake")
            if (d.has("dimStyle")) dimStyle = d.str("dimStyle")
            if (d.has("dimBrightness")) dimBrightness = d.optInt("dimBrightness", 10)
            if (d.has("group")) groupByCategory = d.optBoolean("group")
            if (d.has("clock")) showClock = d.optBoolean("clock")
            if (d.has("battery")) showBattery = d.optBoolean("battery")
            if (d.has("noticeSeconds")) noticeSeconds = d.optInt("noticeSeconds", 30)
            if (d.has("quietEnabled")) quietEnabled = d.optBoolean("quietEnabled")
            if (d.has("soundsEnabled")) soundsEnabled = d.optBoolean("soundsEnabled")
            if (d.has("quietStart")) quietStart = d.optInt("quietStart", 1320)
            if (d.has("quietEnd")) quietEnd = d.optInt("quietEnd", 420)
            if (d.has("photoSource")) photoSource = d.str("photoSource")
            if (d.has("photoSourceName")) photoSourceName = d.str("photoSourceName")
            if (d.has("photoSeconds")) photoSeconds = d.optInt("photoSeconds", 30)
            if (d.has("immichUrl")) immichUrl = d.str("immichUrl")
            if (d.has("brightness")) brightness = d.optInt("brightness", -1)
            if (d.has("language")) language = d.str("language")
            if (d.has("personAlert")) personAlert = d.optBoolean("personAlert")
            if (d.has("alertSeconds")) alertSeconds = d.optInt("alertSeconds", 20)
            if (d.has("alertTrigger")) alertTrigger = d.str("alertTrigger")
            if (d.has("doorbellAlert")) doorbellAlert = d.optBoolean("doorbellAlert")
        }
        o.optJSONObject("home")?.let { h ->
            if (h.has("weather")) weatherEntity = h.str("weather")
            h.optJSONArray("calendars")?.let { a -> calendars = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("persons")?.let { a -> persons = (0 until a.length()).map { a.getString(it) } }
            if (h.has("recycleCalendar")) recycleCalendar = h.str("recycleCalendar")
            if (h.has("wasteCalendar")) wasteCalendar = h.str("wasteCalendar")
            if (h.has("recycleKeyword")) recycleKeyword = h.str("recycleKeyword")
            if (h.has("wasteKeyword")) wasteKeyword = h.str("wasteKeyword")
            if (h.has("peakCalendar")) peakCalendar = h.str("peakCalendar")
            if (h.has("peakReminder")) peakReminder = h.optInt("peakReminder", 60)
            h.optJSONArray("weatherAlerts")?.let { a -> weatherAlerts = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("shortcuts")?.let { a -> shortcuts = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("customChips")?.let { customChips = it }
            h.optJSONArray("morningSources")?.let { morningSources = it }
            if (h.has("morningStart")) morningStart = h.optInt("morningStart", 300)
            if (h.has("morningEnd")) morningEnd = h.optInt("morningEnd", 600)
            h.optJSONArray("floorOrder")?.let { a -> setFloorOrder((0 until a.length()).map { a.getString(it) }) }
        }
        o.optJSONObject("connection")?.let { c -> if (c.str("url").isNotEmpty()) url = c.str("url") }
        o.optJSONObject("mqtt")?.let { m ->
            if (m.has("enabled")) mqttEnabled = m.optBoolean("enabled")
            if (m.str("host").isNotEmpty()) mqttHost = m.str("host")
            if (m.has("port")) mqttPort = m.optInt("port", 1883)
            if (m.has("user")) mqttUser = m.str("user")
            if (m.str("deviceName").isNotEmpty()) deviceName = m.str("deviceName")
            // Même appareil dans HA après une réinstallation ou un changement de tablette
            if (Regex("homepanel_[a-z0-9]{4,32}").matches(m.str("deviceId"))) mqttDeviceId = m.str("deviceId")
        }
        o.optJSONObject("web")?.let { w -> if (w.has("enabled")) webEnabled = w.optBoolean("enabled") }
        o.optJSONObject("secrets")?.let { s ->
            if (s.str("token").isNotEmpty()) token = s.str("token")
            if (s.str("mqttPass").isNotEmpty()) mqttPass = s.str("mqttPass")
            if (s.has("pin")) pin = s.str("pin")
            if (s.str("immichKey").isNotEmpty()) immichKey = s.str("immichKey")
        }
    }

    fun newRoom(name: String, areaId: String? = null) = Room(UUID.randomUUID().toString(), name, areaId)

    fun allEntityIds(): Set<String> {
        val s = HashSet<String>()
        for (r in rooms) for (e in r.entities) s.add(e.id)
        // Accueil : météo et personnes
        if (weatherEntity.isNotEmpty()) s.add(weatherEntity)
        s.addAll(persons)
        // Accueil : alertes météo, raccourcis, pastilles personnalisées, carte du matin
        s.addAll(weatherAlerts)
        s.addAll(shortcuts)
        for (a in listOf(customChips, morningSources)) for (i in 0 until a.length()) a.optJSONObject(i)?.optString("entity")?.takeIf { it.isNotEmpty() }?.let { s.add(it) }
        return s
    }
}
