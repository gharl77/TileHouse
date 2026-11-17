package com.homepanel.kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Écran de veille « radar » : un balayage lent où chaque pièce qui a quelque chose d'allumé ou d'ouvert est un point,
 * avec l'heure, la météo et les voyants de la maison à droite. Les points sont répartis autour du cercle dans l'ordre
 * des pièces : ce n'est pas un plan.
 */
object Radar {
    /** Une pièce à signaler : [alert] si une porte ou une fenêtre y est ouverte, ou une serrure déverrouillée. */
    class Blip(val name: String, val alert: Boolean, val angle: Float, val dist: Float)
    class Scan(val blips: List<Blip>, val lights: Int, val open: Int, val unlocked: Int, val locks: Int)

    /** Angle (degrés, 0 = en haut, sens horaire) et distance au centre (0 à 1) de la pièce [index] parmi [count]. */
    fun place(index: Int, count: Int): Pair<Float, Float> =
        ((360f * index / maxOf(1, count) + 24f) % 360f) to (0.42f + 0.44f * ((index * 3) % 5) / 4f)

    fun scan(rooms: List<Room>, states: Map<String, EntityState>): Scan {
        val blips = ArrayList<Blip>()
        var lights = 0; var open = 0; var unlocked = 0; var locks = 0
        for ((i, room) in rooms.withIndex()) {
            val n = HomeCare.night(room, states)
            lights += n.lights.size; open += n.openings.size; unlocked += n.locks.size
            locks += room.entities.count { Present.domain(it.id) == "lock" && states[it.id] != null }
            if (n.clear) continue
            val (angle, dist) = place(i, rooms.size)
            blips.add(Blip(room.name, n.openings.isNotEmpty() || n.locks.isNotEmpty(), angle, dist))
        }
        return Scan(blips, lights, open, unlocked, locks)
    }

    /** Voyants sous la météo : texte et niveau (0 normal, 1 allumé, 2 à surveiller). */
    fun lines(s: Scan): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        if (s.lights > 0) out.add(Present.plural(s.lights, "1 " + tr("lumière allumée"), tr("lumières allumées")) to 1)
        if (s.open > 0) out.add(Present.plural(s.open, "1 " + tr("porte ou fenêtre ouverte"), tr("portes ou fenêtres ouvertes")) to 2)
        if (s.unlocked > 0) out.add(Present.plural(s.unlocked, "1 " + tr("serrure déverrouillée"), tr("serrures déverrouillées")) to 2)
        else if (s.locks > 0) out.add(tr("Serrures verrouillées") to 0)
        if (out.isEmpty()) out.add(tr("Tout est calme") to 0)
        return out
    }

    /** Lignes sous les voyants : au plus deux matchs (« MTL 2 – 1 TOR · 3e 12:04 ») puis ce qui joue (« ♪ titre »). */
    fun extras(games: List<Scores.Game>, playing: String): List<String> =
        games.take(2).map { g -> g.away.abbr + " " + Scores.score(g) + " " + g.home.abbr + "  ·  " + Scores.status(g) } + listOfNotNull(playing.takeIf { it.isNotBlank() }?.let { "♪  $it" })

    /** Éclat d'un point selon le temps écoulé depuis le passage du balayage : vif juste après, puis il s'estompe. */
    fun glow(blipAngle: Float, sweepAngle: Float): Float {
        val behind = ((sweepAngle - blipAngle) % 360f + 360f) % 360f
        return 0.30f + 0.70f * (1f - behind / 360f)
    }
}

