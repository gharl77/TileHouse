package com.homepanel.kiosk

import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/**
 * Accueil « par priorité » (réglage Apparence → Accueil) : au lieu d'un mur de pastilles toutes au même niveau,
 *  - une zone « À voir maintenant » : au plus trois cartes, pour ce qui demande une action ou une vérification ;
 *  - une ligne discrète pour le reste (lumières allumées, stores, puissance…) ;
 *  - les mêmes cartes qu'avant, dans un ordre qui suit la journée (matin, journée, soir), avec le soir une carte
 *    « Pour la nuit ».
 * L'accueil classique reste le choix par défaut.
 */
object HomeFlow {
    const val MORNING = "morning"
    const val DAY = "day"
    const val EVENING = "evening"
    const val MAX_ATTENTION = 3
    /** Durée de l'aperçu d'un moment demandé depuis la page web. */
    const val PREVIEW_MS = 120_000L

    /** Les cartes de l'accueil, dans l'ordre où la page web les propose. */
    val CARDS = listOf("birthday", "notes", "weather", "agenda", "night", "favorites", "shortcuts", "previews", "cameras", "energy", "music", "news")
    /** Cartes ajoutées par l'utilisateur, autant qu'il en veut : une pièce, un appareil, une caméra (« room:salon », « entity:light.x »). */
    val ADDED = listOf("room:", "entity:", "camera:")
    fun isCard(id: String) = id in CARDS || ADDED.any { id.startsWith(it) && id.length > it.length }
    /** Version du réglage : avant la 2, « weather » voulait dire la météo et l'agenda ensemble. */
    const val PLAN_VERSION = 2
    val MOMENTS = listOf(MORNING, DAY, EVENING)

    /** Réglage de l'accueil par priorité : l'heure où commence chaque moment, et les cartes de chacun, dans l'ordre (une carte absente est masquée). */
    class Plan(val from: Map<String, Int>, val cards: Map<String, List<String>>)

    private val DEFAULT_FROM = mapOf(MORNING to 5, DAY to 10, EVENING to 19)
    private val DEFAULT_CARDS = mapOf(
        MORNING to listOf("birthday", "weather", "agenda", "notes", "previews", "favorites", "shortcuts", "cameras"),
        DAY to listOf("birthday", "notes", "weather", "agenda", "favorites", "shortcuts", "previews", "cameras"),
        EVENING to listOf("birthday", "night", "cameras", "notes", "favorites", "shortcuts", "weather", "agenda", "previews"))
    val DEFAULT = Plan(DEFAULT_FROM, DEFAULT_CARDS)

    /**
     * Lit le réglage enregistré. Tolérant : un texte vide ou abîmé donne le réglage d'origine ; une carte inconnue ou en
     * double est ignorée ; des heures qui ne se suivent pas (matin < journée < soir) reviennent à celles d'origine.
     */
    fun parsePlan(json: String): Plan {
        val o = try { org.json.JSONObject(json.ifBlank { "{}" }) } catch (_: Exception) { return DEFAULT }
        val from = HashMap<String, Int>(); val cards = HashMap<String, List<String>>()
        val old = o.optInt("v", 1) < PLAN_VERSION
        for (m in MOMENTS) {
            val part = o.optJSONObject(m)
            from[m] = part?.optInt("from", DEFAULT_FROM.getValue(m))?.coerceIn(0, 23) ?: DEFAULT_FROM.getValue(m)
            val a = part?.optJSONArray("cards")
            cards[m] = if (a == null) DEFAULT_CARDS.getValue(m) else (0 until a.length()).map { a.optString(it) }.filter { isCard(it) }.distinct()
                // Ancien réglage : l'agenda suivait la météo sans être nommé.
                .let { list -> if (old && "weather" in list && "agenda" !in list) list.flatMap { if (it == "weather") listOf(it, "agenda") else listOf(it) } else list }
        }
        val ordered = from.getValue(MORNING) < from.getValue(DAY) && from.getValue(DAY) < from.getValue(EVENING)
        return Plan(if (ordered) from else DEFAULT_FROM, cards)
    }

    fun planJson(p: Plan): org.json.JSONObject = org.json.JSONObject().also { o ->
        o.put("v", PLAN_VERSION)
        for (m in MOMENTS) o.put(m, org.json.JSONObject().put("from", p.from.getValue(m)).put("cards", org.json.JSONArray(p.cards.getValue(m))))
    }

    /** Moment de la journée à [hour] heures : le matin, la journée, puis le soir, qui dure jusqu'au matin suivant. */
    fun moment(hour: Int, plan: Plan = DEFAULT): String = when {
        hour >= plan.from.getValue(EVENING) || hour < plan.from.getValue(MORNING) -> EVENING
        hour >= plan.from.getValue(DAY) -> DAY
        else -> MORNING
    }

    /** Cartes du moment, dans l'ordre ; [fixed] garde toujours celles de la journée. */
    fun order(moment: String, fixed: Boolean, plan: Plan = DEFAULT): List<String> = plan.cards[if (fixed) DAY else moment] ?: DEFAULT_CARDS.getValue(DAY)

