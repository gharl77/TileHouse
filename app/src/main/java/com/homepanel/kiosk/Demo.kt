package com.homepanel.kiosk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Mode démo : une maison fictive, sans Home Assistant.
 * Sert à essayer l'app et à produire les captures d'écran de la documentation.
 * Rien n'est enregistré : la vraie configuration reste intacte.
 */
object Demo {
    private fun n(fr: String, en: String) = if (I18n.english) en else fr

    // ------------------------------------------------------------ maison

    class DemoArea(val id: String, val fr: String, val en: String, val floor: String)

    private val AREAS = listOf(
        DemoArea("living_room", "Salon", "Living Room", "ground"),
        DemoArea("kitchen", "Cuisine", "Kitchen", "ground"),
        DemoArea("dining_room", "Salle à manger", "Dining Room", "ground"),
        DemoArea("entrance", "Entrée", "Entrance", "ground"),
        DemoArea("master_bedroom", "Chambre principale", "Master Bedroom", "upstairs"),
        DemoArea("kids_room", "Chambre des enfants", "Kids' Room", "upstairs"),
        DemoArea("office", "Bureau", "Office", "upstairs"),
        DemoArea("bathroom", "Salle de bain", "Bathroom", "upstairs"),
        DemoArea("garage", "Garage", "Garage", "outside"),
        DemoArea("garden", "Jardin", "Garden", "outside"),
    )

    private fun floorName(id: String) = when (id) {
        "ground" -> n("Rez-de-chaussée", "Ground Floor")
        "upstairs" -> n("Étage", "Upstairs")
        else -> n("Extérieur", "Outside")
    }

    fun floors(): List<Floor> = listOf("ground", "upstairs", "outside").map { f ->
        Floor(f, floorName(f), AREAS.filter { it.floor == f }.map { it.id })
    }

    /** Entités par zone, dans l'ordre d'affichage. */
    private val ROOM_ENTITIES = mapOf(
        "living_room" to listOf("camera.living_room", "light.living_room_ceiling", "light.floor_lamp", "light.tv_backlight",
            "scene.movie_night", "cover.living_room_blinds", "media_player.living_room_speaker", "climate.living_room",
            "sensor.living_room_temperature", "sensor.living_room_humidity", "binary_sensor.living_room_motion", "binary_sensor.living_room_window"),
        "kitchen" to listOf("light.kitchen_ceiling", "light.under_cabinet", "switch.coffee_maker", "media_player.kitchen_display",
            "sensor.kitchen_temperature", "binary_sensor.kitchen_leak", "binary_sensor.fridge_door", "binary_sensor.kitchen_window"),
        "dining_room" to listOf("vacuum.robbie", "light.chandelier", "cover.patio_shade", "binary_sensor.patio_door", "sensor.dining_room_temperature"),
        "entrance" to listOf("camera.front_door", "lock.front_door", "light.porch", "light.hallway",
            "binary_sensor.front_door", "binary_sensor.entrance_motion"),
        "master_bedroom" to listOf("light.bedside_left", "light.bedside_right", "light.bedroom_ceiling", "fan.ceiling_fan",
            "cover.bedroom_blinds", "media_player.bedroom_tv", "sensor.bedroom_temperature", "sensor.bedroom_humidity", "binary_sensor.bedroom_window"),
        "kids_room" to listOf("light.kids_ceiling", "light.night_light", "media_player.kids_speaker", "switch.humidifier",
            "sensor.kids_room_temperature", "binary_sensor.kids_room_window"),
        "office" to listOf("light.desk_lamp", "light.office_ceiling", "switch.monitor_plug", "climate.office",
            "sensor.office_temperature", "sensor.office_humidity", "binary_sensor.office_presence", "binary_sensor.office_window"),
        "bathroom" to listOf("light.bathroom_mirror", "light.bathroom_ceiling", "fan.bathroom_fan", "climate.bathroom_floor",
            "sensor.bathroom_humidity", "binary_sensor.bathroom_leak", "binary_sensor.bathroom_window"),
        "garage" to listOf("camera.driveway", "cover.garage_door", "lock.garage_side_door", "light.garage", "switch.ev_charger",
            "binary_sensor.driveway_motion"),
        "garden" to listOf("camera.backyard", "light.string_lights", "light.path_lights", "switch.sprinkler", "sensor.outdoor_temperature",
            "binary_sensor.backyard_motion"),
    )

