package com.homepanel.kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Calculs des contrôles dessinés (store, rideau, thermostat), séparés du dessin pour être vérifiés par les tests. */
object ControlMath {
    /** Part de la fenêtre couverte (0 à 1) pour une position de 0 (fermé) à 100 (ouvert) ; il reste toujours un peu de tissu replié. */
    fun covered(position: Float, folded: Float) = folded + (1f - folded) * (1f - position.coerceIn(0f, 100f) / 100f)

    /** Position d'un store pour un doigt à la hauteur [y] dans une fenêtre de [top] à [bottom] : le bas du store suit le doigt. */
    fun blindPosition(y: Float, top: Float, bottom: Float) = (100f * (1f - (y - top) / (bottom - top))).coerceIn(0f, 100f)

    /** Position d'un rideau pour un doigt en [x] : le bord du rideau suit le doigt, des deux côtés du centre. */
    fun curtainPosition(x: Float, left: Float, right: Float): Float {
        val half = (right - left) / 2f
        return (100f * abs(x - (left + half)) / half).coerceIn(0f, 100f)
    }

    const val ARC_START = 125f
    const val ARC_SWEEP = 110f

    /** Part de l'arc (0 en bas, 1 en haut) pour un doigt en ([dx], [dy]) depuis le centre ; null hors de la moitié gauche. */
    fun arcFraction(dx: Float, dy: Float): Float? {
        var a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (a < 0) a += 360f
        if (a < ARC_START - 25f || a > ARC_START + ARC_SWEEP + 25f) return null
        return ((a - ARC_START) / ARC_SWEEP).coerceIn(0f, 1f)
    }

    /** Température arrondie au pas du thermostat, dans ses limites. */
    fun snap(v: Double, step: Double, min: Double, max: Double) = (Math.round(v / step) * step).coerceIn(min, max)

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).roundToInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Fenêtre dessinée avec son store ou son rideau à la position réelle : le store descend du haut, le rideau se ferme
 * des deux côtés. On règle la position en glissant le doigt (le bord du tissu le suit) quand [draggable].
 */
class CoverView(ctx: Context, private val curtain: Boolean) : View(ctx) {
    /** 0 = fermé, 100 = ouvert. */
    var value = 0f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    var draggable = true
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null
    var dragging = false
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val win = RectF()
    private val r = RectF()
    private val clip = Path()
    private val frameW = ctx.dpf(6)
    private val railH = ctx.dpf(12)

    private companion object {
        const val SKY_TOP = 0xFF7FB2DD.toInt()
        const val SKY_BOTTOM = 0xFFD9ECF8.toInt()
        const val FRAME = 0xFFF2F2F4.toInt()
        const val RAIL = 0xFF8C8C92.toInt()
        const val LINEN = 0xFFDCCBB2.toInt()
        const val LINEN_FOLD = 0xFFB9A486.toInt()
        const val SHADE = 0xFFECEAE4.toInt()
        const val SHADE_LINE = 0xFFC9C6BE.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val side = if (curtain) context.dpf(14) else context.dpf(4)
        win.set(side, railH + context.dpf(4), w - side, h - context.dpf(4))
    }

    override fun onDraw(c: Canvas) {
        val rad = context.dpf(8)
        // Le ciel derrière la vitre, puis le cadre et ses croisillons.
        p.shader = LinearGradient(0f, win.top, 0f, win.bottom, SKY_TOP, SKY_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRoundRect(win, rad, rad, p)
        p.shader = null
        line.color = FRAME; line.strokeWidth = frameW / 2
        c.drawLine(win.centerX(), win.top, win.centerX(), win.bottom, line)
        c.drawLine(win.left, win.centerY(), win.right, win.centerY(), line)
        line.strokeWidth = frameW
        r.set(win); r.inset(frameW / 2, frameW / 2)
        c.drawRoundRect(r, rad, rad, line)

        c.save()
        if (curtain) drawCurtain(c) else drawBlind(c)
        c.restore()
    }

    private fun drawBlind(c: Canvas) {
        val bottom = win.top + win.height() * ControlMath.covered(value, 0.07f)
        clip.rewind(); clip.addRoundRect(win, context.dpf(8), context.dpf(8), Path.Direction.CW)
        c.save(); c.clipPath(clip)
        p.color = SHADE
        c.drawRect(win.left, win.top, win.right, bottom, p)
        // Lamelles : un trait tous les 14 dp, comptés depuis le bas pour qu'elles descendent avec le store.
        line.color = SHADE_LINE; line.strokeWidth = context.dpf(1.5f)
        val gap = context.dpf(14)
        var y = bottom - gap
        while (y > win.top) { c.drawLine(win.left, y, win.right, y, line); y -= gap }
        c.restore()
        // Barre du bas et caisson du haut.
        p.color = RAIL
        c.drawRoundRect(win.left - context.dpf(2), bottom - context.dpf(5), win.right + context.dpf(2), bottom + context.dpf(3), context.dpf(3), context.dpf(3), p)
        c.drawRoundRect(0f, 0f, width.toFloat(), railH, context.dpf(4), context.dpf(4), p)
    }

    private fun drawCurtain(c: Canvas) {
        val half = (width - context.dpf(8)) / 2f
        val panel = half * ControlMath.covered(value, 0.16f)
        val top = railH / 2
        val bottom = height.toFloat()
        for (side in 0..1) {
            val x0 = if (side == 0) context.dpf(4) else width - context.dpf(4) - panel
            p.color = LINEN
            c.drawRoundRect(x0, top, x0 + panel, bottom, context.dpf(5), context.dpf(5), p)
            // Plis : toujours le même nombre, donc serrés quand le rideau est ouvert.
            line.color = LINEN_FOLD; line.strokeWidth = context.dpf(2)
            val folds = 7
            for (i in 1 until folds) { val x = x0 + panel * i / folds; c.drawLine(x, top + context.dpf(6), x, bottom - context.dpf(5), line) }
        }
        // Tringle et embouts.
        p.color = RAIL
        c.drawRoundRect(0f, top - context.dpf(3), width.toFloat(), top + context.dpf(3), context.dpf(3), context.dpf(3), p)
        c.drawCircle(context.dpf(4), top, context.dpf(5), p)
        c.drawCircle(width - context.dpf(4), top, context.dpf(5), p)
    }

    private fun at(ev: MotionEvent) =
        if (curtain) ControlMath.curtainPosition(ev.x, context.dpf(4), width - context.dpf(4)) else ControlMath.blindPosition(ev.y, win.top, win.bottom)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!draggable) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); value = at(ev); onChange?.invoke(value.roundToInt()) }
            MotionEvent.ACTION_MOVE -> { value = at(ev); onChange?.invoke(value.roundToInt()) }
            MotionEvent.ACTION_UP -> { dragging = false; onRelease?.invoke(value.roundToInt()) }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

