package com.homepanel.kiosk

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pointage des matchs, d'après les capteurs de l'intégration TeamTracker de Home Assistant choisis dans les réglages
 * (un capteur par équipe suivie). Montré derrière une icône du bandeau et sur l'écran de veille.
 */
object Scores {
    class Side(val abbr: String, val name: String, val logo: String, val score: String)

    /** [state] : PRE (à venir), IN (en cours) ou POST (terminé). Visiteur à gauche, équipe locale à droite. */
    class Game(val state: String, val away: Side, val home: Side, val clock: String, val startsAt: Long, val league: String, val lastPlay: String) {
        val live get() = state == "IN"
        val key get() = "$state|${away.abbr}|${away.score}|${home.abbr}|${home.score}|$clock"
    }

    /** États que prend un capteur TeamTracker : sert à les reconnaître dans la liste des entités. */
    val STATES = setOf("PRE", "IN", "POST", "NOT_FOUND", "BYE")

    private fun time(iso: String): Long = try {
        // « 2026-11-15T00:00Z » : sans secondes, en temps universel.
        SimpleDateFormat(if (iso.count { it == ':' } >= 2) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd'T'HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.removeSuffix("Z"))?.time ?: 0L
    } catch (_: Exception) { 0L }

    fun game(e: EntityState?): Game? {
        if (e == null || e.state !in setOf("PRE", "IN", "POST")) return null
        val a = e.attrs
        fun side(p: String) = Side(a.str("${p}_abbr"), a.str("${p}_name"), a.str("${p}_logo"), a.str("${p}_score"))
        val team = side("team"); val opp = side("opponent")
        if (team.abbr.isEmpty() || opp.abbr.isEmpty()) return null
        val teamHome = a.str("team_homeaway") != "away"
        return Game(e.state, if (teamHome) opp else team, if (teamHome) team else opp, a.str("clock"), time(a.str("date")), a.str("league"), a.str("last_play"))
    }

    /** À montrer : un match en cours, un match qui commence d'ici 12 heures, ou terminé depuis moins de 8 heures. */
    fun visible(g: Game, now: Long) = when (g.state) {
        "IN" -> true
        "PRE" -> g.startsAt > 0 && g.startsAt - now in -3_600_000L..12 * 3_600_000L
        else -> g.startsAt > 0 && now - g.startsAt in 0..8 * 3_600_000L
    }

    fun active(ids: List<String>, states: Map<String, EntityState>, now: Long): List<Game> =
        ids.mapNotNull { game(states[it]) }.filter { visible(it, now) }.distinctBy { it.away.abbr + it.home.abbr }.sortedBy { if (it.live) 0 else if (it.state == "PRE") 1 else 2 }

    /** « 1 – 2 », ou « @ » avant le match. */
    fun score(g: Game) = if (g.state == "PRE") "@" else "${g.away.score} – ${g.home.score}"

    /** Où en est le match : l'heure du début, l'horloge du match, ou « Final ». */
    fun status(g: Game): String = when (g.state) {
        "PRE" -> if (g.startsAt > 0) SimpleDateFormat("HH:mm", Locale.US).format(Date(g.startsAt)) else g.clock
        "IN" -> g.clock.ifEmpty { tr("En cours") }
        else -> tr("Final")
    }

    /** Une ligne : « COL 1 – 2 MTL · 2e 12:34 ». */
    fun line(g: Game) = "${g.away.abbr} ${score(g)} ${g.home.abbr}  ·  ${status(g)}"
}

/** Icône « sport » du bandeau : visible quand un match suivi est en cours, à venir aujourd'hui ou tout juste terminé. */
class ScoresButton(ctx: Context) : ImageView(ctx) {
    init {
        setImageResource(R.drawable.ic_trophy)
        setPadding(ctx.dp(6), ctx.dp(5), ctx.dp(6), ctx.dp(5))
        isClickable = true
        contentDescription = tr("Pointage")
        visibility = View.GONE
    }

    fun set(games: List<Scores.Game>) {
        visibility = if (games.isEmpty()) View.GONE else View.VISIBLE
        imageTintList = android.content.res.ColorStateList.valueOf(if (games.any { it.live }) Skin.accent else Skin.fg(0xE6))
    }
}

internal fun MainActivity.activeGames() = Scores.active(cfg.sportSensors, app.ha.states, System.currentTimeMillis())

internal fun MainActivity.updateScoresButton() = scoresButton.set(activeGames())

