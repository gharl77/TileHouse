package com.homepanel.kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Textes et icônes de la page d'accueil. */
object HomeText {
    fun greeting(hour: Int) = when {
        hour < 5 -> tr("Bonne nuit")
        hour < 12 -> tr("Bonjour")
        hour < 17 -> tr("Bon après-midi")
        else -> tr("Bonsoir")
    }

    /** « Alice et Bob sont à la maison » d'après les entités person.* choisies. */
    /** États qui veulent dire « absent » pour un capteur de présence (ESPresense, Bermuda…). */
    private val AWAY = setOf("not_home", "away", "absent", "unknown", "unavailable", "none", "", "off", "false", "0", "none_home")

    /**
     * Présence selon le type d'entité :
     *  - person / device_tracker (app compagnon) : « home » ;
     *  - binary_sensor / input_boolean : « on » ;
     *  - capteur (ESPresense…) : tout état autre qu'une absence (souvent le nom de la pièce).
     */
    fun isHome(e: EntityState): Boolean = when (e.domain) {
        "person", "device_tracker" -> e.state == "home"
        "binary_sensor", "input_boolean", "switch" -> e.state == "on"
        else -> e.state.trim().lowercase() !in AWAY
    }

    /** Prénom affiché : nom de l'entité sans « Presence / Présence », premier mot. */
    fun personName(e: EntityState): String {
        val cleaned = e.name.replace(Regex("(?i)\\b(pr[ée]sence|presence|occupancy|tracker|location)\\b"), "")
            .replace(Regex("[_\\-:]+"), " ").trim()
        return (cleaned.ifEmpty { e.name }).substringBefore(' ')
    }

    fun presence(ids: List<String>, states: Map<String, EntityState>): String {
        if (ids.isEmpty()) return ""
        val known = ids.mapNotNull { states[it] }
        if (known.isEmpty()) return ""
        val home = known.filter { isHome(it) }.map { personName(it) }
        return when {
            home.isEmpty() -> tr("Personne n'est à la maison")
            home.size == known.size && known.size > 1 -> tr("Tout le monde est à la maison")
            home.size == 1 -> "${home[0]} " + tr("est à la maison")
            else -> home.dropLast(1).joinToString(", ") + " " + tr("et") + " " + home.last() + " " + tr("sont à la maison")
        }
    }

    /** Durée pendant laquelle un lecteur en pause garde sa carte « En lecture ». */
    const val PAUSE_GRACE = 10 * 60_000L

    /**
     * Un lecteur en pause l'est-il depuis peu ? D'après l'heure de sa dernière position (attribut
     * media_position_updated_at de Home Assistant, mise à jour au moment de la pause). Sert quand l'app
     * n'a pas vu le lecteur jouer elle-même (elle vient de démarrer).
     */
    fun pausedRecently(updatedAt: String, now: Long, grace: Long = PAUSE_GRACE): Boolean {
        if (updatedAt.isEmpty()) return false
        val t = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(updatedAt.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00"))?.time
        } catch (_: Exception) { null } ?: return false
        return now - t in 0 until grace
    }

    /** Vrai quand le soleil est couché (entité sun.sun de Home Assistant) ; faux si elle est inconnue. */
    fun nightNow(): Boolean = try { App.instance.ha.states["sun.sun"]?.state == "below_horizon" } catch (_: Throwable) { false }

    /**
     * Fait-il nuit à l'instant [t] ? Calculé d'après le prochain lever et le prochain coucher du soleil
     * (attributs de sun.sun), en supposant que leurs heures bougent peu d'un jour à l'autre.
     */
    fun isNight(t: Long, nextRising: Long?, nextSetting: Long?): Boolean {
        if (nextRising == null || nextSetting == null) return false
        val day = 86_400_000L
        val r = Math.floorMod(nextRising, day); val s = Math.floorMod(nextSetting, day); val x = Math.floorMod(t, day)
        val light = if (r <= s) x in r until s else x >= r || x < s
        return !light
    }

