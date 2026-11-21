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
        "binary_sensor", "scene", "script", "media_player", "button", "input_button", "automation", "camera", "vacuum",
    )

    val CATEGORIES get() = listOf(tr("Caméras"), tr("Scènes"), tr("Lumières"), tr("Climat"), tr("Volets et stores"), tr("Prises et interrupteurs"), tr("Sécurité"), tr("Médias"), tr("Ménage"), tr("Capteurs"))

    private const val YELLOW = 0xFFFFB300.toInt()
    private val nf get() = NumberFormat.getNumberInstance(I18n.locale).apply { maximumFractionDigits = 1 }

    fun domain(id: String) = id.substringBefore('.')
    /** Nombre affiché ; une petite valeur négative arrondie à zéro s'écrit « 0 », pas « -0 ». */
    fun num(v: Double): String = nf.format(v).let { if (it.trimStart('-', '−').all { c -> c == '0' || c == ',' || c == '.' }) it.trimStart('-', '−') else it }

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
            "vacuum" -> s == "cleaning" || s == "returning" || s == "error"
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

    /** Règle la luminosité (0 éteint la lumière). */
    fun setBrightness(ha: HaClient, id: String, pct: Int) {
        if (pct <= 0) ha.callService("light", "turn_off", id)
        else ha.callService("light", "turn_on", id, JSONObject().put("brightness_pct", pct.coerceAtMost(100)))
    }

    /** Un rideau s'ouvre sur les côtés ; un store, un volet ou une porte de garage monte et descend. */
    fun isCurtain(id: String, e: EntityState?): Boolean {
        val dc = e?.attrs?.str("device_class").orEmpty()
        if (dc.isNotEmpty()) return dc == "curtain"
        val t = (id + " " + (e?.name ?: "")).lowercase()
        return listOf("rideau", "curtain", "drape").any { it in t }
    }

    /** Fonctions d'un store d'après supported_features (1 ouvrir, 2 fermer, 4 position, 8 arrêt) ; tout est permis si HA ne le dit pas. */
    fun coverCan(e: EntityState?, bit: Int): Boolean = e?.attrs?.int("supported_features")?.let { it and bit != 0 } ?: true

    /** Position d'un store, 0 fermé à 100 ouvert ; sans position annoncée, d'après l'état. */
    fun coverPosition(e: EntityState?): Int = e?.attrs?.int("current_position") ?: if (e?.state == "open" || e?.state == "closing") 100 else 0

    fun status(e: EntityState?): String {
        if (e == null) return "…"
        val s = e.state
        if (s == "unavailable") return tr("Non disponible")
        val a = e.attrs
        return when (e.domain) {
            "light" -> if (s == "on") brightnessPct(e)?.let { "${tr("Allumé")} · $it %" } ?: tr("Allumé") else tr("Éteint")
            "switch", "input_boolean", "automation" -> if (s == "on") tr("Activé") else tr("Désactivé")
            "fan" -> if (s == "on") a.int("percentage")?.let { "${tr("Activé")} · $it %" } ?: tr("Activé") else tr("Désactivé")
            "vacuum" -> vacuumStatus(e)
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
                // Allumé mais rien ne joue (écran d'accueil d'une Apple TV, menu d'une app).
                "idle" -> tr("Inactif")
                "on" -> tr("Allumé")
                "buffering" -> tr("Chargement…")
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
        "carbon_monoxide" -> if (on) tr("Monoxyde de carbone détecté") else "OK"
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
        if (Skin.shortStatus && cur != null && target != null) return "$cur → $target"
        val main = if (target != null) "$mode ${tr("à")} $target" else mode
        return listOfNotNull(cur, main).joinToString(" · ")
    }

    /** Pastille d'un thermostat : « 19,5 °C → 21 °C », « 18,5 °C · Arrêt » quand il est arrêté. */
    fun climateChip(e: EntityState): String {
        val u = climateUnit(e)
        val cur = e.attrs.dbl("current_temperature")?.let { degrees(it, u) }
        if (e.state == "off") return listOfNotNull(cur, tr("Arrêt")).joinToString(" · ")
        val target = e.attrs.dbl("temperature")?.let { degrees(it, u) }
        return listOfNotNull(cur, target).joinToString(" → ").ifEmpty { hvacName(e.state) }
    }

    /** Température avec son unité (« 19,5 °C ») ; un simple « ° » quand l'unité n'est pas connue. */
    fun degrees(v: Double, unit: String) = if (unit.isEmpty()) num(v) + "°" else num(v) + "\u00A0" + unit

    /** Unité d'un thermostat : celle qu'il annonce, sinon d'après ses limites (plus de 50 = Fahrenheit). */
    fun climateUnit(e: EntityState?): String {
        val u = e?.attrs?.str("temperature_unit").orEmpty()
        return if (u.isNotEmpty()) u else if ((e?.attrs?.dbl("max_temp") ?: 0.0) > 50) "°F" else "°C"
    }

    /** Aspirateur robot : état + batterie (ex. « Sur sa base · 100 % »). */
    /**
     * Pièces que l'aspirateur sait nettoyer séparément (identifiant, nom), d'après l'attribut « rooms » de l'intégration
     * Dreame : {carte: [{id, name}, …]}. On prend la carte sélectionnée, sinon la première. Vide pour un autre aspirateur.
     */
    fun vacuumRooms(e: EntityState?): List<Pair<Int, String>> {
        val maps = e?.attrs?.optJSONObject("rooms") ?: return emptyList()
        val arr = maps.optJSONArray(e.attrs.str("selected_map")) ?: maps.keys().asSequence().firstOrNull()?.let { maps.optJSONArray(it) } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o -> if (o.has("id") && o.optString("name").isNotBlank()) o.optInt("id") to o.optString("name") else null } }
    }

    fun vacuumStatus(e: EntityState): String {
        val a = e.attrs
        val st = when (e.state) {
            "docked" -> if (a.optBoolean("charging")) tr("En charge") else tr("Sur sa base")
            "cleaning" -> tr("Nettoyage")
            "returning" -> tr("Retour à la base")
            "paused" -> tr("En pause")
            "idle" -> tr("Arrêté")
            "error" -> tr("Erreur") + a.str("error").takeIf { it.isNotEmpty() && it != "No error" }?.let { " : $it" }.orEmpty()
            else -> e.state
        }
        val bat = a.int("battery_level") ?: a.int("battery")
        return if (bat != null) "$st · $bat %" else st
    }

    fun icon(id: String, e: EntityState?): Int {
        val dc = e?.attrs?.str("device_class") ?: ""
        return when (domain(id)) {
            "light" -> R.drawable.ic_light
            "switch" -> if (dc == "outlet") R.drawable.ic_outlet else R.drawable.ic_power
            "input_boolean" -> R.drawable.ic_toggle
            "fan" -> R.drawable.ic_fan
            "cover" -> if (isCurtain(id, e)) R.drawable.ic_curtain else R.drawable.ic_blinds
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
                "smoke", "carbon_monoxide" -> R.drawable.ic_smoke
                else -> R.drawable.ic_sensor
            }
            "scene" -> R.drawable.ic_star
            "script", "button", "input_button", "automation" -> R.drawable.ic_play
            "media_player" -> R.drawable.ic_tv
            "camera" -> R.drawable.ic_camera
            "vacuum" -> R.drawable.ic_vacuum
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
            "vacuum" -> if (e.state == "error") 0xFFFF453A.toInt() else 0xFF30B0C7.toInt()
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
        "vacuum" -> 8
        else -> 9
    }

    fun hasDetail(id: String, e: EntityState?): Boolean = when (domain(id)) {
        "light" -> supportsBrightness(e)
        "fan", "cover", "climate", "media_player", "vacuum" -> true
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

    /** Bouton « tout… » du détail d'une pastille (null : rien à faire en groupe pour ce type). */
    fun bulkLabel(kind: String): String? = when (kind) {
        "light" -> tr("Tout éteindre")
        "cover" -> tr("Tout fermer")
        "lock" -> tr("Tout verrouiller")
        else -> null
    }

    /** L'appareil est-il allumé, ouvert ou déverrouillé, donc concerné par le bouton « tout… » ? */
    fun bulkNeeded(id: String, e: EntityState?): Boolean {
        val s = e?.state ?: return false
        if (s == "unavailable" || s == "unknown") return false
        return when (domain(id)) {
            "light" -> s == "on"
            "cover" -> s == "open" || s == "opening"
            "lock" -> s != "locked" && s != "locking"
            else -> false
        }
    }

    /** Éteint, ferme ou verrouille l'appareil s'il en a besoin (jamais l'inverse). Retourne true si une commande est partie. */
    fun bulkOff(ha: HaClient, id: String, e: EntityState?): Boolean {
        if (!bulkNeeded(id, e)) return false
        when (domain(id)) {
            "light" -> ha.callService("light", "turn_off", id)
            "cover" -> ha.callService("cover", "close_cover", id)
            "lock" -> ha.callService("lock", "lock", id)
        }
        return true
    }

    /** Le toucher déverrouillerait-il cette serrure ? (Verrouiller ne demande jamais de confirmation.) */
    fun wouldUnlock(id: String, e: EntityState?) = domain(id) == "lock" && e?.state == "locked"

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

    /**
     * Home Assistant colle le nom de l'appareil devant celui du capteur : « Réfrigérateur Porte du réfrigérateur ».
     * Quand les premiers mots (un à trois) reviennent tels quels dans la suite, on les retire : « Porte du réfrigérateur ».
     */
    fun tidyName(name: String): String {
        val words = name.trim().split(Regex("\\s+"))
        fun plain(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        for (k in minOf(3, words.size - 2) downTo 1) {
            val head = words.take(k).map { plain(it) }
            val rest = words.drop(k).map { plain(it) }
            if (head.any { it.length < 3 }) continue
            if ((0..rest.size - k).any { i -> rest.subList(i, i + k) == head }) return words.drop(k).joinToString(" ").replaceFirstChar { it.uppercase() }
        }
        return name
    }

    fun displayName(re: RoomEntity, e: EntityState?, roomName: String): String {
        re.name?.let { if (it.isNotBlank()) return it }
        val n = tidyName(e?.name ?: re.id.substringAfter('.').replace('_', ' '))
        if (n.length > roomName.length + 1 && n.startsWith(roomName, ignoreCase = true)) {
            val rest = n.substring(roomName.length).trimStart(' ', '-', ':', '·').trim()
            if (rest.isNotEmpty()) return rest.replaceFirstChar { it.uppercase() }
        }
        return n
    }

    /** Pastille de l'en-tête : icône, texte, niveau (0 normal, 1 attention, 2 alerte). */
    /** [color] : couleur choisie pour une pastille personnalisée (sinon la couleur vient du niveau d'alerte et de la skin). */
    /** [entity] : pastille d'un seul appareil (thermostat) ; la toucher ouvre sa carte. */
    data class Chip(val icon: Int, val text: String, val level: Int = 0, val kind: String = "", val iconTint: Int? = null, val color: Int? = null, val entity: String? = null)

    /** Type de pastille auquel contribue une entité (null si aucune). */
    fun chipKind(id: String, e: EntityState?): String? {
        val dc = e?.attrs?.str("device_class") ?: return when (domain(id)) { "light" -> "light"; "lock" -> "lock"; "cover" -> "cover"; "climate" -> "climate"; else -> null }
        return when (domain(id)) {
            "sensor" -> when (dc) { "temperature" -> "temp"; "humidity" -> "hum"; else -> null }
            "climate" -> "climate"
            "light" -> "light"
            "lock" -> "lock"
            // Stores, rideaux, volets ; une porte de garage ou un portail n'est pas un store.
            "cover" -> if (dc in setOf("garage", "door", "gate")) null else "cover"
            "binary_sensor" -> when (dc) {
                "door", "garage_door", "opening" -> "door"
                "window" -> "window"
                "motion" -> "motion"
                "occupancy", "presence" -> "presence"
                "moisture" -> "leak"
                "smoke", "carbon_monoxide" -> "smoke"
                else -> null
            }
            else -> null
        }
    }

    /** Capteur résumé dans l'en-tête : il n'est pas affiché en tuile. */
    fun isHeaderSensor(id: String, e: EntityState?): Boolean {
        val d = domain(id)
        // Un thermostat est une pastille de sa pièce (qui ouvre sa carte), pas une tuile.
        return d == "climate" || (d == "sensor" || d == "binary_sensor") && chipKind(id, e) != null
    }

    fun plural(n: Int, one: String, many: String) = if (n == 1) one else "$n $many"

    /**
     * Voyant d'alerte principal du tableau de bord. [reds] : les alertes rouges du moment ; [acked] : celles déjà vues.
     * Rend s'il faut l'allumer (une alerte pas encore vue) et la liste des alertes vues encore en cours : une alerte
     * qui disparaît puis revient rallume le voyant.
     */
    fun caution(reds: Set<String>, acked: Set<String>): Pair<Boolean, Set<String>> {
        val still = acked intersect reds
        return (reds - still).isNotEmpty() to still
    }

    /** Systèmes toujours présents sur le panneau de voyants du tableau de bord, dans cet ordre. */
    val STANDING = listOf("light", "lock", "door", "window", "smoke")

    /**
     * Panneau de voyants : les systèmes de [STANDING] que la maison possède ([present]) gardent toujours leur case, en tête
     * et dans le même ordre, éteinte quand il n'y a rien à signaler ; les autres pastilles suivent.
     */
    fun standing(chips: List<Chip>, present: Set<String>): List<Chip> {
        val head = STANDING.mapNotNull { k ->
            chips.firstOrNull { it.kind == k } ?: if (k !in present) null else when (k) {
                "light" -> Chip(R.drawable.ic_light, tr("Lumières"), kind = k)
                "lock" -> Chip(R.drawable.ic_lock, tr("Serrures"), kind = k)
                "door" -> Chip(R.drawable.ic_door, tr("Portes"), kind = k)
                "window" -> Chip(R.drawable.ic_window, tr("Fenêtres"), kind = k)
                else -> null
            }
        }
        return head + chips.filter { it.kind !in STANDING }
    }

    /** Types dont l'utilisateur peut choisir l'état normal (voir [chips]). */
    val NORMAL_KINDS = listOf("light", "cover", "door", "window")

    /**
     * Un appareil du type [kind] est-il « au repos » (éteint, fermé) ? C'est l'écart que signale une pastille dont l'état
     * normal est « allumé / ouvert ». Un appareil injoignable ne compte pas.
     */
    fun atRest(kind: String, e: EntityState?): Boolean {
        if (e == null || e.state == "unavailable" || e.state == "unknown") return false
        return if (kind == "cover") !active(e) else e.state == "off"
    }

    /**
     * Résumé de la pièce façon HomeKit : une pastille par type de capteur présent.
     * [normalOn] : les types ([NORMAL_KINDS]) dont l'état normal est « allumé / ouvert ». Leur pastille ne dit plus ce qui
     * est allumé ou ouvert mais ce qui s'en écarte (« 2 stores fermés »), et disparaît quand tout est normal.
     */
    fun chips(room: Room, states: Map<String, EntityState>, normalOn: Set<String> = emptySet()): List<Chip> {
        var lightsOff = 0; var doorsClosed = 0; var windowsClosed = 0; var coversClosed = 0
        var tSum = 0.0; var tN = 0; var tUnit = ""
        var hSum = 0.0; var hN = 0
        val thermostats = ArrayList<Chip>()
        var lights = 0
        var locks = 0; var unlocked = 0; var jammed = 0; var silent = 0
        var doorsOpen = 0
        var windowsOpen = 0
        var coversOpen = 0; var curtainsOpen = 0
        var motion = 0; var motionOn = 0
        var presence = 0; var presenceOn = 0
        var leaks = 0; var leaksOn = 0
        var smoke = 0; var smokeOn = 0; var coOn = 0
        for (re in room.entities) {
            val e = states[re.id] ?: continue
            // Une serrure qui ne répond plus compte quand même : on ne sait pas si la porte est verrouillée.
            if (e.domain == "lock" && (e.state == "unavailable" || e.state == "unknown")) { locks++; silent++; continue }
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
                            if (c > -40 && c < 45 && tN == 0) { tSum = v; tN = 1; tUnit = e.attrs.str("unit_of_measurement") }
                        }
                        "humidity" -> if (hN == 0) { hSum = v; hN = 1 }
                    }
                }
                "climate" -> thermostats.add(Chip(R.drawable.ic_thermostat, climateChip(e), kind = "climate", entity = e.id,
                    iconTint = when (e.attrs.str("hvac_action")) { "heating" -> 0xFFFF7A00.toInt(); "cooling" -> 0xFF4DB8FF.toInt(); else -> null }))
                "light" -> if (on) lights++ else lightsOff++
                "cover" -> if (dc !in setOf("garage", "door", "gate")) { if (active(e)) { coversOpen++; if (isCurtain(e.id, e)) curtainsOpen++ } else coversClosed++ }
                "lock" -> {
                    locks++
                    if (e.state == "jammed") jammed++ else if (e.state != "locked" && e.state != "locking") unlocked++
                }
                "binary_sensor" -> when (dc) {
                    "door", "garage_door", "opening" -> { if (on) doorsOpen++ else doorsClosed++ }
                    "window" -> { if (on) windowsOpen++ else windowsClosed++ }
                    "motion" -> { motion++; if (on) motionOn++ }
                    "occupancy", "presence" -> { presence++; if (on) presenceOn++ }
                    "moisture" -> { leaks++; if (on) leaksOn++ }
                    "smoke" -> { smoke++; if (on) smokeOn++ }
                    "carbon_monoxide" -> { smoke++; if (on) coOn++ }
                }
            }
        }
        val out = ArrayList<Chip>()
        if (tN > 0) out.add(Chip(R.drawable.ic_thermostat, degrees(tSum / tN, tUnit), kind = "temp"))
        if (hN > 0) out.add(Chip(R.drawable.ic_drop, num(hSum / hN) + " %", kind = "hum"))
        // Une pastille par thermostat (température actuelle et consigne), après la température et l'humidité qui se suivent.
        out.addAll(thermostats)
        // Lumières, portes et fenêtres : rien à zéro ; sinon en jaune, pour ce qui est allumé ou ouvert.
        if ("light" in normalOn) { if (lightsOff > 0) out.add(Chip(R.drawable.ic_light, plural(lightsOff, "1 " + tr("éteinte"), tr("éteintes")), 1, "light")) }
        else if (lights > 0) out.add(Chip(R.drawable.ic_light, plural(lights, "1 " + tr("allumée"), tr("allumées")), 1, "light"))
        if (locks > 0) out.add(when {
            jammed > 0 -> Chip(R.drawable.ic_lock_open, tr("Serrure bloquée"), 2, "lock")
            unlocked > 0 -> Chip(R.drawable.ic_lock_open, if (locks == 1) tr("Déverrouillé") else plural(unlocked, "1 " + tr("déverrouillée"), tr("déverrouillées")), 1, "lock")
            silent > 0 -> Chip(R.drawable.ic_lock_open, plural(silent, tr("Serrure sans réponse"), tr("serrures sans réponse")), 1, "lock")
            else -> Chip(R.drawable.ic_lock, tr("Verrouillé"), kind = "lock")
        })
        if ("door" in normalOn) { if (doorsClosed > 0) out.add(Chip(R.drawable.ic_door, plural(doorsClosed, tr("Porte fermée"), tr("portes fermées")), 1, "door")) }
        else if (doorsOpen > 0) out.add(Chip(R.drawable.ic_door, plural(doorsOpen, tr("Porte ouverte"), tr("portes ouvertes")), 1, "door"))
        if ("window" in normalOn) { if (windowsClosed > 0) out.add(Chip(R.drawable.ic_window, plural(windowsClosed, tr("Fenêtre fermée"), tr("fenêtres fermées")), 1, "window")) }
        else if (windowsOpen > 0) out.add(Chip(R.drawable.ic_window, plural(windowsOpen, tr("Fenêtre ouverte"), tr("fenêtres ouvertes")), 1, "window"))
        // Comme les lumières : la pastille n'existe que s'il y a quelque chose d'ouvert.
        // Le texte dit ce qui est ouvert : des stores, des rideaux, ou les deux.
        if ("cover" in normalOn) { if (coversClosed > 0) out.add(Chip(R.drawable.ic_blinds, plural(coversClosed, tr("1 store fermé"), tr("stores fermés")), kind = "cover")) }
        else if (coversOpen > 0) out.add(when (curtainsOpen) {
            0 -> Chip(R.drawable.ic_blinds, plural(coversOpen, tr("1 store ouvert"), tr("stores ouverts")), kind = "cover")
            coversOpen -> Chip(R.drawable.ic_curtain, plural(coversOpen, tr("1 rideau ouvert"), tr("rideaux ouverts")), kind = "cover")
            else -> Chip(R.drawable.ic_blinds, "$coversOpen " + tr("stores et rideaux ouverts"), kind = "cover")
        })
        if (motion > 0) out.add(
            if (motionOn > 0) Chip(R.drawable.ic_walk, tr("Mouvement"), 1, "motion") else Chip(R.drawable.ic_walk, tr("Aucun mouvement"), kind = "motion")
        )
        if (presence > 0) out.add(
            if (presenceOn > 0) Chip(R.drawable.ic_person, tr("Présence"), 1, "presence") else Chip(R.drawable.ic_person, tr("Personne"), kind = "presence")
        )
        if (leaks > 0) out.add(
            if (leaksOn > 0) Chip(R.drawable.ic_drop, tr("Fuite d'eau !"), 2, "leak") else Chip(R.drawable.ic_drop, tr("Pas de fuite"), kind = "leak")
        )
        // Détecteurs de fumée (et de monoxyde de carbone) : toujours là, en alerte dès qu'un seul sonne.
        if (smoke > 0) out.add(when {
            smokeOn > 0 -> Chip(R.drawable.ic_smoke, tr("Fumée détectée !"), 2, "smoke")
            coOn > 0 -> Chip(R.drawable.ic_smoke, tr("Monoxyde de carbone !"), 2, "smoke")
            else -> Chip(R.drawable.ic_smoke, tr("Aucune fumée"), kind = "smoke")
        })
        return out
    }
}
