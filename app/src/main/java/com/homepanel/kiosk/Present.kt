package com.homepanel.kiosk

import android.graphics.Color
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
fun JSONObject.dbl(k: String): Double? = if (isNull(k)) null else (opt(k) as? Number)?.toDouble() ?: optString(k).toDoubleOrNull()
fun JSONObject.int(k: String): Int? = dbl(k)?.let { Math.round(it).toInt() }

/** Traduction d'un état HA en texte, icône et couleur « style HomeKit ». */
object Present {
    val SUPPORTED = setOf(
        "light", "switch", "input_boolean", "fan", "cover", "lock", "climate", "sensor",
        "binary_sensor", "scene", "script", "media_player", "button", "input_button", "automation", "camera",
    )

    val CATEGORIES get() = listOf(tr("Caméras"), tr("Scènes"), tr("Lumières"), tr("Climat"), tr("Volets et stores"), tr("Prises et interrupteurs"), tr("Sécurité"), tr("Médias"), tr("Capteurs"))

    private const val YELLOW = 0xFFFFB300.toInt()
    private val nf get() = NumberFormat.getNumberInstance(I18n.locale).apply { maximumFractionDigits = 1 }

    fun domain(id: String) = id.substringBefore('.')
    fun num(v: Double): String = nf.format(v)

    fun unavailable(e: EntityState?) = e == null || e.state == "unavailable"

    fun active(e: EntityState?): Boolean {
        if (e == null) return false
        val s = e.state
        return when (e.domain) {
            "light", "switch", "input_boolean", "fan", "automation", "binary_sensor" -> s == "on"
            "cover" -> s == "open" || s == "opening" || s == "closing"
            "lock" -> s == "unlocked" || s == "unlocking" || s == "open" || s == "opening" || s == "jammed"
            "climate" -> s != "off" && s != "unavailable" && s != "unknown"
            "media_player" -> s == "playing" || s == "paused" || s == "on" || s == "buffering" || s == "idle"
            else -> false
        }
    }

    fun brightnessPct(e: EntityState): Int? {
        if (e.state != "on") return null
        val b = e.attrs.dbl("brightness") ?: return null
        return Math.round(b * 100.0 / 255.0).toInt()
    }

    fun supportsBrightness(e: EntityState?): Boolean {
        e ?: return false
        val modes = e.attrs.optJSONArray("supported_color_modes") ?: return e.attrs.has("brightness")
        for (i in 0 until modes.length()) if (modes.optString(i) != "onoff") return true
        return false
    }

    fun status(e: EntityState?): String {
        if (e == null) return "…"
        val s = e.state
        if (s == "unavailable") return tr("Non disponible")
        val a = e.attrs
        return when (e.domain) {
            "light" -> if (s == "on") brightnessPct(e)?.let { "${tr("Allumé")} · $it %" } ?: tr("Allumé") else tr("Éteint")
            "switch", "input_boolean", "automation" -> if (s == "on") tr("Activé") else tr("Désactivé")
            "fan" -> if (s == "on") a.int("percentage")?.let { "${tr("Activé")} · $it %" } ?: tr("Activé") else tr("Désactivé")
            "cover" -> when (s) {
                "open" -> a.int("current_position")?.let { if (it in 1..99) "${tr("Ouvert à")} $it %" else tr("Ouvert") } ?: tr("Ouvert")
                "closed" -> tr("Fermé")
                "opening" -> tr("Ouverture…")
                "closing" -> tr("Fermeture…")
                else -> s
            }
            "lock" -> when (s) {
                "locked" -> tr("Verrouillé")
                "unlocked" -> tr("Déverrouillé")
                "locking" -> tr("Verrouillage…")
                "unlocking" -> tr("Déverrouillage…")
                "jammed" -> tr("Bloqué")
                "open" -> tr("Ouvert")
                else -> s
            }
            "climate" -> climateStatus(e)
            "sensor" -> sensorValue(e)
            "binary_sensor" -> binaryText(a.str("device_class"), s == "on")
            "scene" -> tr("Scène")
            "camera" -> if (s == "streaming" || s == "recording") tr("En direct") else tr("Caméra")
            "script", "button", "input_button" -> if (s == "on") tr("En cours…") else tr("Exécuter")
            "media_player" -> when (s) {
                "playing" -> a.str("media_title").ifEmpty { "Lecture" }
                "paused" -> tr("En pause")
                "off" -> tr("Éteint")
                "standby" -> tr("Veille")
                "idle", "on" -> tr("Allumé")
                else -> s
            }
            else -> s
        }
    }