    const val WEATHER = "weather.home"
    val CALENDARS = listOf("calendar.family", "calendar.school", "calendar.holidays")
    val PERSONS = listOf("person.alex", "person.sam", "person.jordan", "sensor.riley_presence")

    fun rooms(): MutableList<Room> = AREAS.map { a ->
        Room("demo-${a.id}", n(a.fr, a.en), a.id).apply {
            ROOM_ENTITIES[a.id]?.forEach { entities.add(RoomEntity(it)) }
        }
    }.toMutableList()

    // ------------------------------------------------------------ états

    private fun st(id: String, state: String, name: String, vararg attrs: Pair<String, Any>): EntityState {
        val a = JSONObject().put("friendly_name", name)
        for ((k, v) in attrs) a.put(k, if (v is List<*>) JSONArray(v) else v)
        return EntityState(id, state, a)
    }

    private val DIM = "supported_color_modes" to listOf("brightness")
    private val RGB = "supported_color_modes" to listOf("rgb")
    private fun bri(pct: Int) = "brightness" to (pct * 2.55).roundToInt()
    private fun temp(v: Double) = arrayOf("unit_of_measurement" to "°C", "device_class" to "temperature", "state_class" to "measurement")
    private val HUM = arrayOf("unit_of_measurement" to "%", "device_class" to "humidity")

