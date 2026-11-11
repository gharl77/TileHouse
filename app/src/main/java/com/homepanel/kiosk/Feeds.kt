package com.homepanel.kiosk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Flux de nouvelles (RSS 2.0, RSS 1.0/RDF ou Atom) lus directement par la tablette à partir d'un lien XML.
 * Titre, résumé (texte sans HTML), date et image (enclosure, media:content, media:thumbnail ou 1re image du résumé).
 * Les flux sont gardés en mémoire 20 min ; les images, réduites, dans un petit cache.
 */
object Feeds {
    class Item(val title: String, val summary: String, val date: Long, val image: String?)
    class Feed(val items: List<Item>, val error: String?, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Feed>()
    private val loading = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(Feed) -> Unit>>()
    private val http by lazy {
        App.instance.ha.http.newBuilder().readTimeout(20, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build()
    }
    private const val TTL = 20 * 60_000L
    private const val MAX_FEED = 3 * 1024 * 1024
    private const val MAX_IMAGE = 6 * 1024 * 1024

    /** Lit au plus [max] octets : un lien qui renvoie un fichier énorme ne doit pas remplir la mémoire. */
    private fun limited(input: java.io.InputStream, max: Int): java.io.InputStream {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw java.io.IOException("too large")
        }
        return java.io.ByteArrayInputStream(out.toByteArray())
    }

    /** Dernière version connue (peut être vieille ou null). */
    fun cached(url: String): Feed? = cache[url]

    /** Charge le flux (depuis le cache s'il est récent, sauf [force]). [cb] est appelé sur le fil principal. */
    fun load(url: String, force: Boolean = false, cb: (Feed) -> Unit) {
        val c = cache[url]
        if (!force && c != null && System.currentTimeMillis() - c.at < TTL) { cb(c); return }
        if (url.startsWith("demo:")) { val f = Feed(demoItems(), null, System.currentTimeMillis()); cache[url] = f; cb(f); return }
        waiting.getOrPut(url) { ArrayList() }.add(cb)
        if (!loading.add(url)) return
        val req = try {
            Request.Builder().url(url).header("User-Agent", "TileHouse/" + App.instance.updater.installed)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*").build()
        } catch (_: Exception) { null }
        if (req == null) { finish(url, Feed(emptyList(), tr("Lien invalide"), System.currentTimeMillis())); return }
        // Dernières nouvelles connues, lues ici (fil principal) : un flux en panne garde ses nouvelles.
        val old = c?.items ?: emptyList()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                main.post { finish(url, Feed(old, e.message ?: tr("réseau"), System.currentTimeMillis())) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val feed = response.use { r ->
                    if (!r.isSuccessful) Feed(old, "HTTP ${r.code}", System.currentTimeMillis())
                    else try {
                        val items = parse(limited(r.body!!.byteStream(), MAX_FEED))
                        Feed(items, if (items.isEmpty()) tr("Aucune nouvelle dans ce flux") else null, System.currentTimeMillis())
                    } catch (e: Exception) {
                        Feed(old, tr("Ce lien n'est pas un flux RSS ou Atom"), System.currentTimeMillis())
                    }
                }
                main.post { finish(url, feed) }
            }
        })
    }

    /** Nouvelles fictives du mode démo (aucune requête réseau). */
    private fun demoItems(): List<Item> {
        val now = System.currentTimeMillis()
        val en = I18n.english
        fun i(t: String, f: String, s: String, fs: String, min: Int) = Item(if (en) t else f, if (en) s else fs, now - min * 60_000L, null)
        return listOf(
            i("Farmers' market opens on Saturday", "Le marché public ouvre samedi", "Forty local producers will be on site from 9 a.m., with a hot cider stand and activities for kids.", "Une quarantaine de producteurs seront sur place dès 9 h, avec un kiosque de cidre chaud et des activités pour les enfants.", 35),
            i("Main Street roadwork this week", "Travaux sur la rue Principale cette semaine", "One lane will be closed between 9 a.m. and 3 p.m. until Friday. Plan a few extra minutes.", "Une voie sera fermée de 9 h à 15 h jusqu'à vendredi. Prévoyez quelques minutes de plus.", 95),
            i("Library extends its opening hours", "La bibliothèque prolonge ses heures", "The branch will now close at 9 p.m. on weeknights, starting next Monday.", "La succursale fermera désormais à 21 h en semaine, dès lundi prochain.", 180),
            i("First snow expected this weekend", "Première neige attendue ce week-end", "Up to 5 cm could fall Sunday night. Time to think about winter tires.", "Jusqu'à 5 cm pourraient tomber dimanche soir. C'est le temps de penser aux pneus d'hiver.", 300),
            i("Hockey: the local team wins in overtime", "Hockey : l'équipe locale gagne en prolongation", "A goal in the final minute of overtime sealed a 3-2 win.", "Un but dans la dernière minute de la prolongation a scellé une victoire de 3 à 2.", 600),
            i("New bike path along the river", "Nouvelle piste cyclable le long de la rivière", "The 4 km path will connect the two main parks by next summer.", "La piste de 4 km reliera les deux grands parcs d'ici l'été prochain.", 1300))
    }

    private fun finish(url: String, f: Feed) {
        loading.remove(url)
        cache[url] = f
        waiting.remove(url)?.forEach { it(f) }
    }

    // ------------------------------------------------------------ lecture XML

    internal fun parse(input: java.io.InputStream): List<Item> {
        // Beaucoup de flux ne sont pas du XML strict (« & » seuls dans les liens d'images) : on les corrige avant la lecture.
        val bytes = input.readBytes()
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val cs = Regex("encoding=[\"']([\\w-]+)").find(head)?.groupValues?.get(1)?.let { try { charset(it) } catch (_: Exception) { null } } ?: Charsets.UTF_8
        val xml = String(bytes, cs).trimStart('\uFEFF').replace(Regex("&(?!#?[A-Za-z0-9]+;)"), "&amp;")
        val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) } catch (_: Exception) {}
        p.setInput(java.io.StringReader(xml))
        val out = ArrayList<Item>()
        var inItem = false
        var title = ""; var summary = ""; var content = ""; var date = 0L; var image: String? = null
        var sawFeed = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                val n = p.name.lowercase(Locale.ROOT)
                when {
                    n == "rss" || n == "feed" || n == "rdf:rdf" -> sawFeed = true
                    n == "item" || n == "entry" -> { inItem = true; title = ""; summary = ""; content = ""; date = 0L; image = null }
                    !inItem -> {}
                    n == "title" -> title = text(p)
                    n == "description" || n == "summary" -> summary = text(p)
                    n == "content" || n == "content:encoded" -> content = text(p)
                    n == "pubdate" || n == "published" || n == "updated" || n == "dc:date" -> { if (date == 0L || n != "updated") date = parseDate(text(p)) }
                    n == "media:thumbnail" -> p.getAttributeValue(null, "url")?.let { if (image == null) image = it }
                    n == "media:content" || n == "enclosure" -> {
                        val url = p.getAttributeValue(null, "url")
                        val type = p.getAttributeValue(null, "type").orEmpty()
                        val medium = p.getAttributeValue(null, "medium").orEmpty()
                        if (url != null && image == null && (type.startsWith("image") || medium == "image" || Regex("\\.(jpe?g|png|webp)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url))) image = url
                    }
                    n == "link" && p.getAttributeValue(null, "rel") == "enclosure" -> {
                        if (image == null && p.getAttributeValue(null, "type").orEmpty().startsWith("image")) image = p.getAttributeValue(null, "href")
                    }
                }
            } else if (p.eventType == XmlPullParser.END_TAG) {
                val n = p.name.lowercase(Locale.ROOT)
                if (inItem && (n == "item" || n == "entry")) {
                    inItem = false
                    val raw = summary.ifBlank { content }
                    if (image == null) image = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(raw.ifBlank { content })?.groupValues?.get(1)
                    val t = plain(title)
                    if (t.isNotEmpty()) out.add(Item(t, plain(raw).let { if (it == t) "" else it }, date, image?.replace("&amp;", "&")))
                }
            }
        }
        if (!sawFeed) throw IllegalArgumentException("not a feed")
        return out
    }

    /** Texte d'un élément, y compris s'il contient d'autres balises (Atom type="xhtml"). */
    private fun text(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                XmlPullParser.START_TAG -> { depth++; if (p.name.equals("img", true)) p.getAttributeValue(null, "src")?.let { sb.append("<img src=\"").append(it).append("\">") } }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString().trim()
    }

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "hellip" to "…", "ndash" to "–", "mdash" to "—", "euro" to "€", "deg" to "°", "copy" to "©",
        "agrave" to "à", "acirc" to "â", "auml" to "ä", "ccedil" to "ç", "eacute" to "é", "egrave" to "è",
        "ecirc" to "ê", "euml" to "ë", "icirc" to "î", "iuml" to "ï", "ocirc" to "ô", "ouml" to "ö",
        "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü", "oelig" to "œ", "aelig" to "æ",
        "Agrave" to "À", "Acirc" to "Â", "Ccedil" to "Ç", "Eacute" to "É", "Egrave" to "È", "Ecirc" to "Ê",
        "Icirc" to "Î", "Ocirc" to "Ô", "Ugrave" to "Ù", "Ucirc" to "Û", "OElig" to "Œ")

    /** Texte simple d'un fragment HTML : sans balises ni images, entités décodées, espaces regroupés. */
    internal fun plain(s: String): String {
        if (s.isBlank()) return ""
        val noTags = s.replace(Regex("<(br|/p|/div|/li)[^>]*>", RegexOption.IGNORE_CASE), " ").replace(Regex("<[^>]*>"), "")
        val decoded = Regex("&(#[xX]?[0-9a-fA-F]+|[A-Za-z]+);").replace(noTags) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> ENTITIES[e]
            } ?: m.value
        }
        return decoded.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")

    internal fun parseDate(s: String): Long {
        val v = s.trim()
        for (f in DATE_FORMATS) try {
            val fmt = SimpleDateFormat(f, Locale.US)
            if (f.endsWith("'Z'")) fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.parse(v)?.time ?: continue
        } catch (_: Exception) {}
        return 0L
    }

    // ------------------------------------------------------------ images

    private val images = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val imgWaiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Vignette réduite à environ [px] pixels de large. */
    fun image(url: String, px: Int, cb: (Bitmap?) -> Unit) {
        images.get(url)?.let { cb(it); return }
        val list = imgWaiting[url]
        if (list != null) { list.add(cb); return }
        imgWaiting[url] = arrayListOf(cb)
        val req = try { Request.Builder().url(url).header("User-Agent", "TileHouse/" + App.instance.updater.installed).build() } catch (_: Exception) { null }
        fun done(b: Bitmap?) { if (b != null) images.put(url, b); imgWaiting.remove(url)?.forEach { it(b) } }
        if (req == null) { done(null); return }
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { main.post { done(null) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bytes = try { response.use { if (it.isSuccessful) it.body?.byteStream()?.let { s -> limited(s, MAX_IMAGE).readBytes() } else null } } catch (_: Exception) { null }
                val bmp = bytes?.let {
                    try {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(it, 0, it.size, o)
                        var s = 1
                        while (o.outWidth / (s * 2) >= px) s *= 2
                        BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = s })
                    } catch (_: Throwable) { null }
                }
                main.post { done(bmp) }
            }
        })
    }
}
