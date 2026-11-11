package com.homepanel.kiosk

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun roundRect(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

const val WHITE_TILE = 0xF5FFFFFF.toInt()
const val GLASS = 0x73303034
const val GLASS_LIGHT = 0x40FFFFFF

object Wallpapers {
    val keys = listOf("nuit", "aube", "ocean", "foret", "graphite")
    val names get() = listOf(tr("Nuit"), tr("Aube"), tr("Océan"), tr("Forêt"), "Graphite")

    fun colorsOf(key: String): IntArray = colors(key)

    private fun colors(key: String): IntArray = when (key) {
        "aube" -> intArrayOf(0xFF2E2440.toInt(), 0xFF5B3A55.toInt(), 0xFF8A5048.toInt())
        "ocean" -> intArrayOf(0xFF0F3A5F.toInt(), 0xFF0B2236.toInt(), 0xFF07121D.toInt())
        "foret" -> intArrayOf(0xFF1F3B2D.toInt(), 0xFF12241B.toInt(), 0xFF0A140F.toInt())
        "graphite" -> intArrayOf(0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt(), 0xFF0E0E10.toInt())
        else -> intArrayOf(0xFF2B3A55.toInt(), 0xFF141C2B.toInt(), 0xFF080B12.toInt())
    }

    fun drawable(key: String) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors(key))

    /** Fond de l'app selon la skin (Papier : papier clair teinté par le fond choisi). */
    fun skinned(key: String) = GradientDrawable(GradientDrawable.Orientation.TL_BR, Skin.bgColors(colors(key)))
}

fun Context.label(size: Float, color: Int = Skin.ink, bold: Boolean = false, text: String = "") = TextView(this).apply {
    textSize = size * Skin.textScale
    setTextColor(color)
    if (bold) typeface = Skin.bold
    this.text = text
}

fun Context.roundIcon(iconRes: Int, sizeDp: Int, bg: Int, tint: Int, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(tint)
    background = oval(bg)
    val pad = dp(sizeDp) / 4
    setPadding(pad, pad, pad, pad)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { setMargins(dp(10), 0, dp(10), 0) }
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.pill(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 15f * Skin.textScale
    typeface = Skin.medium
    setPadding(dp(18), dp(9), dp(18), dp(9))
    stylePill(this, selected)
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
}

fun stylePill(tv: TextView, selected: Boolean) {
    val (bg, fg) = Skin.pill(tv.context, selected)
    tv.background = bg
    tv.setTextColor(fg)
}

private val GLOW = intArrayOf(0x14, 0x22, 0x40)

/** Tuile d'accessoire dessinée à la main (aucune image, très légère). L'apparence vient de la skin ([TileLook]). */
class TileView(ctx: Context) : View(ctx) {
    private var name = ""
    private var status = ""
    private var active = false
    private var accent = Color.WHITE
    private var icon: Drawable? = null
    private var iconRes = 0
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dpf(Skin.nameSize); typeface = Skin.medium
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1) }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dpf(Skin.statusSize) }
    private val rect = RectF()
    private val inner = RectF()
    private var look: TileLook? = null
    private val radius = ctx.dpf(Skin.tileRadius)
    private val pad = ctx.dpf(Skin.tilePad)
    private val iconD = ctx.dpf(Skin.iconD)
    private val textGap = ctx.dpf(3)

    init { isClickable = true; isLongClickable = true }

    fun bind(name: String, status: String, active: Boolean, unavailable: Boolean, accent: Int, iconRes: Int) {
        this.name = name; this.status = status; this.active = active; this.accent = accent
        look = Skin.tile(active, accent)
        tooltipText = name // nom complet au survol (souris) 
        if (iconRes != this.iconRes) {
            this.iconRes = iconRes
            icon = context.getDrawable(iconRes)?.mutate()
        }
        alpha = if (unavailable) 0.55f else 1f
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed == isPressed) { super.setPressed(pressed); return }
        super.setPressed(pressed)
        val s = if (pressed) 0.95f else 1f
        animate().scaleX(s).scaleY(s).setDuration(90).start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val cy = h / 2f
        val cx = pad + iconD / 2f
        val tx = pad + iconD + context.dpf(12)
        val k = look ?: return
        // Fond, lueur intérieure, contour ou simple ligne : selon la skin (voir TileLook)
        if (k.fill != 0) { bg.color = k.fill; c.drawRoundRect(rect, radius, radius, bg) }
        if (k.glow != 0) for ((i, al) in GLOW.withIndex()) {
            stroke.strokeWidth = context.dpf(6 - i * 2); stroke.color = (k.glow and 0x00FFFFFF) or (al shl 24)
            inner.set(rect); inner.inset(context.dpf(3 - i), context.dpf(3 - i))
            c.drawRoundRect(inner, radius, radius, stroke)
        }
        if (k.stroke != 0) {
            stroke.strokeWidth = context.dpf(k.strokeDp); stroke.color = k.stroke
            inner.set(rect); inner.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
            c.drawRoundRect(inner, radius, radius, stroke)
        }
        if (k.underline != 0) {
            stroke.strokeWidth = context.dpf(1); stroke.color = k.underline
            c.drawLine(context.dpf(4), h - stroke.strokeWidth, w - context.dpf(4), h - stroke.strokeWidth, stroke)
        }
        if (k.iconCircle != 0) { circle.color = k.iconCircle; c.drawCircle(cx, cy, iconD / 2f, circle) }
        icon?.let {
            val s = (iconD * k.iconScale).toInt()
            it.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
            it.setTint(k.iconTint)
            it.draw(c)
        }
        if (k.dot != 0) { circle.color = k.dot; c.drawCircle(w - pad, cy, context.dpf(4), circle) }
        val avail = max(0f, w - tx - pad - if (k.dot != 0) context.dpf(14) else 0f)
        k.nameFace?.let { namePaint.typeface = it }
        namePaint.color = k.name
        statusPaint.color = k.status
        val n = TextUtils.ellipsize(name, namePaint, avail, TextUtils.TruncateAt.END).toString()
        val s = TextUtils.ellipsize(status, statusPaint, avail, TextUtils.TruncateAt.END).toString()
        val nh = -namePaint.ascent()
        // Nom trop long : on l'écrit sur deux lignes plutôt que de le couper.
        if (n != name && avail > 0) {
            val lay = android.text.StaticLayout.Builder.obtain(name, 0, name.length, namePaint, avail.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).setLineSpacing(0f, 0.92f).build()
            val sh = if (s.isEmpty()) 0f else -statusPaint.ascent() + textGap
            val top = cy - (lay.height + sh) / 2f
            c.save(); c.translate(tx, top); lay.draw(c); c.restore()
            if (s.isNotEmpty()) c.drawText(s, tx, top + lay.height + sh, statusPaint)
            return
        }
        if (s.isEmpty()) {
            // Une seule ligne (ex. tuile de pièce) : nom centré verticalement
            c.drawText(n, tx, cy + (nh - namePaint.descent()) / 2f, namePaint)
            return
        }
        val sh = -statusPaint.ascent()
        val top = cy - (nh + textGap + sh) / 2f
        c.drawText(n, tx, top + nh, namePaint)
        c.drawText(s, tx, top + nh + textGap + sh, statusPaint)
    }
}

