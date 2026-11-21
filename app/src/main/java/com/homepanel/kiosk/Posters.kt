package com.homepanel.kiosk

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Affiche de ce qu'un lecteur joue. Pour la musique, la photo de l'artiste (Deezer). Sinon, dans l'ordre : l'image que donne Home Assistant, la vignette YouTube, puis une
 * recherche par titre sur Internet (TMDB si une clé est fournie, TVmaze, iTunes). La recherche se coupe dans les réglages.
 * Chaque titre n'est cherché qu'une fois ; une affiche introuvable est retenue aussi, pour ne pas redemander.
 */
object Posters {
    private val found = HashMap<String, String>()
    private val pending = HashMap<String, ArrayList<(String) -> Unit>>()
    // Le nom de l'application dit si c'est de l'audio ; son identifiant aussi, mais sans « radio » (ca.radio-canada.toutv est de la vidéo).
    private val AUDIO_APP = Regex("music|musique|radio|podcast|balado|spotify|tunein|sonos|airplay|assistant|deezer|tidal|audible")
    private val AUDIO_ID = Regex("music|podcast|spotify|tunein|sonos|deezer|tidal|audible|music_assistant")

    /** Ce qu'on sait du titre en cours, d'après les attributs du lecteur. */
    class What(val title: String, val series: String, val artist: String, val app: String, val contentId: String, val video: Boolean) {
        /** Ce qu'on tape dans un moteur : la série plutôt que l'épisode, l'artiste avec la chanson. */
        val term: String get() = when {
            youtube -> listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" ")
            series.isNotEmpty() -> series
            video || artist.isEmpty() -> title
            else -> "$artist $title"
        }
        val youtube: Boolean get() = "youtube" in app.lowercase() || "youtu" in contentId.lowercase()

        /**
         * Ce qu'on essaie tour à tour : le titre tel quel, puis ce qui précède un tiret ou un deux-points
         * (« Tatoué Rodéo - Ayer's Cliff » est une série suivie d'un épisode).
         */
        val terms: List<String> get() = listOf(term, term.split(Regex("\\s+[-–—:|]\\s+|\\s*:\\s+"))[0].trim()
            // Un seul mot court avant le tiret (« Coupe - Alouettes contre Elks ») est trop vague pour chercher une émission.
            .takeIf { it.length >= 8 || it.contains(' ') }.orEmpty()).filter { it.length >= 2 }.distinct()

