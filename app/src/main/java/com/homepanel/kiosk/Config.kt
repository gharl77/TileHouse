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
                .put("theme", theme).put("wallpaper", wallpaper).put("dim", dimMinutes).put("motionWake", motionWake)
                .put("group", groupByCategory).put("clock", showClock).put("brightness", brightness)
                .put("language", language).put("personAlert", personAlert).put("alertSeconds", alertSeconds).put("alertTrigger", alertTrigger).put("doorbellAlert", doorbellAlert))
            .put("home", JSONObject()
                .put("weather", weatherEntity).put("calendars", JSONArray(calendars))
                .put("persons", JSONArray(persons)).put("floorOrder", JSONArray(floors.map { it.id })))
            .put("connection", JSONObject().put("url", url))
            .put("mqtt", JSONObject()
                .put("enabled", mqttEnabled).put("host", mqttHost).put("port", mqttPort)
                .put("user", mqttUser).put("deviceName", deviceName).put("deviceId", mqttDeviceId))
            .put("web", JSONObject().put("enabled", webEnabled))
        if (withSecrets) o.put("secrets", JSONObject().put("token", token).put("mqttPass", mqttPass).put("pin", pin))
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
            if (d.has("group")) groupByCategory = d.optBoolean("group")
            if (d.has("clock")) showClock = d.optBoolean("clock")
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
        }
    }

    fun newRoom(name: String, areaId: String? = null) = Room(UUID.randomUUID().toString(), name, areaId)

    fun allEntityIds(): Set<String> {
        val s = HashSet<String>()
        for (r in rooms) for (e in r.entities) s.add(e.id)
        // Accueil : météo et personnes
        if (weatherEntity.isNotEmpty()) s.add(weatherEntity)
        s.addAll(persons)
        return s
    }
}
