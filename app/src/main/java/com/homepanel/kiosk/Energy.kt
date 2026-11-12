package com.homepanel.kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Électricité : une pastille sur l'accueil (premier capteur choisi) et une fenêtre avec les capteurs choisis,
 * la pointe en cours ou à venir et la courbe des dernières 24 heures.
 */
object Energy {
    class Point(val t: Long, val v: Double)

    /** « 2,4 kW », « 18,6 kWh » ; « — » si le capteur n'a pas de valeur. */
    fun value(e: EntityState?): String {
        val v = e?.state?.toDoubleOrNull() ?: return "—"
        val unit = e.attrs.str("unit_of_measurement")
        // Les watts au-dessus de 1000 se lisent mieux en kW.
        return if (unit == "W" && kotlin.math.abs(v) >= 1000) Present.num(Math.round(v / 100.0) / 10.0) + " kW"
        else Present.num(if (kotlin.math.abs(v) >= 100) Math.round(v).toDouble() else Math.round(v * 10) / 10.0) + (if (unit.isEmpty()) "" else " $unit")
    }

    /** Vrai pour un compteur qui ne fait que monter (kWh cumulés) : on en montre la consommation par heure. */
    fun cumulative(e: EntityState?) = e != null && e.attrs.str("device_class") == "energy" && e.attrs.str("state_class").startsWith("total")

    /**
     * Pastille de l'accueil. Pour un compteur cumulatif, le total (« 3 159 kWh ») ne dit rien : on montre la consommation
     * d'aujourd'hui ([today]) dès qu'elle est connue et non nulle.
     */
    fun chips(ids: List<String>, states: Map<String, EntityState>, today: Recent? = null): List<Present.Chip> {
        val e = ids.firstOrNull()?.let { states[it] } ?: return emptyList()
        if (e.state.toDoubleOrNull() == null) return emptyList()
        val unit = e.attrs.str("unit_of_measurement")
        val text = when {
            !cumulative(e) || today == null || today.value <= 0 -> value(e)
            today.sinceMidnight -> format(today.value, unit) + " " + tr("aujourd'hui")
            // Compteur relevé une fois par jour (Hydro-Québec) : on montre ce que le dernier relevé a ajouté.
            else -> "+" + format(today.value, unit) + " · " + tr("dernier relevé")
        }
        return listOf(Present.Chip(R.drawable.ic_bolt, text, 0, "energy"))
    }

    /** Consommation récente d'un compteur : depuis minuit s'il est relevé souvent, sinon l'ajout du dernier relevé. */
    class Recent(val value: Double, val sinceMidnight: Boolean)

    /**
     * [points] : historique des deux derniers jours. Un compteur qui a changé au moins trois fois depuis [midnight]
     * est suivi en continu : on donne sa hausse depuis minuit. Sinon (un relevé par jour), la hausse du dernier relevé.
     */
    fun recent(points: List<Point>, midnight: Long): Recent? {
        val steps = ArrayList<Point>() // valeurs distinctes successives
        for (p in points) if (steps.isEmpty() || steps.last().v != p.v) steps.add(p)
        if (steps.size < 2) return null
        val today = steps.filter { it.t >= midnight }
        if (today.size >= 3) {
            val before = steps.lastOrNull { it.t < midnight } ?: today.first()
            return Recent(maxOf(0.0, steps.last().v - before.v), true)
        }
        return Recent(maxOf(0.0, steps.last().v - steps[steps.size - 2].v), false)
    }

    fun format(v: Double, unit: String) =
        Present.num(if (kotlin.math.abs(v) >= 100) Math.round(v).toDouble() else Math.round(v * 10) / 10.0) + (if (unit.isEmpty()) "" else " $unit")

    /** Hausse d'un compteur sur la période couverte par [points] (zéro s'il a été remis à zéro). */
    fun increase(points: List<Point>): Double? = if (points.size < 2) null else maxOf(0.0, points.last().v - points.first().v)