class RadarView(ctx: Context) : View(ctx) {
    private var time = ""
    private var date = ""
    private var weather = ""
    private var scan = Radar.Scan(emptyList(), 0, 0, 0, 0)
    private var lines: List<Pair<String, Int>> = emptyList()
    /** Sous les voyants : pointage des matchs suivis et ce qui joue. */
    private var extras: List<String> = emptyList()
    /** Décalage anti-marquage, changé chaque minute par l'écran de veille. */
    var shiftX = 0f
    var shiftY = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1); color = RING }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Typeface.MONOSPACE
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)

    fun set(time: String, date: String, weather: String, scan: Radar.Scan, extras: List<String> = emptyList()) {
        this.time = time; this.date = date; this.weather = weather; this.scan = scan; lines = Radar.lines(scan); this.extras = extras
        invalidate()
    }

    private fun alpha(color: Int, k: Float) = (color and 0x00FFFFFF) or ((255 * k.coerceIn(0f, 1f)).toInt() shl 24)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        c.translate(shiftX, shiftY)
        val r = h * 0.36f
        val cx = w * 0.30f; val cy = h / 2f
        line.color = RING
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3f, line)
        c.drawLine(cx - r, cy, cx + r, cy, line); c.drawLine(cx, cy - r, cx, cy + r, line)

        // Balayage : un tour en douze secondes, avec une traîne qui s'estompe derrière la ligne.
        val sweep = (SystemClock.uptimeMillis() % TURN_MS) * 360f / TURN_MS
        for (k in 0 until 10) {
            fill.color = alpha(CYAN, 0.10f * (1f - k / 10f))
            c.drawArc(cx - r, cy - r, cx + r, cy + r, sweep - 90f - (k + 1) * 4f, 4f, true, fill)
        }
        val rad = Math.toRadians((sweep - 90f).toDouble())
        line.color = alpha(CYAN, 0.8f)
        c.drawLine(cx, cy, cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat(), line)

        text.typeface = sans; text.textSize = context.dpf(13); text.textAlign = Paint.Align.LEFT
        for (b in scan.blips) {
            val a = Math.toRadians((b.angle - 90f).toDouble())
            val x = cx + r * b.dist * cos(a).toFloat(); val y = cy + r * b.dist * sin(a).toFloat()
            val g = Radar.glow(b.angle, sweep)
            val color = if (b.alert) RED else AMBER
            fill.color = alpha(color, g); c.drawCircle(x, y, context.dpf(4.5f), fill)
            text.color = alpha(color, 0.35f + 0.5f * g)
            // Le nom reste à l'intérieur du cercle : à gauche du point dans la moitié droite.
            if (x > cx) { text.textAlign = Paint.Align.RIGHT; c.drawText(b.name, x - context.dpf(9), y + context.dpf(4), text) }
            else { text.textAlign = Paint.Align.LEFT; c.drawText(b.name, x + context.dpf(9), y + context.dpf(4), text) }
        }

        val right = w * 0.93f
        text.textAlign = Paint.Align.RIGHT
        text.typeface = mono; text.color = TEXT; text.textSize = h * 0.20f
        var y = cy - h * 0.06f
        c.drawText(time, right, y, text)
        text.typeface = sans; text.color = FAINT; text.textSize = context.dpf(20)
        y += context.dpf(34); c.drawText(date, right, y, text)
        if (weather.isNotEmpty()) { text.color = TEXT; text.textSize = context.dpf(22); y += context.dpf(44); c.drawText(weather, right, y, text) }
        text.typeface = mono; text.textSize = context.dpf(15)
        y += context.dpf(22)
        for ((label, level) in lines) {
            text.color = when (level) { 2 -> RED; 1 -> AMBER; else -> alpha(CYAN, 0.7f) }
            y += context.dpf(26); c.drawText(label.uppercase(I18n.locale), right, y, text)
        }
        if (extras.isNotEmpty()) {
            text.typeface = sans; text.color = TEXT; text.textSize = context.dpf(17)
            y += context.dpf(14)
            val room = w * 0.93f - (cx + r) - context.dpf(30)
            for (e in extras) { y += context.dpf(28); c.drawText(android.text.TextUtils.ellipsize(e, text, room, android.text.TextUtils.TruncateAt.END).toString(), right, y, text) }
        }
        if (isShown) postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        const val TURN_MS = 12_000L
        /** Dix images par seconde suffisent pour un balayage lent, et ménagent la tablette. */
        const val FRAME_MS = 100L
        const val RING = 0xFF12303A.toInt()
        const val CYAN = 0xFF3FD0E0.toInt()
        const val AMBER = 0xFFD9961B.toInt()
        const val RED = 0xFFE05A5A.toInt()
        const val TEXT = 0xFF7FB8C4.toInt()
        const val FAINT = 0xFF557784.toInt()
    }
}
