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

/** Aperçus des outils qu'on peut choisir pour l'accueil. */
val PREVIEWS = listOf("lists", "timers", "scores")

/** Nombre de pièces par page du rail (navigation latérale). */
const val RAIL_PAGE = 10

/** Pagination du rail des pièces. */
object Rail {
    fun pages(rooms: Int) = maxOf(1, (rooms + RAIL_PAGE - 1) / RAIL_PAGE)
    fun pageOf(room: Int) = room / RAIL_PAGE
    /** Indices des pièces de la page [page]. */
    fun range(page: Int, rooms: Int) = (page * RAIL_PAGE) until minOf(rooms, (page + 1) * RAIL_PAGE)
}

/** Sections qu'on peut masquer, dans l'ordre où les réglages les proposent. */
val SECTIONS = listOf("birthdays", "notes", "trackers", "weather", "agenda", "shortcuts", "favorites", "previews", "cameras", "energy", "scores", "timers", "lists", "music", "morning")

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
    /**
     * Fond d'écran : un dégradé ([Wallpapers.keys]) ou "photo" (photo personnelle, [PhotoWallpaper]).
     * Il n'est affiché que s'il n'y a pas de thème : c'est l'un ou l'autre (voir [chooseTheme] et [chooseWallpaper]).
     * En démo, la photo personnelle n'est jamais montrée.
     */
    var wallpaper: String
        get() = (p.getString("wallpaper", "nuit") ?: "nuit").let { if (it == PhotoWallpaper.KEY && demo) "nuit" else it }
        set(v) = p.edit().putString("wallpaper", v).apply()
    /** Change à chaque nouvelle photo : sert à redessiner le fond. */
    var wallpaperRev: Long
        get() = p.getLong("wallpaperRev", 0L)
        set(v) = p.edit().putLong("wallpaperRev", v).apply()
    /** Dernier thème utilisé, repris quand on revient du fond d'écran au thème. */
    var lastTheme: String
        get() = p.getString("lastTheme", "") ?: ""
        set(v) = p.edit().putString("lastTheme", v).apply()

    /** Vrai si l'arrière-plan est un thème (choisi ou automatique), faux si c'est un fond d'écran. */
    val themeMode: Boolean get() = theme == Themes.AUTO || Themes.get(theme) != null

    /** Thème réellement affiché : celui choisi, ou celui du jour en mode automatique ("" = aucun, fond d'écran). */
    val activeTheme: String get() = if (theme == Themes.AUTO) Themes.forDate(java.util.Calendar.getInstance(), autoHolidays, java.util.Locale.getDefault().country) else theme

    /** Fêtes suivies par le thème automatique (clés de thèmes). Par défaut : celles du pays de la tablette. */
    var autoHolidays: Set<String>
        get() = p.getString("autoHolidays", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: Themes.defaultHolidays(java.util.Locale.getDefault().country)
        set(v) = p.edit().putString("autoHolidays", v.joinToString(",")).apply()

    /** Force du voile posé sur une photo de fond, de 0 (photo bien visible) à 100 (texte très lisible) ; 50 = réglage de la skin. */
    var photoVeilLevel: Int
        get() = p.getInt("photoVeilLevel", 50)
        set(v) = p.edit().putInt("photoVeilLevel", v.coerceIn(0, 100)).apply()
    /** Jour (AAAAMMJJ) de la dernière photo Immich du jour. */
    var immichDay: Int
        get() = p.getInt("immichDay", 0)
        set(v) = p.edit().putInt("immichDay", v).apply()

    /** Affiche un thème (et donc plus le fond d'écran). Clé vide ou inconnue : le dernier thème utilisé, sinon l'automatique. */
    fun chooseTheme(key: String = "") {
        fun ok(k: String) = k == Themes.AUTO || Themes.get(k) != null
        val k = if (ok(key)) key else if (ok(lastTheme)) lastTheme else Themes.AUTO
        theme = k; lastTheme = k
    }

    /** Affiche un fond d'écran (et donc plus de thème). Clé vide : on garde le fond déjà choisi. */
    fun chooseWallpaper(key: String = "") {
        if (theme.isNotEmpty()) { lastTheme = theme; theme = "" }
        if (key.isNotEmpty()) wallpaper = key
    }
    /**
     * Disposition de l'écran : "classic" (accueil avec les pièces, outils en haut) ou "side" (navigation latérale :
     * pièces à gauche, caméra et lecteurs côte à côte).
     */
    var layout: String
        get() = if (p.getString("layout", "classic") == "side") "side" else "classic"
        set(v) = p.edit().putString("layout", if (v == "side") "side" else "classic").apply()
    val sideNav get() = layout == "side"
    /** Accueil : "classic" (pastilles et cartes dans l'ordre habituel) ou "priority" (voir [HomeFlow]). */
    var homeStyle: String
        get() = if (p.getString("homeStyle", "classic") == "priority") "priority" else "classic"
        set(v) = p.edit().putString("homeStyle", if (v == "priority") "priority" else "classic").apply()
    /** Accueil par priorité : les heures des moments et les cartes de chacun (voir [HomeFlow.parsePlan]) ; vide = réglage d'origine. */
    var homePlan: String
        get() = p.getString("homePlan", "") ?: ""
        set(v) = p.edit().putString("homePlan", v).apply()
    /** Accueil par priorité : minutes avant qu'une porte ou une fenêtre ouverte devienne une carte « À voir maintenant » (0 = tout de suite). */
    var attentionDelay: Int
        get() = p.getInt("attentionDelay", 5)
        set(v) = p.edit().putInt("attentionDelay", v.coerceIn(0, 120)).apply()
    /** Accueil par priorité : portes et fenêtres dont l'alerte est allumée ou coupée à la main (les autres suivent [HomeFlow.alertByDefault]). */
    var alertOn: List<String>
        get() = csv("alertOn")
        set(v) = p.edit().putString("alertOn", v.distinct().joinToString(",")).apply()
    var alertOff: List<String>
        get() = csv("alertOff")
        set(v) = p.edit().putString("alertOff", v.distinct().joinToString(",")).apply()
    /** Accueil par priorité : garder toujours le même ordre des cartes, au lieu de suivre le moment de la journée. */
    /** Accueil par priorité : niveau et délai choisis par type d'alerte (voir [HomeFlow.parseRules]) ; vide = règles d'origine. */
    var attentionRules: String
        get() = p.getString("attentionRules", "") ?: ""
        set(v) = p.edit().putString("attentionRules", v).apply()
    /** Pastilles : types dont l'état normal est « allumé / ouvert » (voir [Present.chips]) ; les autres sont normalement éteints ou fermés. */
    var normalOn: List<String>
        get() = csv("normalOn")
        set(v) = p.edit().putString("normalOn", v.filter { it in Present.NORMAL_KINDS }.distinct().joinToString(",")).apply()
    /** Affiches refusées à la main : clés de recherche ([Posters.searchKey]) pour lesquelles on ne cherche plus d'affiche. */
    var posterRejected: Set<String>
        get() = try { JSONArray(p.getString("posterRejected", "[]")).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } } catch (_: Exception) { emptySet() }
        set(v) = p.edit().putString("posterRejected", JSONArray(v.filter { it.isNotBlank() }.take(300)).toString()).apply()
    /** Accueil par priorité : options des cartes (voir [HomeFlow.Options]). */
    var homeCardOptions: String
        get() = p.getString("homeCardOptions", "") ?: ""
        set(v) = p.edit().putString("homeCardOptions", v).apply()

    var homeFixedOrder: Boolean
        get() = p.getBoolean("homeFixedOrder", false)
        set(v) = p.edit().putBoolean("homeFixedOrder", v).apply()
    /** Skin : façon d'afficher l'app ("homekit", "epure", "papier"). */
    var skin: String
        get() = p.getString("skin", "homekit") ?: "homekit"
        set(v) = p.edit().putString("skin", v).apply()
    /** Skin de nuit : remplace [skin] entre [nightSkinStart] et [nightSkinEnd] (minutes depuis minuit) ; vide = aucune. */
    var nightSkin: String
        get() = p.getString("nightSkin", "") ?: ""
        set(v) = p.edit().putString("nightSkin", v).apply()
    var nightSkinStart: Int
        get() = p.getInt("nightSkinStart", 19 * 60)
        set(v) = p.edit().putInt("nightSkinStart", v.coerceIn(0, 1439)).apply()
    var nightSkinEnd: Int
        get() = p.getInt("nightSkinEnd", 7 * 60)
        set(v) = p.edit().putInt("nightSkinEnd", v.coerceIn(0, 1439)).apply()
    /** Skin à afficher maintenant : celle de nuit pendant sa plage (qui peut passer minuit), sinon la skin choisie. */
    val activeSkin: String
        get() {
            val n = nightSkin
            if (n.isEmpty() || n == skin) return skin
            val c = java.util.Calendar.getInstance()
            return if (TimeRange.contains(nightSkinStart, nightSkinEnd, c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE))) n else skin
        }
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
    /** Écran de veille : "black" (noir), "clock" (heure + météo), "radar" (balayage et voyants) ou "photos" (cadre photo). */
    var dimStyle: String
        get() = p.getString("dimStyle", "clock") ?: "clock"
        set(v) = p.edit().putString("dimStyle", if (v in setOf("black", "photos", "radar")) v else "clock").apply()
    /** Style réellement affiché : noir pendant les heures calmes. */
    val effectiveDimStyle: String get() = if (quietBlack && isQuietNow()) "black" else dimStyle
    /** Écran de veille noir pendant les heures calmes (décoché : les heures calmes ne coupent que le son). */
    var quietBlack: Boolean
        get() = p.getBoolean("quietBlack", true)
        set(v) = p.edit().putBoolean("quietBlack", v).apply()
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
        return TimeRange.contains(quietStart, quietEnd, m)
    }

    /** Pointage : heures avant le début d'un match où il s'affiche déjà, et heures après le début où il reste affiché. */
    var scoreBefore: Int
        get() = p.getInt("scoreBefore", 12)
        set(v) = p.edit().putInt("scoreBefore", v.coerceIn(0, 72)).apply()
    var scoreAfter: Int
        get() = p.getInt("scoreAfter", 8)
        set(v) = p.edit().putInt("scoreAfter", v.coerceIn(3, 72)).apply()

    /** Alarme plein écran, avec sonnerie, quand un détecteur de fumée ou de monoxyde des pièces se déclenche. */
    var smokeAlarm: Boolean
        get() = p.getBoolean("smokeAlarm", true)
        set(v) = p.edit().putBoolean("smokeAlarm", v).apply()
    /** Alarme plein écran (bleue, sonnerie plus douce) quand un détecteur de fuite d'eau des pièces se déclenche. */
    var leakAlarm: Boolean
        get() = p.getBoolean("leakAlarm", true)
        set(v) = p.edit().putBoolean("leakAlarm", v).apply()
    /** Demander une confirmation avant de déverrouiller une serrure (verrouiller reste immédiat). */
    var confirmUnlock: Boolean
        get() = p.getBoolean("confirmUnlock", true)
        set(v) = p.edit().putBoolean("confirmUnlock", v).apply()
    /** Calendrier des anniversaires : "" = automatique (le calendrier de l'agenda qui en porte le nom), "none" = aucun. */
    var birthdayCalendar: String
        get() = p.getString("birthdayCalendar", "") ?: ""
        set(v) = p.edit().putString("birthdayCalendar", v).apply()
    /** Raccourci qui ouvre le résumé du soir : "" = automatique (« Bonne nuit »), "none" = aucun, sinon l'entité. */
    /** Séquence de lancement plein écran après une scène ou un script (voir [Launch]). */
    var launchSequence: Boolean
        get() = p.getBoolean("launchSequence", false)
        set(v) = p.edit().putBoolean("launchSequence", v).apply()
    /** Raccourcis qui n'ont pas de séquence de lancement (par défaut, tous en ont une quand elle est activée). */
    var launchSkip: List<String>
        get() = if (demo) emptyList() else csv("launchSkip")
        set(v) = p.edit().putString("launchSkip", v.distinct().joinToString(",")).apply()
    /**
     * Vérification après un raccourci (fenêtre de ce qui reste allumé, ouvert ou déverrouillé) : raccourcis où elle est
     * activée à la main, et ceux où elle est coupée à la main. Pour les autres, voir [HomeCare.checkAfter].
     */
    var summaryOn: List<String>
        get() = if (demo) emptyList() else csv("summaryOn")
        set(v) = p.edit().putString("summaryOn", v.distinct().joinToString(",")).apply()
    var summaryOff: List<String>
        get() = if (demo) emptyList() else csv("summaryOff")
        set(v) = p.edit().putString("summaryOff", v.distinct().joinToString(",")).apply()
    /** La vérification suit-elle le raccourci [id], nommé [name] ? */
    fun checksAfter(id: String, name: String) = HomeCare.checkAfter(summaryOn, summaryOff, nightShortcut, departShortcut, id, name)
    /** Ancien réglage (2026.11.14), encore lu : raccourci suivi de la liste « Avant de partir » (ce qui reste allumé, ouvert ou déverrouillé) ; "" = aucun. */
    var departShortcut: String
        get() = if (demo) "" else p.getString("departShortcut", "") ?: ""
        set(v) = p.edit().putString("departShortcut", v).apply()
    /** Seuil du cadran de puissance pendant une pointe, en dixièmes de kW (50 = 5 kW) ; 0 = pas de zone rouge. */
    var peakPowerLimit: Int
        get() = p.getInt("peakPowerLimit", 50)
        set(v) = p.edit().putInt("peakPowerLimit", v.coerceIn(0, 100)).apply()
    var nightShortcut: String
        get() = if (demo) "" else p.getString("nightShortcut", "") ?: ""
        set(v) = p.edit().putString("nightShortcut", v).apply()
    /** Appareils suivis (voir [Trackers]) : [{name, icon, entity, progress, remaining, title, image, room, notify}]. */
    var trackers: JSONArray
        get() = if (demo) Demo.trackers() else json("trackers")
        set(v) = p.edit().putString("trackers", v.toString()).apply()
    /** Caméra du radar météo : "" = automatique (la caméra de Home Assistant qui s'appelle « radar »), "none" = aucune. */
    var radarCamera: String
        get() = p.getString("radarCamera", "") ?: ""
        set(v) = p.edit().putString("radarCamera", v).apply()
    /** Pastille d'entretien des aspirateurs (filtre, brosses… en fin de vie) : seuil en %, 0 = pas de pastille. */
    var maintenanceLow: Int
        get() = if (demo) 10 else p.getInt("maintenanceLow", 10)
        set(v) = p.edit().putInt("maintenanceLow", v.coerceIn(0, 50)).apply()
    /** Pastille « piles faibles » de l'accueil : seuil en % (0 = pas de pastille). */
    var batteryLow: Int
        get() = if (demo) 15 else p.getInt("batteryLow", 15)
        set(v) = p.edit().putInt("batteryLow", v.coerceIn(0, 50)).apply()
    /** Pastille « appareils hors ligne » de l'accueil (appareils des pièces que Home Assistant ne joint plus). */
    var offlineChip: Boolean
        get() = p.getBoolean("offlineChip", true)
        set(v) = p.edit().putBoolean("offlineChip", v).apply()
    /** Capteurs de présence ou de mouvement (Home Assistant) qui sortent la tablette de veille quand on approche. */
    var wakeSensors: List<String>
        get() = if (demo) emptyList() else csv("wakeSensors")
        set(v) = p.edit().putString("wakeSensors", v.distinct().joinToString(",")).apply()
    /** Lumières de la pièce où est la tablette : toutes éteintes, l'écran passe à [darkBrightness]. Vide = pas d'ajustement. */
    var ambientLights: List<String>
        get() = if (demo) emptyList() else csv("ambientLights")
        set(v) = p.edit().putString("ambientLights", v.distinct().joinToString(",")).apply()
    var darkBrightness: Int
        get() = p.getInt("darkBrightness", 20)
        set(v) = p.edit().putInt("darkBrightness", v.coerceIn(1, 100)).apply()
    /** Dernière version annoncée par une notification (une seule annonce par version). */
    var announcedVersion: String
        get() = p.getString("announcedVersion", "") ?: ""
        set(v) = p.edit().putString("announcedVersion", v).apply()

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
    /** Pièce choisie pour un raccourci : entité → "" (automatique), "home" (accueil) ou identifiant de pièce. */
    var shortcutRooms: JSONObject
        get() = if (demo) JSONObject() else jsonObj("shortcutRooms")
        set(v) = p.edit().putString("shortcutRooms", v.toString()).apply()
    /** Zone Home Assistant de chaque raccourci (entité → zone), relue à chaque connexion. */
    var shortcutAreas: JSONObject
        get() = if (demo) JSONObject().put("scene.movie_night", "living_room") else jsonObj("shortcutAreas")
        set(v) = p.edit().putString("shortcutAreas", v.toString()).apply()
    private val objCache = HashMap<String, Pair<String, JSONObject>>()
    private fun jsonObj(k: String): JSONObject {
        val raw = p.getString(k, "{}") ?: "{}"
        objCache[k]?.let { if (it.first == raw) return it.second }
        val o = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        objCache[k] = raw to o
        return o
    }
    var customChips: JSONArray
        get() = if (demo) JSONArray().put(JSONObject().put("entity", "input_select.washer").put("states", JSONArray().put(if (I18n.english) "To empty" else "À vider")).put("text", if (I18n.english) "🧺 Washer to empty" else "🧺 Laveuse à vider").put("color", "orange")) else json("customChips")
        set(v) = p.edit().putString("customChips", v.toString()).apply()
    /** Listes d'épicerie et de tâches affichées (entités todo.* de Home Assistant). */
    var todoLists: List<String>
        get() = if (demo) listOf("todo.groceries", "todo.chores") else csv("todoLists")
        set(v) = p.edit().putString("todoLists", v.joinToString(",")).apply()
    /** Capteurs d'électricité (puissance, énergie) : le premier fait la pastille de l'accueil, tous sont dans la fenêtre Énergie. */
    /** Enceintes de Music Assistant proposées dans la fenêtre Musique (vide = pas d'icône). */
    /**
     * Sections masquées par choix (réglage « Sections affichées ») : leur configuration reste, seul l'affichage est coupé.
     * Clés : weather, agenda, shortcuts, energy, scores, timers, lists, music, morning.
     */
    var hiddenSections: List<String>
        get() = if (demo) emptyList() else csv("hiddenSections")
        set(v) = p.edit().putString("hiddenSections", v.filter { it in SECTIONS }.distinct().joinToString(",")).apply()
    fun shows(section: String) = section !in hiddenSections

    /** Accueil en navigation latérale : appareils favoris (tuiles) et caméras (vignettes), choisis parmi ceux des pièces. */
    var homeFavorites: List<String>
        get() = if (demo) listOf("light.living_room_ceiling", "light.kitchen_ceiling", "lock.front_door", "cover.living_room_blinds", "light.bedside_right") else csv("homeFavorites")
        set(v) = p.edit().putString("homeFavorites", v.distinct().joinToString(",")).apply()
    /** Aperçus des outils décochés sur l'accueil : "lists", "timers", "scores". */
    var previewsOff: List<String>
        get() = if (demo) emptyList() else csv("previewsOff")
        set(v) = p.edit().putString("previewsOff", v.filter { it in PREVIEWS }.distinct().joinToString(",")).apply()
    fun previews(kind: String) = kind !in previewsOff

    var homeCameras: List<String>
        get() = if (demo) listOf("camera.front_door", "camera.driveway") else csv("homeCameras")
        set(v) = p.edit().putString("homeCameras", v.filter { it.startsWith("camera.") }.distinct().joinToString(",")).apply()

    var musicPlayers: List<String>
        get() = if (demo) listOf("media_player.living_room_speaker", "media_player.kids_speaker") else csv("musicPlayers")
        set(v) = p.edit().putString("musicPlayers", v.joinToString(",")).apply()

    /** Serveur Music Assistant (vide = la machine de Home Assistant, port 8095) et son jeton, pour la file de lecture. */
    var musicUrl: String
        get() = p.getString("musicUrl", "") ?: ""
        set(v) = p.edit().putString("musicUrl", v.trim().trimEnd('/')).apply()
    var musicToken: String
        get() = if (demo) "" else secret("musicToken")
        set(v) = setSecret("musicToken", v.trim())

    var energySensors: List<String>
        get() = if (demo) listOf("sensor.home_power", "sensor.energy_today") else csv("energySensors")
        set(v) = p.edit().putString("energySensors", v.joinToString(",")).apply()
    /** Lumières, serrures, portes et fenêtres qui ne comptent pas dans les pastilles de l'accueil (les autres comptent). */
    var summaryHidden: List<String>
        get() = if (demo) emptyList() else csv("summaryHidden")
        set(v) = p.edit().putString("summaryHidden", v.joinToString(",")).apply()
    /** Appareils décochés d'office (voir [Choices]) que l'utilisateur a recochés. */
    var summaryShown: List<String>
        get() = if (demo) emptyList() else csv("summaryShown")
        set(v) = p.edit().putString("summaryShown", v.joinToString(",")).apply()
    var nowPlayingShown: List<String>
        get() = if (demo) emptyList() else csv("nowPlayingShown")
        set(v) = p.edit().putString("nowPlayingShown", v.joinToString(",")).apply()
    /** Minutes pendant lesquelles la carte « En lecture » reste après une pause ; 0 = elle disparaît, -1 = tant que dure la pause. */
    var nowPlayingPause: Int
        get() = p.getInt("nowPlayingPause", 10)
        set(v) = p.edit().putInt("nowPlayingPause", if (v < 0) -1 else v.coerceAtMost(240)).apply()
    /** Lecteurs qui ne font pas de carte « En lecture » (les autres lecteurs des pièces en font une). */
    var nowPlayingHidden: List<String>
        get() = if (demo) emptyList() else csv("nowPlayingHidden")
        set(v) = p.edit().putString("nowPlayingHidden", v.joinToString(",")).apply()
    /** Capteurs TeamTracker des équipes suivies (pointage dans le bandeau et sur l'écran de veille). */
    var sportSensors: List<String>
        get() = if (demo) emptyList() else csv("sportSensors")
        set(v) = p.edit().putString("sportSensors", v.joinToString(",")).apply()
    /** Chaînes de télé (Télé Fibe) : {code: {name, number}}, le numéro et le nom que l'abonné veut voir. */
    var fibeChannels: String
        get() = if (demo) "{}" else p.getString("fibeChannels", "{}") ?: "{}"
        set(v) = p.edit().putString("fibeChannels", v).apply()
    /** Vignette de ce qui joue dans le bandeau du haut. */
    var nowPlayingHeader: Boolean
        get() = p.getBoolean("nowPlayingHeader", true)
        set(v) = p.edit().putBoolean("nowPlayingHeader", v).apply()
    /** Chercher l'affiche sur Internet quand le lecteur n'en donne pas ; clés facultatives de TMDB et de YouTube. */
    var posterSearch: Boolean
        get() = p.getBoolean("posterSearch", true)
        set(v) = p.edit().putBoolean("posterSearch", v).apply()
    var tmdbKey: String
        get() = if (demo) "" else secret("tmdbKey")
        set(v) = setSecret("tmdbKey", v.trim())
    var youtubeKey: String
        get() = if (demo) "" else secret("youtubeKey")
        set(v) = setSecret("youtubeKey", v.trim())
    /** Carte « En lecture » en haut d'une pièce quand un de ses haut-parleurs ou sa télé joue. */
    var showNowPlaying: Boolean
        get() = p.getBoolean("showNowPlaying", true)
        set(v) = p.edit().putBoolean("showNowPlaying", v).apply()
    /** Minuteries favorites : [{name, minutes}] (JSON), réglées sur la page web. */
    var timerPresets: JSONArray
        get() = if (demo) JSONArray().put(JSONObject().put("name", if (I18n.english) "Pasta" else "Pâtes").put("minutes", 10)).put(JSONObject().put("name", if (I18n.english) "Eggs" else "Œufs").put("minutes", 7)).put(JSONObject().put("name", if (I18n.english) "Tea" else "Thé").put("minutes", 4)) else json("timerPresets")
        set(v) = p.edit().putString("timerPresets", v.toString()).apply()
    /** Ce matin : sources [{type:"rss", title, url, count} ou {entity, attribute, title}] (JSON) et plage horaire (minutes). */
    var morningSources: JSONArray
        get() = if (demo) JSONArray().put(JSONObject().put("entity", "sensor.morning_summary").put("attribute", "weather").put("title", if (I18n.english) "Weather" else "Météo")).put(JSONObject().put("entity", "sensor.morning_summary").put("attribute", "commute").put("title", if (I18n.english) "Commute" else "Trajet")).put(JSONObject().put("type", "rss").put("url", "demo:news").put("count", 6).put("title", if (I18n.english) "Local news" else "Nouvelles locales")) else json("morningSources")
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
        return TimeRange.contains(morningStart, morningEnd, m)
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
    fun resetDemo() { Demo.resetLists(); demoRooms = null; demoFloors = null; demoWeather = Demo.WEATHER; demoCalendars = Demo.CALENDARS; demoPersons = Demo.PERSONS }

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

    /**
     * Liste JSON d'un réglage, décodée une seule fois tant que le texte enregistré ne change pas
     * (ces listes sont relues à chaque changement d'état d'une entité). À lire seulement, sans la modifier.
     */
    private val jsonCache = HashMap<String, Pair<String, JSONArray>>()
    private fun json(k: String): JSONArray {
        val raw = p.getString(k, "[]") ?: "[]"
        jsonCache[k]?.let { if (it.first == raw) return it.second }
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        jsonCache[k] = raw to arr
        return arr
    }

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
                .put("theme", theme).put("photoVeilLevel", photoVeilLevel).put("autoHolidays", JSONArray(autoHolidays.toList())).put("skin", skin).put("layout", layout).put("homeStyle", homeStyle).put("homeFixedOrder", homeFixedOrder).put("homePlan", homePlan).put("posterRejected", JSONArray(posterRejected.toList())).put("normalOn", JSONArray(normalOn)).put("attentionRules", attentionRules).put("homeCardOptions", homeCardOptions).put("attentionDelay", attentionDelay).put("alertOn", JSONArray(alertOn)).put("alertOff", JSONArray(alertOff)).put("nightSkin", nightSkin).put("nightSkinStart", nightSkinStart).put("nightSkinEnd", nightSkinEnd).put("wallpaper", wallpaper).put("dim", dimMinutes).put("motionWake", motionWake).put("dimStyle", dimStyle).put("dimBrightness", dimBrightness)
                .put("group", groupByCategory).put("clock", showClock).put("battery", showBattery).put("noticeSeconds", noticeSeconds)
                .put("quietEnabled", quietEnabled).put("quietStart", quietStart).put("quietEnd", quietEnd).put("soundsEnabled", soundsEnabled)
                .put("photoSource", photoSource).put("photoSourceName", photoSourceName).put("photoSeconds", photoSeconds)
                .put("immichUrl", immichUrl).put("musicUrl", musicUrl).put("nowPlayingHeader", nowPlayingHeader).put("posterSearch", posterSearch).put("fibeChannels", try { JSONObject(fibeChannels) } catch (_: Exception) { JSONObject() }).put("brightness", brightness)
                .put("language", language).put("personAlert", personAlert).put("alertSeconds", alertSeconds).put("alertTrigger", alertTrigger).put("doorbellAlert", doorbellAlert)
                .put("launchSequence", launchSequence).put("launchSkip", JSONArray(launchSkip)).put("departShortcut", departShortcut).put("summaryOn", JSONArray(summaryOn)).put("summaryOff", JSONArray(summaryOff)).put("peakPowerLimit", peakPowerLimit).put("quietBlack", quietBlack).put("smokeAlarm", smokeAlarm).put("trackers", trackers).put("radarCamera", radarCamera).put("maintenanceLow", maintenanceLow).put("birthdayCalendar", birthdayCalendar).put("nightShortcut", nightShortcut).put("leakAlarm", leakAlarm).put("confirmUnlock", confirmUnlock).put("batteryLow", batteryLow).put("offlineChip", offlineChip)
                .put("wakeSensors", JSONArray(wakeSensors)).put("ambientLights", JSONArray(ambientLights)).put("darkBrightness", darkBrightness)
                .put("scoreBefore", scoreBefore).put("scoreAfter", scoreAfter))
            .put("home", JSONObject()
                .put("weather", weatherEntity).put("calendars", JSONArray(calendars))
                .put("persons", JSONArray(persons)).put("recycleCalendar", recycleCalendar).put("wasteCalendar", wasteCalendar)
                .put("peakCalendar", peakCalendar).put("peakReminder", peakReminder).put("weatherAlerts", JSONArray(weatherAlerts))
                .put("shortcuts", JSONArray(shortcuts)).put("shortcutRooms", shortcutRooms).put("customChips", customChips).put("morningSources", morningSources).put("timerPresets", timerPresets).put("todoLists", JSONArray(todoLists)).put("showNowPlaying", showNowPlaying).put("nowPlayingHidden", JSONArray(nowPlayingHidden)).put("nowPlayingShown", JSONArray(nowPlayingShown)).put("nowPlayingPause", nowPlayingPause)
                .put("summaryHidden", JSONArray(summaryHidden)).put("summaryShown", JSONArray(summaryShown)).put("energySensors", JSONArray(energySensors)).put("musicPlayers", JSONArray(musicPlayers)).put("hiddenSections", JSONArray(hiddenSections)).put("homeFavorites", JSONArray(homeFavorites)).put("homeCameras", JSONArray(homeCameras)).put("previewsOff", JSONArray(previewsOff)).put("sportSensors", JSONArray(sportSensors))
                .put("morningStart", morningStart).put("morningEnd", morningEnd)
                .put("recycleKeyword", recycleKeyword).put("wasteKeyword", wasteKeyword).put("floorOrder", JSONArray(floors.map { it.id })))
            .put("connection", JSONObject().put("url", url))
            .put("mqtt", JSONObject()
                .put("enabled", mqttEnabled).put("host", mqttHost).put("port", mqttPort)
                .put("user", mqttUser).put("deviceName", deviceName).put("deviceId", mqttDeviceId))
            .put("web", JSONObject().put("enabled", webEnabled))
        if (withSecrets) o.put("secrets", JSONObject().put("token", token).put("mqttPass", mqttPass).put("pin", pin).put("immichKey", immichKey).put("musicToken", musicToken).put("tmdbKey", tmdbKey).put("youtubeKey", youtubeKey))
        return o
    }

    /** Applique une sauvegarde. Lève une exception si le fichier n'est pas reconnu. */
    fun importJson(o: JSONObject) {
        require(o.optString("format") == "homepanel-config") { "Ce fichier n'est pas une sauvegarde de l'app." }
        o.optJSONArray("rooms")?.let { val r = parseRooms(it); rooms.clear(); rooms.addAll(r); saveRooms() }
        o.optJSONObject("display")?.let { d ->
            if (d.has("theme")) theme = d.str("theme")
            if (d.has("skin")) skin = d.str("skin")
            if (d.has("layout")) layout = d.str("layout")
            if (d.has("homeStyle")) homeStyle = d.str("homeStyle")
            if (d.has("homeFixedOrder")) homeFixedOrder = d.optBoolean("homeFixedOrder", false)
            if (d.has("homePlan")) homePlan = d.str("homePlan")
            if (d.has("attentionRules")) attentionRules = d.str("attentionRules")
            d.optJSONArray("normalOn")?.let { a -> normalOn = (0 until a.length()).map { a.optString(it) } }
            d.optJSONArray("posterRejected")?.let { a -> posterRejected = (0 until a.length()).map { a.optString(it) }.toSet() }
            if (d.has("homeCardOptions")) homeCardOptions = d.str("homeCardOptions")
            if (d.has("attentionDelay")) attentionDelay = d.optInt("attentionDelay", 5)
            d.optJSONArray("alertOn")?.let { a -> alertOn = (0 until a.length()).map { a.optString(it) } }
            d.optJSONArray("alertOff")?.let { a -> alertOff = (0 until a.length()).map { a.optString(it) } }
            if (d.has("photoVeilLevel")) photoVeilLevel = d.optInt("photoVeilLevel", 50)
            d.optJSONArray("autoHolidays")?.let { a -> autoHolidays = (0 until a.length()).map { a.getString(it) }.toSet() }
            if (d.has("nightSkin")) nightSkin = d.str("nightSkin")
            if (d.has("nightSkinStart")) nightSkinStart = d.optInt("nightSkinStart", 19 * 60)
            if (d.has("nightSkinEnd")) nightSkinEnd = d.optInt("nightSkinEnd", 7 * 60)
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
            if (d.has("musicUrl")) musicUrl = d.str("musicUrl")
            if (d.has("nowPlayingHeader")) nowPlayingHeader = d.optBoolean("nowPlayingHeader", true)
            if (d.has("posterSearch")) posterSearch = d.optBoolean("posterSearch", true)
            d.optJSONObject("fibeChannels")?.let { fibeChannels = it.toString() }
            if (d.has("brightness")) brightness = d.optInt("brightness", -1)
            if (d.has("language")) language = d.str("language")
            if (d.has("personAlert")) personAlert = d.optBoolean("personAlert")
            if (d.has("alertSeconds")) alertSeconds = d.optInt("alertSeconds", 20)
            if (d.has("alertTrigger")) alertTrigger = d.str("alertTrigger")
            if (d.has("doorbellAlert")) doorbellAlert = d.optBoolean("doorbellAlert")
            if (d.has("quietBlack")) quietBlack = d.optBoolean("quietBlack", true)
            if (d.has("smokeAlarm")) smokeAlarm = d.optBoolean("smokeAlarm", true)
            if (d.has("leakAlarm")) leakAlarm = d.optBoolean("leakAlarm", true)
            if (d.has("birthdayCalendar")) birthdayCalendar = d.str("birthdayCalendar")
            d.optJSONArray("trackers")?.let { trackers = it }
            if (d.has("radarCamera")) radarCamera = d.str("radarCamera")
            if (d.has("maintenanceLow")) maintenanceLow = d.optInt("maintenanceLow", 10)
            if (d.has("nightShortcut")) nightShortcut = d.str("nightShortcut")
            if (d.has("launchSequence")) launchSequence = d.optBoolean("launchSequence", false)
            d.optJSONArray("launchSkip")?.let { a -> launchSkip = (0 until a.length()).map { a.optString(it) } }
            if (d.has("departShortcut")) departShortcut = d.str("departShortcut")
            d.optJSONArray("summaryOn")?.let { a -> summaryOn = (0 until a.length()).map { a.optString(it) } }
            d.optJSONArray("summaryOff")?.let { a -> summaryOff = (0 until a.length()).map { a.optString(it) } }
            if (d.has("peakPowerLimit")) peakPowerLimit = d.optInt("peakPowerLimit", 50)
            if (d.has("confirmUnlock")) confirmUnlock = d.optBoolean("confirmUnlock", true)
            if (d.has("batteryLow")) batteryLow = d.optInt("batteryLow", 15)
            if (d.has("offlineChip")) offlineChip = d.optBoolean("offlineChip", true)
            d.optJSONArray("wakeSensors")?.let { a -> wakeSensors = (0 until a.length()).map { a.getString(it) } }
            d.optJSONArray("ambientLights")?.let { a -> ambientLights = (0 until a.length()).map { a.getString(it) } }
            if (d.has("darkBrightness")) darkBrightness = d.optInt("darkBrightness", 20)
            if (d.has("scoreBefore")) scoreBefore = d.optInt("scoreBefore", 12)
            if (d.has("scoreAfter")) scoreAfter = d.optInt("scoreAfter", 8)
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
            h.optJSONObject("shortcutRooms")?.let { shortcutRooms = it }
            h.optJSONArray("customChips")?.let { customChips = it }
            h.optJSONArray("morningSources")?.let { morningSources = it }
            h.optJSONArray("timerPresets")?.let { timerPresets = it }
            h.optJSONArray("todoLists")?.let { a -> todoLists = (0 until a.length()).map { a.getString(it) } }
            if (h.has("showNowPlaying")) showNowPlaying = h.optBoolean("showNowPlaying", true)
            h.optJSONArray("summaryHidden")?.let { a -> summaryHidden = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("summaryShown")?.let { a -> summaryShown = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("nowPlayingShown")?.let { a -> nowPlayingShown = (0 until a.length()).map { a.getString(it) } }
            if (h.has("nowPlayingPause")) nowPlayingPause = h.optInt("nowPlayingPause", 10)
            h.optJSONArray("nowPlayingHidden")?.let { a -> nowPlayingHidden = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("energySensors")?.let { a -> energySensors = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("musicPlayers")?.let { a -> musicPlayers = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("hiddenSections")?.let { a -> hiddenSections = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("homeFavorites")?.let { a -> homeFavorites = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("homeCameras")?.let { a -> homeCameras = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("previewsOff")?.let { a -> previewsOff = (0 until a.length()).map { a.getString(it) } }
            h.optJSONArray("sportSensors")?.let { a -> sportSensors = (0 until a.length()).map { a.getString(it) } }
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
            if (s.str("musicToken").isNotEmpty()) musicToken = s.str("musicToken")
            if (s.str("tmdbKey").isNotEmpty()) tmdbKey = s.str("tmdbKey")
            if (s.str("youtubeKey").isNotEmpty()) youtubeKey = s.str("youtubeKey")
        }
    }

    fun newRoom(name: String, areaId: String? = null) = Room(UUID.randomUUID().toString(), name, areaId)

    fun allEntityIds(): Set<String> {
        val s = HashSet<String>()
        for (r in rooms) for (e in r.entities) s.add(e.id)
        // Accueil : météo et personnes
        // Le soleil dit s'il fait nuit (icônes de la météo).
        if (weatherEntity.isNotEmpty()) { s.add(weatherEntity); s.add("sun.sun") }
        s.addAll(persons)
        // Accueil : alertes météo, raccourcis, pastilles personnalisées, Ce matin
        s.addAll(weatherAlerts)
        s.addAll(shortcuts)
        s.addAll(todoLists)
        s.addAll(energySensors)
        s.addAll(musicPlayers)
        s.addAll(sportSensors)
        for (t in Trackers.parse(trackers)) s.addAll(t.ids)
        s.addAll(wakeSensors)
        s.addAll(ambientLights)
        for (a in listOf(customChips, morningSources)) for (i in 0 until a.length()) a.optJSONObject(i)?.optString("entity")?.takeIf { it.isNotEmpty() }?.let { s.add(it) }
        return s
    }
}
