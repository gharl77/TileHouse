package com.homepanel.kiosk

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Panneau « Ce matin » (plein écran, ouvert par l'icône ☀️ du bandeau).
 * Les textes (entité Home Assistant) en cartes côte à côte, puis chaque flux de nouvelles (lien RSS / Atom)
 * en grille de deux colonnes : image, titre, début du résumé et heure. Toucher une nouvelle déplie son résumé.
 */
class MorningPanel(private val act: MainActivity, private val onRead: () -> Unit) : FrameLayout(act) {
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val subtitle = act.label(15f, Ui.MUTE)
    private val titleView = act.label(26f, Color.WHITE, true)
    private val scroll = ScrollView(act)
    val showing get() = visibility == View.VISIBLE
    private var gen = 0

    init {
        setBackgroundColor(0xB3000000.toInt())
        isClickable = true
        setOnClickListener { hide() }
        elevation = act.dpf(28)
        visibility = View.GONE

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF51C222D.toInt()); cornerRadius = act.dpf(26); setStroke(act.dp(1), 0x26FFFFFF) }
            setPadding(act.dp(28), act.dp(22), act.dp(28), act.dp(10))
            isClickable = true
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(act.label(30f, text = "☀️").apply { setPadding(0, 0, act.dp(14), 0) })
        head.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(act.roundIcon(R.drawable.ic_close, 44, 0x1FFFFFFF, Color.WHITE) { hide() }.apply {
            layoutParams = LinearLayout.LayoutParams(act.dp(44), act.dp(44))
        })
        panel.addView(head)
        panel.addView(View(act).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = act.dp(16) })
        scroll.addView(body)
        scroll.isVerticalScrollBarEnabled = false
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val dm = act.resources.displayMetrics
        addView(panel, LayoutParams(minOf(dm.widthPixels - act.dp(56), act.dp(1180)), (dm.heightPixels * 0.9f).toInt(), Gravity.CENTER))
    }

    fun show() {
        render()
        scroll.scrollTo(0, 0)
        visibility = View.VISIBLE
        bringToFront()
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
    }

    fun hide() {
        if (!showing) return
        gen++
        animate().alpha(0f).setDuration(150).withEndAction { visibility = View.GONE; body.removeAllViews() }.start()
    }

    private fun render() {
        val my = ++gen
        val cfg = App.instance.config
        val states = App.instance.ha.states
        val day = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale).format(Date())
        titleView.text = tr("Ce matin")
        subtitle.text = day.replaceFirstChar { it.uppercase() }
        body.removeAllViews()

        val sources = cfg.morningSources
        val texts = ArrayList<Pair<String, String>>()
        val feeds = ArrayList<JSONObject>()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            if (HomeExtras.isFeed(s)) feeds.add(s)
            else HomeExtras.sourceText(s, states)?.let { texts.add(s.optString("title").ifEmpty { states[s.optString("entity")]?.name ?: "" } to it) }
        }

        // Textes : cartes côte à côte (3 au plus par rangée)
        for (row in texts.chunked(3)) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { k, (title, text) ->
                val card = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundRect(0x14FFFFFF, act.dpf(18))
                    setPadding(act.dp(20), act.dp(16), act.dp(20), act.dp(18))
                }
                card.addView(act.label(14f, Ui.ACCENT, true, title.uppercase(I18n.locale)).apply { letterSpacing = 0.06f })
                card.addView(act.label(16f, 0xEBFFFFFF.toInt()).apply {
                    this.text = HomeExtras.markdown(text); setLineSpacing(0f, 1.18f); setPadding(0, act.dp(8), 0, 0)
                })
                r.addView(card, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (k > 0) marginStart = act.dp(14) })
            }
            body.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(18) })
        }

        // Flux de nouvelles : une section par flux
        for (f in feeds) {
            val url = f.optString("url")
            val max = f.optInt("count", 8).coerceIn(1, 20)
            val section = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(View(act).apply { background = roundRect(Ui.ACCENT, act.dpf(2)) }, LinearLayout.LayoutParams(act.dp(4), act.dp(22)).apply { marginEnd = act.dp(12) })
            head.addView(act.label(21f, Color.WHITE, true, f.optString("title").ifEmpty { tr("Nouvelles") }), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val info = act.label(14f, Ui.MUTE)
            head.addView(info)
            section.addView(head)
            val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            section.addView(grid)
            body.addView(section, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(26) })

            fun fill(feed: Feeds.Feed?) {
                grid.removeAllViews()
                if (feed == null) { info.text = tr("Chargement…"); return }
                info.text = when {
                    feed.items.isEmpty() -> ""
                    feed.error != null -> tr("Hors ligne · dernière lecture") + " " + SimpleDateFormat("HH:mm", I18n.locale).format(Date(feed.at))
                    else -> tr("Mis à jour") + " " + SimpleDateFormat("HH:mm", I18n.locale).format(Date(feed.at))
                }
                if (feed.items.isEmpty()) {
                    grid.addView(act.label(15f, Ui.MUTE, text = feed.error ?: tr("Aucune nouvelle dans ce flux")).apply { setPadding(act.dp(16), act.dp(12), 0, 0) })
                    return
                }
                for (pair in feed.items.take(max).chunked(2)) {
                    val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEachIndexed { k, it -> r.addView(newsCard(it), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (k > 0) marginStart = act.dp(14) }) }
                    if (pair.size == 1) r.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = act.dp(14) })
                    grid.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(14) })
                }
            }
            val cached = Feeds.cached(url)
            fill(cached)
            Feeds.load(url) { if (my == gen) { fill(it); onRead() } }
        }

        if (texts.isEmpty() && feeds.isEmpty())
            body.addView(act.label(18f, Ui.MUTE, text = tr("Aucune source : ajoutez-en sur la page web (Accueil → Ce matin).")).apply {
                gravity = Gravity.CENTER; setPadding(0, act.dp(60), 0, act.dp(60))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(View(act), LinearLayout.LayoutParams(MATCH_PARENT, act.dp(18)))
    }

    private fun newsCard(it: Feeds.Item): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(0x14FFFFFF, act.dpf(18))
            setPadding(act.dp(12), act.dp(12), act.dp(16), act.dp(12))
        }
        if (it.image != null) {
            val img = ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundRect(0x1FFFFFFF, act.dpf(12))
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(act.dp(150), act.dp(104)).apply { marginEnd = act.dp(14) })
            Feeds.image(it.image, act.dp(300)) { b -> if (b != null) img.setImageBitmap(b) else img.visibility = View.GONE }
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(act.label(17f, Color.WHITE, true, it.title).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.08f)
        })
        val sum = act.label(14f, 0xB3FFFFFF.toInt(), text = it.summary).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.12f); setPadding(0, act.dp(5), 0, 0)
            visibility = if (it.summary.isEmpty()) View.GONE else View.VISIBLE
        }
        col.addView(sum)
        if (it.date > 0) col.addView(act.label(13f, Ui.MUTE, text = relativeTime(it.date)).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, act.dp(6), 0, 0)
        })
        card.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Toucher : résumé complet (ou replié)
        if (it.summary.isNotEmpty()) {
            card.isClickable = true
            card.setOnClickListener { sum.maxLines = if (sum.maxLines == 2) 40 else 2 }
        }
        return card
    }
}