    private fun plainText(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    private val OUTSIDE = listOf("entree", "entry", "entrance", "hall", "foyer", "vestibule", "porch", "garage", "exterieur", "outside", "outdoor", "patio",
        "jardin", "garden", "cour", "yard", "balcon", "balcony", "terrasse", "terrace", "cabanon", "shed", "front", "back", "avant", "arriere")
    private val APPLIANCE = listOf("refrigerateur", "frigo", "fridge", "congelateur", "freezer", "four", "oven", "laveuse", "secheuse", "washer", "dryer",
        "lave-vaisselle", "dishwasher", "armoire", "cabinet", "placard", "closet", "garde-robe", "tiroir", "drawer", "micro")

    /**
     * Sans choix de l'utilisateur, une ouverture alerte-t-elle ? Une fenêtre, oui. Une porte, seulement si sa pièce ou son
     * nom dit qu'elle donne sur l'extérieur (entrée, garage, patio…) ; jamais celle d'un électroménager ou d'un placard.
     * Une porte de salle de bain ou de chambre ouverte n'est pas une alerte.
     */
    fun alertByDefault(kind: String, room: String, name: String): Boolean {
        val n = plainText(name); val r = plainText(room)
        if (APPLIANCE.any { it in n }) return false
        if (kind == "window") return true
        return OUTSIDE.any { w -> Regex("\\b" + w + "\\b").containsMatchIn("$r $n") }
    }

    /** L'ouverture [id] alerte-t-elle ? Le choix de l'utilisateur ([on], [off]) l'emporte sur la règle d'origine. */
    fun alerts(id: String, kind: String, room: String, name: String, on: Collection<String>, off: Collection<String>) =
        if (id in on) true else if (id in off) false else alertByDefault(kind, room, name)

    /**
     * Une porte ou une fenêtre ouverte mérite-t-elle une carte ? Oui si elle l'est depuis au moins [delayMinutes] ;
     * un moment de changement inconnu ([changed] = 0, en mode démo) compte comme ancien.
     */
    fun overdue(changed: Long, now: Long, delayMinutes: Int) = changed <= 0L || now - changed >= delayMinutes * 60_000L

    /** « depuis 40 min », « depuis 2 h » ; "" pour moins d'une minute ou un moment inconnu. */
    fun since(changed: Long, now: Long): String {
        val min = if (changed <= 0L) 0 else (now - changed) / 60_000L
        return when {
            min < 1 -> ""
            min < 60 -> tr("depuis") + " $min min"
            min < 48 * 60 -> tr("depuis") + " " + (min / 60) + " h"
            else -> tr("depuis") + " " + (min / 1440) + " " + tr("jours")
        }
    }

    // --- Ce qui mérite « À voir maintenant » : un niveau et un délai par type de pastille.
    const val RED = "red"; const val AMBER = "amber"; const val INFO = "info"; const val HIDDEN = "hidden"
    val LEVELS = listOf(RED, AMBER, INFO, HIDDEN)
    /** Types réglables, dans l'ordre de la page web. */
    val KINDS = listOf("smoke", "lock", "door", "window", "light", "cover", "battery", "offline", "wear", "peak", "wx", "collect", "energy")
    /** Types dont la pastille n'est pas une alerte en soi : présents, ils comptent quand même si on les monte en carte. */
    private val QUIET = setOf("cover", "collect", "energy")

    /** [level] : où le type s'affiche ; [delay] : minutes à attendre avant qu'il devienne une carte. */
    data class Rule(val level: String, val delay: Int)

    /** Règle d'origine : le comportement d'avant ce réglage. [openDelay] : le délai des portes et fenêtres. */
    fun defaultRule(kind: String, openDelay: Int = 5): Rule = when (kind) {
        "smoke" -> Rule(RED, 0)
        "door", "window" -> Rule(AMBER, openDelay)
        "light", "cover", "collect", "energy" -> Rule(INFO, 0)
        else -> Rule(AMBER, 0)
    }

    /** Règles en vigueur pour tous les [KINDS] : celles de l'utilisateur ([json]), sinon celles d'origine. Tolérant aux valeurs abîmées. */
    fun parseRules(json: String, openDelay: Int = 5): Map<String, Rule> {
        val o = try { org.json.JSONObject(json.ifBlank { "{}" }) } catch (_: Exception) { org.json.JSONObject() }
        return KINDS.associateWith { k ->
            val d = defaultRule(k, openDelay); val r = o.optJSONObject(k)
            if (r == null) d else Rule(r.optString("level").takeIf { it in LEVELS } ?: d.level, r.optInt("delay", d.delay).coerceIn(0, 1440))
        }
    }

    fun rulesJson(rules: Map<String, Rule>): org.json.JSONObject = org.json.JSONObject().also { o ->
        for (k in KINDS) rules[k]?.let { o.put(k, org.json.JSONObject().put("level", it.level).put("delay", it.delay)) }
    }

    /** Une pastille montée en carte : [red] pour une alerte rouge. */
    class Card(val chip: Present.Chip, val red: Boolean)
    class Placed(val cards: List<Card>, val info: List<Present.Chip>)

    /**
     * Range chaque pastille : carte « À voir maintenant », ligne d'information, ou rien. [ready] dit si le type a attendu
     * son délai (minutes). Un type sans règle (pastille personnalisée, appareil suivi) garde son niveau propre.
     * Une pastille grave en soi (serrure bloquée, pointe en cours) reste rouge même réglée sur « ambre ».
     */
    fun place(chips: List<Present.Chip>, rules: Map<String, Rule>, ready: (Present.Chip, Int) -> Boolean): Placed {
        val cards = ArrayList<Card>(); val info = ArrayList<Present.Chip>()
        for (c in chips) {
            val r = rules[c.kind] ?: Rule(if (c.level >= 1) AMBER else INFO, 0)
            val active = c.level >= 1 || c.kind in QUIET
            when {
                r.level == HIDDEN -> Unit
                // « Aucune fumée » n'apprend rien : la fumée ne compte que détectée, et c'est alors une alerte.
                c.kind == "smoke" && c.level < 1 -> Unit
                r.level == INFO || !active || !ready(c, r.delay) -> info.add(c)
                else -> cards.add(Card(c, r.level == RED || c.level >= 2))
            }
        }
        fun rank(k: String) = RANK.indexOf(k).let { i -> if (i < 0) RANK.size else i }
        return Placed(cards.sortedWith(compareByDescending<Card> { it.red }.thenBy { rank(it.chip.kind) }), info)
    }

    // --- Options des cartes : {"agenda":{"days":2},"room:salon":{"hide":["cover"]}}
    class Options(json: String) {
        private val o = try { org.json.JSONObject(json.ifBlank { "{}" }) } catch (_: Exception) { org.json.JSONObject() }
        fun int(card: String, key: String, def: Int) = o.optJSONObject(card)?.optInt(key, def) ?: def
        fun bool(card: String, key: String, def: Boolean) = o.optJSONObject(card)?.optBoolean(key, def) ?: def
        fun str(card: String, key: String): String = o.optJSONObject(card)?.optString(key).orEmpty()
        fun list(card: String, key: String): List<String> = o.optJSONObject(card)?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    }

    /** Un titre de la carte « Actualités » : [source] est le nom du flux d'où il vient. */
    class Headline(val source: String, val title: String, val summary: String, val date: Long, val image: String? = null)

    /**
     * Les [count] titres les plus récents, tous flux confondus. Sans date, un titre garde sa place dans son flux
     * et passe après les titres datés. Un même titre repris par deux flux n'apparaît qu'une fois.
     */
    fun headlines(feeds: List<Pair<String, List<Headline>>>, count: Int): List<Headline> =
        feeds.flatMap { it.second }.distinctBy { it.title.trim().lowercase() }.sortedByDescending { it.date }.take(count.coerceIn(1, 20))

    /** Largeur d'une carte : « half » la moitié, « third » le tiers, sinon toute la largeur. */
    val SIZES = listOf("full", "half", "third")
    fun width(size: String): Float = when (size) { "half" -> 0.5f; "third" -> 1f / 3f; else -> 1f }

    /**
     * Range les cartes en rangées d'après leur largeur ([widths], de 0 à 1) : une carte pleine largeur est seule ;
     * les autres se suivent sur une rangée tant qu'il reste de la place. Retourne les rangs des cartes de chaque rangée.
     */
    fun rows(widths: List<Float>): List<List<Int>> {
        val out = ArrayList<MutableList<Int>>(); var used = 2f
        widths.forEachIndexed { i, w ->
            if (w >= 1f || used + w > 1.001f) { out.add(arrayListOf(i)); used = w } else { out.last().add(i); used += w }
        }
        return out
    }

    /** Groupes d'appareils d'une carte de pièce, que l'on peut masquer un à un. */
    val ROOM_GROUPS = listOf("light", "switch", "climate", "cover", "lock", "other")
    fun roomGroup(domain: String) = when (domain) {
        "light" -> "light"; "switch", "input_boolean" -> "switch"; "climate", "fan", "humidifier" -> "climate"; "cover" -> "cover"; "lock" -> "lock"; else -> "other"
    }

    /** Les événements des [days] prochains jours (1 = aujourd'hui seulement ; 0 = tous), d'après leur début. */
    fun <T> within(events: List<T>, days: Int, now: Long, start: (T) -> Long): List<T> {
        if (days <= 0) return events
        val end = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0); add(java.util.Calendar.DAY_OF_YEAR, days) }.timeInMillis
        return events.filter { start(it) < end }
    }

    /** Ordre d'importance des pastilles de même niveau : la sécurité d'abord. */
    private val RANK = listOf("smoke", "leak", "lock", "door", "window", "peak", "wx", "battery", "offline", "wear", "custom")

    /**
     * Ce qui demande une action ou une vérification : toute pastille d'alerte (orange ou rouge), sauf les lumières
     * allumées et les stores ouverts, qui sont de l'information. Les plus graves d'abord.
     */
    fun attention(chips: List<Present.Chip>): List<Present.Chip> =
        chips.filter { it.level >= 1 && it.kind != "light" && it.kind != "cover" }
            .sortedWith(compareByDescending<Present.Chip> { it.level }.thenBy { RANK.indexOf(it.kind).let { i -> if (i < 0) RANK.size else i } })

    /** Le reste : l'information, sur la ligne discrète. */
    fun info(chips: List<Present.Chip>): List<Present.Chip> = attention(chips).let { a -> chips.filter { it !in a } }

    /** « Salon, Cuisine, Bureau et 3 autres pièces ». */
    fun rooms(names: List<String>): String = when {
        names.size <= 3 -> names.joinToString(", ")
        else -> names.take(3).joinToString(", ") + " " + tr("et") + " " + Present.plural(names.size - 3, tr("1 autre pièce"), tr("autres pièces"))
    }
}

