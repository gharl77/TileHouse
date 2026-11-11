package com.homepanel.kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Informations de l'accueil : pointes d'électricité, alertes météo, pastilles personnalisées,
 * raccourcis et « Ce matin » (sources choisies dans la page web, ouvertes par une icône du bandeau).
 */
object HomeExtras {
    private val hm get() = SimpleDateFormat("HH:mm", I18n.locale)

    // ------------------------------------------------------------ pointes d'électricité

    /** Pointe (début, fin) en millisecondes. */
    class Peak(val start: Long, val end: Long)

    /** « Pointe en cours · fin 20:00 », « Pointe aujourd'hui 16:00–20:00 », « Pointe demain 06:00–10:00 ». */
    fun peakChips(peaks: List<Peak>): List<Present.Chip> {
        val now = System.currentTimeMillis()
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val next = peaks.filter { it.end > now }.minByOrNull { it.start } ?: return emptyList()
        val fmt = hm
        return listOf(when {
            next.start <= now -> Present.Chip(R.drawable.ic_bolt, tr("Pointe en cours") + " · " + tr("fin") + " " + fmt.format(Date(next.end)), 2, "peak")
            next.start < today + 86_400_000L -> Present.Chip(R.drawable.ic_bolt, tr("Pointe aujourd'hui") + " " + fmt.format(Date(next.start)) + "–" + fmt.format(Date(next.end)), 1, "peak")
            next.start < today + 2 * 86_400_000L -> Present.Chip(R.drawable.ic_bolt, tr("Pointe demain") + " " + fmt.format(Date(next.start)) + "–" + fmt.format(Date(next.end)), 1, "peak")
            else -> return emptyList()
        })
    }

    // ------------------------------------------------------------ alertes météo

    /** Niveau d'une alerte d'après le nom du capteur (Environnement Canada : warnings, watches, advisory, statements). */
    private fun alertKind(id: String): Triple<String, Int, String> = when {
        "warning" in id || "alerte" in id -> Triple(tr("Alerte météo"), 2, "⚠️")
        "watch" in id || "veille" in id -> Triple(tr("Veille météo"), 1, "⚠️")
        "advisory" in id || "avis" in id -> Triple(tr("Avis météo"), 1, "ℹ️")
        else -> Triple(tr("Bulletin météo"), 0, "ℹ️")
    }

    fun weatherChips(ids: List<String>, states: Map<String, EntityState>): List<Present.Chip> = ids.mapNotNull { id ->
        val n = states[id]?.state?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
        if (n <= 0) return@mapNotNull null
        val (label, level, emoji) = alertKind(id)
        Present.Chip(0, "$emoji $label" + if (n > 1) " ($n)" else "", level, "wx")
    }