    /** Historique de Home Assistant (/api/history/period, réponse allégée) → points (temps, valeur). */
    fun series(json: String?): List<Point> {
        val outer = try { JSONArray(json ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        val arr = outer.optJSONArray(0) ?: return emptyList()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val out = ArrayList<Point>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = o.optString("state").toDoubleOrNull() ?: continue
            val t = try { iso.parse(o.optString("last_changed").replace(Regex("\\.\\d+"), "").replace("Z", "+00:00"))?.time } catch (_: Exception) { null } ?: continue
            out.add(Point(t, v))
        }
        return out
    }

    /**
     * Consommation par heure d'un compteur cumulatif : pour chaque heure de [from] à [to], la hausse du compteur.
     * Une baisse (remise à zéro du compteur) compte pour zéro.
     */
    fun hourly(points: List<Point>, from: Long, to: Long): List<Point> {
        if (points.isEmpty()) return emptyList()
        val hour = 3_600_000L
        val out = ArrayList<Point>()
        var t = from - from % hour
        var i = 0
        var last = points.first().v
        while (t < to) {
            val start = last
            while (i < points.size && points[i].t < t + hour) { last = points[i].v; i++ }
            out.add(Point(t, maxOf(0.0, last - start)))
            t += hour
        }
        return out
    }
}

/** Petit graphique : courbe (puissance) ou barres (consommation par heure), avec le maximum et les heures en repère. */
class ChartView(ctx: Context) : View(ctx) {
    private var points: List<Energy.Point> = emptyList()
    private var bars = false
    private var unit = ""
    private var from = 0L
    private var to = 0L
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2.5f); strokeJoin = Paint.Join.ROUND; color = Skin.accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Skin.accent }
    private val grid = Paint().apply { color = Skin.fg(0x1F); strokeWidth = ctx.dpf(1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.fg(0x99); textSize = ctx.dpf(12f) }

    fun set(points: List<Energy.Point>, bars: Boolean, unit: String, from: Long, to: Long) {
        this.points = points; this.bars = bars; this.unit = unit; this.from = from; this.to = to
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val padL = context.dpf(4f); val padB = context.dpf(20f); val padT = context.dpf(18f)
        val w = width - padL * 2; val h = height - padB - padT
        if (w <= 0 || h <= 0 || to <= from) return
        val max = (points.maxOfOrNull { it.v } ?: 0.0).let { if (it <= 0) 1.0 else it }
        fun x(t: Long) = padL + w * (t - from).toFloat() / (to - from)
        fun y(v: Double) = padT + h * (1f - (v / max).toFloat().coerceIn(0f, 1f))
        c.drawLine(padL, padT, padL + w, padT, grid)
        c.drawLine(padL, padT + h, padL + w, padT + h, grid)
        c.drawText(Present.num(Math.round(max * 10) / 10.0) + " " + unit, padL, padT - context.dpf(5f), text)
        // Repères d'heure toutes les six heures
        val fmt = SimpleDateFormat(if (I18n.english) "ha" else "H 'h'", I18n.locale)
        var t = from - from % 3_600_000L + 3_600_000L
        val cal = java.util.Calendar.getInstance()
        while (t < to) {
            cal.timeInMillis = t
            if (cal.get(java.util.Calendar.HOUR_OF_DAY) % 6 == 0) {
                c.drawLine(x(t), padT, x(t), padT + h, grid)
                val s = fmt.format(Date(t)).lowercase(I18n.locale)
                c.drawText(s, x(t) - text.measureText(s) / 2, height - context.dpf(4f), text)
            }
            t += 3_600_000L
        }
        if (points.isEmpty()) return
        if (bars) {
            val bw = w / maxOf(1, points.size) * 0.7f
            for (p in points) if (p.v > 0) c.drawRoundRect(x(p.t) + bw * 0.2f, y(p.v), x(p.t) + bw * 1.2f, padT + h, context.dpf(3f), context.dpf(3f), fill)
        } else {
            // Courbe en escalier : un capteur garde sa valeur jusqu'au changement suivant.
            val path = Path()
            path.moveTo(x(maxOf(from, points.first().t)), y(points.first().v))
            for (i in 1 until points.size) {
                path.lineTo(x(points[i].t), y(points[i - 1].v))
                path.lineTo(x(points[i].t), y(points[i].v))
            }
            path.lineTo(x(to), y(points.last().v))
            c.drawPath(path, line)
        }
    }
}