    fun condition(c: String, night: Boolean = false) = when (c) {
        "sunny" -> if (night) tr("Nuit claire") else tr("Ensoleillé")
        "clear-night" -> tr("Nuit claire")
        "cloudy" -> tr("Nuageux")
        "fog" -> tr("Brouillard")
        "hail" -> tr("Grêle")
        "lightning" -> tr("Orages")
        "lightning-rainy" -> tr("Orages et pluie")
        "partlycloudy" -> tr("Partiellement nuageux")
        "pouring" -> tr("Averses fortes")
        "rainy" -> tr("Pluie")
        "snowy" -> tr("Neige")
        "snowy-rainy" -> tr("Pluie et neige")
        "windy", "windy-variant" -> tr("Venteux")
        "exceptional" -> tr("Exceptionnel")
        else -> c
    }

    /** [night] : certaines sources annoncent « ensoleillé » en pleine nuit ; on montre alors la lune. */
    fun emoji(c: String, night: Boolean = false) = when (c) {
        "clear-night" -> "🌙"
        "cloudy" -> "☁️"
        "fog" -> "🌫️"
        "hail", "snowy-rainy" -> "🌨️"
        "lightning", "lightning-rainy" -> "⛈️"
        "partlycloudy" -> if (night) "☁️" else "⛅"
        "pouring", "rainy" -> "🌧️"
        "snowy" -> "❄️"
        "sunny" -> if (night) "🌙" else "☀️"
        "windy", "windy-variant" -> "💨"
        else -> "🌡️"
    }

    /** Icône d'une pièce d'après son nom. */
    fun roomIcon(name: String): Int {
        val n = name.lowercase()
        return when {
            listOf("chambre", "bedroom", "nursery", "kid", "enfant", "bébé", "baby").any { it in n } -> R.drawable.ic_bed
            listOf("cuisine", "manger", "kitchen", "dining").any { it in n } -> R.drawable.ic_kitchen
            listOf("bureau", "office", "study").any { it in n } -> R.drawable.ic_desk
            listOf("salon", "living", "lounge", "family", "tv").any { it in n } -> R.drawable.ic_sofa
            listOf("bain", "bath", "douche", "shower").any { it in n } -> R.drawable.ic_bath
            listOf("toilette", "toilet", "laundry", "lavage").any { it in n } -> R.drawable.ic_drop
            listOf("entrée", "entree", "entry", "entrance", "hall", "foyer", "porch").any { it in n } -> R.drawable.ic_door
            listOf("extérieur", "exterieur", "jardin", "cour", "outdoor", "outside", "garden", "yard", "patio", "garage").any { it in n } -> R.drawable.ic_tree
            else -> R.drawable.ic_home
        }
    }
}

/** Carte météo : conditions actuelles + prévisions sur 5 jours. */
class WeatherCard(ctx: Context) : LinearLayout(ctx) {
    private val emoji = ctx.label(46f, Skin.ink)
    private val temp = ctx.label(40f, Skin.ink).apply { typeface = Skin.light }
    private val cond = ctx.label(15f, Skin.fg(0xCC))
    private val details = ctx.label(14f, Skin.fg(0xB3))
    private val days = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val dayFmt = SimpleDateFormat("EEE", I18n.locale)
    private val dials = List(3) { DialView(ctx) }

