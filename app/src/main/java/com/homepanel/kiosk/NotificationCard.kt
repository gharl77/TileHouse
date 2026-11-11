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
    /** Étiquette : une nouvelle notification de même étiquette remplace l'ancienne ; « clear_notification » l'efface. */
    val tag: String = "",
    /** Image enregistrée sur la tablette (alertes caméra : capture prise au moment de la détection). */
    val imageFile: String? = null,
    /** Boutons d'action (au plus 3) : {title, service: "domaine.service", entity_id, data}. */
    val actions: List<JSONObject> = emptyList(),
) {
    /** Demande d'effacement (comme sur les téléphones) plutôt qu'une notification à afficher. */
    val isClear get() = message.trim().equals("clear_notification", ignoreCase = true)
    /** Alerte rouge : garde son son même pendant les heures calmes. */
    val isUrgent get() = color == 0xFFFF453A.toInt() || color == 0xFFFF3B30.toInt()

    /** Même notification, sans le son (réouverture depuis le centre de notifications). */
    fun copyNoSound() = Notice(title, message, icon, duration, color, image, false, tag, imageFile, actions)

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
                // Émoji trop récent pour la tablette (Android 8 = Emoji 5) : bulle par défaut plutôt qu'un carré.
                icon = o.optString("icon").takeIf { it.isNotEmpty() && android.graphics.Paint().hasGlyph(it) } ?: "💬",
                duration = o.optInt("duration", 30).coerceIn(0, 3600),
                color = color,
                image = o.optString("image").takeIf { it.contains('.') && !it.contains("://") },
                sound = o.optBoolean("sound", false),
                tag = o.optString("tag").trim(),
                imageFile = o.optString("imageFile").takeIf { it.isNotEmpty() },
                actions = o.optJSONArray("actions")?.let { a -> (0 until minOf(3, a.length())).mapNotNull { a.optJSONObject(it) }
                    .filter { it.optString("title").isNotBlank() && it.optString("service").contains('.') } } ?: emptyList(),
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
    /** Appelé quand on touche OK (la notification est alors lue). */
    private var onOk: (() -> Unit)? = null

    init {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        setOnClickListener { close() }
        elevation = act.dpf(30)
        visibility = View.GONE
    }

    val showing get() = visibility == View.VISIBLE

    /**
     * Affiche [n] pendant [seconds] (0 = sans fermeture automatique). [onOk] est appelé si on touche OK ;
     * une fermeture automatique ou un toucher à côté laisse la notification non lue.
     */
    /** Étiquette de la notification affichée (pour l'effacement par HA). */
    var shownTag = ""
        private set

    fun show(n: Notice, seconds: Int, onOk: (() -> Unit)? = null) {
        clear()
        this.onOk = onOk
        shownTag = n.tag
        val ctx = act
        // Encadré arrondi : contenu (avec marges) puis barre de temps sur toute la largeur.
        val frame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Skin.panel); cornerRadius = ctx.dpf(28)
                setStroke(ctx.dp(1), Skin.fg(0x26))
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
        card.addView(ctx.label(40f, Skin.ink, text = n.icon).apply {
            gravity = Gravity.CENTER
            background = oval((n.color and 0x00FFFFFF) or 0x33000000)
        }, LinearLayout.LayoutParams(ctx.dp(84), ctx.dp(84)).apply { bottomMargin = ctx.dp(16) })
        if (n.title.isNotEmpty()) card.addView(ctx.label(28f, Skin.ink, true, n.title).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) })
        if (n.message.isNotEmpty()) card.addView(ctx.label(20f, Skin.fg(0xE6), text = n.message).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        })
        if (n.imageFile != null) {
            // Capture enregistrée (alerte caméra).
            val bmp = decodeFile(n.imageFile, ctx.dp(560))
            if (bmp != null) {
                bitmap = bmp
                card.addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = roundRect(0xFF000000.toInt(), ctx.dpf(16))
                    clipToOutline = true
                    setImageBitmap(bmp)
                }, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(320)).apply { topMargin = ctx.dp(14) })
            }
        } else if (n.image != null) {
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
        // Boutons d'action (ex. « Verrouiller »), puis OK
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (a in n.actions) buttons.addView(ctx.label(17f, Skin.ink, true, a.optString("title")).apply {
            gravity = Gravity.CENTER
            background = roundRect(Skin.fg(0x33), ctx.dpf(14))
            setPadding(ctx.dp(26), ctx.dp(12), ctx.dp(26), ctx.dp(12))
            setOnClickListener { runAction(a) }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.dp(12) })
        buttons.addView(ctx.label(18f, Skin.onPrimary, true, "OK").apply {
            gravity = Gravity.CENTER
            background = roundRect(n.color, ctx.dpf(14))
            setPadding(ctx.dp(48), ctx.dp(12), ctx.dp(48), ctx.dp(12))
            setOnClickListener { this@NotificationCard.onOk?.invoke(); this@NotificationCard.onOk = null; close() }
        })
        card.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.dp(24); bottomMargin = ctx.dp(24) })
        // Barre de temps restant, collée au bas de l'encadré
        if (seconds > 0) {
            val track = FrameLayout(ctx).apply { setBackgroundColor(Skin.fg(0x1A)) }
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

        // Heures calmes : silence, sauf pour les alertes rouges (fuite, serrure…).
        val cfg = App.instance.config
        if (n.sound && cfg.soundsEnabled && (n.isUrgent || !cfg.isQuietNow())) try {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        } catch (_: Exception) {}

        if (seconds > 0) timer = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = seconds * 1000L
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

    /** Bouton d'action : appelle le service de HA, puis la notification est lue et se ferme. */
    private fun runAction(a: JSONObject) {
        val ha = App.instance.ha
        if (!ha.demo && ha.status != HaClient.Status.CONNECTED) {
            android.widget.Toast.makeText(act, tr("Home Assistant ne répond pas : commande impossible pour le moment."), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val (domain, service) = a.optString("service").split('.', limit = 2)
        ha.callService(domain, service, a.optString("entity_id"), a.optJSONObject("data"))
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        onOk?.invoke(); onOk = null
        close()
    }

    fun close() {
        if (!showing) return
        onOk = null
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

/** Lit une image enregistrée en la réduisant à environ [w] pixels de large. */
fun decodeFile(path: String, w: Int): Bitmap? = try {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= w) s *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
} catch (_: Throwable) { null }
