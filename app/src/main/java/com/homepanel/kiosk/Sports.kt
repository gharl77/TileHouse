package com.homepanel.kiosk

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Logos d'équipes pour un match : quand le titre de ce qui joue nomme deux équipes d'une même ligue
 * (« Avalanche @ Canadiens », « Canadiens c. Rangers »), on montre leurs deux logos à la place de l'affiche.
 * Les listes d'équipes viennent des pages publiques d'ESPN, gardées un mois sur la tablette.
 */
object Sports {
    class Team(val league: String, val names: List<String>, val logo: String)

    private val LEAGUES = listOf("hockey/nhl", "football/nfl", "basketball/nba", "baseball/mlb", "soccer/usa.1")
    private const val MONTH = 30L * 86_400_000L

    /** Noms français courants (surnom anglais sans accents → variantes), surtout pour le hockey. */
    private val FRENCH = mapOf(
        "canadiens" to listOf("canadien", "habs"), "senators" to listOf("senateurs"), "flames" to listOf("flammes"),
        "penguins" to listOf("pingouins"), "devils" to listOf("diables"), "stars" to listOf("etoiles"), "kings" to listOf("rois"),
        "blues" to listOf("bleus"), "panthers" to listOf("pantheres"), "hurricanes" to listOf("ouragans"), "capitals" to listOf("capitales"),
        "islanders" to listOf("insulaires"), "red wings" to listOf("ailes rouges"), "sharks" to listOf("requins"), "ducks" to listOf("canards"),
        "golden knights" to listOf("chevaliers dores", "knights"), "predators" to listOf("predateurs"), "lightning" to listOf("eclair"),
        "blue jackets" to listOf("vestes bleues"), "oilers" to listOf("petroliers"), "blue jays" to listOf("geais bleus"),
    )

    private var teams: List<Team>? = null
    private var loading: ArrayList<(List<Team>) -> Unit>? = null

    /** Minuscules, sans accents, ponctuation remplacée par des espaces. */
    fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Équipes d'une réponse d'ESPN (…/teams). */
    fun parse(text: String?, league: String): List<Team> {
        val arr = try {
            JSONObject(text ?: "").optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams")
        } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Team>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i)?.optJSONObject("team") ?: continue
            val logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
            val nick = norm(t.optString("name"))
            if (logo.isEmpty() || nick.length < 3) continue
            out.add(Team(league, (listOf(nick, norm(t.optString("displayName"))) + FRENCH[nick].orEmpty()).filter { it.length >= 3 }.distinct(), logo))
        }
        return out
    }

    /**
     * Les deux équipes d'une même ligue nommées dans [title], dans l'ordre où elles apparaissent ; null sinon.
     * Exiger deux équipes de la même ligue écarte les mots courants (« Stars », « Kings », « Jets »).
     */
    fun match(title: String, all: List<Team>): Pair<Team, Team>? {
        val t = " " + norm(title) + " "
        val hits = all.mapNotNull { team -> team.names.map { t.indexOf(" $it ") }.filter { it >= 0 }.minOrNull()?.let { it to team } }
        return hits.groupBy { it.second.league }.values.filter { it.size >= 2 }
            .map { g -> g.sortedBy { it.first } }.minByOrNull { it[1].first }?.let { it[0].second to it[1].second }
    }

    /** Adresse spéciale comprise par le chargeur d'images : les deux logos côte à côte. */
    fun pairUrl(a: Team, b: Team) = "teams:${a.logo}|${b.logo}"

    private fun toJson(list: List<Team>) = JSONArray().apply {
        for (t in list) put(JSONObject().put("l", t.league).put("n", JSONArray(t.names)).put("g", t.logo))
    }

    private fun fromJson(text: String?): List<Team> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Team(o.getString("l"), o.getJSONArray("n").let { n -> (0 until n.length()).map { n.getString(it) } }, o.getString("g")) }
    } catch (_: Exception) { emptyList() }

    /** Liste des équipes : en mémoire, sinon celle gardée sur la tablette (moins d'un mois), sinon téléchargée. */
    private fun load(cb: (List<Team>) -> Unit) {
        teams?.let { cb(it); return }
        loading?.let { it.add(cb); return }
        val prefs = App.instance.getSharedPreferences("sports", 0)
        val kept = fromJson(prefs.getString("teams", null))
        if (kept.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("at", 0) < MONTH) { teams = kept; cb(kept); return }
        val waiting = arrayListOf(cb); loading = waiting
        val all = ArrayList<Team>()
        fun next(i: Int) {
            if (i == LEAGUES.size) {
                // Téléchargement raté : on se rabat sur l'ancienne liste, sans la rendre définitive.
                val list = if (all.isEmpty()) kept else all
                if (all.isNotEmpty()) { teams = all; prefs.edit().putString("teams", toJson(all).toString()).putLong("at", System.currentTimeMillis()).apply() }
                loading = null
                waiting.forEach { it(list) }
                return
            }
            Covers.text("https://site.api.espn.com/apis/site/v2/sports/${LEAGUES[i]}/teams?limit=100") { text ->
                all.addAll(parse(text, LEAGUES[i]))
                next(i + 1)
            }
        }
        next(0)
    }

    /** Adresse des deux logos si [title] nomme deux équipes d'une même ligue, sinon une chaîne vide. */
    fun find(title: String, cb: (String) -> Unit) {
        if (norm(title).length < 6) { cb(""); return }
        load { list -> cb(match(title, list)?.let { pairUrl(it.first, it.second) }.orEmpty()) }
    }
}

/**
 * Relevé de ce que les lecteurs annoncent (titre, application, série…) et d'où est venue l'affiche. Sert à comprendre
 * pourquoi une affiche manque. Gardé sur la tablette (les 80 derniers titres différents), montré sur la page web.
 */
object PlayLog {
    private const val MAX = 80
    private var entries: JSONArray? = null

    private fun prefs() = App.instance.getSharedPreferences("playlog", 0)
    private fun all(): JSONArray = entries ?: (try { JSONArray(prefs().getString("log", "[]")) } catch (_: Exception) { JSONArray() }).also { entries = it }

    fun record(e: EntityState, w: Posters.What, source: String) {
        if (App.instance.config.demo || w.title.isEmpty() && w.series.isEmpty()) return
        val a = e.attrs
        val key = e.id + "|" + w.title + "|" + w.series
        val old = all()
        if (old.optJSONObject(0)?.let { it.optString("key") == key && it.optString("source") == source } == true) return
        val keep = JSONArray()
        keep.put(JSONObject().put("key", key).put("at", System.currentTimeMillis()).put("player", e.name).put("app", w.app)
            .put("title", w.title).put("series", w.series).put("artist", w.artist).put("type", a.str("media_content_type"))
            .put("contentId", w.contentId.take(80)).put("channel", w.channel.ifEmpty { a.str("media_channel") }).put("source", source).put("search", Posters.searchKey(w)))
        for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("key") != key && keep.length() < MAX) keep.put(o) }
        entries = keep
        prefs().edit().putString("log", keep.toString()).apply()
    }

    fun json(): JSONArray = all()
    fun clear() { entries = JSONArray(); prefs().edit().remove("log").apply() }
}