    fun states(): List<EntityState> = listOf(
        // Salon
        st("camera.living_room", "idle", n("Caméra du salon", "Living Room Camera")),
        st("light.living_room_ceiling", "on", n("Plafonnier", "Ceiling Light"), DIM, bri(70)),
        st("light.floor_lamp", "off", n("Lampe sur pied", "Floor Lamp"), DIM),
        st("light.tv_backlight", "on", n("Rétroéclairage TV", "TV Backlight"), RGB, bri(55), "rgb_color" to listOf(120, 90, 255)),
        st("scene.movie_night", "2026-11-01T20:00:00+00:00", n("Soirée cinéma", "Movie Night")),
        st("cover.living_room_blinds", "open", n("Stores", "Blinds"), "current_position" to 60, "device_class" to "blind"),
        st("media_player.living_room_speaker", "playing", n("Haut-parleur", "Speaker"),
            "media_title" to "Blue in Green", "media_artist" to "Miles Davis", "volume_level" to 0.35),
        st("climate.living_room", "heat", n("Thermostat", "Thermostat"), "current_temperature" to 20.5, "temperature" to 21.0,
            "hvac_action" to "heating", "hvac_modes" to listOf("off", "heat", "auto"), "min_temp" to 7.0, "max_temp" to 30.0, "target_temp_step" to 0.5),
        st("sensor.living_room_temperature", "21.3", n("Température salon", "Living Room Temperature"), *temp(21.3)),
        st("sensor.living_room_humidity", "41", n("Humidité salon", "Living Room Humidity"), *HUM),
        st("binary_sensor.living_room_motion", "on", n("Mouvement salon", "Living Room Motion"), "device_class" to "motion"),
        st("binary_sensor.living_room_window", "off", n("Fenêtre salon", "Living Room Window"), "device_class" to "window"),
        // Cuisine
        st("light.kitchen_ceiling", "on", n("Plafonnier cuisine", "Kitchen Ceiling"), DIM, bri(100)),
        st("light.under_cabinet", "on", n("Sous les armoires", "Under Cabinet"), DIM, bri(40)),
        st("switch.coffee_maker", "off", n("Cafetière", "Coffee Maker"), "device_class" to "outlet"),
        st("media_player.kitchen_display", "paused", n("Écran cuisine", "Kitchen Display"),
            "media_title" to n("Bulletin de nouvelles", "Morning News"), "volume_level" to 0.2),
        st("sensor.kitchen_temperature", "22.1", n("Température cuisine", "Kitchen Temperature"), *temp(22.1)),
        st("binary_sensor.kitchen_leak", "off", n("Fuite évier", "Sink Leak"), "device_class" to "moisture"),
        st("binary_sensor.fridge_door", "off", n("Porte du frigo", "Fridge Door"), "device_class" to "door"),
        st("binary_sensor.kitchen_window", "off", n("Fenêtre cuisine", "Kitchen Window"), "device_class" to "window"),
        // Salle à manger
        st("light.chandelier", "on", n("Lustre", "Chandelier"), DIM, bri(80)),
        st("cover.patio_shade", "closed", n("Toile du patio", "Patio Shade"), "current_position" to 0, "device_class" to "shade"),
        st("binary_sensor.patio_door", "on", n("Porte patio", "Patio Door"), "device_class" to "door"),
        st("sensor.dining_room_temperature", "20.8", n("Température salle à manger", "Dining Room Temperature"), *temp(20.8)),
        // Entrée
        st("camera.front_door", "idle", n("Sonnette", "Doorbell")),
        st("lock.front_door", "locked", n("Porte d'entrée", "Front Door Lock")),
        st("light.porch", "off", n("Perron", "Porch Light")),
        st("light.hallway", "off", n("Couloir", "Hallway"), DIM),
        st("binary_sensor.front_door", "off", n("Porte d'entrée", "Front Door"), "device_class" to "door"),
        st("binary_sensor.entrance_motion", "off", n("Mouvement entrée", "Entrance Motion"), "device_class" to "motion"),
        // Chambre principale
        st("vacuum.robbie", "cleaning", "Robbie", "battery_level" to 64, "fan_speed" to "standard"),
        st("light.bedside_left", "off", n("Chevet gauche", "Bedside Left"), DIM),
        st("light.bedside_right", "on", n("Chevet droit", "Bedside Right"), DIM, bri(20)),
        st("light.bedroom_ceiling", "off", n("Plafonnier chambre", "Bedroom Ceiling"), DIM),
        st("fan.ceiling_fan", "on", n("Ventilateur de plafond", "Ceiling Fan"), "percentage" to 50),
        st("cover.bedroom_blinds", "closed", n("Stores chambre", "Bedroom Blinds"), "current_position" to 0, "device_class" to "blind"),
        st("media_player.bedroom_tv", "off", n("Téléviseur", "Bedroom TV")),
        st("sensor.bedroom_temperature", "19.4", n("Température chambre", "Bedroom Temperature"), *temp(19.4)),
        st("sensor.bedroom_humidity", "45", n("Humidité chambre", "Bedroom Humidity"), *HUM),
        st("binary_sensor.bedroom_window", "on", n("Fenêtre chambre", "Bedroom Window"), "device_class" to "window"),
        // Chambre des enfants
        st("light.kids_ceiling", "off", n("Plafonnier", "Ceiling Light"), DIM),
        st("light.night_light", "on", n("Veilleuse", "Night Light"), RGB, bri(15), "rgb_color" to listOf(255, 170, 60)),
        st("media_player.kids_speaker", "paused", n("Haut-parleur", "Speaker"), "media_title" to n("Histoires du soir", "Bedtime Stories"), "volume_level" to 0.15),
        st("switch.humidifier", "on", n("Humidificateur", "Humidifier")),
        st("sensor.kids_room_temperature", "20.2", n("Température enfants", "Kids' Room Temperature"), *temp(20.2)),
        st("binary_sensor.kids_room_window", "off", n("Fenêtre enfants", "Kids' Room Window"), "device_class" to "window"),
        // Bureau
        st("light.desk_lamp", "on", n("Lampe de bureau", "Desk Lamp"), DIM, bri(90)),
        st("light.office_ceiling", "off", n("Plafonnier bureau", "Office Ceiling"), DIM),
        st("switch.monitor_plug", "on", n("Prise écrans", "Monitor Plug"), "device_class" to "outlet"),
        st("climate.office", "heat", n("Thermostat bureau", "Office Thermostat"), "current_temperature" to 21.8, "temperature" to 22.0,
            "hvac_action" to "idle", "hvac_modes" to listOf("off", "heat"), "min_temp" to 7.0, "max_temp" to 30.0, "target_temp_step" to 0.5),
        st("sensor.office_temperature", "21.8", n("Température bureau", "Office Temperature"), *temp(21.8)),
        st("sensor.office_humidity", "38", n("Humidité bureau", "Office Humidity"), *HUM),
        st("binary_sensor.office_presence", "on", n("Présence bureau", "Office Occupancy"), "device_class" to "occupancy"),
        st("binary_sensor.office_window", "off", n("Fenêtre bureau", "Office Window"), "device_class" to "window"),
        // Salle de bain
        st("light.bathroom_mirror", "off", n("Miroir", "Mirror Light"), DIM),
        st("light.bathroom_ceiling", "off", n("Plafonnier", "Ceiling Light"), DIM),
        st("fan.bathroom_fan", "off", n("Ventilateur", "Exhaust Fan")),
        st("climate.bathroom_floor", "heat", n("Plancher chauffant", "Heated Floor"), "current_temperature" to 23.5, "temperature" to 24.0,
            "hvac_action" to "heating", "hvac_modes" to listOf("off", "heat"), "min_temp" to 5.0, "max_temp" to 35.0, "target_temp_step" to 0.5),
        st("sensor.bathroom_humidity", "63", n("Humidité salle de bain", "Bathroom Humidity"), *HUM),
        st("binary_sensor.bathroom_leak", "off", n("Fuite douche", "Shower Leak"), "device_class" to "moisture"),
        st("binary_sensor.bathroom_window", "off", n("Fenêtre salle de bain", "Bathroom Window"), "device_class" to "window"),
        // Garage
        st("camera.driveway", "idle", n("Entrée de garage", "Driveway")),
        st("cover.garage_door", "closed", n("Porte de garage", "Garage Door"), "device_class" to "garage"),
        st("lock.garage_side_door", "unlocked", n("Porte latérale", "Side Door Lock")),
        st("light.garage", "off", n("Lumière garage", "Garage Light")),
        st("switch.ev_charger", "on", n("Borne de recharge", "EV Charger")),
        st("binary_sensor.driveway_motion", "on", n("Mouvement entrée de garage", "Driveway Motion"), "device_class" to "motion"),
        // Jardin
        st("camera.backyard", "idle", n("Cour arrière", "Backyard")),
        st("light.string_lights", "on", n("Guirlande", "String Lights"), DIM, bri(60)),
        st("light.path_lights", "off", n("Allée", "Path Lights")),
        st("switch.sprinkler", "off", n("Arrosage", "Sprinkler")),
        st("sensor.outdoor_temperature", "8.4", n("Température extérieure", "Outdoor Temperature"), *temp(8.4)),
        st("binary_sensor.backyard_motion", "off", n("Mouvement cour", "Backyard Motion"), "device_class" to "motion"),
        // Accueil
        st(WEATHER, "partlycloudy", n("Maison", "Home"), "temperature" to 12.0, "humidity" to 64, "wind_speed" to 14.0,
            "wind_speed_unit" to "km/h", "temperature_unit" to "°C"),
        st("person.alex", "home", "Alex"),
        st("person.sam", "home", "Sam"),
        st("person.jordan", "not_home", "Jordan"),
        st("sensor.riley_presence", "office", n("Riley présence", "Riley Presence")),
        st("calendar.family", "off", n("Famille", "Family")),
        st("calendar.school", "off", n("École", "School")),
        st("calendar.holidays", "off", n("Jours fériés", "Holidays")),
    )