/**
 * Arc de consigne d'un thermostat, sur la gauche du cadran : fin en haut, épais en bas, du rouge orangé au jaune.
 * La partie remplie monte avec la consigne ; on la règle en glissant le doigt le long de l'arc.
 */
class ThermostatDial(ctx: Context) : View(ctx) {
    /** Part remplie de l'arc, 0 (minimum) à 1 (maximum) ; null quand il n'y a pas de consigne. */
    var fraction: Float? = null
        set(v) { field = v?.coerceIn(0f, 1f); invalidate() }
    /** Couleurs du bas et du haut de l'arc (chauffage : rouge orangé → jaune ; climatisation : bleus). */
    var low = HEAT_LOW
    var high = HEAT_HIGH
    /** Arc éteint (thermostat arrêté) : seulement la piste. */
    var lit = true
        set(v) { field = v; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null
    var dragging = false
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val thick = ctx.dpf(18)
    private val thin = ctx.dpf(5)

    companion object {
        const val HEAT_LOW = 0xFFFF4A1C.toInt()
        const val HEAT_HIGH = 0xFFFFC233.toInt()
        const val COOL_LOW = 0xFF0A6CFF.toInt()
        const val COOL_HIGH = 0xFF8FD8FF.toInt()
        private const val STEPS = 48
    }

    private fun radius() = minOf(width, height) / 2f - thick

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val rad = radius()
        oval.set(cx - rad, cy - rad, cx + rad, cy + rad)
        val f = if (lit) fraction ?: 0f else 0f
        val seg = ControlMath.ARC_SWEEP / STEPS
        for (i in 0 until STEPS) {
            val t = (i + 0.5f) / STEPS
            p.strokeWidth = thick + (thin - thick) * t
            val on = t <= f
            p.color = if (on) ControlMath.mix(low, high, t) else Skin.fg(0x2E)
            // Léger recouvrement pour ne pas voir de joints entre les segments (pas sur la piste, translucide : il se verrait).
            c.drawArc(oval, ControlMath.ARC_START + i * seg, seg + if (on) 0.6f else 0f, false, p)
        }
        // Bouts arrondis.
        knob.color = if (f > 0f) low else Skin.fg(0x2E)
        end(c, cx, cy, rad, 0f, thick / 2)
        knob.color = if (f >= 1f) high else Skin.fg(0x2E)
        end(c, cx, cy, rad, 1f, thin / 2)
        // Poignée à la consigne.
        if (lit && fraction != null) {
            knob.color = 0xFFFFFFFF.toInt()
            knob.setShadowLayer(context.dpf(4), 0f, context.dpf(1), 0x66000000)
            end(c, cx, cy, rad, f, context.dpf(11))
            knob.clearShadowLayer()
        }
    }

    private fun end(c: Canvas, cx: Float, cy: Float, rad: Float, t: Float, r: Float) {
        val a = Math.toRadians((ControlMath.ARC_START + ControlMath.ARC_SWEEP * t).toDouble())
        c.drawCircle(cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat(), r, knob)
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val dx = ev.x - width / 2f; val dy = ev.y - height / 2f
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Seulement près de l'arc : le centre du cadran reste aux boutons posés dessus.
                val d = hypot(dx, dy) / radius()
                val f = ControlMath.arcFraction(dx, dy)
                if (fraction == null || f == null || d < 0.72f || d > 1.3f) return false
                dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                fraction = f; onChange?.invoke(f)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) ControlMath.arcFraction(dx, dy)?.let { fraction = it; onChange?.invoke(it) }
            MotionEvent.ACTION_UP -> if (dragging) { dragging = false; fraction?.let { onRelease?.invoke(it) } }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
