package com.homepanel.kiosk

import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
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

    /** Les cartes de l'accueil, dans l'ordre où la page web les propose. */
    val CARDS = listOf("birthday", "notes", "weather", "night", "favorites", "shortcuts", "previews", "cameras")
    val MOMENTS = listOf(MORNING, DAY, EVENING)

    /** Réglage de l'accueil par priorité : l'heure où commence chaque moment, et les cartes de chacun, dans l'ordre (une carte absente est masquée). */
    class Plan(val from: Map<String, Int>, val cards: Map<String, List<String>>)

    private val DEFAULT_FROM = mapOf(MORNING to 5, DAY to 10, EVENING to 19)
    private val DEFAULT_CARDS = mapOf(
        MORNING to listOf("birthday", "weather", "notes", "previews", "favorites", "shortcuts", "cameras"),
        DAY to listOf("birthday", "notes", "weather", "favorites", "shortcuts", "previews", "cameras"),
        EVENING to listOf("birthday", "night", "cameras", "notes", "favorites", "shortcuts", "weather", "previews"))
    val DEFAULT = Plan(DEFAULT_FROM, DEFAULT_CARDS)

    /**
     * Lit le réglage enregistré. Tolérant : un texte vide ou abîmé donne le réglage d'origine ; une carte inconnue ou en
     * double est ignorée ; des heures qui ne se suivent pas (matin < journée < soir) reviennent à celles d'origine.
     */
    fun parsePlan(json: String): Plan {
        val o = try { org.json.JSONObject(json.ifBlank { "{}" }) } catch (_: Exception) { return DEFAULT }
        val from = HashMap<String, Int>(); val cards = HashMap<String, List<String>>()
        for (m in MOMENTS) {
            val part = o.optJSONObject(m)
            from[m] = part?.optInt("from", DEFAULT_FROM.getValue(m))?.coerceIn(0, 23) ?: DEFAULT_FROM.getValue(m)
            val a = part?.optJSONArray("cards")
            cards[m] = if (a == null) DEFAULT_CARDS.getValue(m) else (0 until a.length()).map { a.optString(it) }.filter { it in CARDS }.distinct()
        }
        val ordered = from.getValue(MORNING) < from.getValue(DAY) && from.getValue(DAY) < from.getValue(EVENING)
        return Plan(if (ordered) from else DEFAULT_FROM, cards)
    }

    fun planJson(p: Plan): org.json.JSONObject = org.json.JSONObject().also { o ->
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
    infoLine = label(15f, Skin.fg(0xB3)).apply {
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(4), dp(10), dp(4), dp(6)); isClickable = true
    }.also { content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    updateHomeHeader()

    val plan = HomeFlow.parsePlan(cfg.homePlan)
    shownMoment = HomeFlow.moment(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), plan)
    for (part in HomeFlow.order(shownMoment, cfg.homeFixedOrder, plan)) when (part) {
        "birthday" -> addBirthdayBanner()
        "notes" -> addFamilyNotes()
        "weather" -> addWeatherAgenda()
        "night" -> addNightCard()
        // Favoris, aperçus et caméras de l'accueil appartiennent à la navigation latérale, comme dans l'accueil classique.
        "favorites" -> if (cfg.sideNav) addHomeFavorites()
        "shortcuts" -> addShortcuts(Shortcuts.forHome(cfg), dp(14))
        "previews" -> if (cfg.sideNav) addHomePreviews()
        "cameras" -> if (cfg.sideNav) addHomeCameras()
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
    fun late(kind: String) = night.openings.filter {
        val e = app.ha.states[it.id]
        Present.chipKind(it.id, e) == kind && HomeFlow.alerts(it.id, kind, roomName[it.id].orEmpty(), it.name ?: e?.name.orEmpty(), alertOn, alertOff) &&
            HomeFlow.overdue(e?.changed ?: 0L, nowMs, cfg.attentionDelay)
    }
    val lateDoors = late("door"); val lateWindows = late("window")
    val recent = chips.filter { (it.kind == "door" && lateDoors.isEmpty()) || (it.kind == "window" && lateWindows.isEmpty()) }
    val attention = HomeFlow.attention(chips - recent.toSet())
    val info = HomeFlow.info(chips - recent.toSet()) + recent
    fun since(list: List<RoomEntity>) = list.mapNotNull { app.ha.states[it.id]?.changed }.filter { it > 0 }.minOrNull()?.let { HomeFlow.since(it, nowMs) }.orEmpty()
    // Rien n'a changé à l'écran : on ne reconstruit pas (cette fonction est rappelée chaque minute pour les durées).
    val key = attention.joinToString("|") { it.text + it.level } + "#" + info.joinToString("|") { it.text } + "#" + lateDoors.size + since(lateDoors) + "/" + lateWindows.size + since(lateWindows)
    if (key == priorityKey && box.childCount > 0) { nightCardRefresh?.invoke(); return }
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
        shown.forEachIndexed { i, c ->
            val red = c.level >= 2
            val tint = if (red) HomeCare.RED else 0xFFFFB300.toInt()
            // Sous le titre : de quoi il s'agit, et ce que la tablette peut ou non y faire.
            val detail = when (c.kind) { "door" -> names(lateDoors); "window" -> names(lateWindows); "lock" -> names(night.locks); else -> "" }
            // Le titre ne compte que les ouvertures qui ont dépassé le délai, et dit depuis quand la plus ancienne est ouverte.
            val heading = when (c.kind) {
                "door" -> Present.plural(lateDoors.size, tr("Porte ouverte"), tr("portes ouvertes"))
                "window" -> Present.plural(lateWindows.size, tr("Fenêtre ouverte"), tr("fenêtres ouvertes"))
                else -> c.text
            }
            val elapsed = when (c.kind) { "door" -> since(lateDoors); "window" -> since(lateWindows); else -> "" }
            // Une carte sans précision dit au moins qu'on peut la toucher pour en savoir plus.
            val hint = when (c.kind) { "door", "window" -> listOf(tr("À fermer à la main"), elapsed).filter { it.isNotEmpty() }.joinToString(" · "); else -> if (detail.isEmpty()) tr("Toucher pour voir le détail") else "" }
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
            setOnClickListener { showAllChips(attention, make) }
        })
    }
    infoLine?.let { line ->
        line.text = info.joinToString("   ·   ") { it.text }
        line.visibility = if (info.isEmpty()) View.GONE else View.VISIBLE
        line.setOnClickListener { showAllChips(info, make) }
    }
    nightCardRefresh?.invoke()
}