        /** Chaîne de télé, quand l'application (Télé Fibe) donne son code comme identifiant de contenu. */
        val channel: String get() = Channels.name(contentId)
        /** La chaîne avec son numéro, pour l'affichage. */
        val channelLabel: String get() = Channels.label(contentId)
    }

    /** Le lecteur sait s'éteindre (fonction TURN_OFF de Home Assistant, bit 256). */
    fun canTurnOff(e: EntityState) = e.attrs.optInt("supported_features", 0) and 256 != 0

    /** Le lecteur propose des applications ou des sources à lancer (fonction SELECT_SOURCE, bit 2048, et une liste non vide). */
    fun canSelectSource(e: EntityState) = e.attrs.optInt("supported_features", 0) and 2048 != 0 && (e.attrs.optJSONArray("source_list")?.length() ?: 0) > 0

    fun what(e: EntityState): What {
        val a = e.attrs
        val type = a.str("media_content_type").lowercase()
        val app = a.str("app_name").ifEmpty { a.str("app_id") }.ifEmpty { a.str("source") }
        // Une Apple TV ne dit pas le type de contenu : une application qui n'est pas de la musique est prise pour de la vidéo.
        val video = type in setOf("video", "tvshow", "movie", "episode", "channel") || a.str("device_class") == "tv" ||
            type !in setOf("music", "podcast", "playlist") && a.str("app_id").isNotEmpty() && !AUDIO_APP.containsMatchIn(app.lowercase()) && !AUDIO_ID.containsMatchIn(a.str("app_id").lowercase())
        return What(a.str("media_title").trim(), a.str("media_series_title").trim(), a.str("media_artist").trim(), app, a.str("media_content_id"), video)
    }

    /** Identifiant d'une vidéo YouTube dans une adresse ou un identifiant de contenu (null s'il n'y en a pas). */
    fun youtubeId(contentId: String): String? {
        Regex("(?:v=|youtu\\.be/|/embed/|/shorts/|/vi/)([A-Za-z0-9_-]{11})").find(contentId)?.let { return it.groupValues[1] }
        return contentId.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) && it.any { c -> c.isDigit() || c.isUpperCase() || c == '_' || c == '-' } }
    }

    /** Miniature en 16:9, sans bandes noires ; le chargeur d'images se rabat sur la petite version si la grande n'existe pas. */
    fun youtubeThumb(id: String) = "https://i.ytimg.com/vi/$id/hq720.jpg"

    // --- Réponses des services de recherche (une adresse d'image, ou vide)

    /** Titre réduit à l'essentiel pour comparer : minuscules, sans accents ni ponctuation, sans article en tête. */
    fun plainTitle(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("^(le|la|les|l|the|un|une|a|an) "), "")

    /**
     * Le résultat [found] est-il bien l'émission cherchée ([wanted]) ? Oui si les titres sont les mêmes, ou si l'un commence
     * par l'autre, qui fait au moins deux mots, sans être beaucoup plus long (« Occupation Double Panamá » pour « Occupation double »). Les moteurs de
     * recherche renvoient toujours quelque chose : sans cette vérification, « Sports 30 » recevait l'affiche d'une autre émission.
     */
    fun sameTitle(wanted: String, found: String): Boolean {
        val w = plainTitle(wanted); val f = plainTitle(found)
        if (w.length < 2 || f.length < 2) return false
        if (w == f) return true
        val (short, long) = if (w.length <= f.length) w to f else f to w
        // Un seul mot ne suffit pas pour reconnaître un titre plus long (« Sports » n'est pas « Sports 30 »).
        return long.startsWith("$short ") && short.contains(' ') && short.length >= long.length * 0.6
    }

    /** TVmaze renvoie l'émission la plus proche du titre : on ne la garde que si c'est bien [query] (vide = sans vérification). */
    fun fromTvmaze(text: String?, query: String = ""): String = try {
        val o = JSONObject(text ?: "")
        if (query.isNotEmpty() && !sameTitle(query, o.optString("name"))) ""
        else o.optJSONObject("image")?.let { it.optString("original").ifEmpty { it.optString("medium") } }.orEmpty().replace("http://", "https://")
    } catch (_: Exception) { "" }

    /** iTunes : le premier résultat, seulement si son titre est bien [query] (vide = sans vérification). */
    fun fromItunes(text: String?, query: String = ""): String = try {
        val o = JSONObject(text ?: "").optJSONArray("results")?.optJSONObject(0)
        if (query.isNotEmpty() && (o == null || !sameTitle(query, o.optString("trackName")))) ""
        else o?.optString("artworkUrl100").orEmpty().replace("100x100bb", "600x600bb")
    } catch (_: Exception) { "" }

    /** Un film ou une série dans une réponse de recherche de TMDB : ses titres visibles (traduit et d'origine) et son image. */
    class Tmdb(val type: String, val id: Long, val titles: List<String>, val image: String, val popularity: Double, val local: Boolean)

    /** Films et séries d'une réponse search/multi qui ont une image, dans l'ordre de TMDB. Jamais une personne. */
    fun tmdbCandidates(text: String?): List<Tmdb> = try {
        val r = JSONObject(text ?: "").optJSONArray("results") ?: JSONArray()
        fun path(o: JSONObject, k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotEmpty() }
        (0 until r.length()).mapNotNull { r.optJSONObject(it) }.filter { it.optString("media_type") != "person" }.mapNotNull { o ->
            // Le cadre est en largeur : on préfère l'image en largeur de TMDB, sinon l'affiche (rognée).
            val image = path(o, "backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" } ?: path(o, "poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" } ?: return@mapNotNull null
            val type = o.optString("media_type").ifEmpty { if (o.has("name")) "tv" else "movie" }
            val local = o.optString("original_language") == "fr" || (o.optJSONArray("origin_country")?.let { c -> (0 until c.length()).any { c.optString(it) == "CA" } } ?: false)
            Tmdb(type, o.optLong("id"), listOf("name", "title", "original_name", "original_title").map { o.optString(it) }.filter { it.isNotEmpty() }.distinct(), image, o.optDouble("popularity", 0.0), local)
        }
    } catch (_: Exception) { emptyList() }

    /**
     * Les candidats dont un titre visible est bien [query], le meilleur d'abord : le titre exact, puis une série quand on
     * regarde une chaîne de télé ([tv]), puis une production canadienne ou en français, puis la plus populaire.
     */
    fun tmdbMatches(all: List<Tmdb>, query: String, tv: Boolean): List<Tmdb> = all.filter { c -> c.titles.any { sameTitle(query, it) } }
        .sortedWith(compareByDescending<Tmdb> { c -> c.titles.any { plainTitle(it) == plainTitle(query) } }
            .thenByDescending { tv && it.type == "tv" }.thenByDescending { it.local }.thenByDescending { it.popularity })

    /**
     * Tous les titres d'une fiche de TMDB demandée avec « alternative_titles,translations » : le titre d'origine, le titre
     * traduit, les titres par pays (dont celui du Québec) et ceux de chaque traduction. C'est ce qui permet de reconnaître
     * un film doublé sous le titre que donne le télédistributeur.
     */
    fun tmdbTitles(detail: String?): List<String> = try {
        val o = JSONObject(detail ?: "")
        val out = ArrayList<String>()
        for (k in listOf("original_title", "original_name", "title", "name")) out.add(o.optString(k))
        o.optJSONObject("alternative_titles")?.let { a -> (a.optJSONArray("titles") ?: a.optJSONArray("results"))?.let { l -> for (i in 0 until l.length()) out.add(l.optJSONObject(i)?.optString("title").orEmpty()) } }
        o.optJSONObject("translations")?.optJSONArray("translations")?.let { l ->
            for (i in 0 until l.length()) l.optJSONObject(i)?.optJSONObject("data")?.let { d -> out.add(d.optString("title")); out.add(d.optString("name")) }
        }
        out.filter { it.isNotBlank() }.distinct()
    } catch (_: Exception) { emptyList() }

    /** Titre d'origine d'une fiche de TMDB. */
    fun tmdbOriginal(detail: String?): String = try { JSONObject(detail ?: "").let { it.optString("original_title").ifEmpty { it.optString("original_name") } } } catch (_: Exception) { "" }

    /** Réseaux qui diffusent une série, d'après sa fiche de TMDB. */
    fun tmdbNetworks(detail: String?): List<String> = try {
        JSONObject(detail ?: "").optJSONArray("networks")?.let { l -> (0 until l.length()).map { l.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty().filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    /** La chaîne regardée ([channel], « ICI Télé ») est-elle l'un de ces réseaux (« ICI Radio-Canada Télé ») ? Tous ses mots doivent s'y trouver. */
    fun onNetwork(channel: String, networks: List<String>): Boolean {
        val want = plainTitle(channel).split(' ').filter { it.isNotEmpty() }
        return want.isNotEmpty() && networks.any { n -> plainTitle(n).split(' ').containsAll(want) }
    }

    /**
     * Réponse de TMDB (search/multi). Avec [query], on ne garde que les films et les séries dont le titre visible est bien
     * celui cherché (voir [tmdbMatches]). Sans [query] : le premier résultat qui a une image.
     */
    fun fromTmdb(text: String?, query: String = "", tv: Boolean = false): String =
        tmdbCandidates(text).let { all -> if (query.isEmpty()) all.firstOrNull() else tmdbMatches(all, query, tv).firstOrNull() }?.image.orEmpty()

    /**
     * Recherche sur TMDB en plusieurs temps. [cb] reçoit l'image (vide si rien n'est sûr) et une précision pour le relevé.
     *  1. La recherche par titre ; un résultat dont le titre visible est le bon est pris tel quel.
     *  2. Aucun titre visible ne correspond (film ou série doublés, connus de TMDB sous un autre titre) : on lit la fiche
     *     complète des trois premiers résultats et on compare avec tous leurs titres, d'origine, traduits et par pays.
     *  3. Plusieurs séries portent ce titre et l'on regarde une chaîne de télé : celle que diffuse cette chaîne l'emporte.
     */
    private fun tmdbFind(term: String, channel: String, key: String, cb: (String, String) -> Unit) {
        val k = Uri.encode(key)
        fun detail(c: Tmdb, done: (String?) -> Unit) =
            Covers.text("https://api.themoviedb.org/3/${c.type}/${c.id}?language=fr-CA&append_to_response=alternative_titles,translations&api_key=$k", done)
        Covers.text("https://api.themoviedb.org/3/search/multi?query=${Uri.encode(term)}&include_adult=false&language=fr-CA&api_key=$k") { text ->
            val all = tmdbCandidates(text)
            val direct = tmdbMatches(all, term, channel.isNotEmpty())
            val series = direct.filter { it.type == "tv" }.take(3)
            when {
                direct.isEmpty() -> {
                    val tries = all.take(3)
                    fun next(i: Int) {
                        if (i >= tries.size) { cb("", ""); return }
                        detail(tries[i]) { d -> if (tmdbTitles(d).any { sameTitle(term, it) }) cb(tries[i].image, " · " + tr("titre original :") + " " + tmdbOriginal(d)) else next(i + 1) }
                    }
                    next(0)
                }
                channel.isNotEmpty() && series.size > 1 -> {
                    fun next(i: Int) {
                        if (i >= series.size) { cb(direct[0].image, ""); return }
                        detail(series[i]) { d -> if (onNetwork(channel, tmdbNetworks(d))) cb(series[i].image, " · " + tr("diffusé par") + " " + channel) else next(i + 1) }
                    }
                    next(0)
                }
                else -> cb(direct[0].image, "")
            }
        }
    }

    fun fromYoutube(text: String?): String = try {
        val item = JSONObject(text ?: "").optJSONArray("items")?.optJSONObject(0)
        // L'identifiant de la vidéo donne la miniature 16:9 ; celles que liste la réponse ont des bandes noires.
        item?.optJSONObject("id")?.optString("videoId")?.takeIf { it.length == 11 }?.let { youtubeThumb(it) } ?: item?.optJSONObject("snippet")?.optJSONObject("thumbnails")
            ?.let { t -> (t.optJSONObject("high") ?: t.optJSONObject("medium") ?: t.optJSONObject("default"))?.optString("url") }.orEmpty()
    } catch (_: Exception) { "" }

    /** Page de résultats de YouTube : la première vidéo trouvée (repli sans clé, quand le lecteur ne donne que le titre). */
    fun fromYoutubePage(html: String?): String =
        Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"|videoId\\\\x22:\\\\x22([A-Za-z0-9_-]{11})").find(html ?: "")
            ?.let { youtubeThumb(it.groupValues[1].ifEmpty { it.groupValues[2] }) }.orEmpty()

    /** Premier artiste d'un crédit (« A, B », « A feat. B », « A & B ») : on essaie le nom entier, puis celui-là. */
    fun artistNames(artist: String): List<String> =
        listOf(artist.trim(), artist.split(Regex("\\s*[,;/]\\s*|\\s+(?:feat\\.?|ft\\.?|featuring|avec|with|x|&|et|and)\\s+", RegexOption.IGNORE_CASE))[0].trim()).filter { it.length >= 2 }.distinct()

    /**
     * Photo de l'artiste dans une réponse de recherche de Deezer : le premier résultat qui porte bien le nom [name]
     * (la recherche est approximative) et qui a une vraie photo (Deezer renvoie une silhouette grise sinon).
     */
    fun fromDeezerArtist(text: String?, name: String): String = try {
        val want = Sports.norm(name)
        val r = JSONObject(text ?: "").optJSONArray("data") ?: JSONArray()
        (0 until r.length()).mapNotNull { r.optJSONObject(it) }.firstOrNull { Sports.norm(it.optString("name")) == want }
            ?.let { it.optString("picture_xl").ifEmpty { it.optString("picture_big") } }?.takeIf { "/artist//" !in it }.orEmpty()
    } catch (_: Exception) { "" }

    /** Essai de la clé TMDB sur un film connu : [cb] reçoit le code HTTP (200 = clé acceptée) et si une affiche est revenue. */
    fun testTmdb(cb: (Int, Boolean) -> Unit) {
        val k = App.instance.config.tmdbKey
        if (k.isEmpty()) { cb(-1, false); return }
        Covers.textCode("https://api.themoviedb.org/3/search/multi?query=Inception&language=fr-CA&api_key=${Uri.encode(k)}") { text, code -> cb(code, fromTmdb(text, "Inception").isNotEmpty()) }
    }

    /**
     * Adresse de l'affiche pour [e] ([cb] reçoit une chaîne vide s'il n'y en a pas). Répond tout de suite quand
     * Home Assistant donne l'image ou que l'affiche est déjà connue ; sinon après la recherche.
     */
    fun find(e: EntityState, cb: (String) -> Unit) {
        val w = what(e)
        val cfg = App.instance.config
        // Musique : la photo de l'artiste plutôt que la pochette ; sans photo trouvée, on retombe sur l'image habituelle.
        if (!w.video && !w.youtube && w.artist.length >= 2 && cfg.posterSearch && !cfg.demo) {
            val key = "a|" + w.artist.lowercase()
            found[key]?.let { if (it.isNotEmpty()) { cb(it); return } else { cover(e, w, cb); return } }
            pending[key]?.let { it.add { url -> if (url.isNotEmpty()) cb(url) else cover(e, w, cb) }; return }
            pending[key] = arrayListOf({ url -> if (url.isNotEmpty()) cb(url) else cover(e, w, cb) })
            val names = artistNames(w.artist)
            fun next(i: Int) {
                if (i >= names.size) { found[key] = ""; pending.remove(key)?.forEach { it("") }; return }
                Covers.text("https://api.deezer.com/search/artist?limit=5&q=" + Uri.encode(names[i])) { text ->
                    val url = fromDeezerArtist(text, names[i])
                    if (url.isEmpty()) next(i + 1)
                    else { if (found.size > 300) found.clear(); found[key] = url; PlayLog.record(e, w, tr("photo de l'artiste")); pending.remove(key)?.forEach { it(url) } }
                }
            }
            next(0)
            return
        }
        cover(e, w, cb)
    }

    /** L'image habituelle : celle de Home Assistant, la vignette YouTube, puis la recherche par titre. */
    private fun cover(e: EntityState, w: What, cb: (String) -> Unit) {
        val pic = e.attrs.str("entity_picture")
        if (pic.isNotEmpty()) { PlayLog.record(e, w, "Home Assistant"); cb(pic); return }
        if (w.youtube) youtubeId(w.contentId)?.let { PlayLog.record(e, w, "YouTube"); cb(youtubeThumb(it)); return }
        val cfg = App.instance.config
        if (!cfg.posterSearch || cfg.demo || w.term.length < 2) { PlayLog.record(e, w, "—"); cb(""); return }
        val key = (if (w.youtube) "yt|" else if (w.video) "v|" else "m|") + w.term.lowercase()
        found[key]?.let { cb(it); return }
        pending[key]?.let { it.add(cb); return }
        pending[key] = arrayListOf(cb)
        val q = Uri.encode(w.term)
        // Étapes : nom de la source (pour le relevé), adresse, lecture de la réponse.
        val steps = ArrayList<Pair<String, ((String, String) -> Unit) -> Unit>>()
        fun simple(name: String, url: String, read: (String?) -> String) = steps.add(name to { cb2 -> Covers.text(url) { cb2(read(it), "") } })
        if (w.youtube) {
            // Sans l'identifiant de la vidéo, on cherche le titre et la chaîne sur YouTube.
            val k = cfg.youtubeKey
            if (k.isNotEmpty()) simple("YouTube (clé)", "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q=$q&key=${Uri.encode(k)}", ::fromYoutube)
            // Sans clé (ou si elle échoue) : la page de recherche de YouTube, lue comme le ferait un navigateur.
            simple("YouTube", "https://www.youtube.com/results?search_query=$q", ::fromYoutubePage)
        } else if (w.video) {
            val k = cfg.tmdbKey
            // Le titre complet d'abord, puis la série seule quand le titre contient aussi l'épisode.
            for (term in w.terms) {
                val t = Uri.encode(term)
                if (k.isNotEmpty()) steps.add("TMDB" to { cb2 -> tmdbFind(term, w.channel, k, cb2) })
                simple("TVmaze", "https://api.tvmaze.com/singlesearch/shows?q=$t") { fromTvmaze(it, term) }
            }
            // Une chaîne de télé en direct ne passe pas par iTunes : il renverrait un film au titre voisin.
            if (w.channel.isEmpty()) simple("iTunes", "https://itunes.apple.com/search?term=$q&media=movie&limit=1") { fromItunes(it, w.term) }
        } else {
            simple("iTunes", "https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=1") { fromItunes(it) }
            simple("iTunes", "https://itunes.apple.com/search?term=$q&media=podcast&limit=1") { fromItunes(it) }
        }
        fun done(url: String, source: String) {
            if (found.size > 300) found.clear()
            found[key] = url
            PlayLog.record(e, w, source)
            pending.remove(key)?.forEach { it(url) }
        }
        fun next(i: Int) {
            if (i >= steps.size) {
                // Dernier recours pour la télé en direct : le logo de la chaîne.
                val logo = Channels.logo(w.contentId)
                if (logo.isNotEmpty()) done(logo, tr("logo de la chaîne")) else done("", tr("aucune affiche"))
                return
            }
            steps[i].second { url, note -> if (url.isNotEmpty()) done(url, steps[i].first + note) else next(i + 1) }
        }
        // Un match : deux équipes d'une même ligue dans le titre. On le vérifie avant la recherche par titre, qui
        // renverrait volontiers l'affiche d'une émission sans rapport.
        if (w.video && !w.youtube) Sports.find(listOf(w.title, w.series, w.artist).joinToString(" ")) { pair -> if (pair.isNotEmpty()) done(pair, tr("logos d'équipes")) else next(0) }
        else next(0)
    }
}

/**
 * Chaînes de télé : Télé Fibe donne le code de la chaîne (« RDSHD ») comme identifiant de contenu. On en tire un nom
 * lisible et l'adresse d'un logo, dans une collection publique de logos de chaînes canadiennes (tv-logo/tv-logos).
 */
object Channels {
    private const val BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/canada/"

    /** Code sans « HD » → nom affiché et nom du fichier du logo. Complétée au fil de ce que montre le relevé. */
    private val KNOWN = mapOf(
        "RDS" to ("RDS" to "rds"), "RDS2" to ("RDS 2" to "rds-2"), "RDSI" to ("RDS Info" to "rds-info"), "RDSINFO" to ("RDS Info" to "rds-info"),
        "TVA" to ("TVA" to "tva"), "CFTM" to ("TVA" to "tva"), "TVASP" to ("TVA Sports" to "tva-sports"), "TVAS" to ("TVA Sports" to "tva-sports"), "TVASPORTS" to ("TVA Sports" to "tva-sports"),
        "SRC" to ("ICI Télé" to "ici-tele"), "CBFT" to ("ICI Télé" to "ici-tele"), "ICITELE" to ("ICI Télé" to "ici-tele"), "RDI" to ("ICI RDI" to "ici-rdi"),
        "ARTV" to ("ICI ARTV" to "ici-artv"), "EXPLORA" to ("ICI Explora" to "ici-explora"), "TSN" to ("TSN" to "tsn"), "SN" to ("Sportsnet" to "sportsnet"),
"NVQB" to ("Noovo" to "noovo"), "TVAQH" to ("TVA" to "tva"), "TVAQ" to ("TVA" to "tva"), "NOOVO" to ("Noovo" to "noovo"),
        "CBC" to ("CBC" to "cbc-television"), "CBMT" to ("CBC" to "cbc-television"),
    )

    /** Un code de chaîne : quelques lettres ou chiffres en majuscules (pas une adresse, pas un identifiant YouTube). */
    fun code(contentId: String): String? = contentId.trim().takeIf { Regex("^[A-Z][A-Z0-9+]{1,9}$").matches(it) }?.removeSuffix("HD")?.removeSuffix("4K")?.takeIf { it.length >= 2 }

    /** Noms et numéros saisis dans les réglages : {code: {name, number}}. Vide hors de l'application (tests). */
    var custom: () -> JSONObject = { try { JSONObject(App.instance.config.fibeChannels) } catch (_: Throwable) { JSONObject() } }

    fun name(contentId: String): String = code(contentId)?.let { c -> custom().optJSONObject(c)?.optString("name")?.takeIf { it.isNotBlank() } ?: KNOWN[c]?.first ?: c }.orEmpty()

    /** Numéro de la chaîne chez l'abonné (saisi dans les réglages : l'application ne le transmet pas). */
    fun number(contentId: String): String = code(contentId)?.let { custom().optJSONObject(it)?.optString("number")?.trim() }.orEmpty()

    /** « RDS · chaîne 1125 », ou le nom seul sans numéro connu. */
    fun label(contentId: String): String = listOf(name(contentId), number(contentId).takeIf { it.isNotEmpty() }?.let { tr("chaîne") + " " + it }.orEmpty()).filter { it.isNotEmpty() }.joinToString("  ·  ")

    /** Adresse du logo ; pour un code inconnu, on tente le fichier du même nom (l'image ne s'affiche simplement pas s'il n'existe pas). */
    fun logo(contentId: String): String = code(contentId)?.let { BASE + (KNOWN[it]?.second ?: it.lowercase()) + "-ca.png" }.orEmpty()

    fun isLogo(url: String) = url.startsWith(BASE) || url.startsWith("teams:")
}

/** Icône « télé » du bandeau : visible quand un lecteur joue, avec leur nombre s'il y en a plusieurs. La toucher montre les affiches. */
class NowPlayingChip(ctx: Context) : android.widget.FrameLayout(ctx) {
    private var onOpen: () -> Unit = {}
    private val badge = ctx.label(12f, Skin.onPrimary, true).apply {
        gravity = android.view.Gravity.CENTER
        background = roundRect(Skin.accent, ctx.dpf(10))
        setPadding(ctx.dp(5), 0, ctx.dp(5), 0)
        minWidth = ctx.dp(20)
        visibility = View.GONE
    }

    init {
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_tv)
            imageTintList = android.content.res.ColorStateList.valueOf(Skin.accent)
        }, LayoutParams(ctx.dp(30), ctx.dp(30), android.view.Gravity.CENTER))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(20), android.view.Gravity.TOP or android.view.Gravity.END))
        contentDescription = tr("En lecture")
        visibility = View.GONE
        isClickable = true
        setOnClickListener { onOpen() }
    }

    fun set(count: Int, onOpen: () -> Unit) {
        this.onOpen = onOpen
        visibility = if (count > 0) View.VISIBLE else View.GONE
        badge.text = count.toString()
        badge.visibility = if (count > 1) View.VISIBLE else View.GONE
    }
}
