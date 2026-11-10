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
 * raccourcis et carte du matin (sources choisies dans la page web).
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

    /** Carte du matin : une colonne par source (au plus 3 par rangée), texte coupé ; un toucher ouvre tout. */
    fun morningCard(ctx: Context, sources: JSONArray, states: Map<String, EntityState>, onOpen: () -> Unit): View? {
        val blocks = (0 until sources.length()).mapNotNull { i ->
            val s = sources.optJSONObject(i) ?: return@mapNotNull null
            val text = sourceText(s, states) ?: return@mapNotNull null
            s.optString("title").ifEmpty { states[s.optString("entity")]?.name ?: "" } to text
        }
        if (blocks.isEmpty()) return null
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(GLASS, ctx.dpf(22))
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(18))
            isClickable = true
            setOnClickListener { onOpen() }
        }
        card.addView(ctx.label(17f, Color.WHITE, true, "☀️  " + tr("Ce matin")))
        for (row in blocks.chunked(3)) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { k, (title, text) ->
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                col.addView(ctx.label(15f, Color.WHITE, true, title).apply { setPadding(0, ctx.dp(10), 0, ctx.dp(4)) })
                col.addView(ctx.label(14f, 0xD9FFFFFF.toInt()).apply {
                    this.text = markdown(text); maxLines = 9; ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.1f)
                })
                r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (k > 0) marginStart = ctx.dp(20) })
            }
            repeat(3 - row.size) { r.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f)) }
            card.addView(r)
        }
        return card
    }

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