/** Grille simple à colonnes automatiques selon la largeur. */
class TileGrid(ctx: Context) : ViewGroup(ctx) {
    private val minW = ctx.dp(Skin.tileMinW)
    private val tileH = ctx.dp(Skin.tileH)
    private val gap = ctx.dp(Skin.tileGap)
    private var cols = 1
    private var tileW = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cols = max(1, (w + gap) / (minW + gap))
        tileW = (w - gap * (cols - 1)) / cols
        val cw = MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        val rows = (childCount + cols - 1) / cols
        setMeasuredDimension(w, if (rows == 0) 0 else rows * tileH + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = (i % cols) * (tileW + gap)
            val y = (i / cols) * (tileH + gap)
            getChildAt(i).layout(x, y, x + tileW, y + tileH)
        }
    }
}

/** Grand curseur vertical façon HomeKit (glisser ou toucher). */
class VerticalSlider(ctx: Context) : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null
    var dragging = false
        private set

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.sliderTrack; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.sliderFill; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val rect = RectF()
    private val radius = ctx.dpf(Skin.sliderRadius)
    private val slop = ctx.dpf(6)
    private var startY = 0f
    private var startV = 0f
    private var moved = false

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val sc = c.saveLayer(rect, null)
        // Forme arrondie comme masque, puis le fond et le niveau peints dedans, chacun avec sa propre opacité.
        c.drawRoundRect(rect, radius, radius, mask)
        val level = height * (1f - value / 100f)
        c.drawRect(0f, 0f, width.toFloat(), level, track)
        c.drawRect(0f, level, width.toFloat(), height.toFloat(), fill)
        c.restoreToCount(sc)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; moved = false
                startY = ev.y; startV = value
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(ev.y - startY) > slop) moved = true
                if (moved) {
                    value = startV + (startY - ev.y) / height * 100f
                    onChange?.invoke(value.roundToInt())
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    value = (1f - ev.y / height) * 100f
                    onChange?.invoke(value.roundToInt())
                }
                dragging = false
                onRelease?.invoke(value.roundToInt())
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

/** Pastille de l'en-tête (icône + texte). */
fun Context.chipView(c: Present.Chip) = TextView(this).apply {
    val (bg, fg) = Skin.chip(this@chipView, c.level)
    text = c.text
    textSize = 15f * Skin.textScale
    setTextColor(fg)
    typeface = Skin.medium
    gravity = android.view.Gravity.CENTER_VERTICAL
    if (c.icon != 0) getDrawable(c.icon)?.mutate()?.let { d ->
        val s = dp((if (c.iconTint != null) 22 else 18) * Skin.textScale)
        d.setBounds(0, 0, s, s)
        d.setTint(c.iconTint ?: fg)
        setCompoundDrawables(d, null, null, null)
    }
    compoundDrawablePadding = dp(6)
    val k = Skin.textScale
    setPadding(dp(10 * k), dp(6 * k), dp(14 * k), dp(6 * k))
    background = bg
}

/** Disposition en lignes qui passent à la ligne suivante si nécessaire. */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    private val hGap = ctx.dp(8)
    private val vGap = ctx.dp(8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val childSpec = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
        var x = 0; var y = 0; var rowH = 0; var usedW = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap
            usedW = max(usedW, x - hGap)
            rowH = max(rowH, c.measuredHeight)
        }
        val h = if (childCount == 0) 0 else y + rowH
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) maxW else usedW
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = max(rowH, c.measuredHeight)
        }
    }
}