    fun sensorValue(e: EntityState): String {
        val unit = e.attrs.str("unit_of_measurement")
        val n = e.state.toDoubleOrNull() ?: return if (e.state == "unknown") "—" else e.state
        val v = num(n)
        return when (unit) {
            "°C", "°F" -> "$v°"
            "" -> v
            "%" -> "$v %"
            else -> "$v $unit"
        }
    }

    private fun binaryText(dc: String, on: Boolean): String = when (dc) {
        "door", "garage_door", "opening", "window" -> if (on) tr("Ouvert") else tr("Fermé")
        "motion" -> if (on) tr("Mouvement détecté") else tr("Aucun mouvement")
        "occupancy", "presence" -> if (on) tr("Présence") else tr("Personne")
        "moisture" -> if (on) tr("Fuite détectée") else tr("Sec")
        "smoke" -> if (on) tr("Fumée détectée") else "OK"
        "battery" -> if (on) tr("Batterie faible") else tr("Batterie OK")
        "connectivity" -> if (on) tr("Connecté") else tr("Déconnecté")
        "lock" -> if (on) tr("Déverrouillé") else tr("Verrouillé")
        "plug", "power" -> if (on) tr("Branché") else tr("Débranché")
        else -> if (on) tr("Actif") else tr("Inactif")
    }

    fun hvacName(m: String) = when (m) {
        "heat" -> tr("Chauffage")
        "cool" -> tr("Climatisation")
        "heat_cool" -> tr("Chaud/froid")
        "auto" -> "Auto"
        "dry" -> tr("Déshumidif.")
        "fan_only" -> tr("Ventilation")
        "off" -> tr("Arrêt")
        else -> m
    }

    private fun climateStatus(e: EntityState): String {
        val a = e.attrs
        val cur = a.dbl("current_temperature")?.let { num(it) + "°" }
        if (e.state == "off") return listOfNotNull(cur, tr("Arrêt")).joinToString(" · ")
        val target = a.dbl("temperature")?.let { num(it) + "°" }
        val action = when (a.str("hvac_action")) { "heating" -> tr("Chauffe"); "cooling" -> tr("Refroidit"); else -> null }
        val mode = action ?: hvacName(e.state)
        val main = if (target != null) "$mode ${tr("à")} $target" else mode
        return listOfNotNull(cur, main).joinToString(" · ")
    }

    fun icon(id: String, e: EntityState?): Int {
        val dc = e?.attrs?.str("device_class") ?: ""
        return when (domain(id)) {
            "light" -> R.drawable.ic_light
            "switch" -> if (dc == "outlet") R.drawable.ic_outlet else R.drawable.ic_power
            "input_boolean" -> R.drawable.ic_toggle
            "fan" -> R.drawable.ic_fan
            "cover" -> R.drawable.ic_blinds
            "lock" -> if (e?.state == "locked") R.drawable.ic_lock else R.drawable.ic_lock_open
            "climate" -> R.drawable.ic_thermostat
            "sensor" -> when (dc) {
                "temperature" -> R.drawable.ic_thermostat
                "humidity", "moisture" -> R.drawable.ic_drop
                "power", "energy", "voltage", "current", "battery" -> R.drawable.ic_bolt
                else -> R.drawable.ic_sensor
            }
            "binary_sensor" -> when (dc) {
                "door", "garage_door", "opening" -> R.drawable.ic_door
                "window" -> R.drawable.ic_window
                "motion" -> R.drawable.ic_walk
                "occupancy", "presence" -> R.drawable.ic_person
                "moisture" -> R.drawable.ic_drop
                else -> R.drawable.ic_sensor
            }
            "scene" -> R.drawable.ic_star
            "script", "button", "input_button", "automation" -> R.drawable.ic_play
            "media_player" -> R.drawable.ic_tv
            "camera" -> R.drawable.ic_camera
            else -> R.drawable.ic_sensor
        }
    }

    fun accent(e: EntityState?): Int {
        if (e == null) return YELLOW
        return when (e.domain) {
            "light" -> lightColor(e)
            "switch", "input_boolean", "automation" -> 0xFF34C759.toInt()
            "fan" -> 0xFF30B0C7.toInt()
            "cover" -> 0xFF0A84FF.toInt()
            "climate" -> when (e.attrs.str("hvac_action").ifEmpty { e.state }) {
                "heating", "heat" -> 0xFFFF7A00.toInt()
                "cooling", "cool" -> 0xFF0A84FF.toInt()
                else -> 0xFF34C759.toInt()
            }
            "media_player" -> 0xFFAF52DE.toInt()
            else -> 0xFFFF9F0A.toInt()
        }
    }