    /** Texte des alertes en vigueur (attributs du capteur), pour la fenêtre de détail. */
    fun weatherDetails(ids: List<String>, states: Map<String, EntityState>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val e = states[id] ?: continue
            val n = e.state.toDoubleOrNull()?.toInt() ?: 0
            if (n <= 0) continue
            sb.append("<b>").append(Html.escapeHtml(alertKind(id).first)).append("</b><br>")
            val a = e.attrs
            for (k in a.keys()) {
                if (k in setOf("attribution", "friendly_name", "icon", "unit_of_measurement", "state_class")) continue
                val v = a.opt(k)?.toString().orEmpty()
                if (v.isNotBlank()) sb.append("• ").append(Html.escapeHtml(v)).append("<br>")
            }
            sb.append("<br>")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ pastilles personnalisées

    /** Règles {entity, states:[…], text, color} : la pastille s'affiche quand l'entité est dans un de ces états. */
    fun customChips(rules: JSONArray, states: Map<String, EntityState>): List<Present.Chip> {
        val out = ArrayList<Present.Chip>()
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val e = states[r.optString("entity")] ?: continue
            val wanted = r.optJSONArray("states")?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() } } ?: emptyList()
            if (e.state.trim().lowercase() !in wanted) continue
            val level = when (r.optString("color")) { "red" -> 2; "orange" -> 1; else -> 0 }
            out.add(Present.Chip(0, r.optString("text").ifEmpty { e.name }, level, "custom"))
        }
        return out
    }

    // ------------------------------------------------------------ carte du matin

    /** Texte d'une source : attribut ou état ; un flux (attribut « entries ») donne la liste des titres. */
    fun sourceText(src: org.json.JSONObject, states: Map<String, EntityState>): String? {
        val e = states[src.optString("entity")] ?: return null
        val attr = src.optString("attribute")
        if (attr.isEmpty()) {
            (e.attrs.opt("entries") as? JSONArray)?.let { return feed(it) }
            return e.state.takeIf { it != "unknown" && it != "unavailable" }
        }
        val v = e.attrs.opt(attr) ?: return null
        if (v is JSONArray) return feed(v)
        return v.toString().takeIf { it.isNotBlank() }
    }

    private fun feed(a: JSONArray): String = (0 until minOf(6, a.length())).mapNotNull { i ->
        a.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let { "- **$it**" }
    }.joinToString("\n")

    /** Markdown simple (titres, gras, liens, puces) vers du texte mis en forme. Les liens restent du texte (kiosque). */
    fun markdown(md: String): CharSequence {
        val html = md.lines().joinToString("<br>") { line0 ->
            var line = Html.escapeHtml(line0).toString()
            line = line.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            line = line.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
            when {
                line.startsWith("### ") || line.startsWith("## ") -> "<b>" + line.trimStart('#', ' ') + "</b>"
                line.startsWith("- ") || line.startsWith("* ") -> "• " + line.substring(2)
                else -> line
            }
        }
        @Suppress("DEPRECATION")
        return Html.fromHtml(html)
    }

    /** Icône « Ce matin » du bandeau (comme la cloche) : visible pendant la plage horaire, point bleu tant que le contenu n'a pas été lu. */
    class MorningButton(ctx: Context) : android.widget.FrameLayout(ctx) {
        private val dot = View(ctx).apply { background = oval(0xFF0A84FF.toInt()); visibility = View.GONE }

        init {
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_morning)
                imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
            }, LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER))
            addView(dot, LayoutParams(ctx.dp(11), ctx.dp(11), Gravity.TOP or Gravity.END))
            setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, oval(Color.WHITE))
            contentDescription = tr("Ce matin")
            visibility = View.GONE
        }

        fun set(visible: Boolean, unread: Boolean) {
            visibility = if (visible) View.VISIBLE else View.GONE
            dot.visibility = if (unread) View.VISIBLE else View.GONE
        }
    }

    /** Empreinte du contenu, pour savoir s'il a changé depuis la dernière lecture. */
    fun morningHash(sources: JSONArray, states: Map<String, EntityState>): Int =
        (0 until sources.length()).mapNotNull { i -> sources.optJSONObject(i)?.let { sourceText(it, states) } }.joinToString("\n").hashCode()

    fun hasMorning(sources: JSONArray, states: Map<String, EntityState>): Boolean =
        (0 until sources.length()).any { i -> sources.optJSONObject(i)?.let { sourceText(it, states) } != null }

    /** Texte complet de toutes les sources, pour la fenêtre de lecture. */
    fun morningFull(sources: JSONArray, states: Map<String, EntityState>): CharSequence {
        val md = StringBuilder()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val text = sourceText(s, states) ?: continue
            md.append("## ").append(s.optString("title").ifEmpty { states[s.optString("entity")]?.name ?: "" }).append("\n").append(text).append("\n\n")
        }
        return markdown(md.toString().trim())
    }

    // ------------------------------------------------------------ raccourcis

    /** Bouton de raccourci : icône + nom (script, scène, bouton). */
    fun shortcut(ctx: Context, id: String, e: EntityState?, onTap: (TextView) -> Unit): TextView = ctx.label(16f, Color.WHITE).apply {
        text = e?.name ?: id.substringAfter('.').replace('_', ' ')
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        ctx.getDrawable(Present.icon(id, e))?.mutate()?.let { d ->
            val s = ctx.dp(20); d.setBounds(0, 0, s, s); d.setTint(Color.WHITE); setCompoundDrawables(d, null, null, null)
        }
        compoundDrawablePadding = ctx.dp(10)
        setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(22), ctx.dp(14))
        background = roundRect(GLASS, ctx.dpf(18))
        isClickable = true
        setOnClickListener { onTap(this) }
    }
}
