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
    /** Dégradés fournis, puis la photo personnelle ([PhotoWallpaper]). */
    val keys = listOf("nuit", "aube", "ocean", "foret", "graphite", PhotoWallpaper.KEY, PhotoWallpaper.IMMICH)
    val names get() = listOf(tr("Nuit"), tr("Aube"), tr("Océan"), tr("Forêt"), "Graphite", tr("Ma photo"), tr("Photo Immich du jour"))

    /** « Ma photo » demande une photo enregistrée ; « Photo Immich du jour » demande Immich et un album choisi. */
    fun available(ctx: Context, cfg: ConfigStore, key: String): Boolean = when (key) {
        PhotoWallpaper.KEY -> PhotoWallpaper.exists(ctx)
        PhotoWallpaper.IMMICH -> PhotoFrame.configured && cfg.photoSource.isNotEmpty() && !cfg.demo
        else -> key in keys
    }

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
    private val cut = ctx.dpf(Skin.tileCut)
    private val shape = android.graphics.Path()
    /** Forme de la tuile : coins arrondis, ou coupés si la skin le demande. */
    private fun box(c: Canvas, r: RectF, p: Paint) {
        if (cut > 0f) { cutPath(shape, r.left, r.top, r.right, r.bottom, cut); c.drawPath(shape, p) } else c.drawRoundRect(r, radius, radius, p)
    }
    private val pad = ctx.dpf(Skin.tilePad)
    private val iconD = ctx.dpf(Skin.iconD)
    private val textGap = ctx.dpf(3)

    init { isClickable = true; isLongClickable = true }

    /**
     * Tuile réglable en glissant à gauche ou à droite (lumière à intensité variable) : [slideFrom] donne le niveau
     * de départ (0–100), ou null si la tuile ne se règle pas pour l'instant ; [onSlide] reçoit le niveau au relâchement.
     */
    var slideFrom: (() -> Int?)? = null
    var onSlide: ((Int) -> Unit)? = null
    private var sliding = false
    private var slideV = 0f
    private var slideStart = 0f
    private var downX = 0f
    private var downY = 0f
    /** Le niveau choisi reste affiché un instant, le temps que l'appareil confirme. */
    private var holdUntil = 0L
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()

    companion object {
        /** Vrai pendant qu'une tuile est réglée du doigt : le balayage entre pièces ne doit pas se déclencher. */
        var anySliding = false
        /** Texte du niveau pendant le réglage. */
        fun levelText(v: Int) = if (v <= 0) tr("Éteint") else "$v %"
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val from = slideFrom ?: return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; sliding = false }
            MotionEvent.ACTION_MOVE -> {
                if (!sliding) {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    val start = if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) from() else null
                    if (start != null) {
                        sliding = true; anySliding = true
                        slideStart = start.toFloat(); downX = ev.rawX
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // Annule le toucher et l'appui long en cours : glisser n'allume ni n'ouvre rien.
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL; super.onTouchEvent(c); c.recycle()
                    }
                }
                if (sliding) {
                    slideV = (slideStart + (ev.rawX - downX) / max(1, width) * 100f).coerceIn(0f, 100f)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> if (sliding) {
                sliding = false; anySliding = false
                holdUntil = android.os.SystemClock.uptimeMillis() + 1500
                postInvalidateDelayed(1550)
                invalidate()
                onSlide?.invoke(slideV.roundToInt())
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (sliding) { sliding = false; anySliding = false; invalidate(); return true }
        }
        return super.onTouchEvent(ev)
    }

    /** [level] : niveau (0–100) qui remplit la tuile de gauche à droite, ou null (lumière à intensité variable allumée). */
    private var level: Int? = null

    /** [switchable] : l'appareil s'allume et s'éteint d'un toucher (la skin peut alors dessiner un interrupteur). */
    private var switchable = false

    fun bind(name: String, status: String, active: Boolean, unavailable: Boolean, accent: Int, iconRes: Int, level: Int? = null, switchable: Boolean = false) {
        this.level = level; this.switchable = switchable
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
        if (k.fill != 0) { bg.color = k.fill; box(c, rect, bg) }
        if (k.glow != 0) for ((i, al) in GLOW.withIndex()) {
            stroke.strokeWidth = context.dpf(6 - i * 2); stroke.color = (k.glow and 0x00FFFFFF) or (al shl 24)
            inner.set(rect); inner.inset(context.dpf(3 - i), context.dpf(3 - i))
            box(c, inner, stroke)
        }
        if (k.stroke != 0) {
            stroke.strokeWidth = context.dpf(k.strokeDp); stroke.color = k.stroke
            inner.set(rect); inner.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
            box(c, inner, stroke)
        }
        if (k.underline != 0) {
            stroke.strokeWidth = context.dpf(1); stroke.color = k.underline
            c.drawLine(context.dpf(4), h - stroke.strokeWidth, w - context.dpf(4), h - stroke.strokeWidth, stroke)
        }
        // Réglage du doigt : le niveau remplit la tuile de gauche à droite.
        // Au repos, le niveau de la lumière remplit discrètement la tuile ; pendant le réglage, il suit le doigt, plus marqué.
        val adjusting = sliding || android.os.SystemClock.uptimeMillis() < holdUntil
        val shown = if (adjusting) slideV else level?.toFloat()
        if (shown != null && shown > 0f) {
            if (cut > 0f) cutPath(clip, 0f, 0f, w, h, cut) else { clip.rewind(); clip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) }
            levelPaint.color = (accent and 0x00FFFFFF) or (if (adjusting) 0x66000000 else 0x30000000)
            c.save(); c.clipPath(clip); c.drawRect(0f, 0f, w * shown / 100f, h, levelPaint); c.restore()
        }
        val status = if (adjusting) levelText(slideV.roundToInt()) else status
        if (k.iconCircle != 0) { circle.color = k.iconCircle; c.drawCircle(cx, cy, iconD / 2f, circle) }
        if (k.toggle && switchable) {
            // Interrupteur à bascule : le levier est en haut et coloré quand c'est allumé, en bas et gris sinon.
            val bw = iconD * 0.52f; val bh = iconD * 1.05f
            inner.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            circle.color = 0xFF0A121C.toInt(); c.drawRoundRect(inner, context.dpf(4), context.dpf(4), circle)
            stroke.strokeWidth = context.dpf(1); stroke.color = if (active) k.iconTint else 0xFF2A4A66.toInt(); c.drawRoundRect(inner, context.dpf(4), context.dpf(4), stroke)
            val g = context.dpf(2.5f)
            if (active) inner.set(inner.left + g, inner.top + g, inner.right - g, cy) else inner.set(inner.left + g, cy, inner.right - g, inner.bottom - g)
            circle.color = if (active) k.iconTint else 0xFF47657F.toInt(); c.drawRoundRect(inner, context.dpf(3), context.dpf(3), circle)
        } else icon?.let {
            val s = (iconD * k.iconScale).toInt()
            it.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
            it.setTint(k.iconTint)
            it.draw(c)
        }
        if (k.dot != 0) { circle.color = k.dot; c.drawCircle(w - pad, cy, context.dpf(4), circle) }
        val avail = max(0f, w - tx - pad - if (k.dot != 0) context.dpf(14) else 0f)
        k.nameFace?.let { namePaint.typeface = it }
        k.statusFace?.let { statusPaint.typeface = it }
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

    private val lever = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Tableau de bord : une manette dans sa glissière graduée, comme une manette des gaz. */
    private fun drawLever(c: Canvas) {
        val w = width.toFloat(); val h = width * 0.30f
        val top = h / 2; val bottom = height - h / 2
        val y = bottom - (bottom - top) * value / 100f
        val slot = context.dpf(12)
        lever.style = Paint.Style.FILL; lever.color = 0xFF0A121C.toInt()
        rect.set(w / 2 - slot / 2, top - slot / 2, w / 2 + slot / 2, bottom + slot / 2); c.drawRoundRect(rect, slot / 2, slot / 2, lever)
        lever.color = Skin.sliderFill; rect.set(w / 2 - slot / 2, y, w / 2 + slot / 2, bottom + slot / 2); c.drawRoundRect(rect, slot / 2, slot / 2, lever)
        // Graduations tous les 10 %, plus longues à 0, 50 et 100.
        lever.style = Paint.Style.STROKE; lever.strokeWidth = context.dpf(1.5f); lever.color = Skin.fg(0x59)
        for (k in 0..10) {
            val gy = bottom - (bottom - top) * k / 10f; val len = context.dpf(if (k % 5 == 0) 16 else 9)
            c.drawLine(w / 2 - slot - len, gy, w / 2 - slot, gy, lever); c.drawLine(w / 2 + slot, gy, w / 2 + slot + len, gy, lever)
        }
        // Poignée : large, avec trois stries.
        lever.style = Paint.Style.FILL; lever.color = if (value > 0f) Skin.accent else 0xFF47657F.toInt()
        rect.set(w * 0.08f, y - h / 2, w * 0.92f, y + h / 2); c.drawRoundRect(rect, context.dpf(8), context.dpf(8), lever)
        lever.style = Paint.Style.STROKE; lever.strokeWidth = context.dpf(2); lever.color = 0x66000000
        for (k in -1..1) c.drawLine(w * 0.26f, y + k * h * 0.22f, w * 0.74f, y + k * h * 0.22f, lever)
    }

    override fun onDraw(c: Canvas) {
        if (Skin.gauges) { drawLever(c); return }
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

/** Titre de section ; dans le tableau de bord, une étiquette gravée : petites majuscules espacées. */
fun Context.sectionLabel(text: String) = label(17f, Skin.fg(0xE6), true, text).apply {
    if (Skin.gauges) { this.text = text.uppercase(I18n.locale); textSize = 13f; letterSpacing = 0.16f; setTextColor(Skin.mute) }
}

/** Chiffres à sept segments : quels segments (a à g, du haut dans le sens horaire, g au milieu) s'allument pour chaque chiffre. */
object Segments {
    private val DIGITS = arrayOf(0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110, 0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111)
    /** Masque des segments de [c] (bit 0 = a … bit 6 = g) ; 0 pour tout autre caractère. */
    fun mask(c: Char): Int = if (c in '0'..'9') DIGITS[c - '0'] else 0
    /** Largeur de [c] en unités d'un chiffre : le deux-points est étroit. */
    fun width(c: Char): Float = if (c == ':' || c == '.' || c == ' ') 0.42f else 1f
}

/** Heure de l'en-tête. Dans le tableau de bord, elle est dessinée en chiffres à sept segments, les segments éteints restant à peine visibles. */
class ClockText(ctx: Context) : TextView(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        if (!Skin.gauges) { super.onMeasure(wSpec, hSpec); return }
        val h = context.dp(34)
        // Toujours la place de « 88:88 » : la largeur ne dépend pas du moment où l'heure est écrite.
        val units = maxOf(4.42f, text.sumOf { Segments.width(it).toDouble() }.toFloat())
        setMeasuredDimension((units * h * 0.62f).toInt() + context.dp(4), h)
    }

    override fun onDraw(c: Canvas) {
        if (!Skin.gauges) { super.onDraw(c); return }
        val h = height.toFloat()
        val unit = h * 0.62f
        val t = h * 0.11f
        paint.strokeWidth = t
        var x = context.dpf(2)
        for (ch in text) {
            val w = unit * Segments.width(ch)
            if (ch == ':') {
                paint.color = Skin.accent
                c.drawPoint(x + w / 2, h * 0.34f, paint); c.drawPoint(x + w / 2, h * 0.68f, paint)
            } else if (ch in '0'..'9') {
                val l = x + t; val r = x + w - t * 1.4f; val top = t; val mid = h / 2f; val bot = h - t; val g = t * 0.9f
                val m = Segments.mask(ch)
                fun seg(bit: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
                    paint.color = if (m shr bit and 1 == 1) Skin.accent else (Skin.accent and 0x00FFFFFF) or 0x1F000000
                    c.drawLine(x1, y1, x2, y2, paint)
                }
                seg(0, l + g, top, r - g, top); seg(1, r, top + g, r, mid - g); seg(2, r, mid + g, r, bot - g); seg(3, l + g, bot, r - g, bot)
                seg(4, l, mid + g, l, bot - g); seg(5, l, top + g, l, mid - g); seg(6, l + g, mid, r - g, mid)
            }
            x += w
        }
    }
}

