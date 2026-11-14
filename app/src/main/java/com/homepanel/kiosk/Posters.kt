package com.homepanel.kiosk

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Affiche de ce qu'un lecteur joue. Dans l'ordre : l'image que donne Home Assistant, la vignette YouTube, puis une
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
        val terms: List<String> get() = listOf(term, term.split(Regex("\\s+[-–—:|]\\s+|\\s*:\\s+"))[0].trim()).filter { it.length >= 2 }.distinct()

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

    fun fromTvmaze(text: String?): String = try {
        JSONObject(text ?: "").optJSONObject("image")?.let { it.optString("original").ifEmpty { it.optString("medium") } }.orEmpty().replace("http://", "https://")
    } catch (_: Exception) { "" }

    fun fromItunes(text: String?): String = try {
        JSONObject(text ?: "").optJSONArray("results")?.optJSONObject(0)?.optString("artworkUrl100").orEmpty().replace("100x100bb", "600x600bb")
    } catch (_: Exception) { "" }

    fun fromTmdb(text: String?): String = try {
        val r = JSONObject(text ?: "").optJSONArray("results") ?: JSONArray()
        fun path(o: JSONObject, k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotEmpty() }
        // Le cadre est en largeur : on préfère l'image en largeur de TMDB, sinon l'affiche (rognée).
        (0 until r.length()).mapNotNull { r.optJSONObject(it) }.firstOrNull { path(it, "backdrop_path") != null || path(it, "poster_path") != null }
            ?.let { o -> path(o, "backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ("https://image.tmdb.org/t/p/w342" + path(o, "poster_path")) }.orEmpty()
    } catch (_: Exception) { "" }

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

    /** Essai de la clé TMDB sur un film connu : [cb] reçoit le code HTTP (200 = clé acceptée) et si une affiche est revenue. */
    fun testTmdb(cb: (Int, Boolean) -> Unit) {
        val k = App.instance.config.tmdbKey
        if (k.isEmpty()) { cb(-1, false); return }
        Covers.textCode("https://api.themoviedb.org/3/search/multi?query=Inception&language=fr-CA&api_key=${Uri.encode(k)}") { text, code -> cb(code, fromTmdb(text).isNotEmpty()) }
    }

    /**
     * Adresse de l'affiche pour [e] ([cb] reçoit une chaîne vide s'il n'y en a pas). Répond tout de suite quand
     * Home Assistant donne l'image ou que l'affiche est déjà connue ; sinon après la recherche.
     */
    fun find(e: EntityState, cb: (String) -> Unit) {
        val w = what(e)
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
        val steps = ArrayList<Triple<String, String, (String?) -> String>>()
        if (w.youtube) {
            // Sans l'identifiant de la vidéo, on cherche le titre et la chaîne sur YouTube.
            val k = cfg.youtubeKey
            if (k.isNotEmpty()) steps.add(Triple("YouTube (clé)", "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q=$q&key=${Uri.encode(k)}", ::fromYoutube))
            // Sans clé (ou si elle échoue) : la page de recherche de YouTube, lue comme le ferait un navigateur.
            steps.add(Triple("YouTube", "https://www.youtube.com/results?search_query=$q", ::fromYoutubePage))
        } else if (w.video) {
            val k = cfg.tmdbKey
            // Le titre complet d'abord, puis la série seule quand le titre contient aussi l'épisode.
            for (term in w.terms) {
                val t = Uri.encode(term)
                if (k.isNotEmpty()) steps.add(Triple("TMDB", "https://api.themoviedb.org/3/search/multi?query=$t&include_adult=false&language=fr-CA&api_key=${Uri.encode(k)}", ::fromTmdb))
                steps.add(Triple("TVmaze", "https://api.tvmaze.com/singlesearch/shows?q=$t", ::fromTvmaze))
            }
            // Une chaîne de télé en direct ne passe pas par iTunes : il renverrait un film au titre voisin.
            if (w.channel.isEmpty()) steps.add(Triple("iTunes", "https://itunes.apple.com/search?term=$q&media=movie&limit=1", ::fromItunes))
        } else {
            steps.add(Triple("iTunes", "https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=1", ::fromItunes))
            steps.add(Triple("iTunes", "https://itunes.apple.com/search?term=$q&media=podcast&limit=1", ::fromItunes))
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
            Covers.text(steps[i].second) { text ->
                val url = steps[i].third(text)
                if (url.isNotEmpty()) done(url, steps[i].first) else next(i + 1)
            }
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
