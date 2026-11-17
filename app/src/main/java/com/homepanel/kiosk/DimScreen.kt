package com.homepanel.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
 *  - "clock"  : l'heure (sans secondes) et la date, puis une rangée de panneaux aérés — météo, pointage des matchs
 *               suivis, ce qui joue —, le tout déplacé un peu chaque minute (anti-marquage) ;
 *  - "radar"  : balayage radar, heure, météo et voyants de la maison ([RadarView]) ;
 *  - "photos" : cadre photo (album Immich…) avec les mêmes éléments en plus petit dans un coin.
 */
class DimScreen(ctx: Context) : FrameLayout(ctx) {
    private val photoA = photoView(ctx)
    private val photoB = photoView(ctx)
    private var front = photoA
    private val shade = View(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
    }
    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val radar = RadarView(ctx).apply { visibility = View.GONE }
    private val time = ctx.label(150f, WHITE).apply {
        typeface = Skin.clockFace
        includeFontPadding = false
    }
    private val date = ctx.label(26f, SOFT).apply { letterSpacing = 0.04f }
    /** Rangée de panneaux sous l'heure : météo, pointage, ce qui joue. */
    private val cards = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var cardsKey = ""
    /** Tout ce qui joue, le plus récent d'abord ; à plusieurs, le panneau les montre tour à tour. */
    private var playingAll: List<EntityState> = emptyList()
    private var playIndex = 0
    private var lastWeather: EntityState? = null
    private var lastGames: List<Scores.Game> = emptyList()
    private val rotate = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (visibility != View.VISIBLE || playingAll.size < 2) return
            playIndex = (playIndex + 1) % playingAll.size
            updateCards(lastWeather, lastGames, fade = true)
            handler.postDelayed(this, ROTATE_MS)
        }
    }
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
        addView(shade, LayoutParams(MATCH_PARENT, ctx.dp(340), Gravity.BOTTOM))
        for (v in listOf(time, date)) {
            v.gravity = Gravity.CENTER
            v.setShadowLayer(12f, 0f, 0f, 0xCC000000.toInt())
            box.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        box.addView(cards, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(box, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        addView(radar, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun photoView(ctx: Context) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }

    /** Met à jour le contenu selon [style] ("black", "clock" ou "photos"). */
    fun update(style: String, weatherState: EntityState?, games: List<Scores.Game> = emptyList(), playing: List<EntityState> = emptyList()) {
        // La liste de ce qui joue a changé (un lecteur de plus ou de moins) : on repart du plus récent.
        if (playing.map { it.id } != playingAll.map { it.id }) {
            playIndex = 0
            handler.removeCallbacks(rotate)
            if (playing.size > 1 && style != "black") handler.postDelayed(rotate, ROTATE_MS)
        }
        playingAll = playing; lastWeather = weatherState; lastGames = games
        if (style != this.style) setStyle(style)
        box.visibility = if (style == "black" || style == "radar") View.GONE else View.VISIBLE
        radar.visibility = if (style == "radar") View.VISIBLE else View.GONE
        if (style == "black") return
        val now = Date()
        val t = SimpleDateFormat("HH:mm", I18n.locale).format(now)
        time.text = t
        date.text = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale).format(now)
            .replaceFirstChar { it.uppercase() }
        if (style == "radar") {
            val app = App.instance
            val sky = weatherState?.let { w -> listOfNotNull(w.attrs.dbl("temperature")?.let { Present.num(Math.round(it).toDouble()) + "°" }, HomeText.condition(w.state, HomeText.nightNow())).joinToString("  ") }.orEmpty()
            // Léger déplacement à chaque nouvelle minute (anti-marquage).
            if (t != lastMinute) {
                lastMinute = t
                radar.shiftX = (Random.nextFloat() - 0.5f) * width * 0.05f
                radar.shiftY = (Random.nextFloat() - 0.5f) * height * 0.06f
            }
            val now0 = playing.firstOrNull()?.let { p -> Posters.what(p).let { w -> listOf(w.title.ifEmpty { w.series }, w.artist).filter { it.isNotEmpty() }.joinToString(" · ") } }.orEmpty()
            radar.set(t, date.text.toString(), sky, Radar.scan(app.config.rooms, app.ha.states), Radar.extras(games, now0))
            return
        }
        updateCards(weatherState, games)
        // Horloge seule : léger déplacement à chaque nouvelle minute (anti-marquage), sans jamais sortir de l'écran.
        if (style == "clock" && t != lastMinute) {
            lastMinute = t
            val r = Random.Default
            val freeX = ((width - box.width) / 2f - context.dp(24)).coerceIn(0f, width * 0.05f)
            val freeY = ((height - box.height) / 2f - context.dp(24)).coerceIn(0f, height * 0.06f)
            box.translationX = (r.nextFloat() - 0.5f) * 2 * freeX
            box.translationY = (r.nextFloat() - 0.5f) * 2 * freeY
        }
    }

    /** Panneau translucide aux coins arrondis : même forme pour la météo, le pointage et ce qui joue. */
    private fun panel(k: Float) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = context.dpf(26 * k); setStroke(context.dp(1), 0x26FFFFFF) }
        setPadding(context.dp(24 * k), context.dp(18 * k), context.dp(24 * k), context.dp(18 * k))
        minimumHeight = context.dp(112 * k)
    }

    /** Les panneaux ne sont reconstruits que lorsque leur contenu change. */
    private fun updateCards(weather: EntityState?, games: List<Scores.Game>, fade: Boolean = false) {
        val many = playingAll.size > 1
        val playing = playingAll.getOrNull(playIndex % maxOf(1, playingAll.size))
        val w = playing?.let { Posters.what(it) }
        val title = w?.let { it.title.ifEmpty { it.series } }.orEmpty()
        val night = HomeText.nightNow()
        val key = style + "#" + (weather?.let { it.state + "|" + it.attrs.dbl("temperature") + "|" + night } ?: "") + "#" + games.take(2).joinToString("|") { it.key } +
            "#" + playIndex + "/" + playingAll.size + "|" + (playing?.id ?: "") + "|" + title + "|" + (playing?.attrs?.str("entity_picture") ?: "") + "|" + (w?.contentId ?: "")
        if (key == cardsKey) return
        cardsKey = key
        cards.removeAllViews()
        val k = if (style == "photos") 0.78f else 1f
        val gap = context.dp(20 * k)
        // Même hauteur pour tous les panneaux : celle du plus haut.
        fun add(v: View) = cards.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { if (cards.childCount > 0) marginStart = gap })

        if (weather != null) add(panel(k).apply {
            addView(context.label(42f * k, WHITE, text = HomeText.emoji(weather.state, night)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                weather.attrs.dbl("temperature")?.let { addView(context.label(38f * k, WHITE, text = Present.num(it) + "°").apply { typeface = Skin.light; includeFontPadding = false }) }
                addView(context.label(16f * k, SOFT, text = HomeText.condition(weather.state, night)))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(16 * k) })
        })

        if (games.isNotEmpty()) add(panel(k).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            games.take(2).forEachIndexed { i, g -> addView(scoreRow(g, k), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) topMargin = context.dp(12 * k) }) }
        })

        if (playing != null && w != null && title.isNotEmpty()) add(panel(k).apply {
            // À plusieurs, le panneau garde la même largeur d'un titre à l'autre et apparaît en fondu.
            if (fade) { alpha = 0f; animate().alpha(1f).setDuration(450).start() }
            val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundRect(0x1FFFFFFF, context.dpf(14 * k)); visibility = View.GONE }
            addView(img, LinearLayout.LayoutParams(context.dp(124 * k), context.dp(93 * k)).apply { marginEnd = context.dp(18 * k) })
            Posters.find(playing) { url ->
                Covers.load(url, context.dp(260)) { bmp ->
                    if (cardsKey != key || bmp == null) return@load
                    if (Channels.isLogo(url)) { img.scaleType = ImageView.ScaleType.FIT_CENTER; if (!url.startsWith("teams:")) { val p = context.dp(12 * k); img.setPadding(p, p, p, p) } }
                    img.setImageBitmap(bmp); img.visibility = View.VISIBLE
                }
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val head = tr("En lecture").uppercase() + if (many) "   ${playIndex % playingAll.size + 1} / ${playingAll.size}" else ""
                addView(context.label(12f * k, FAINT, text = head).apply { letterSpacing = 0.14f })
                addView(context.label(21f * k, WHITE, text = title).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, context.dp(3 * k), 0, context.dp(3 * k))
                    if (many) width = context.dp(300 * k) else maxWidth = context.dp(300 * k)
                })
                addView(context.label(14f * k, SOFT, text = listOf(playing.name, w.app.takeIf { it != playing.name }.orEmpty(), w.channelLabel).filter { it.isNotEmpty() }.joinToString("  ·  "))
                    .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = context.dp(300 * k) })
            })
        })
        cards.visibility = if (cards.childCount == 0) View.GONE else View.VISIBLE
    }

    /** Un match : logo, sigle, pointage (ou l'heure du début) avec l'état dessous, sigle, logo. */
    private fun scoreRow(g: Scores.Game, k: Float): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun logo(s: Scores.Side) = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            Covers.logo(s.logo) { bmp -> if (bmp != null) setImageBitmap(bmp) }
        }
        fun abbr(s: Scores.Side) = context.label(17f * k, SOFT, text = s.abbr).apply { setPadding(context.dp(10 * k), 0, context.dp(10 * k), 0) }
        val side = context.dp(52 * k)
        row.addView(logo(g.away), LinearLayout.LayoutParams(side, side)); row.addView(abbr(g.away))
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            // Avant le match : l'heure du début en grand. Pendant et après : le pointage, avec l'horloge ou « Final » dessous.
            val pre = g.state == "PRE"
            addView(context.label(34f * k, WHITE, !pre, if (pre) Scores.status(g) else Scores.score(g)).apply { gravity = Gravity.CENTER; includeFontPadding = false; if (pre) typeface = Skin.light })
            addView(context.label(13f * k, if (g.live) Skin.accent else FAINT, text = if (pre) g.league else Scores.status(g)).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(6 * k); marginEnd = context.dp(6 * k) })
        row.addView(abbr(g.home)); row.addView(logo(g.home), LinearLayout.LayoutParams(side, side))
        return row
    }

    private fun setStyle(s: String) {
        style = s
        val photos = s == "photos"
        val big = !photos
        time.textSize = if (big) 150f else 76f
        date.textSize = if (big) 26f else 20f
        box.gravity = if (big) Gravity.CENTER_HORIZONTAL else Gravity.START
        for (i in 0 until box.childCount) (box.getChildAt(i).layoutParams as LinearLayout.LayoutParams).apply {
            gravity = if (big) Gravity.CENTER_HORIZONTAL else Gravity.START
            // De l'air entre l'heure, la date et les panneaux.
            topMargin = when (box.getChildAt(i)) { date -> context.dp(if (big) 6 else 2); cards -> context.dp(if (big) 44 else 20); else -> 0 }
        }
        box.layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT, if (big) Gravity.CENTER else Gravity.BOTTOM or Gravity.START).apply {
            if (!big) setMargins(context.dp(40), 0, 0, context.dp(36))
        }
        box.translationX = 0f; box.translationY = 0f; lastMinute = ""
        cardsKey = ""
        shade.visibility = if (photos) View.VISIBLE else View.GONE
        handler.removeCallbacks(nextPhoto)
        if (photos && visibility == View.VISIBLE) loadPhoto() else clearPhotos()
    }

    /** Appelé quand l'écran de veille apparaît ou disparaît. */
    fun onShown(shown: Boolean) {
        handler.removeCallbacks(nextPhoto)
        handler.removeCallbacks(rotate)
        if (shown && playingAll.size > 1 && style != "black") handler.postDelayed(rotate, ROTATE_MS)
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

    private companion object {
        /** Durée d'affichage de chaque lecteur quand plusieurs jouent. */
        const val ROTATE_MS = 10_000L
        const val WHITE = 0xF0FFFFFF.toInt()
        const val SOFT = 0xB3FFFFFF.toInt()
        const val FAINT = 0x80FFFFFF.toInt()
    }
}
