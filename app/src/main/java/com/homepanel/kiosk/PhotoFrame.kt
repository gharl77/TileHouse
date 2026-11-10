package com.homepanel.kiosk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Photos du cadre photo : un dossier de médias de Home Assistant (ex. album ou favoris Immich,
 * « media-source://immich/… »). Liste rafraîchie toutes les 6 h ; chaque photo est résolue par HA
 * (URL signée) puis réduite à la taille de l'écran.
 */
object PhotoFrame {
    private val main = Handler(Looper.getMainLooper())
    private var items: List<String> = emptyList()
    private var source = ""
    private var loadedAt = 0L
    private var index = 0
    private val http by lazy { App.instance.ha.http.newBuilder().readTimeout(40, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build() }

    /** Dossiers proposés dans les réglages : (nom, media_content_id), en parcourant Immich sur deux niveaux. */
    fun sources(cb: (List<Pair<String, String>>) -> Unit) {
        val out = ArrayList<Pair<String, String>>()
        browse("media-source://immich") { root ->
            val users = root?.optJSONArray("children")
            if (users == null || users.length() == 0) { cb(out); return@browse }
            var left = users.length()
            for (i in 0 until users.length()) {
                val u = users.getJSONObject(i)
                browse(u.optString("media_content_id")) { user ->
                    val kids = user?.optJSONArray("children")
                    var pending = 1
                    fun done() { if (--pending == 0 && --left == 0) cb(out.sortedBy { it.first.lowercase() }) }
                    if (kids != null) for (k in 0 until kids.length()) {
                        val d = kids.getJSONObject(k)
                        val title = d.optString("title"); val id = d.optString("media_content_id")
                        if (title == "albums") {
                            pending++
                            browse(id) { albums ->
                                albums?.optJSONArray("children")?.let { a ->
                                    for (j in 0 until a.length()) a.getJSONObject(j).let { out.add("Album · " + it.optString("title") to it.optString("media_content_id")) }
                                }
                                done()
                            }
                        } else if (title == "favorites") out.add(tr("Favoris") to id)
                    }
                    done()
                }
            }
        }
    }

    private fun browse(id: String, cb: (JSONObject?) -> Unit) {
        App.instance.ha.request(JSONObject().put("type", "media_source/browse_media").put("media_content_id", id)) { res ->
            cb(if (res.optBoolean("success")) res.optJSONObject("result") else null)
        }
    }

    /** Photo suivante (taille max [w]×[h]), ou null si aucune n'est disponible. */
    fun next(w: Int, h: Int, cb: (Bitmap?) -> Unit) {
        val cfg = App.instance.config
        val src = cfg.photoSource
        if (src.isEmpty() || App.instance.ha.demo) { cb(null); return }
        val age = System.currentTimeMillis() - loadedAt
        // Liste rechargée si la source change, toutes les 6 h, ou après 2 min si elle était vide (pas en boucle).
        if (src != source || age > 6 * 3_600_000L || (items.isEmpty() && age > 120_000L)) {
            browse(src) { dir ->
                val a = dir?.optJSONArray("children")
                val list = ArrayList<String>()
                if (a != null) for (i in 0 until a.length()) a.getJSONObject(i).let {
                    // Images seulement (l'album contient aussi des vidéos).
                    if (it.optString("media_class") == "image" && !it.optBoolean("can_expand")) list.add(it.optString("media_content_id"))
                }
                items = list.shuffled(); source = src; loadedAt = System.currentTimeMillis(); index = 0
                if (items.isEmpty()) cb(null) else fetch(items[index++ % items.size], w, h, cb)
            }
            return
        }
        if (items.isEmpty()) { cb(null); return }
        fetch(items[index++ % items.size], w, h, cb)
    }

    private fun fetch(id: String, w: Int, h: Int, cb: (Bitmap?) -> Unit) {
        App.instance.ha.request(JSONObject().put("type", "media_source/resolve_media").put("media_content_id", id).put("expires", 600)) { res ->
            val url = res.optJSONObject("result")?.optString("url").orEmpty()
            if (url.isEmpty()) { cb(null); return@request }
            val cfg = App.instance.config
            val full = if (url.startsWith("http")) url else cfg.url.trimEnd('/') + url
            val req = try { Request.Builder().url(full).header("Authorization", "Bearer ${cfg.token}").build() } catch (_: Exception) { cb(null); return@request }
            http.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { main.post { cb(null) } }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val bytes = response.use { if (it.isSuccessful) it.body?.bytes() else null }
                    val bmp = bytes?.let { decode(it, w, h) }
                    main.post { cb(bmp) }
                }
            })
        }
    }

    /** Décode en réduisant à la taille de l'écran (évite de charger une photo de 12 Mpx en mémoire). */
    private fun decode(b: ByteArray, w: Int, h: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
    } catch (_: Throwable) { null }
}