    private fun lightColor(e: EntityState): Int {
        val rgb = e.attrs.optJSONArray("rgb_color") ?: return YELLOW
        if (rgb.length() < 3) return YELLOW
        val r = rgb.optInt(0); val g = rgb.optInt(1); val b = rgb.optInt(2)
        if (minOf(r, g, b) > 190) return YELLOW // blanc → jaune chaud façon HomeKit
        return Color.rgb(r, g, b)
    }

    fun category(id: String): Int = when (domain(id)) {
        "camera" -> 0
        "scene", "script", "button", "input_button", "automation" -> 1
        "light" -> 2
        "climate", "fan" -> 3
        "cover" -> 4
        "switch", "input_boolean" -> 5
        "lock" -> 6
        "media_player" -> 7
        else -> 8
    }

    fun hasDetail(id: String, e: EntityState?): Boolean = when (domain(id)) {
        "light" -> supportsBrightness(e)
        "fan", "cover", "climate", "media_player" -> true
        else -> false
    }

    /** Libellé du bouton d'action dans le détail d'une pastille (null si l'entité ne se commande pas). */
    fun quickAction(id: String, e: EntityState?): String? {
        val s = e?.state ?: return null
        if (s == "unavailable" || s == "unknown") return null
        return when (domain(id)) {
            "light", "switch" -> if (s == "on") tr("Éteindre") else tr("Allumer")
            "lock" -> if (s == "locked" || s == "locking") tr("Déverrouiller") else tr("Verrouiller")
            "cover" -> when (s) { "opening", "closing" -> tr("Arrêter"); "open" -> tr("Fermer"); else -> tr("Ouvrir") }
            else -> null
        }
    }

    /** Action d'un simple toucher. Retourne false si aucune action directe. */
    fun tap(ha: HaClient, id: String, e: EntityState?): Boolean {
        val d = domain(id)
        val s = e?.state
        when (d) {
            "light", "switch", "input_boolean", "fan", "automation" -> ha.callService(d, "toggle", id)
            "cover" -> ha.callService(d, when (s) { "opening", "closing" -> "stop_cover"; "open" -> "close_cover"; else -> "open_cover" }, id)
            "lock" -> ha.callService(d, if (s == "locked") "unlock" else "lock", id)
            "scene", "script" -> ha.callService(d, "turn_on", id)
            "button", "input_button" -> ha.callService(d, "press", id)
            "media_player" -> ha.callService(d, if (s == "off" || s == "standby") "turn_on" else "media_play_pause", id)
            else -> return false
        }
        return true
    }

    fun displayName(re: RoomEntity, e: EntityState?, roomName: String): String {
        re.name?.let { if (it.isNotBlank()) return it }
        val n = e?.name ?: re.id.substringAfter('.').replace('_', ' ')
        if (n.length > roomName.length + 1 && n.startsWith(roomName, ignoreCase = true)) {
            val rest = n.substring(roomName.length).trimStart(' ', '-', ':', '·').trim()
            if (rest.isNotEmpty()) return rest.replaceFirstChar { it.uppercase() }
        }
        return n
    }

    /** Pastille de l'en-tête : icône, texte, niveau (0 normal, 1 attention, 2 alerte). */
    data class Chip(val icon: Int, val text: String, val level: Int = 0, val kind: String = "")

    /** Type de pastille auquel contribue une entité (null si aucune). */
    fun chipKind(id: String, e: EntityState?): String? {
        val dc = e?.attrs?.str("device_class") ?: return when (domain(id)) { "light" -> "light"; "lock" -> "lock"; else -> null }
        return when (domain(id)) {
            "sensor" -> when (dc) { "temperature" -> "temp"; "humidity" -> "hum"; else -> null }
            "climate" -> null
            "light" -> "light"
            "lock" -> "lock"
            "binary_sensor" -> when (dc) {
                "door", "garage_door", "opening" -> "door"
                "window" -> "window"
                "motion" -> "motion"
                "occupancy", "presence" -> "presence"
                "moisture" -> "leak"
                else -> null
            }
            else -> null
        }
    }