/** L'accueil par priorité est-il celui qui s'affiche ? */
internal val MainActivity.priorityHome get() = cfg.homeStyle == "priority"

internal fun MainActivity.showPriorityHome() {
    clearContent()
    backBtn.visibility = View.GONE
    subtitleView.visibility = View.VISIBLE
    // Zone « À voir maintenant » et ligne d'information : remplies par updateHomeHeader, à chaque changement d'état.
    attentionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    infoLine = FlowLayout(this).apply { maxRows = 2 }.also { content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(8) }) }
    updateHomeHeader()

    val plan = HomeFlow.parsePlan(cfg.homePlan)
    // Aperçu demandé depuis la page web : un moment choisi, pendant deux minutes, quelle que soit l'heure.
    val previewing = previewMoment.isNotEmpty() && SystemClock.uptimeMillis() < previewUntil
    shownMoment = if (previewing) previewMoment else HomeFlow.moment(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), plan)
    if (previewing) content.addView(label(14f, Skin.onPrimary, true, tr("Aperçu") + " · " + tr(when (shownMoment) { HomeFlow.MORNING -> "Matin"; HomeFlow.EVENING -> "Soir"; else -> "Journée" })).apply {
        background = roundRect(Skin.accent, dpf(16)); setPadding(dp(14), dp(6), dp(14), dp(6))
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
    val opts = HomeFlow.Options(cfg.homeCardOptions)
    val order = HomeFlow.order(shownMoment, cfg.homeFixedOrder && !previewing, plan)
    // Chaque carte devient une « unité » : sa clé (pour sa taille) et ce qui la dessine.
    val units = ArrayList<Pair<String, () -> Unit>>()
    var i = 0
    while (i < order.size) {
        val part = order[i]; val next = order.getOrNull(i + 1)
        fun full(k: String) = HomeFlow.width(opts.str(k, "size")) >= 1f
        // Les cartes ajoutées qui se suivent partagent une grille : trois appareils de suite font une rangée de tuiles.
        fun run(prefix: String): List<String> = order.drop(i).takeWhile { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        when {
            // Météo et agenda voisins dans la liste, tous deux en pleine largeur : côte à côte, comme avant.
            ((part == "weather" && next == "agenda") || (part == "agenda" && next == "weather")) && full("weather") && full("agenda") -> { units.add("" to { addWeatherAgenda(true, true, opts) }); i++ }
            part == "weather" -> units.add(part to { addWeatherAgenda(true, false, opts) })
            part == "agenda" -> units.add(part to { addWeatherAgenda(false, true, opts) })
            part == "birthday" -> units.add(part to { addBirthdayBanner() })
            part == "notes" -> units.add(part to { addFamilyNotes() })
            part == "night" -> units.add(part to { addNightCard(opts) })
            // Favoris, aperçus et caméras de l'accueil appartiennent à la navigation latérale, comme dans l'accueil classique.
            part == "favorites" -> if (cfg.sideNav) units.add(part to { addHomeFavorites() })
            part == "shortcuts" -> units.add(part to { addShortcuts(Shortcuts.forHome(cfg), dp(14)) })
            part == "previews" -> if (cfg.sideNav) units.add(part to { addHomePreviews() })
            part == "cameras" -> if (cfg.sideNav) units.add(part to { addHomeCameras() })
            part == "energy" -> units.add(part to { addEnergyCard(opts) })
            part == "music" -> units.add(part to { addMusicCard(opts) })
            part == "news" -> units.add(part to { addNewsCard(opts) })
            part.startsWith("room:") -> units.add(part to { addRoomCard(part.removePrefix("room:"), opts.list(part, "hide").toSet()) })
            part.startsWith("entity:") -> run("entity:").let { ids -> units.add(part to { addEntityCards(ids) }); i += ids.size - 1 }
            part.startsWith("camera:") -> run("camera:").let { ids -> units.add(part to { addCameraCards(ids) }); i += ids.size - 1 }
        }
        i++
    }
    // Taille des cartes : les cartes en demi-largeur ou en tiers qui se suivent partagent une rangée.
    val widths = units.map { HomeFlow.width(opts.str(it.first, "size")) }
    val main = content
    for (row in HomeFlow.rows(widths)) {
        if (row.size == 1 && widths[row[0]] >= 1f) { units[row[0]].second(); continue }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 1f }
        main.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        row.forEachIndexed { k, idx ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            line.addView(col, LinearLayout.LayoutParams(0, MATCH_PARENT, widths[idx]).apply { if (k > 0) marginStart = dp(12) })
            // La carte se dessine dans sa colonne plutôt que dans la page.
            content = col
            try { units[idx].second() } finally { content = main }
            // Même marge en haut pour toutes les colonnes : les cartes d'une rangée partent de la même ligne.
            (col.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
            // Une carte seule dans sa colonne prend la hauteur de la rangée : les voisines s'alignent.
            if (col.childCount == 1) col.getChildAt(0).let { v -> (v.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }; v.requestLayout() }
        }
    }
    updateRail()
    if (!cfg.sideNav) addFloorSections()
    if (System.currentTimeMillis() - homeDataAt > 10 * 60_000L) loadHomeData()
}

/**
 * Remplit la zone « À voir maintenant » et la ligne d'information à partir des pastilles de la maison.
 * [make] fabrique la pastille d'origine : son toucher (ouvrir la liste, le détail…) est repris tel quel.
 */
internal fun MainActivity.updatePriorityHeader(chips: List<Present.Chip>, make: (Present.Chip, () -> Unit) -> View) {
    val box = attentionBox ?: return
    // Une porte ou une fenêtre ouverte depuis peu (le réfrigérateur qu'on vient d'ouvrir) reste de l'information :
    // elle ne devient une carte qu'après le délai choisi, et la carte ne compte que celles qui l'ont dépassé.
    val nowMs = System.currentTimeMillis()
    val night = HomeCare.night(homeRoom(), app.ha.states)
    val roomName = HashMap<String, String>()
    for (r in cfg.rooms) for (e in r.entities) roomName.putIfAbsent(e.id, r.name)
    val alertOn = cfg.alertOn.toSet(); val alertOff = cfg.alertOff.toSet()
    // Seules les ouvertures qui alertent (portes donnant sur l'extérieur, fenêtres, ou celles choisies) font une carte.
    val rules = HomeFlow.parseRules(cfg.attentionRules, cfg.attentionDelay)
    fun late(kind: String) = night.openings.filter {
        val e = app.ha.states[it.id]
        Present.chipKind(it.id, e) == kind && HomeFlow.alerts(it.id, kind, roomName[it.id].orEmpty(), it.name ?: e?.name.orEmpty(), alertOn, alertOff) &&
            HomeFlow.overdue(e?.changed ?: 0L, nowMs, rules.getValue(kind).delay)
    }
    val lateDoors = late("door"); val lateWindows = late("window")
    // Types dont l'état normal est « allumé / ouvert » (réglage commun à toutes les pastilles) : leur pastille signale
    // ce qui est éteint ou fermé ; on garde ces appareils pour le détail des cartes et leur délai.
    val inverted = cfg.normalOn.filter { it in Present.NORMAL_KINDS }
    val resting = inverted.associateWith { kind ->
        homeRoom().entities.filter { Present.chipKind(it.id, app.ha.states[it.id]) == kind && Present.atRest(kind, app.ha.states[it.id]) }
    }
    // Depuis quand chaque type d'alerte est présent : sert au délai des types qui n'ont pas d'appareil daté (piles, hors ligne…).
    val present = chips.filter { it.level >= 1 }.map { it.kind }.toSet()
    prioritySeen.keys.retainAll(present); for (k in present) if (k !in prioritySeen) prioritySeen[k] = nowMs
    fun waited(list: List<RoomEntity>, delay: Int) = list.any { HomeFlow.overdue(app.ha.states[it.id]?.changed ?: 0L, nowMs, delay) }
    val placed = HomeFlow.place(chips, rules) { c, delay ->
        when {
            c.kind in inverted -> delay == 0 || waited(resting.getValue(c.kind), delay)
            c.kind == "door" -> lateDoors.isNotEmpty()
            c.kind == "window" -> lateWindows.isNotEmpty()
            delay == 0 -> true
            c.kind == "lock" && night.locks.isNotEmpty() -> waited(night.locks, delay)
            c.kind == "light" && night.lights.isNotEmpty() -> waited(night.lights, delay)
            else -> nowMs - (prioritySeen[c.kind] ?: nowMs) >= delay * 60_000L
        }
    }
    val attention = placed.cards; val info = placed.info
    fun since(list: List<RoomEntity>) = list.mapNotNull { app.ha.states[it.id]?.changed }.filter { it > 0 }.minOrNull()?.let { HomeFlow.since(it, nowMs) }.orEmpty()
    // Rien n'a changé à l'écran : on ne reconstruit pas (cette fonction est rappelée chaque minute pour les durées).
    val key = attention.joinToString("|") { it.chip.text + it.red } + "#" + info.joinToString("|") { it.text } + "#" + lateDoors.size + since(lateDoors) + "/" + lateWindows.size + since(lateWindows) + "/" + since(night.locks) + "/" + inverted.joinToString { it + resting.getValue(it).size }
    if (key == priorityKey && box.childCount > 0) { for (r in priorityRefresh) r(); return }
    priorityKey = key
    box.removeAllViews()
    if (attention.isEmpty()) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundRect(0x2434C759, dpf(18)); setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(label(15f, HomeCare.GREEN, true, "✓").apply { setPadding(0, 0, dp(10), 0) })
            addView(label(15f, HomeCare.GREEN, text = tr("Tout est en ordre")))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    } else {
        box.addView(label(12.5f, Skin.mute, true, tr("À voir maintenant").uppercase(I18n.locale)).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(4), 0, dp(8)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shown = attention.take(HomeFlow.MAX_ATTENTION)
        val roomOf = HashMap<String, Room>()
        for (r in cfg.rooms) for (e in r.entities) roomOf.putIfAbsent(e.id, r)
        fun names(list: List<RoomEntity>) = list.take(2).joinToString(", ") { re ->
            listOf(Present.displayName(re, app.ha.states[re.id], roomOf[re.id]?.name ?: ""), roomOf[re.id]?.name.orEmpty()).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
        } + if (list.size > 2) " +" + (list.size - 2) else ""
        shown.forEachIndexed { i, card ->
            val c = card.chip; val red = card.red
            val tint = if (red) HomeCare.RED else 0xFFFFB300.toInt()
            // Sous le titre : de quoi il s'agit, et ce que la tablette peut ou non y faire.
            val flipped = c.kind in inverted
            val detail = when { flipped -> names(resting.getValue(c.kind)); c.kind == "door" -> names(lateDoors); c.kind == "window" -> names(lateWindows); c.kind == "lock" -> names(night.locks); else -> "" }
            // Le titre ne compte que les ouvertures qui ont dépassé le délai, et dit depuis quand la plus ancienne est ouverte.
            val heading = when (if (flipped) "" else c.kind) {
                "door" -> Present.plural(lateDoors.size, tr("Porte ouverte"), tr("portes ouvertes"))
                "window" -> Present.plural(lateWindows.size, tr("Fenêtre ouverte"), tr("fenêtres ouvertes"))
                else -> c.text
            }
            val elapsed = when (if (flipped) "" else c.kind) { "door" -> since(lateDoors); "window" -> since(lateWindows); "lock" -> since(night.locks); else -> "" }
            // Une carte sans précision dit au moins qu'on peut la toucher pour en savoir plus.
            val hint = when (if (flipped) "" else c.kind) { "door", "window" -> listOf(tr("À fermer à la main"), elapsed).filter { it.isNotEmpty() }.joinToString(" · "); "lock" -> elapsed; else -> if (detail.isEmpty()) tr("Toucher pour voir le détail") else "" }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; minimumHeight = dp(84)
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dpf(18); setColor((tint and 0x00FFFFFF) or 0x24000000); setStroke(dp(1.5f), tint) }
                setPadding(dp(16), dp(12), dp(16), dp(12)); isClickable = true
                addView(label(17f, Skin.ink, true, heading).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (detail.isNotEmpty()) addView(label(14f, Skin.mix(tint, Skin.ink, 0.6f), text = detail).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
                if (hint.isNotEmpty()) addView(label(13f, Skin.mute, text = hint).apply { setPadding(0, dp(2), 0, 0) })
                // Même effet qu'un toucher sur la pastille : la liste, le détail ou la fenêtre de l'alerte.
                setOnClickListener { make(c) {}.performClick() }
            }
            row.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(12) })
        }
        // Moins de trois cartes : elles gardent une largeur de carte, pas toute la rangée.
        repeat(HomeFlow.MAX_ATTENTION - shown.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(12) }) }
        box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (attention.size > shown.size) box.addView(label(14f, Skin.fg(0xCC), true, "+ " + Present.plural(attention.size - shown.size, tr("1 autre"), tr("autres"))).apply {
            setPadding(dp(4), dp(8), dp(4), 0); isClickable = true
            setOnClickListener { showAllChips(attention.map { it.chip }, make) }
        })
    }
    infoLine?.let { line ->
        // L'information courante, en pastilles calmes : une pastille par sujet, chacune avec sa couleur et son toucher.
        line.removeAllViews()
        for (c in info) line.addView(infoPill(c) { make(c) {}.performClick() })
        val more = infoPill(Present.Chip(0, "+9", 0, "more")) { showAllChips(info, make) }
        line.moreView = more; line.onOverflow = { n -> ((more as ViewGroup).getChildAt(more.childCount - 1) as TextView).text = "+$n" }
        line.addView(more); line.resetOverflow()
        line.visibility = if (info.isEmpty()) View.GONE else View.VISIBLE
    }
    for (r in priorityRefresh) r()
}