    init {
        orientation = VERTICAL
        background = Skin.card(ctx, ctx.dpf(22))
        setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(18))
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(emoji, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.dp(14) })
        if (Skin.gauges) {
            // Tableau de bord : trois cadrans à aiguille (extérieur, puissance ou vent, humidité), les conditions dessous.
            for (d in dials) top.addView(d, LayoutParams(0, ctx.dp(132), 1f))
            emoji.visibility = View.GONE
        } else top.addView(LinearLayout(ctx).apply { orientation = VERTICAL; addView(temp); addView(cond) })
        addView(top)
        if (Skin.gauges) addView(cond, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
        addView(details, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        addView(days, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(14) })
        cond.text = tr("Météo")
    }

    fun update(e: EntityState?) {
        if (e == null) { cond.text = tr("Météo non configurée"); temp.text = ""; emoji.text = "🌡️"; details.text = ""; if (Skin.gauges) updateDials(org.json.JSONObject()); return }
        val a = e.attrs
        val night = HomeText.nightNow()
        emoji.text = HomeText.emoji(e.state, night)
        temp.text = a.dbl("temperature")?.let { Present.num(Math.round(it).toDouble()) + "°" } ?: "—"
        cond.text = HomeText.condition(e.state, night)
        val parts = ArrayList<String>()
        a.dbl("humidity")?.let { parts.add("${tr("Humidité")} ${Present.num(it)} %") }
        a.dbl("wind_speed")?.let { parts.add("${tr("Vent")} ${Math.round(it)} ${a.str("wind_speed_unit").ifEmpty { "km/h" }}") }
        // Tableau de bord : les cadrans donnent déjà l'humidité, et le vent quand aucun capteur de puissance n'est choisi.
        if (Skin.gauges) { val windOnDial = updateDials(a); parts.removeAll { it.startsWith(tr("Humidité")) || (windOnDial && it.startsWith(tr("Vent"))) }; details.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE }
        details.text = parts.joinToString("  ·  ")
    }

    /** Cadrans : température extérieure, puissance de la maison (si un capteur de puissance est choisi, sinon le vent), humidité. Retourne vrai si le vent est sur un cadran. */
    private fun updateDials(a: org.json.JSONObject): Boolean {
        val t = a.dbl("temperature")
        dials[0].set(tr("Extérieur"), t?.let { Present.num(Math.round(it).toDouble()) + "°" } ?: "—", Dial.temperature(t, a.str("temperature_unit")))
        val app = App.instance
        val power = app.config.energySensors.asSequence().mapNotNull { app.ha.states[it] }.firstOrNull { it.attrs.str("device_class") == "power" }
        val kw = power?.let { Dial.kilowatts(it.state.toDoubleOrNull(), it.attrs.str("unit_of_measurement")) }
        // Pendant une pointe, la zone au-delà du seuil choisi passe au rouge.
        val limit = app.config.peakPowerLimit / 10.0
        val peak = limit > 0 && Dial.inPeak((context as? MainActivity)?.peaks.orEmpty().map { it.start to it.end }, System.currentTimeMillis())
        if (kw != null) dials[1].set(tr("Puissance") + " kW", Present.num(Math.round(kw * 10) / 10.0), Dial.level(kw, 0.0, 10.0), if (peak) Dial.level(limit, 0.0, 10.0) else null)
        else a.dbl("wind_speed").let { w -> dials[1].set(tr("Vent") + " " + a.str("wind_speed_unit").ifEmpty { "km/h" }, w?.let { Math.round(it).toString() } ?: "—", Dial.level(w, 0.0, 80.0)) }
        val h = a.dbl("humidity")
        dials[2].set(tr("Humidité") + " %", h?.let { Math.round(it).toString() } ?: "—", Dial.level(h, 0.0, 100.0))
        return kw == null
    }

    /** Prévisions quotidiennes renvoyées par weather.get_forecasts. */
    fun updateForecast(list: JSONArray?) {
        days.removeAllViews()
        if (list == null) return
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (i in 0 until minOf(5, list.length())) {
            val f = list.optJSONObject(i) ?: continue
            val date = try { iso.parse(f.str("datetime").take(10)) } catch (_: Exception) { null }
            val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            // « Auj. » seulement si la prévision est vraiment celle d'aujourd'hui (le soir, la première
            // prévision peut déjà être celle de demain).
            val isToday = date != null && iso.format(date) == iso.format(Date())
            val dayName = if (isToday) tr("Auj.") else date?.let { dayFmt.format(it).replaceFirstChar { c -> c.uppercase() } } ?: ""
            col.addView(context.label(13f, Skin.fg(0xB3), text = dayName))
            col.addView(context.label(22f, Skin.ink, text = HomeText.emoji(f.str("condition"))))
            val hi = f.dbl("temperature")?.let { Present.num(Math.round(it).toDouble()) + "°" } ?: ""
            val lo = f.dbl("templow")?.let { " / " + Present.num(Math.round(it).toDouble()) + "°" } ?: ""
            col.addView(context.label(13f, Skin.ink, text = hi + lo))
            days.addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }
}

