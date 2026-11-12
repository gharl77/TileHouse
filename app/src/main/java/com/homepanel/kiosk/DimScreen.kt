package com.homepanel.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.random.Random

/**
 * Écran de veille :
 *  - "black"  : noir complet ;
 *  - "clock"  : l'heure (sans secondes), la date et la météo, déplacées un peu chaque minute (anti-marquage) ;
 *  - "photos" : cadre photo (album Immich…) avec l'heure et la météo en petit dans un coin.
 */
class DimScreen(ctx: Context) : FrameLayout(ctx) {
    private val photoA = photoView(ctx)
    private val photoB = photoView(ctx)
    private var front = photoA
    private val shade = View(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
    }
    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val time = ctx.label(150f, 0xE6FFFFFF.toInt()).apply {
        typeface = Skin.clockFace
        includeFontPadding = false
    }
    private val date = ctx.label(26f, 0xB3FFFFFF.toInt())
    private val weather = ctx.label(30f, 0xD9FFFFFF.toInt()).apply { setPadding(0, ctx.dp(18), 0, 0) }
    private var lastMinute = ""
    private var style = ""
    private val handler = Handler(Looper.getMainLooper())
    private val nextPhoto = Runnable { loadPhoto() }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        visibility = View.GONE
        addView(photoA, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(photoB, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(shade, LayoutParams(MATCH_PARENT, ctx.dp(260), Gravity.BOTTOM))
        for (v in listOf(time, date, weather)) {
            v.gravity = Gravity.CENTER
            v.setShadowLayer(12f, 0f, 0f, 0xCC000000.toInt())
            box.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        addView(box, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }

    private fun photoView(ctx: Context) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }

    /** Met à jour le contenu selon [style] ("black", "clock" ou "photos"). */
    fun update(style: String, weatherState: EntityState?) {
        if (style != this.style) setStyle(style)
        box.visibility = if (style == "black") View.GONE else View.VISIBLE
        if (style == "black") return
        val now = Date()
        val t = SimpleDateFormat("HH:mm", I18n.locale).format(now)
        time.text = t
        date.text = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale).format(now)
            .replaceFirstChar { it.uppercase() }
        weather.text = weatherState?.let { w ->
            val temp = w.attrs.dbl("temperature")?.let { Present.num(it) + "°" } ?: ""
            "${HomeText.emoji(w.state, HomeText.nightNow())}  $temp  ${HomeText.condition(w.state, HomeText.nightNow())}".trim()
        } ?: ""
        weather.visibility = if (weather.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // Horloge seule : léger déplacement à chaque nouvelle minute (anti-marquage).
        if (style == "clock" && t != lastMinute) {
            lastMinute = t
            val r = Random.Default
            box.translationX = (r.nextFloat() - 0.5f) * width * 0.25f
            box.translationY = (r.nextFloat() - 0.5f) * height * 0.25f
        }
    }

    private fun setStyle(s: String) {
        style = s
        val photos = s == "photos"
        val big = !photos
        time.textSize = if (big) 150f else 72f
        date.textSize = if (big) 26f else 20f
        weather.textSize = if (big) 30f else 22f
        weather.setPadding(0, context.dp(if (big) 18 else 6), 0, 0)
        box.gravity = if (big) Gravity.CENTER_HORIZONTAL else Gravity.START
        for (i in 0 until box.childCount) (box.getChildAt(i).layoutParams as LinearLayout.LayoutParams).gravity =
            if (big) Gravity.CENTER_HORIZONTAL else Gravity.START
        box.layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT, if (big) Gravity.CENTER else Gravity.BOTTOM or Gravity.START).apply {
            if (!big) setMargins(context.dp(40), 0, 0, context.dp(32))
        }
        box.translationX = 0f; box.translationY = 0f; lastMinute = ""
        shade.visibility = if (photos) View.VISIBLE else View.GONE
        handler.removeCallbacks(nextPhoto)
        if (photos && visibility == View.VISIBLE) loadPhoto() else clearPhotos()
    }

    /** Appelé quand l'écran de veille apparaît ou disparaît. */
    fun onShown(shown: Boolean) {
        handler.removeCallbacks(nextPhoto)
        if (shown && style == "photos") loadPhoto()
        if (!shown) clearPhotos()
    }

    private fun loadPhoto() {
        handler.removeCallbacks(nextPhoto)
        if (visibility != View.VISIBLE || style != "photos") return
        val dm = resources.displayMetrics
        PhotoFrame.next(dm.widthPixels, dm.heightPixels) { bmp ->
            if (visibility != View.VISIBLE || style != "photos") { bmp?.recycle(); return@next }
            if (bmp != null) show(bmp)
            // Photo illisible : on essaie la suivante tout de suite (3 s), sinon on attend l'intervalle.
            handler.postDelayed(nextPhoto, if (bmp != null) App.instance.config.photoSeconds * 1000L else 3000L)
        }
    }

    /** Fondu enchaîné vers la nouvelle photo ; l'ancienne image est libérée après le fondu. */
    private fun show(bmp: Bitmap) {
        val back = if (front === photoA) photoB else photoA
        val old = front
        back.setImageBitmap(bmp)
        back.bringToFront(); shade.bringToFront(); box.bringToFront()
        back.animate().alpha(1f).setDuration(1200).withEndAction {
            old.alpha = 0f
            ((old.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)?.let { b -> old.setImageDrawable(null); b.recycle() }
        }.start()
        front = back
    }

    private fun clearPhotos() {
        for (v in listOf(photoA, photoB)) {
            v.animate().cancel(); v.alpha = 0f
            ((v.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)?.let { b -> v.setImageDrawable(null); b.recycle() }
        }
    }
}