/** Carte « Pour la nuit » (le soir) : ce qui reste allumé, avec le raccourci du coucher et « Tout éteindre ». */
internal fun MainActivity.addNightCard(opts: HomeFlow.Options = HomeFlow.Options(cfg.homeCardOptions)) {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Skin.card(this@addNightCard, dpf(22)); setPadding(dp(22), dp(16), dp(22), dp(18))
    }
    card.addView(label(12.5f, Skin.mute, true, tr("Pour la nuit").uppercase(I18n.locale)).apply { letterSpacing = 0.08f })
    val title = label(20f, Skin.ink, true).apply { setPadding(0, dp(8), 0, dp(2)) }
    val detail = label(15f, Skin.mute)
    card.addView(title); card.addView(detail)
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    // Le raccourci du coucher : le premier raccourci suivi de la vérification (« Bonne nuit »), même s'il est rangé dans une pièce.
    val nightId = cfg.shortcuts.firstOrNull { cfg.checksAfter(it, app.ha.states[it]?.name.orEmpty()) }
    if (nightId != null && cfg.shows("shortcuts") && opts.bool("night", "scene", true)) buttons.addView(label(17f, Skin.onPrimary, true, app.ha.states[nightId]?.name ?: tr("Bonne nuit")).apply {
        gravity = Gravity.CENTER; setPadding(dp(26), dp(13), dp(26), dp(13)); background = roundRect(Skin.accent, dpf(24)); isClickable = true
        setOnClickListener { runShortcut(nightId, this) }
    })
    val allOff = label(17f, Skin.ink, true, tr("Tout éteindre")).apply {
        gravity = Gravity.CENTER; setPadding(dp(26), dp(13), dp(26), dp(13)); background = roundRect(Skin.fg(0x1F), dpf(24)); isClickable = true
    }
    buttons.addView(allOff, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (buttons.childCount > 0) marginStart = dp(12) })
    card.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    content.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

    val roomOf = HashMap<String, Room>()
    for (r in cfg.rooms) for (e in r.entities) roomOf.putIfAbsent(e.id, r)
    val refresh = {
        val lights = HomeCare.night(homeRoom(), app.ha.states).lights
        val night = HomeCare.night(homeRoom(), app.ha.states)
        title.text = if (lights.isEmpty()) tr("Tout est éteint") else Present.plural(lights.size, tr("1 lumière encore allumée"), tr("lumières encore allumées"))
        // Option : rappeler aussi les serrures restées déverrouillées, sous les pièces encore allumées.
        val locks = if (opts.bool("night", "locks", false) && night.locks.isNotEmpty()) Present.plural(night.locks.size, tr("1 serrure déverrouillée"), tr("serrures déverrouillées")) else ""
        detail.text = listOf(HomeFlow.rooms(lights.mapNotNull { roomOf[it.id]?.name }.distinct()), locks).filter { it.isNotEmpty() }.joinToString("\n")
        detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE
        allOff.visibility = if (lights.isEmpty() || !opts.bool("night", "allOff", true)) View.GONE else View.VISIBLE
        allOff.setOnClickListener {
            if (!haOffline()) { for (re in lights) Present.bulkOff(app.ha, re.id, app.ha.states[re.id]); allOff.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        buttons.visibility = if (buttons.childCount == 1 && allOff.visibility == View.GONE) View.GONE else View.VISIBLE
    }
    refresh()
    priorityRefresh.add(refresh)
}

/** Le moment de la journée a changé (10 h, 19 h, 5 h) : l'accueil se réordonne, mais pas sous le doigt ni devant une fenêtre ouverte. */
internal fun MainActivity.followMoment() {
    if (!priorityHome || roomIndex != MainActivity.HOME) return
    // Fin de l'aperçu d'un moment : l'accueil revient à l'heure réelle.
    if (previewMoment.isNotEmpty() && SystemClock.uptimeMillis() >= previewUntil) { previewMoment = ""; if (dialog?.isShowing != true) showHome() }
    // Chaque minute : les durées (« depuis 40 min ») avancent, et une ouverture peut dépasser le délai.
    if (SystemClock.uptimeMillis() - priorityTickAt >= 60_000) { priorityTickAt = SystemClock.uptimeMillis(); updateHomeHeader() }
    if (cfg.homeFixedOrder || previewMoment.isNotEmpty()) return
    val now = HomeFlow.moment(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), HomeFlow.parsePlan(cfg.homePlan))
    if (now != shownMoment && dialog?.isShowing != true && SystemClock.uptimeMillis() - lastTouch > 60_000) showHome()
}