/** Carte agenda : prochains événements des calendriers choisis. */
class CalendarCard(ctx: Context) : LinearLayout(ctx) {
    class Event(val title: String, val start: Date, val allDay: Boolean, val color: Int,
                val end: Date? = null, val location: String = "", val description: String = "")

    /** Appelé quand on touche un événement (ouvre son détail). */
    var onEvent: ((Event) -> Unit)? = null

    private val rows = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val timeFmt = SimpleDateFormat("HH:mm", I18n.locale)
    private val dayFmt = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale)

    init {
        orientation = VERTICAL
        background = Skin.card(ctx, ctx.dpf(22))
        setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(18))
        addView(ctx.label(17f, Skin.ink, true, tr("Agenda")))
        addView(rows, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        setEvents(null)
    }

    fun setEvents(events: List<Event>?) {
        rows.removeAllViews()
        if (events == null) { rows.addView(context.label(14f, Skin.fg(0xB3), text = tr("Chargement…"))); return }
        if (events.isEmpty()) { rows.addView(context.label(14f, Skin.fg(0xB3), text = tr("Aucun événement ces prochains jours"))); return }
        // Une cloche à côté des événements pour lesquels un rappel est prévu.
        val reminders = Reminders.all(context).map { it.key }.toSet()
        for (ev in events.take(6)) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(6), 0, context.dp(6))
            }
            row.addView(View(context).apply { background = oval(ev.color) }, LayoutParams(context.dp(10), context.dp(10)).apply { marginEnd = context.dp(12) })
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(context.label(15f, Skin.ink, text = ev.title).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            col.addView(context.label(13f, Skin.fg(0xB3), text = whenText(ev) + if (Reminders.keyOf(ev.title, ev.start.time) in reminders) "  ·  🔔 " + tr("rappel") else ""))
            row.addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
            row.isClickable = true
            row.setOnClickListener { onEvent?.invoke(ev) }
            rows.addView(row)
        }
    }

    /** « Aujourd'hui · 19:00 – 21:30 », « Demain · toute la journée »… [withEnd] ajoute l'heure de fin. */
    fun whenText(ev: Event, withEnd: Boolean = false): String {
        val c = Calendar.getInstance().apply { time = ev.start }
        val today = Calendar.getInstance()
        // Midi pile des deux jours (minutes et secondes comprises), puis arrondi : un événement
        // demain soir ne doit pas tomber à « 0,97 jour » et s'afficher « Aujourd'hui ».
        fun noon(k: Calendar) = k.apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val diff = Math.round((noon(c) - noon(today)) / 86_400_000.0).toInt()
        val day = when (diff) {
            0 -> tr("Aujourd'hui")
            1 -> tr("Demain")
            else -> dayFmt.format(ev.start).replaceFirstChar { it.uppercase() }
        }
        if (ev.allDay) return "$day · " + tr("toute la journée")
        val end = if (withEnd && ev.end != null && ev.end.after(ev.start)) " – " + timeFmt.format(ev.end) else ""
        return "$day · ${timeFmt.format(ev.start)}$end"
    }
}

/** Couleurs des calendriers (dans l'ordre de la configuration). */
val CALENDAR_COLORS = intArrayOf(0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), 0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt())