    // ------------------------------------------------------------ actions

    private val TRACKS = listOf("Blue in Green" to "Miles Davis", "Clair de lune" to "Claude Debussy", "Take Five" to "Dave Brubeck", "Gymnopédie No. 1" to "Erik Satie")

    /** Applique un appel de service sur l'état local. Retourne l'entité modifiée. */
    fun apply(states: Map<String, EntityState>, domain: String, service: String, id: String, data: JSONObject?): String? {
        val e = states[id] ?: return null
        val a = e.attrs
        when (service) {
            "turn_on" -> {
                if (domain == "scene" || domain == "script") return null
                e.state = "on"
                data?.optInt("brightness_pct", -1)?.takeIf { it >= 0 }?.let { a.put("brightness", (it * 2.55).roundToInt()) }
                if (domain == "light" && !a.has("brightness") && a.has("supported_color_modes")) a.put("brightness", 255)
                if (domain == "media_player") e.state = "playing"
            }
            "turn_off" -> e.state = "off"
            "toggle" -> e.state = when {
                domain == "media_player" -> if (e.state == "off") "playing" else "off"
                e.state == "on" -> "off"
                else -> { if (domain == "light" && a.has("supported_color_modes") && !a.has("brightness")) a.put("brightness", 255); "on" }
            }
            "set_percentage" -> { val p = data?.optInt("percentage") ?: 0; a.put("percentage", p); e.state = if (p > 0) "on" else "off" }
            "open_cover" -> { e.state = "open"; if (a.has("current_position")) a.put("current_position", 100) }
            "close_cover" -> { e.state = "closed"; if (a.has("current_position")) a.put("current_position", 0) }
            "set_cover_position" -> { val p = data?.optInt("position") ?: 0; a.put("current_position", p); e.state = if (p > 0) "open" else "closed" }
            "start" -> e.state = "cleaning"
            "pause" -> e.state = "paused"
            "stop" -> e.state = "idle"
            "return_to_base" -> e.state = "returning"
            "lock" -> e.state = "locked"
            "unlock" -> e.state = "unlocked"
            "set_temperature" -> {
                val t = data?.optDouble("temperature") ?: return null
                a.put("temperature", t)
                if (e.state != "off") a.put("hvac_action", if (t > (a.dbl("current_temperature") ?: t)) "heating" else "idle")
            }
            "set_hvac_mode" -> {
                e.state = data?.optString("hvac_mode") ?: return null
                a.put("hvac_action", if (e.state == "off") "off" else "idle")
            }
            "media_play_pause" -> e.state = if (e.state == "playing") "paused" else "playing"
            "volume_set" -> a.put("volume_level", data?.optDouble("volume_level") ?: 0.3)
            "media_next_track", "media_previous_track" -> {
                val i = TRACKS.indexOfFirst { it.first == a.str("media_title") }
                val next = TRACKS[((if (i < 0) 0 else i) + (if (service == "media_next_track") 1 else TRACKS.size - 1)) % TRACKS.size]
                a.put("media_title", next.first).put("media_artist", next.second)
                e.state = "playing"
            }
            else -> return null
        }
        return id
    }