/** Pastille de l'en-tête (icône + texte). */
fun Context.chipView(c: Present.Chip) = TextView(this).apply {
    val (bg, fg) = c.color?.let { Skin.chipColored(this@chipView, it) } ?: Skin.chip(this@chipView, c.level)
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
    if (Skin.gauges) {
        // Voyant de tableau de bord : case de largeur fixe, texte gravé en majuscules.
        text = c.text.uppercase(I18n.locale)
        textSize = 11.5f; typeface = Skin.bold; letterSpacing = 0.05f
        gravity = android.view.Gravity.CENTER
        // Cases identiques, comme un panneau de voyants : six par rangée sur la tablette, deux lignes de texte au plus.
        width = dp(184); height = dp(44); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(0f, 0.9f)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }
}

/**
 * Disposition en lignes qui passent à la ligne suivante si nécessaire.
 * Avec [maxRows] et une vue [moreView] (ajoutée en dernier), ce qui dépasse est caché et [moreView] prend
 * la dernière place ; [onOverflow] reçoit le nombre de vues cachées (0 = tout tient, [moreView] est cachée).
 */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    private val hGap = ctx.dp(8)
    private val vGap = ctx.dp(8)
    var maxRows = Int.MAX_VALUE
    var moreView: View? = null
    var onOverflow: ((Int) -> Unit)? = null
    private var hidden = -1
    /** À appeler après avoir remplacé les vues : le prochain calcul rappelle [onOverflow]. */
    fun resetOverflow() { hidden = -1 }
    private val xs = ArrayList<Int>()
    private val ys = ArrayList<Int>()

    /** Place les [count] premières vues ; retourne (nombre placé, hauteur). [reserve] = largeur gardée libre sur la dernière rangée permise. */
    private fun arrange(maxW: Int, count: Int, reserve: Int): Pair<Int, Int> {
        xs.clear(); ys.clear()
        var x = 0; var y = 0; var rowH = 0; var row = 1
        for (i in 0 until count) {
            val c = getChildAt(i)
            val limit = if (row == maxRows) maxW - reserve else maxW
            if (x > 0 && x + c.measuredWidth > limit) {
                if (row == maxRows) return i to y + rowH
                x = 0; y += rowH + vGap; rowH = 0; row++
            }
            xs.add(x); ys.add(y)
            x += c.measuredWidth + hGap
            rowH = max(rowH, c.measuredHeight)
        }
        return count to if (count == 0) 0 else y + rowH
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val childSpec = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) getChildAt(i).measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val more = moreView?.takeIf { it.parent === this && maxRows != Int.MAX_VALUE }
        val count = if (more != null) childCount - 1 else childCount
        var (placed, h) = arrange(maxW, count, 0)
        if (placed < count && more != null) {
            val r = arrange(maxW, count, more.measuredWidth + hGap); placed = r.first; h = max(r.second, more.measuredHeight)
        }
        val nowHidden = count - placed
        if (nowHidden != hidden) { hidden = nowHidden; post { onOverflow?.invoke(nowHidden) } }
        var usedW = 0
        for (i in 0 until placed) usedW = max(usedW, xs[i] + getChildAt(i).measuredWidth)
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) maxW else usedW
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val more = moreView?.takeIf { it.parent === this && maxRows != Int.MAX_VALUE }
        val count = if (more != null) childCount - 1 else childCount
        val placed = xs.size
        for (i in 0 until count) {
            val c = getChildAt(i)
            if (i < placed) c.layout(xs[i], ys[i], xs[i] + c.measuredWidth, ys[i] + c.measuredHeight) else c.layout(0, 0, 0, 0)
        }
        if (more != null) {
            if (placed < count) {
                val x = if (placed == 0) 0 else xs[placed - 1] + getChildAt(placed - 1).measuredWidth + hGap
                val y = if (placed == 0) 0 else ys[placed - 1]
                more.layout(x, y, x + more.measuredWidth, y + more.measuredHeight)
            } else more.layout(0, 0, 0, 0)
        }
    }
}

/** Zone défilante qui prend la hauteur de son contenu, jusqu'à [maxHeight] pixels. */
class MaxHeightScrollView(ctx: Context, private val maxHeight: Int) : android.widget.ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
}