/** Carte « Pour la nuit » (le soir) : ce qui reste allumé, avec le raccourci du coucher et « Tout éteindre ». */
internal fun MainActivity.addNightCard() {
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
    if (nightId != null && cfg.shows("shortcuts")) buttons.addView(label(17f, Skin.onPrimary, true, app.ha.states[nightId]?.name ?: tr("Bonne nuit")).apply {
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
        title.text = if (lights.isEmpty()) tr("Tout est éteint") else Present.plural(lights.size, tr("1 lumière encore allumée"), tr("lumières encore allumées"))
        detail.text = HomeFlow.rooms(lights.mapNotNull { roomOf[it.id]?.name }.distinct())
        detail.visibility = if (lights.isEmpty()) View.GONE else View.VISIBLE
        allOff.visibility = if (lights.isEmpty()) View.GONE else View.VISIBLE
        allOff.setOnClickListener {
            if (!haOffline()) { for (re in lights) Present.bulkOff(app.ha, re.id, app.ha.states[re.id]); allOff.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        buttons.visibility = if (buttons.childCount == 1 && lights.isEmpty()) View.GONE else View.VISIBLE
    }
    refresh()
    nightCardRefresh = refresh
}

/** Le moment de la journée a changé (10 h, 19 h, 5 h) : l'accueil se réordonne, mais pas sous le doigt ni devant une fenêtre ouverte. */
internal fun MainActivity.followMoment() {
    if (!priorityHome || roomIndex != MainActivity.HOME) return
    // Chaque minute : les durées (« depuis 40 min ») avancent, et une ouverture peut dépasser le délai.
    if (SystemClock.uptimeMillis() - priorityTickAt >= 60_000) { priorityTickAt = SystemClock.uptimeMillis(); updateHomeHeader() }
    if (cfg.homeFixedOrder) return
    val now = HomeFlow.moment(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), HomeFlow.parsePlan(cfg.homePlan))
    if (now != shownMoment && dialog?.isShowing != true && SystemClock.uptimeMillis() - lastTouch > 60_000) showHome()
}