    // ------------------------------------------------------------ requêtes WebSocket

    fun respond(states: Map<String, EntityState>, msg: JSONObject): JSONObject {
        val ok = JSONObject().put("type", "result").put("success", true)
        return when (msg.optString("type")) {
            "get_states" -> ok.put("result", JSONArray().apply {
                for (e in states.values) put(JSONObject().put("entity_id", e.id).put("state", e.state).put("attributes", e.attrs))
            })
            "config/area_registry/list" -> ok.put("result", JSONArray().apply {
                for (a in AREAS) put(JSONObject().put("area_id", a.id).put("name", n(a.fr, a.en)).put("floor_id", a.floor))
            })
            "config/floor_registry/list" -> ok.put("result", JSONArray().apply {
                for (f in floors()) put(JSONObject().put("floor_id", f.id).put("name", f.name))
            })
            "config/device_registry/list" -> ok.put("result", JSONArray())
            "config/entity_registry/list_for_display" -> ok.put("result", JSONObject().put("entities", JSONArray().apply {
                for ((area, ids) in ROOM_ENTITIES) for (id in ids) put(JSONObject().put("ei", id).put("ai", area))
            }))
            "call_service" -> if (msg.optString("service") == "get_forecasts") {
                val we = msg.optJSONObject("target")?.optString("entity_id") ?: WEATHER
                ok.put("result", JSONObject().put("response", JSONObject().put(we, JSONObject().put("forecast", forecast()))))
            } else ok.put("result", JSONObject())
            else -> JSONObject().put("success", false)
                .put("error", JSONObject().put("message", tr("Non disponible en mode démo")))
        }
    }