/** Calculs des cadrans du tableau de bord : place de la valeur sur l'échelle, de 0 à 1 (0 si inconnue). */
object Dial {
    fun level(v: Double?, min: Double, max: Double): Float = if (v == null) 0f else ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    /** Température sur une échelle de −30 à +40 °C (ou l'équivalent en °F). */
    fun temperature(t: Double?, unit: String): Float = level(t?.let { if (unit.contains("F")) (it - 32) / 1.8 else it }, -30.0, 40.0)
    /** Puissance en kW, que le capteur parle en W ou en kW ; null si illisible. */
    fun kilowatts(v: Double?, unit: String): Double? = v?.let { if (unit.trim().equals("kW", true)) it else it / 1000.0 }
    /** Une pointe est-elle en cours à l'instant [now] ? [peaks] : débuts et fins, en millisecondes. */
    fun inPeak(peaks: List<Pair<Long, Long>>, now: Long) = peaks.any { now >= it.first && now < it.second }
    /** Angle de l'aiguille (degrés, 0 = vers la droite, sens horaire) : de 150° (minimum) à 390° (maximum). */
    fun angle(level: Float) = START + SWEEP * level.coerceIn(0f, 1f)
    const val START = 150f
    const val SWEEP = 240f
}

/** Cadran à aiguille : arc gradué, portion colorée jusqu'à la valeur, aiguille, valeur chiffrée et étiquette gravée. */
class DialView(ctx: Context) : View(ctx) {
    private var label = ""
    private var value = ""
    private var level = 0f
    /** Début de la zone rouge sur l'échelle (0 à 1), ou null. */
    private var danger: Float? = null
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.BUTT }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val oval = android.graphics.RectF()
    private companion object { const val RED = 0xFFFF5A52.toInt() }

    fun set(label: String, value: String, level: Float, danger: Float? = null) {
        this.label = label.uppercase(I18n.locale); this.value = value; this.level = level; this.danger = danger
        contentDescription = "$label $value"
        invalidate()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val labelH = context.dpf(18)
        val r = minOf(width / 2f, (height - labelH) / 1.62f) - context.dpf(6)
        if (r <= 0f) return
        val cx = width / 2f; val cy = context.dpf(4) + r
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arc.strokeWidth = context.dpf(3); arc.color = Skin.fg(0x33)
        c.drawArc(oval, Dial.START, Dial.SWEEP, false, arc)
        val over = danger?.let { level >= it } == true
        danger?.let { d -> arc.strokeWidth = context.dpf(4); arc.color = 0x99FF453A.toInt(); c.drawArc(oval, Dial.START + Dial.SWEEP * d, Dial.SWEEP * (1f - d), false, arc) }
        arc.strokeWidth = context.dpf(4); arc.color = if (over) RED else Skin.accent
        if (level > 0f) c.drawArc(oval, Dial.START, Dial.SWEEP * level, false, arc)
        // Graduations : une tous les huitièmes de l'échelle.
        arc.strokeWidth = context.dpf(1.5f); arc.color = Skin.fg(0x66)
        for (k in 0..8) {
            val a = Math.toRadians((Dial.START + Dial.SWEEP * k / 8f).toDouble())
            val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * (r - context.dpf(9)), cy + sn * (r - context.dpf(9)), cx + cs * (r - context.dpf(3)), cy + sn * (r - context.dpf(3)), arc)
        }
        val a = Math.toRadians(Dial.angle(level).toDouble())
        arc.strokeWidth = context.dpf(2.5f); arc.color = if (over) RED else Skin.ink
        c.drawLine(cx, cy, cx + Math.cos(a).toFloat() * (r - context.dpf(12)), cy + Math.sin(a).toFloat() * (r - context.dpf(12)), arc)
        fill.color = Skin.ink; c.drawCircle(cx, cy, context.dpf(4.5f), fill)
        text.typeface = Skin.light; text.color = if (over) RED else Skin.ink; text.textSize = r * 0.44f
        c.drawText(value, cx, cy + r * 0.62f, text)
        text.typeface = Skin.bold; text.color = Skin.mute; text.textSize = context.dpf(11.5f); text.letterSpacing = 0.08f
        c.drawText(label, cx, height - context.dpf(4), text)
        text.letterSpacing = 0f
    }
}