/** Bloc d'un match : les deux logos, le pointage entre les deux, où en est le match. [big] pour la fenêtre, petit pour l'écran de veille. */
internal fun Context.scoreView(g: Scores.Game, big: Boolean, ink: Int = Skin.ink, mute: Int = Skin.fg(0xB3)): View {
    val logo = dp(if (big) 120 else 44)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun side(s: Scores.Side): View {
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        Covers.logo(s.logo) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
        if (!big) return img.apply { layoutParams = LinearLayout.LayoutParams(logo, logo) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(img, LinearLayout.LayoutParams(logo, logo))
            addView(label(17f, ink, text = s.name.ifEmpty { s.abbr }).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            layoutParams = LinearLayout.LayoutParams(dp(190), WRAP_CONTENT)
        }
    }
    row.addView(side(g.away))
    val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    mid.addView(label(if (big) 60f else 30f, ink, true, if (big) Scores.score(g) else "${g.away.abbr} ${Scores.score(g)} ${g.home.abbr}").apply { gravity = Gravity.CENTER; includeFontPadding = false })
    mid.addView(label(if (big) 19f else 18f, if (g.live) Skin.accent else mute, text = Scores.status(g)).apply { gravity = Gravity.CENTER })
    row.addView(mid, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(if (big) 26 else 14); marginEnd = dp(if (big) 26 else 14) })
    row.addView(side(g.home))
    return row
}

/** Fenêtre du pointage : un bloc par match suivi. Toucher referme. */
internal fun MainActivity.showScores() {
    val games = activeGames()
    if (games.isEmpty()) return
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(34), dp(26), dp(34), dp(24))
        background = Skin.panelBg(this@showScores, dpf(22))
        setOnClickListener { d.dismiss() }
    }
    games.take(3).forEachIndexed { i, g ->
        if (i > 0) box.addView(View(this).apply { setBackgroundColor(Skin.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = dp(20); bottomMargin = dp(20) })
        if (g.league.isNotEmpty()) box.addView(label(14f, Skin.fg(0x99), text = g.league).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(8)) })
        box.addView(scoreView(g, big = true))
        if (g.live && g.lastPlay.isNotEmpty()) box.addView(label(15f, Skin.fg(0xB3), text = g.lastPlay).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(560); setPadding(0, dp(12), 0, 0)
        })
    }
    d.setContentView(box)
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    d.window?.setDimAmount(0.75f)
    d.setCanceledOnTouchOutside(true)
    d.window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    d.show()
    @Suppress("DEPRECATION")
    d.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    d.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/** Applications d'un lecteur (Apple TV…) : toucher un nom la lance. */
internal fun MainActivity.showSources(e: EntityState) {
    val list = e.attrs.optJSONArray("source_list")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
    if (list.isEmpty()) return
    val current = e.attrs.str("app_name").ifEmpty { e.attrs.str("source") }
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(26), dp(22), dp(26), dp(20))
        background = Skin.panelBg(this@showSources, dpf(22))
    }
    box.addView(label(20f, Skin.ink, true, e.name).apply { setPadding(0, 0, 0, dp(14)) })
    val cols = 4
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for (chunk in list.chunked(cols)) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until cols) {
            val name = chunk.getOrNull(i)
            val cell = if (name == null) View(this) else label(16f, if (name == current) Skin.onPrimary else Skin.ink, name == current, name).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                background = roundRect(if (name == current) Skin.accent else Skin.fg(0x14), dpf(14))
                setPadding(dp(10), dp(14), dp(10), dp(14))
                minHeight = dp(64)
                setOnClickListener {
                    if (!haOffline()) app.ha.callService("media_player", "select_source", e.id, org.json.JSONObject().put("source", name))
                    d.dismiss()
                }
            }
            row.addView(cell, LinearLayout.LayoutParams(dp(190), WRAP_CONTENT).apply { if (i > 0) marginStart = dp(10) })
        }
        grid.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }
    val tall = list.size > cols * 6
    box.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) },
        LinearLayout.LayoutParams(WRAP_CONTENT, if (tall) resources.displayMetrics.heightPixels * 62 / 100 else WRAP_CONTENT))
    d.setContentView(box)
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    d.window?.setDimAmount(0.7f)
    d.setCanceledOnTouchOutside(true)
    d.window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    d.show()
    @Suppress("DEPRECATION")
    d.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    d.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}