    /** Capteur résumé dans l'en-tête : il n'est pas affiché en tuile. */
    fun isHeaderSensor(id: String, e: EntityState?): Boolean {
        val d = domain(id)
        return (d == "sensor" || d == "binary_sensor") && chipKind(id, e) != null
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else "$n $many"

    /** Résumé de la pièce façon HomeKit : une pastille par type de capteur présent. */
    fun chips(room: Room, states: Map<String, EntityState>): List<Chip> {
        var tSum = 0.0; var tN = 0
        var hSum = 0.0; var hN = 0
        var cSum = 0.0; var cN = 0
        var lights = 0
        var locks = 0; var unlocked = 0; var jammed = 0
        var doors = 0; var doorsOpen = 0
        var windows = 0; var windowsOpen = 0
        var motion = 0; var motionOn = 0
        var presence = 0; var presenceOn = 0
        var leaks = 0; var leaksOn = 0
        for (re in room.entities) {
            val e = states[re.id] ?: continue
            if (e.state == "unavailable" || e.state == "unknown") continue
            val dc = e.attrs.str("device_class")
            val on = e.state == "on"
            when (e.domain) {
                "sensor" -> {
                    val v = e.state.toDoubleOrNull() ?: continue
                    when (dc) {
                        // On ignore les valeurs qui ne peuvent pas être une température ambiante
                        // (buse d'imprimante 3D, four, etc.).
                        "temperature" -> {
                            // Exclut congélateur, frigo, four… qui ne sont pas la température de la pièce.
                            val n = e.name.lowercase()
                            if (listOf("congél", "congel", "frigo", "réfrig", "refrig", "freezer", "fridge", "four", "oven", "piscine", "spa").any { it in n }) continue
                            val c = if (e.attrs.str("unit_of_measurement") == "°F") (v - 32) / 1.8 else v
                            // Premier capteur de la pièce (ordre choisi par l'utilisateur).
                            if (c > -40 && c < 45 && tN == 0) { tSum = v; tN = 1 }
                        }
                        "humidity" -> if (hN == 0) { hSum = v; hN = 1 }
                    }
                }
                "climate" -> if (cN == 0) e.attrs.dbl("current_temperature")?.let { cSum = it; cN = 1 }
                "light" -> if (on) lights++
                "lock" -> {
                    locks++
                    if (e.state == "jammed") jammed++ else if (e.state != "locked" && e.state != "locking") unlocked++
                }
                "binary_sensor" -> when (dc) {
                    "door", "garage_door", "opening" -> { doors++; if (on) doorsOpen++ }
                    "window" -> { windows++; if (on) windowsOpen++ }
                    "motion" -> { motion++; if (on) motionOn++ }
                    "occupancy", "presence" -> { presence++; if (on) presenceOn++ }
                    "moisture" -> { leaks++; if (on) leaksOn++ }
                }
            }
        }
        val out = ArrayList<Chip>()
        if (tN > 0) out.add(Chip(R.drawable.ic_thermostat, num(tSum / tN) + "°", kind = "temp"))
        else if (cN > 0) out.add(Chip(R.drawable.ic_thermostat, num(cSum / cN) + "°", kind = "temp"))
        if (hN > 0) out.add(Chip(R.drawable.ic_drop, num(hSum / hN) + " %", kind = "hum"))
        if (lights > 0) out.add(Chip(R.drawable.ic_light, plural(lights, "1 " + tr("allumée"), tr("allumées")), kind = "light"))
        if (locks > 0) out.add(when {
            jammed > 0 -> Chip(R.drawable.ic_lock_open, tr("Serrure bloquée"), 2, "lock")
            unlocked > 0 -> Chip(R.drawable.ic_lock_open, if (locks == 1) tr("Déverrouillé") else "$unlocked " + tr("déverrouillée(s)"), 1, "lock")
            else -> Chip(R.drawable.ic_lock, tr("Verrouillé"), kind = "lock")
        })
        if (doors > 0) out.add(
            if (doorsOpen > 0) Chip(R.drawable.ic_door, plural(doorsOpen, tr("Porte ouverte"), tr("portes ouvertes")), 1, "door")
            else Chip(R.drawable.ic_door, if (doors == 1) tr("Porte fermée") else tr("Portes fermées"), kind = "door")
        )
        if (windows > 0) out.add(
            if (windowsOpen > 0) Chip(R.drawable.ic_window, plural(windowsOpen, tr("Fenêtre ouverte"), tr("fenêtres ouvertes")), 1, "window")
            else Chip(R.drawable.ic_window, if (windows == 1) tr("Fenêtre fermée") else tr("Fenêtres fermées"), kind = "window")
        )
        if (motion > 0) out.add(
            if (motionOn > 0) Chip(R.drawable.ic_walk, tr("Mouvement"), 1, "motion") else Chip(R.drawable.ic_walk, tr("Aucun mouvement"), kind = "motion")
        )
        if (presence > 0) out.add(
            if (presenceOn > 0) Chip(R.drawable.ic_person, tr("Présence"), 1, "presence") else Chip(R.drawable.ic_person, tr("Personne"), kind = "presence")
        )
        if (leaks > 0) out.add(
            if (leaksOn > 0) Chip(R.drawable.ic_drop, tr("Fuite d'eau !"), 2, "leak") else Chip(R.drawable.ic_drop, tr("Pas de fuite"), kind = "leak")
        )
        return out
    }
}
