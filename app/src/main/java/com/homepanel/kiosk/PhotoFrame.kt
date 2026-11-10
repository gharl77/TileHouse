package com.homepanel.kiosk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Photos du cadre photo, lues directement sur le serveur Immich avec une clé API propre à l'app
 * (Immich → Paramètres du compte → Clés API ; droits de lecture des albums et des médias suffisent).
 *
 * Source ([Config.photoSource]) : "favorites" (favoris) ou "album:<id>". Liste rafraîchie toutes les 6 h ;
 * chaque photo est demandée en taille « preview » (JPEG ~1440 px), puis réduite à la taille de l'écran.
 */
object PhotoFrame {
    private val main = Handler(Looper.getMainLooper())
    private var items: List<String> = emptyList()
    private var source = ""
    private var loadedAt = 0L
    private var index = 0
    private val http by lazy { App.instance.ha.http.newBuilder().readTimeout(40, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build() }
    private val JSON = "application/json".toMediaType()

    private val cfg get() = App.instance.config
    val configured get() = cfg.immichUrl.isNotEmpty() && cfg.immichKey.isNotEmpty()

    private fun request(path: String, body: JSONObject? = null): Request? = try {
        Request.Builder().url(cfg.immichUrl.trimEnd('/') + path)
            .header("x-api-key", cfg.immichKey).header("Accept", "application/json")
            .apply { if (body != null) post(body.toString().toRequestBody(JSON)) }
            .build()
    } catch (_: Exception) { null }

    /** Appel JSON ; [cb] reçoit le texte de la réponse, ou null avec un message d'erreur lisible. */
    private fun call(path: String, body: JSONObject? = null, cb: (String?, String?) -> Unit) {
        val req = request(path, body) ?: run { cb(null, tr("Adresse Immich invalide")); return }
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { main.post { cb(null, e.message ?: tr("réseau")) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val code = response.code
                val text = response.use { if (it.isSuccessful) it.body?.string() else null }
                main.post {
                    when {
                        text != null -> cb(text, null)
                        code == 401 || code == 403 -> cb(null, tr("Clé API refusée par Immich"))
                        else -> cb(null, "HTTP $code")
                    }
                }
            }
        })
    }

    /** Sources proposées dans les réglages : (nom, id) — favoris puis albums. [err] si Immich ne répond pas. */
    fun sources(cb: (List<Pair<String, String>>, String?) -> Unit) {
        if (!configured) { cb(emptyList(), tr("Configurez Immich (adresse et clé API) sur la page web")); return }
        call("/api/albums") { text, err ->
            if (text == null) { cb(emptyList(), err); return@call }
            val out = arrayListOf(tr("Favoris") to "favorites")
            try {
                val a = JSONArray(text)
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    out.add("Album · " + it.optString("albumName") + " (" + it.optInt("assetCount") + ")" to "album:" + it.optString("id"))
                }
            } catch (_: Exception) {}
            cb(out, null)
        }
    }

    /** Vérifie l'adresse et la clé (page web). */
    fun test(cb: (String?) -> Unit) = sources { _, err -> cb(err) }

    private fun loadList(src: String, cb: (List<String>) -> Unit) {
        val images = ArrayList<String>()
        fun collect(arr: JSONArray?) {
            if (arr != null) for (i in 0 until arr.length()) arr.getJSONObject(i).let {
                // Photos seulement (pas les vidéos), et pas celles mises à la corbeille.
                if (it.optString("type") == "IMAGE" && !it.optBoolean("isTrashed")) images.add(it.optString("id"))
            }
        }
        when {
            src.startsWith("album:") -> call("/api/albums/" + src.removePrefix("album:")) { text, _ ->
                try { collect(JSONObject(text ?: "{}").optJSONArray("assets")) } catch (_: Exception) {}
                cb(images)
            }
            src == "favorites" -> call("/api/search/metadata", JSONObject().put("isFavorite", true).put("type", "IMAGE").put("size", 1000)) { text, _ ->
                try { collect(JSONObject(text ?: "{}").optJSONObject("assets")?.optJSONArray("items")) } catch (_: Exception) {}
                cb(images)
            }
            else -> cb(images)
        }
    }

    /** Photo suivante (taille max [w]×[h]), ou null si aucune n'est disponible. */
    fun next(w: Int, h: Int, cb: (Bitmap?) -> Unit) {
        val src = cfg.photoSource
        if (src.isEmpty() || src.startsWith("media-source:") || !configured || App.instance.ha.demo) { cb(null); return }
        val age = System.currentTimeMillis() - loadedAt
        // Liste rechargée si la source change, toutes les 6 h, ou après 2 min si elle était vide (pas en boucle).
        if (src != source || age > 6 * 3_600_000L || (items.isEmpty() && age > 120_000L)) {
            loadedAt = System.currentTimeMillis(); source = src
            loadList(src) { list ->
                items = list.shuffled(); index = 0
                if (items.isEmpty()) cb(null) else fetch(items[index++ % items.size], w, h, cb)
            }
            return
        }
        if (items.isEmpty()) { cb(null); return }
        fetch(items[index++ % items.size], w, h, cb)
    }

    private fun fetch(id: String, w: Int, h: Int, cb: (Bitmap?) -> Unit) {
        val req = request("/api/assets/$id/thumbnail?size=preview") ?: run { cb(null); return }
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { main.post { cb(null) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bytes = response.use { if (it.isSuccessful) it.body?.bytes() else null }
                val bmp = bytes?.let { decode(it, w, h) }
                main.post { cb(bmp) }
            }
        })
    }

    /** Décode en réduisant à la taille de l'écran (évite de garder une grande image en mémoire). */
    private fun decode(b: ByteArray, w: Int, h: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
    } catch (_: Throwable) { null }
}
