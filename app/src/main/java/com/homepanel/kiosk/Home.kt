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

    fun condition(c: String) = when (c) {
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
        "sunny" -> tr("Ensoleillé")
        "windy", "windy-variant" -> tr("Venteux")
        "exceptional" -> tr("Exceptionnel")
        else -> c
    }

    fun emoji(c: String) = when (c) {
        "clear-night" -> "🌙"
        "cloudy" -> "☁️"
        "fog" -> "🌫️"
        "hail", "snowy-rainy" -> "🌨️"
        "lightning", "lightning-rainy" -> "⛈️"
        "partlycloudy" -> "⛅"
        "pouring", "rainy" -> "🌧️"
        "snowy" -> "❄️"
        "sunny" -> "☀️"
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
            listOf("salon", "living", "lounge", "family", "tv").any { it in n } -> R.drawable.ic_tv
            listOf("bain", "toilette", "bath", "toilet", "laundry", "lavage").any { it in n } -> R.drawable.ic_drop
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

    init {
        orientation = VERTICAL
        background = Skin.card(ctx, ctx.dpf(22))
        setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(18))
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(emoji, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.dp(14) })
        top.addView(LinearLayout(ctx).apply { orientation = VERTICAL; addView(temp); addView(cond) })
        addView(top)
        addView(details, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        addView(days, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(14) })
        cond.text = tr("Météo")
    }

    fun update(e: EntityState?) {
        if (e == null) { cond.text = tr("Météo non configurée"); temp.text = ""; emoji.text = "🌡️"; details.text = ""; return }
        val a = e.attrs
        emoji.text = HomeText.emoji(e.state)
        temp.text = a.dbl("temperature")?.let { Present.num(it) + "°" } ?: "—"
        cond.text = HomeText.condition(e.state)
        val parts = ArrayList<String>()
        a.dbl("humidity")?.let { parts.add("${tr("Humidité")} ${Present.num(it)} %") }
        a.dbl("wind_speed")?.let { parts.add("${tr("Vent")} ${Math.round(it)} ${a.str("wind_speed_unit").ifEmpty { "km/h" }}") }
        details.text = parts.joinToString("  ·  ")
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
    class Event(val title: String, val start: Date, val allDay: Boolean, val color: Int)

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
        for (ev in events.take(6)) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(6), 0, context.dp(6))
            }
            row.addView(View(context).apply { background = oval(ev.color) }, LayoutParams(context.dp(10), context.dp(10)).apply { marginEnd = context.dp(12) })
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(context.label(15f, Skin.ink, text = ev.title).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            col.addView(context.label(13f, Skin.fg(0xB3), text = whenText(ev)))
            row.addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
            rows.addView(row)
        }
    }

    private fun whenText(ev: Event): String {
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
        return if (ev.allDay) "$day · " + tr("toute la journée") else "$day · ${timeFmt.format(ev.start)}"
    }
}

/** Couleurs des calendriers (dans l'ordre de la configuration). */
val CALENDAR_COLORS = intArrayOf(0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), 0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt())