/** Cadre commun des cartes de l'accueil par priorité : un petit titre en capitales, puis le contenu. */
private fun MainActivity.homeCard(title: String, onTap: (() -> Unit)? = null): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Skin.card(this@homeCard, dpf(22)); setPadding(dp(22), dp(16), dp(22), dp(18))
        if (onTap != null) { isClickable = true; setOnClickListener { onTap() } }
    }
    card.addView(label(12.5f, Skin.mute, true, title.uppercase(I18n.locale)).apply { letterSpacing = 0.08f })
    content.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
    return card
}

/** Carte « Énergie » : la puissance du moment et la consommation du jour des capteurs choisis ; un toucher ouvre le détail. */
internal fun MainActivity.addEnergyCard(opts: HomeFlow.Options) {
    if (cfg.energySensors.isEmpty() || !cfg.shows("energy")) return
    val card = homeCard(tr("Énergie")) { showEnergy() }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    val live = opts.bool("energy", "live", true); val today = opts.bool("energy", "today", true)
    val refresh = {
        row.removeAllViews()
        val first = cfg.energySensors.first()
        for (id in cfg.energySensors.take(4)) {
            val e = app.ha.states[id] ?: continue
            val meter = Energy.cumulative(e)
            if (if (meter) !today else !live) continue
            // Un compteur cumulatif : sa consommation récente quand elle est connue (premier capteur), sinon son total.
            val recent = energyToday?.takeIf { meter && id == first && it.value > 0 }
            val value = recent?.let { Energy.format(it.value, e.attrs.str("unit_of_measurement")) } ?: Energy.value(e)
            val note = when { recent == null -> ""; recent.sinceMidnight -> tr("aujourd'hui"); recent.yesterday -> tr("hier"); else -> tr("dernier relevé") }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(26f, Skin.ink, true, value).apply { maxLines = 1 })
                addView(label(13.5f, Skin.mute, text = listOf(e.name, note).filter { it.isNotEmpty() }.joinToString(" · ")).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (row.childCount > 0) marginStart = dp(16) })
        }
        card.visibility = if (row.childCount == 0) View.GONE else View.VISIBLE
    }
    refresh(); priorityRefresh.add(refresh)
}

