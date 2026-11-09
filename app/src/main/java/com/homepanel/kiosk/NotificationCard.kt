package com.homepanel.kiosk

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * Notification envoyée par Home Assistant (entité notify de la tablette), affichée au centre
 * de l'écran dans un encadré, par-dessus le tableau de bord.
 *
 * Le message est soit du texte simple, soit un JSON :
 *   {"title": "Lessive", "message": "La laveuse a terminé", "icon": "🧺",
 *    "duration": 30, "color": "#34C759", "image": "camera.sonnette_avant", "sound": true}
 * duration = secondes (0 = jusqu'au toucher « OK »). image = caméra ou entité image de HA.
 */
class Notice(
    val title: String, val message: String, val icon: String, val duration: Int,
    val color: Int, val image: String?, val sound: Boolean,
) {
    companion object {
        private val NAMED = mapOf(
            "red" to 0xFFFF453A.toInt(), "rouge" to 0xFFFF453A.toInt(),
            "orange" to 0xFFFF9F0A.toInt(), "yellow" to 0xFFFFD60A.toInt(), "jaune" to 0xFFFFD60A.toInt(),
            "green" to 0xFF34C759.toInt(), "vert" to 0xFF34C759.toInt(),
            "blue" to 0xFF0A84FF.toInt(), "bleu" to 0xFF0A84FF.toInt(),
            "purple" to 0xFFBF5AF2.toInt(), "violet" to 0xFFBF5AF2.toInt(),
            "amber" to 0xFFFFB300.toInt(), "ambre" to 0xFFFFB300.toInt(),
        )

        fun parse(raw: String): Notice {
            val t = raw.trim()
            val o = if (t.startsWith("{")) try { JSONObject(t) } catch (_: Exception) { null } else null
            if (o == null) return Notice("", t, "💬", 30, 0xFFFFB300.toInt(), null, false)
            val c = o.optString("color").trim().lowercase()
            val color = NAMED[c] ?: try { Color.parseColor(if (c.startsWith("#")) c else "#$c") } catch (_: Exception) { 0xFFFFB300.toInt() }
            return Notice(
                title = o.optString("title"),
                message = o.optString("message", o.optString("text")),
                icon = o.optString("icon").ifEmpty { "💬" },
                duration = o.optInt("duration", 30).coerceIn(0, 3600),
                color = color,
                image = o.optString("image").takeIf { it.contains('.') && !it.contains("://") },
                sound = o.optBoolean("sound", false),
            )
        }
    }
}

/** Couche plein écran (voile sombre + encadré centré). Un toucher sur le voile ou « OK » ferme. */
class NotificationCard(private val act: MainActivity) : FrameLayout(act) {
    private var bar: View? = null
    private var timer: ValueAnimator? = null
    private var bitmap: Bitmap? = null
    var onClosed: (() -> Unit)? = null

    init {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        setOnClickListener { close() }
        elevation = act.dpf(30)
        visibility = View.GONE
    }

    val showing get() = visibility == View.VISIBLE

    fun show(n: Notice) {
        clear()
        val ctx = act
        // Encadré arrondi : contenu (avec marges) puis barre de temps sur toute la largeur.
        val frame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF2202733.toInt()); cornerRadius = ctx.dpf(28)
                setStroke(ctx.dp(1), 0x26FFFFFF)
            }
            isClickable = true // ne ferme pas en touchant l'encadré lui-même
            clipToOutline = true
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(36), ctx.dp(30), ctx.dp(36), 0)
        }
        frame.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // Pastille d'icône colorée
        card.addView(ctx.label(40f, Color.WHITE, text = n.icon).apply {
            gravity = Gravity.CENTER
            background = oval((n.color and 0x00FFFFFF) or 0x33000000)
        }, LinearLayout.LayoutParams(ctx.dp(84), ctx.dp(84)).apply { bottomMargin = ctx.dp(16) })
        if (n.title.isNotEmpty()) card.addView(ctx.label(28f, Color.WHITE, true, n.title).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) })
        if (n.message.isNotEmpty()) card.addView(ctx.label(20f, 0xE6FFFFFF.toInt(), text = n.message).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        })
        if (n.image != null) {
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundRect(0xFF000000.toInt(), ctx.dpf(16))
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(300)).apply { topMargin = ctx.dp(18) })
            App.instance.snapshots.load(n.image, ctx.dp(560)) { bmp ->
                if (bmp == null) { img.visibility = View.GONE; return@load }
                if (!isAttachedToWindow || !showing) { bmp.recycle(); return@load }
                bitmap = bmp; img.setImageBitmap(bmp)
            }
        }
        // Bouton OK
        card.addView(ctx.label(18f, 0xFF1C1C1E.toInt(), true, "OK").apply {
            gravity = Gravity.CENTER
            background = roundRect(n.color, ctx.dpf(14))
            setPadding(ctx.dp(48), ctx.dp(12), ctx.dp(48), ctx.dp(12))
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.dp(24); bottomMargin = ctx.dp(24) })
        // Barre de temps restant, collée au bas de l'encadré
        if (n.duration > 0) {
            val track = FrameLayout(ctx).apply { setBackgroundColor(0x1AFFFFFF) }
            val b = View(ctx).apply { setBackgroundColor(n.color); pivotX = 0f }
            track.addView(b, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            frame.addView(track, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(5)))
            bar = b
        }
        val w = minOf(ctx.resources.displayMetrics.widthPixels - ctx.dp(64), ctx.dp(620))
        addView(frame, LayoutParams(w, WRAP_CONTENT, Gravity.CENTER))

        visibility = View.VISIBLE
        bringToFront()
        alpha = 0f
        frame.scaleX = 0.92f; frame.scaleY = 0.92f
        animate().alpha(1f).setDuration(220).start()
        frame.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()

        if (n.sound) try {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        } catch (_: Exception) {}

        if (n.duration > 0) timer = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = n.duration * 1000L
            interpolator = LinearInterpolator()
            addUpdateListener { bar?.scaleX = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) close() }
            })
            start()
        }
    }

    fun close() {
        if (!showing) return
        timer?.cancel(); timer = null
        animate().alpha(0f).setDuration(180).withEndAction { clear(); visibility = View.GONE; onClosed?.invoke() }.start()
    }

    private fun clear() {
        timer?.cancel(); timer = null
        removeAllViews()
        bar = null
        bitmap?.recycle(); bitmap = null
    }
}