    private fun forecast(): JSONArray {
        val days = listOf(Triple("partlycloudy", 13, 5), Triple("rainy", 11, 6), Triple("cloudy", 9, 3), Triple("sunny", 12, 2), Triple("snowy", 4, -2))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'00:00:00XXX", Locale.US)
        val c = Calendar.getInstance()
        return JSONArray().apply {
            for ((cond, hi, lo) in days) {
                put(JSONObject().put("datetime", iso.format(c.time)).put("condition", cond).put("temperature", hi).put("templow", lo))
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    // ------------------------------------------------------------ REST (agenda)

    /** Réponse REST simulée, ou null si le chemin n'est pas géré. */
    fun rest(path: String): String? {
        if (!path.startsWith("/api/calendars/")) return null
        val cal = path.removePrefix("/api/calendars/").substringBefore('?')
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun at(dayOffset: Int, h: Int, m: Int = 0): String = iso.format(Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
        }.time)
        fun day(offset: Int) = d.format(Date(System.currentTimeMillis() + offset * 86_400_000L))
        fun timed(title: String, s: String, e: String) = JSONObject().put("summary", title)
            .put("start", JSONObject().put("dateTime", s)).put("end", JSONObject().put("dateTime", e))
        fun allDay(title: String, offset: Int) = JSONObject().put("summary", title)
            .put("start", JSONObject().put("date", day(offset))).put("end", JSONObject().put("date", day(offset + 1)))
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return JSONArray().apply {
            when (cal) {
                "calendar.family" -> {
                    put(timed(n("Souper chez grand-maman", "Dinner at Grandma's"), at(0, maxOf(hour + 2, 18)), at(0, maxOf(hour + 4, 20))))
                    put(timed(n("Soccer de Sam", "Sam's soccer practice"), at(1, 17, 30), at(1, 19)))
                    put(timed(n("Rendez-vous chez le dentiste", "Dentist appointment"), at(3, 9, 15), at(3, 10)))
                }
                "calendar.school" -> {
                    put(allDay(n("Journée pédagogique", "Teacher work day"), 2))
                    put(timed(n("Rencontre de parents", "Parent-teacher meeting"), at(4, 18, 30), at(4, 19, 30)))
                }
                "calendar.holidays" -> put(allDay(n("Long congé", "Long weekend"), 4))
            }
        }.toString()
    }

    // ------------------------------------------------------------ images de caméras

    /** Image de caméra dessinée (aucune photo réelle). */
    fun cameraImage(id: String, width: Int): Bitmap {
        val w = width.coerceIn(320, 1280)
        val h = w * 9 / 16
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 0f) {
            p.shader = null; p.color = color
            c.drawRoundRect(RectF(l * w, t * h, r * w, b * h), radius * w, radius * w, p)
        }
        fun sky(top: Int, bottom: Int, until: Float) {
            p.shader = LinearGradient(0f, 0f, 0f, until * h, top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), until * h, p)
            p.shader = null
        }
        when (id) {
            "camera.living_room" -> {
                sky(0xFF8C7B6B.toInt(), 0xFFB9A893.toInt(), 0.72f)                 // mur
                rect(0f, 0.72f, 1f, 1f, 0xFF6B4F3A.toInt())                           // plancher
                rect(0.58f, 0.12f, 0.92f, 0.5f, 0xFF9FC4E0.toInt(), 0.01f)            // fenêtre
                rect(0.745f, 0.12f, 0.755f, 0.5f, 0xFFE8E2D8.toInt())
                rect(0.08f, 0.48f, 0.52f, 0.78f, 0xFF3E5566.toInt(), 0.02f)           // sofa
                rect(0.06f, 0.44f, 0.12f, 0.8f, 0xFF34495A.toInt(), 0.015f)
                rect(0.48f, 0.44f, 0.54f, 0.8f, 0xFF34495A.toInt(), 0.015f)
                rect(0.16f, 0.5f, 0.27f, 0.6f, 0xFFD9A441.toInt(), 0.01f)             // coussins
                rect(0.33f, 0.5f, 0.44f, 0.6f, 0xFFC7643F.toInt(), 0.01f)
                rect(0.22f, 0.82f, 0.46f, 0.88f, 0xFF4A3527.toInt(), 0.01f)           // table basse
                rect(0.66f, 0.56f, 0.68f, 0.86f, 0xFF2E2E2E.toInt())                  // lampe
                rect(0.62f, 0.5f, 0.72f, 0.58f, 0xFFF3E3B5.toInt(), 0.02f)
            }
            "camera.front_door" -> {
                sky(0xFF7FA6C9.toInt(), 0xFFC9DCEB.toInt(), 0.5f)
                rect(0f, 0.5f, 1f, 1f, 0xFF8E8A80.toInt())                            // perron
                rect(0.08f, 0.05f, 0.28f, 0.75f, 0xFFB65B44.toInt())                  // brique
                rect(0.72f, 0.05f, 0.92f, 0.75f, 0xFFB65B44.toInt())
                rect(0.36f, 0.62f, 0.64f, 0.7f, 0xFF5A5650.toInt())                   // marche
                rect(0.3f, 0.7f, 0.7f, 0.82f, 0xFF4E4A45.toInt())
                rect(0.4f, 0.86f, 0.6f, 0.98f, 0xFF7A4E2D.toInt(), 0.01f)             // paillasson
                rect(0.12f, 0.6f, 0.2f, 0.75f, 0xFF3C6E47.toInt(), 0.03f)             // plantes
                rect(0.8f, 0.6f, 0.88f, 0.75f, 0xFF3C6E47.toInt(), 0.03f)
                // Grand-angle de sonnette : vignette sombre
                p.shader = android.graphics.RadialGradient(w / 2f, h / 2f, w * 0.75f, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            }
            "camera.driveway" -> {
                sky(0xFF2B3A55.toInt(), 0xFF5D6E8C.toInt(), 0.42f)                    // crépuscule
                rect(0f, 0.42f, 1f, 1f, 0xFF3D4A3A.toInt())                           // pelouse
                p.color = 0xFF55565A.toInt(); p.shader = null                         // asphalte en perspective
                c.drawPath(android.graphics.Path().apply {
                    moveTo(0.38f * w, 0.42f * h); lineTo(0.62f * w, 0.42f * h); lineTo(0.95f * w, h.toFloat()); lineTo(0.05f * w, h.toFloat()); close()
                }, p)
                rect(0.36f, 0.56f, 0.64f, 0.8f, 0xFF8E2F2F.toInt(), 0.03f)            // voiture
                rect(0.4f, 0.5f, 0.6f, 0.6f, 0xFF6F2424.toInt(), 0.03f)
                rect(0.42f, 0.52f, 0.58f, 0.58f, 0xFF9DB4C8.toInt(), 0.01f)
                rect(0.38f, 0.66f, 0.43f, 0.7f, 0xFFFFF1B8.toInt(), 0.01f)            // phares
                rect(0.57f, 0.66f, 0.62f, 0.7f, 0xFFFFF1B8.toInt(), 0.01f)
                rect(0.05f, 0.18f, 0.25f, 0.45f, 0xFF4B4038.toInt())                  // maison voisine
                rect(0.08f, 0.26f, 0.13f, 0.33f, 0xFFF5D27A.toInt())
                rect(0.17f, 0.26f, 0.22f, 0.33f, 0xFFF5D27A.toInt())
            }
            else -> {                                                                 // cour arrière
                sky(0xFF86B6E0.toInt(), 0xFFD4E7F5.toInt(), 0.45f)
                rect(0f, 0.45f, 1f, 1f, 0xFF5E8C46.toInt())                           // gazon
                for (i in 0 until 14) rect(i / 14f + 0.005f, 0.32f, i / 14f + 0.06f, 0.5f, 0xFF9C7A57.toInt())  // clôture
                rect(0.72f, 0.12f, 0.95f, 0.5f, 0xFF3F6B35.toInt(), 0.1f)             // arbre
                rect(0.82f, 0.45f, 0.85f, 0.62f, 0xFF5B4330.toInt())
                rect(0.1f, 0.62f, 0.38f, 0.72f, 0xFF8A6C4E.toInt(), 0.01f)            // terrasse
                rect(0.15f, 0.55f, 0.2f, 0.66f, 0xFF2F4F6F.toInt(), 0.01f)            // chaises
                rect(0.28f, 0.55f, 0.33f, 0.66f, 0xFF2F4F6F.toInt(), 0.01f)
                rect(0.5f, 0.75f, 0.7f, 0.8f, 0xFF7FB4D6.toInt(), 0.02f)              // piscine pour enfants
            }
        }
        // Horodatage façon caméra
        val ts = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US).format(Date())
        p.shader = null
        p.textSize = h / 20f
        p.typeface = Typeface.MONOSPACE
        p.color = 0x88000000.toInt()
        c.drawText(ts, w * 0.03f + 2, h * 0.08f + 2, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawText(ts, w * 0.03f, h * 0.08f, p)
        return bmp
    }
}
