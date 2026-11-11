package com.homepanel.kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Petit indicateur de batterie de la tablette (icône + pourcentage), dessiné à la main. */
class BatteryView(ctx: Context) : View(ctx) {
    var level = -1
        private set
    var charging = false
        private set
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.6f); color = Skin.fg(0xCC) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dpf(15); color = Skin.fg(0xE6); typeface = Skin.medium
    }
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.ink }
    private val body = RectF()
    private val inner = RectF()
    private val path = Path()
    private val bw = ctx.dpf(26); private val bh = ctx.dpf(13); private val gap = ctx.dpf(6)

    fun set(level: Int, charging: Boolean) {
        if (level == this.level && charging == this.charging) return
        this.level = level; this.charging = charging
        contentDescription = "$level %"
        requestLayout(); invalidate()
    }

    private fun label() = if (level < 0) "" else "$level %"

    override fun onMeasure(w: Int, h: Int) {
        val tw = text.measureText(label())
        setMeasuredDimension((tw + gap + bw + context.dpf(3)).toInt() + 1, (maxOf(bh, text.textSize) + context.dpf(4)).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (level < 0) return
        val cy = height / 2f
        val lbl = label()
        val tw = text.measureText(lbl)
        c.drawText(lbl, 0f, cy - (text.ascent() + text.descent()) / 2f, text)
        val x = tw + gap
        body.set(x, cy - bh / 2f, x + bw, cy + bh / 2f)
        c.drawRoundRect(body, context.dpf(3), context.dpf(3), stroke)
        // Borne +
        fill.color = Skin.fg(0xCC)
        c.drawRoundRect(RectF(body.right + context.dpf(1), cy - bh / 5f, body.right + context.dpf(3), cy + bh / 5f), 2f, 2f, fill)
        // Remplissage : vert en charge, rouge sous 20 %, blanc sinon
        val p = context.dpf(2.4f)
        inner.set(body.left + p, body.top + p, body.left + p + (bw - 2 * p) * level.coerceIn(0, 100) / 100f, body.bottom - p)
        fill.color = when { charging -> 0xFF34C759.toInt(); level < 20 -> 0xFFFF453A.toInt(); else -> Skin.fg(0xE6) }
        c.drawRoundRect(inner, context.dpf(1.5f), context.dpf(1.5f), fill)
        if (charging) {
            // Éclair au centre
            val cx = body.centerX(); val s = bh * 0.42f
            path.reset()
            path.moveTo(cx + s * 0.15f, cy - s); path.lineTo(cx - s * 0.55f, cy + s * 0.12f); path.lineTo(cx - s * 0.02f, cy + s * 0.12f)
            path.lineTo(cx - s * 0.15f, cy + s); path.lineTo(cx + s * 0.55f, cy - s * 0.12f); path.lineTo(cx + s * 0.02f, cy - s * 0.12f); path.close()
            bolt.color = Skin.onPrimary
            c.drawPath(path, bolt)
        }
    }
}