/** Carte « Musique en cours » : ce que jouent les lecteurs des pièces ; un toucher montre les affiches. */
internal fun MainActivity.addMusicCard(opts: HomeFlow.Options) {
    val only = opts.bool("music", "onlyPlaying", true)
    fun playing() = playersInRooms().map { it.first }.filter { it.state == "playing" }.sortedByDescending { playStart[it.id] ?: 0L }
    val card = homeCard(tr("En lecture")) { playing().takeIf { it.isNotEmpty() }?.let { showPlayingPosters(it) } }
    val title = label(20f, Skin.ink, true).apply { setPadding(0, dp(8), 0, dp(2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val detail = label(15f, Skin.mute).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    card.addView(title); card.addView(detail)
    val refresh = {
        val list = playing(); val e = list.firstOrNull()
        card.visibility = if (e == null && only) View.GONE else View.VISIBLE
        title.text = e?.attrs?.str("media_title")?.ifEmpty { e.name } ?: tr("Rien ne joue")
        detail.text = if (e == null) "" else listOf(e.attrs.str("media_artist"), e.name.takeIf { it != title.text }.orEmpty(),
            if (list.size > 1) "+ " + Present.plural(list.size - 1, tr("1 autre"), tr("autres")) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE
    }
    refresh(); priorityRefresh.add(refresh)
}

/** Carte d'une pièce : son nom (un toucher ouvre la pièce) et ses appareils en tuiles, sans les groupes masqués ([hide]). */
internal fun MainActivity.addRoomCard(roomId: String, hide: Set<String>) {
    val index = cfg.rooms.indexOfFirst { it.id == roomId }.takeIf { it >= 0 } ?: return
    val room = cfg.rooms[index]
    val list = room.entities.filter {
        Present.domain(it.id) != "camera" && !Present.isHeaderSensor(it.id, app.ha.states[it.id]) && !hasPlayerCard(it.id) &&
            HomeFlow.roomGroup(Present.domain(it.id)) !in hide && it.id !in tiles
    }
    if (list.isEmpty()) return
    content.addView(sectionLabel(room.name + "  ›").apply { setPadding(dp(2), dp(16), 0, dp(10)); isClickable = true; setOnClickListener { openRoom(index) } })
    val grid = TileGrid(this)
    for (re in list) grid.addView(deviceTile(re, HOME_SHELF))
    content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/** Appareils ajoutés un à un : ceux qui se suivent dans la liste forment une rangée de tuiles. Un appareil déjà à l'écran n'est pas répété. */
internal fun MainActivity.addEntityCards(ids: List<String>) {
    val list = ids.mapNotNull { id -> cfg.rooms.firstNotNullOfOrNull { it.find(id) } }.filter { it.id !in tiles }.distinctBy { it.id }
    if (list.isEmpty()) return
    val grid = TileGrid(this)
    for (re in list) grid.addView(deviceTile(re, HOME_SHELF))
    content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
}

/** Caméras ajoutées une à une, en grandes vignettes ; une caméra déjà à l'écran n'est pas répétée. */
internal fun MainActivity.addCameraCards(ids: List<String>) {
    val list = ids.mapNotNull { id -> cfg.rooms.firstNotNullOfOrNull { it.find(id) } }.filter { it.id !in cameraTiles }.distinctBy { it.id }
    if (list.isEmpty()) return
    val grid = CameraGrid(this)
    for (re in list) {
        val v = CameraTileView(this)
        v.name = Present.displayName(re, app.ha.states[re.id], "")
        v.setOnClickListener { openLive(re, HOME_SHELF) }
        grid.addView(v)
        cameraTiles[re.id] = v
    }
    content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    handler.post(snapshotTick)
}

/**
 * Carte « Actualités » : les titres les plus récents des flux de « Ce matin » (sauf ceux décochés dans les options),
 * sur deux colonnes, chacun avec son image. Toucher une nouvelle ouvre son résumé ; « Plus de nouvelles » ouvre « Ce matin ».
 */
internal fun MainActivity.addNewsCard(opts: HomeFlow.Options) {
    val hidden = opts.list("news", "hide").toSet()
    val sources = (0 until cfg.morningSources.length()).mapNotNull { cfg.morningSources.optJSONObject(it) }
        .filter { HomeExtras.isFeed(it) && it.optString("url").isNotEmpty() && it.optString("url") !in hidden }
    if (sources.isEmpty()) return
    val count = opts.int("news", "count", 4)
    val card = homeCard(tr("Actualités"))
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    card.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    // Sous les nouvelles, un bouton discret vers toutes les autres.
    card.addView(label(15f, Skin.ink, true, tr("Plus de nouvelles") + "  ›").apply {
        gravity = Gravity.CENTER; background = roundRect(Skin.fg(0x1A), dpf(22)); setPadding(dp(22), dp(11), dp(22), dp(11)); isClickable = true
        setOnClickListener { openMorning() }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })

    fun tile(h: HomeFlow.Headline): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = roundRect(Skin.fg(0x12), dpf(18)); setPadding(dp(10), dp(10), dp(16), dp(10))
        // L'image, ou à défaut un pavé à l'initiale du flux : toutes les tuiles gardent la même forme.
        val frame = android.widget.FrameLayout(this@addNewsCard).apply { background = roundRect(Skin.fg(0x1A), dpf(13)); clipToOutline = true }
        frame.addView(label(34f, Skin.fg(0x4D), true, h.source.trim().take(1).uppercase(I18n.locale).ifEmpty { "•" }).apply { gravity = Gravity.CENTER },
            android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (h.image != null) {
            val img = ImageView(this@addNewsCard).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.INVISIBLE }
            frame.addView(img, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            Feeds.image(h.image, dp(340)) { b -> if (b != null) { img.setImageBitmap(b); img.visibility = View.VISIBLE } }
        }
        addView(frame, LinearLayout.LayoutParams(dp(164), dp(110)).apply { marginEnd = dp(16) })
        val col = LinearLayout(this@addNewsCard).apply { orientation = LinearLayout.VERTICAL }
        if (h.source.isNotEmpty()) col.addView(label(12f, Skin.accent, true, h.source.uppercase(I18n.locale)).apply { letterSpacing = 0.07f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(label(17f, Skin.ink, true, h.title).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.08f); setPadding(0, dp(3), 0, 0) })
        if (h.date > 0) col.addView(label(13f, Skin.mute, text = relativeTime(h.date)).apply { setPadding(0, dp(5), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (h.summary.isNotEmpty()) {
            isClickable = true
            setOnClickListener { showReader(h.title, h.summary + "\n\n" + listOf(h.source, if (h.date > 0) relativeTime(h.date) else "").filter { it.isNotEmpty() }.joinToString(" · ")) }
        }
    }

    var shownKey = "?"
    fun fill() {
        val feeds = sources.map { s ->
            val name = s.optString("title")
            name to Feeds.cached(s.optString("url"))?.items.orEmpty().map { HomeFlow.Headline(name, it.title, it.summary, it.date, it.image) }
        }
        val list = HomeFlow.headlines(feeds, count)
        val key = list.joinToString("|") { it.title }
        if (key == shownKey) return
        shownKey = key
        rows.removeAllViews()
        if (list.isEmpty()) { rows.addView(label(15f, Skin.mute, text = tr("Chargement…")).apply { setPadding(0, dp(6), 0, 0) }); return }
        for (pair in list.chunked(2)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { k, h -> r.addView(tile(h), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (k > 0) marginStart = dp(12) }) }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(12) })
            rows.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }
    // Flux relus au besoin (gardés 20 min) ; la carte se remplit à leur arrivée, si elle est encore à l'écran.
    val refresh = {
        for (s in sources) {
            val url = s.optString("url"); val c = Feeds.cached(url)
            if (c == null || System.currentTimeMillis() - c.at > 20 * 60_000L) Feeds.load(url) { if (card.parent != null) fill() }
        }
        fill()
    }
    refresh(); priorityRefresh.add(refresh)
}

/** Couleur du pictogramme d'une pastille d'information, selon son sujet. */
private fun infoTint(c: Present.Chip): Int = c.color ?: c.iconTint ?: when (c.kind) {
    "light" -> 0xFFFFC233.toInt()
    "cover" -> 0xFF64B5F6.toInt()
    "energy", "peak" -> 0xFF34C759.toInt()
    "lock", "door", "window" -> 0xFFFFA726.toInt()
    "smoke" -> if (c.level >= 1) HomeCare.RED else 0xFF34C759.toInt()
    "collect" -> 0xFF4DB6AC.toInt()
    "battery", "offline", "wear" -> 0xFFFF8A65.toInt()
    "wx" -> 0xFF4FC3F7.toInt()
    else -> Skin.accent
}

/**
 * Pastille de la ligne d'information : un pictogramme dans un rond teinté, puis le texte. Plus calme qu'une carte
 * « À voir maintenant » (pas de bordure, fond à peine visible), mais lisible d'un coup d'œil.
 */
private fun MainActivity.infoPill(c: Present.Chip, onTap: () -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    background = roundRect(Skin.fg(0x14), dpf(20)); isClickable = true
    val tint = infoTint(c)
    val icon = if (c.icon != 0) getDrawable(c.icon)?.mutate() else null
    setPadding(dp(if (icon != null) 6 else 14), dp(6), dp(15), dp(6))
    if (icon != null) addView(ImageView(this@infoPill).apply {
        setImageDrawable(icon); imageTintList = android.content.res.ColorStateList.valueOf(tint)
        background = oval((tint and 0x00FFFFFF) or 0x33000000); setPadding(dp(6), dp(6), dp(6), dp(6))
    }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(9) })
    addView(label(15f, Skin.fg(0xE6), text = c.text).apply { typeface = Skin.medium; maxLines = 1; minHeight = dp(30); gravity = Gravity.CENTER_VERTICAL })
    setOnClickListener { onTap() }
}

/** Aperçu d'un moment de la journée, demandé depuis la page web : l'accueil le montre deux minutes, puis revient à l'heure réelle. */
internal fun MainActivity.previewHome(moment: String): Boolean {
    if (!priorityHome || moment !in HomeFlow.MOMENTS || isFinishing) return false
    onMotion()
    previewMoment = moment; previewUntil = SystemClock.uptimeMillis() + HomeFlow.PREVIEW_MS
    dialog?.dismiss()
    if (roomIndex == MainActivity.HOME) showHome() else goHome()
    scroll.scrollTo(0, 0)
    return true
}
