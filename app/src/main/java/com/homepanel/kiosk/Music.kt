package com.homepanel.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Music Assistant : la bibliothèque (favoris, listes, radios, albums, recherche) et les enceintes choisies dans les réglages.
 * Tout passe par les services de l'intégration Home Assistant (music_assistant.get_library, search, play_media, transfer_queue).
 */
object Music {
    class Item(val type: String, val uri: String, val name: String, val sub: String, val image: String)

    /** Une enceinte de Music Assistant porte l'attribut « mass_player_type ». */
    fun isPlayer(attrs: JSONObject?) = attrs?.has("mass_player_type") == true

    fun typeLabel(type: String) = when (type) {
        "playlist" -> tr("Liste"); "radio" -> tr("Radio"); "album" -> tr("Album"); "track" -> tr("Chanson")
        "artist" -> tr("Artiste"); "podcast" -> tr("Balado"); "audiobook" -> tr("Livre audio"); else -> type
    }

    /** Éléments d'une réponse de get_library ou d'une catégorie de search. Sous-titre : les artistes, sinon le type. */
    fun items(arr: JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri"); val name = o.optString("name")
            if (uri.isEmpty() || name.isEmpty()) continue
            val type = o.optString("media_type")
            val artists = o.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotEmpty() } } }.orEmpty()
            val image = if (o.isNull("image")) "" else o.optString("image")
            out.add(Item(type, uri, name, if (artists.isEmpty()) typeLabel(type) else artists.joinToString(", "), image))
        }
        return out
    }

    /** Réponse de search : un tableau par type. On met d'abord ce qu'on cherche le plus souvent. */
    fun searchItems(resp: JSONObject?): List<Item> {
        if (resp == null) return emptyList()
        return listOf("artists", "albums", "tracks", "playlists", "radio", "podcasts").flatMap { items(resp.optJSONArray(it)) }
    }

    /** Message montré quand Music Assistant refuse de jouer un titre. */
    fun playError(message: String) = when {
        "nothing to play" in message.lowercase() -> tr("Music Assistant n'a rien trouvé à jouer : la liste est vide ou le service de musique est déconnecté.")
        message.isEmpty() -> tr("Music Assistant ne répond pas.")
        else -> message
    }

    fun nextRepeat(now: String) = when (now) { "off", "" -> "all"; "all" -> "one"; else -> "off" }

    /** « À suivre : Artiste - Titre · 3 de 442 », d'après la réponse de get_queue ; vide s'il n'y a rien après. */
    fun nextUp(queue: JSONObject?): String {
        if (queue == null) return ""
        val next = queue.optJSONObject("next_item")?.optString("name").orEmpty()
        val total = queue.optInt("items", 0)
        val pos = if (queue.isNull("current_index")) -1 else queue.optInt("current_index", -1)
        val where = if (total > 1 && pos >= 0) "${pos + 1} ${tr("de")} $total" else ""
        return listOf(if (next.isEmpty()) "" else tr("À suivre") + " : " + next, where).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    class QueueItem(val id: String, val name: String, val duration: Int)

    /** « 3:07 », « 1:02:45 ». */
    fun clock(seconds: Int): String {
        val s = maxOf(0, seconds)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Position de lecture en secondes à l'instant [now] : celle de Home Assistant, plus le temps écoulé si ça joue. Null sans durée (radio). */
    fun position(e: EntityState?, now: Long): Pair<Int, Int>? {
        val a = e?.attrs ?: return null
        val dur = a.dbl("media_duration") ?: return null
        if (dur <= 0) return null
        var pos = a.dbl("media_position") ?: 0.0
        if (e.state == "playing") {
            val at = try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                    .parse(a.str("media_position_updated_at").replace(Regex("\\.\\d+"), "").replace("Z", "+00:00"))?.time
            } catch (_: Exception) { null }
            if (at != null && now > at) pos += (now - at) / 1000.0
        }
        return pos.coerceIn(0.0, dur).toInt() to dur.toInt()
    }

    /** Titres de la file renvoyés par le serveur Music Assistant (player_queues/items) : un tableau, parfois sous « result ». */
    fun queueItems(text: String?): List<QueueItem>? {
        val t = text?.trim() ?: return null
        val arr = try {
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("result") ?: it.optJSONArray("items") }
        } catch (_: Exception) { null } ?: return null
        val out = ArrayList<QueueItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("queue_item_id"); if (id.isEmpty()) continue
            out.add(QueueItem(id, o.optString("name").ifEmpty { o.optJSONObject("media_item")?.optString("name").orEmpty() }, o.optInt("duration", 0)))
        }
        return out
    }

    /** Adresse du serveur Music Assistant : celle des réglages, sinon la machine de Home Assistant sur le port 8095. */
    fun serverUrl(custom: String, haUrl: String): String {
        if (custom.isNotBlank()) return custom.trim().trimEnd('/')
        val host = Regex("^https?://([^/:]+)").find(haUrl.trim())?.groupValues?.get(1) ?: return ""
        return "http://$host:8095"
    }

    fun active(e: EntityState?) = e != null && e.state in setOf("playing", "paused", "buffering")
    fun available(e: EntityState?) = e != null && e.state != "unavailable" && e.state != "unknown"

    /** Enceinte que montre le bas de la fenêtre : une cible qui joue, sinon une autre enceinte qui joue, sinon la première cible. */
    fun current(targets: List<String>, players: List<String>, states: Map<String, EntityState>): String? =
        targets.firstOrNull { active(states[it]) } ?: players.firstOrNull { active(states[it]) } ?: targets.firstOrNull()

    /** Enceintes proposées pour un transfert : toutes celles qui répondent, sauf celle qui joue. */
    fun transferTargets(from: String?, players: List<String>, states: Map<String, EntityState>) =
        players.filter { it != from && available(states[it]) }
}

/** Icône « Musique » du bandeau ; teintée quand une des enceintes choisies joue. */
class MusicButton(ctx: Context) : ImageView(ctx) {
    init {
        setImageResource(R.drawable.ic_music)
        setPadding(ctx.dp(6), ctx.dp(5), ctx.dp(6), ctx.dp(5))
        isClickable = true
        contentDescription = tr("Musique")
        visibility = View.GONE
        set(false, false)
    }

    fun set(visible: Boolean, playing: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
        imageTintList = android.content.res.ColorStateList.valueOf(if (playing) Skin.accent else Skin.fg(0xE6))
    }
}

/** Images des pochettes : adresses complètes (fournisseurs de musique) ou chemins de Home Assistant. Petit cache en mémoire. */
private object Covers {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String, size: Int, cb: (Bitmap?) -> Unit) {
        if (url.isEmpty()) { cb(null); return }
        cache.get(url)?.let { cb(it); return }
        val full = if (url.startsWith("/")) App.instance.config.url.trimEnd('/') + url else url
        val req = try { Request.Builder().url(full).build() } catch (_: IllegalArgumentException) { cb(null); return }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { cb(null) } }
            override fun onResponse(call: Call, response: Response) {
                val bmp = try {
                    response.use { r ->
                        val bytes = if (r.isSuccessful) r.body?.bytes() else null
                        bytes?.let {
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(it, 0, it.size, o)
                            var s = 1
                            while (o.outWidth / (s * 2) >= size) s *= 2
                            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
                        }
                    }
                } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                main.post { if (bmp != null) cache.put(url, bmp); cb(bmp) }
            }
        })
    }
}

/** Appels directs au serveur Music Assistant (file de lecture complète), avec le jeton saisi dans les réglages. */
private object MusicServer {
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())

    /** [cb] reçoit le texte de la réponse et le code HTTP (0 si le serveur est injoignable). */
    fun call(command: String, args: JSONObject, cb: (String?, Int) -> Unit) {
        val cfg = App.instance.config
        val url = Music.serverUrl(cfg.musicUrl, cfg.url)
        val body = JSONObject().put("message_id", "tilehouse").put("command", command).put("args", args).toString()
        val req = try {
            Request.Builder().url("$url/api").header("Authorization", "Bearer ${cfg.musicToken}")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        } catch (_: IllegalArgumentException) { cb(null, 0); return }
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { cb(null, 0) } }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val text = try { response.use { if (it.isSuccessful) it.body?.string() else null } } catch (_: Exception) { null }
                main.post { cb(text, code) }
            }
        })
    }
}

/**
 * Fenêtre Musique : à gauche les enceintes (où jouer, volume, transfert), à droite la bibliothèque et la recherche,
 * en bas la lecture en cours.
 */
class MusicPanel(private val act: MainActivity) : FrameLayout(act) {
    private val ha get() = App.instance.ha
    private val cfg get() = App.instance.config
    private val playersTitle = act.label(15f, Skin.mute)
    private val playersBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val tabsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val status = act.label(16f, Skin.fg(0xB3))
    private val search = EditText(act)
    private val art = ImageView(act)
    private val nowTitle = act.label(17f, Skin.ink, true)
    private val nowSub = act.label(14f, Skin.fg(0xB3))
    private val nextUp = act.label(13f, Skin.fg(0x99))
    private val playBtn: ImageView
    private val seek = SeekBar(act)
    private val elapsed = act.label(13f, Skin.fg(0x99))
    private val total = act.label(13f, Skin.fg(0x99))
    private val seekRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private var seeking = false
    private val tick = object : Runnable { override fun run() { if (showing) { renderProgress(); postDelayed(this, 1000) } } }
    private val queueBtn: ImageView
    /** La partie droite montre la file de lecture au lieu de la bibliothèque. */
    private var queueMode = false
    private val shuffleBtn: ImageView
    private val repeatBtn: ImageView
    /** Titre pour lequel la suite de la file a été demandée (enceinte + titre). */
    private var queueKey = ""
    private val transferBtn = act.label(15f, Skin.ink)
    private val gridWidth: Int

    /** Enceintes cochées ; la première reçoit la musique, les autres la suivent. */
    private val targets = ArrayList<String>()
    private var tab = "fav"
    private var query = ""
    private var items: List<Music.Item> = emptyList()
    private var entryId: String? = null
    private var gen = 0
    private var transferring = false
    private var dragging = false
    private var artUrl = ""
    private var failed = false
    private var playGen = 0
    val showing get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xB3000000.toInt())
        isClickable = true
        setOnClickListener { hide() }
        elevation = act.dpf(28)
        visibility = View.GONE

        val dm = act.resources.displayMetrics
        val panelW = minOf(dm.widthPixels - act.dp(48), act.dp(1120))
        val leftW = act.dp(270)
        gridWidth = panelW - leftW - act.dp(26) * 2 - act.dp(20)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.panelBg(act, act.dpf(26))
            setPadding(act.dp(26), act.dp(20), act.dp(26), act.dp(16))
            isClickable = true
        }

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(act.label(26f, Skin.ink, true, tr("Musique")), LinearLayout.LayoutParams(leftW, WRAP_CONTENT))
        search.apply {
            hint = tr("Rechercher un artiste, un album, une radio…")
            textSize = 17f
            setTextColor(Skin.ink); setHintTextColor(Skin.fg(0x73))
            background = GradientDrawable().apply { setColor(Skin.fg(0x14)); cornerRadius = act.dpf(14) }
            setPadding(act.dp(16), act.dp(11), act.dp(16), act.dp(11))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false }
        }
        head.addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = act.dp(20) })
        head.addView(act.roundIcon(R.drawable.ic_close, 44, Skin.fg(0x1F), Skin.ink) { hide() }.apply {
            layoutParams = LinearLayout.LayoutParams(act.dp(44), act.dp(44)).apply { marginStart = act.dp(12) }
        })
        panel.addView(head)

        val body = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        left.addView(playersTitle)
        left.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(playersBox) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = act.dp(8) })
        body.addView(left, LinearLayout.LayoutParams(leftW, MATCH_PARENT))
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        right.addView(tabsRow)
        right.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(14) })
        right.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(grid) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = act.dp(12) })
        body.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = act.dp(20) })
        panel.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = act.dp(14) })

        panel.addView(View(act).apply { setBackgroundColor(Skin.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = act.dp(10) })
        seek.apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Skin.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Skin.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) Music.position(current()?.let { ha.states[it] }, System.currentTimeMillis())?.let { elapsed.text = Music.clock(it.second * p / 1000) }
                }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    seeking = false
                    val id = current() ?: return
                    val dur = Music.position(ha.states[id], System.currentTimeMillis())?.second ?: return
                    if (!act.haOffline()) ha.callService("media_player", "media_seek", id, JSONObject().put("seek_position", dur * s.progress / 1000.0))
                }
            })
        }
        seekRow.addView(elapsed, LinearLayout.LayoutParams(act.dp(52), WRAP_CONTENT))
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        seekRow.addView(total.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(act.dp(52), WRAP_CONTENT))
        panel.addView(seekRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(8) })
        val foot = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        art.apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = roundRect(Skin.fg(0x1F), act.dpf(10)); clipToOutline = true }
        foot.addView(art, LinearLayout.LayoutParams(act.dp(52), act.dp(52)))
        nowTitle.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        nowSub.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        nextUp.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE; setOnClickListener { transport("media_next_track") } }
        foot.addView(LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(nowTitle); addView(nowSub); addView(nextUp) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = act.dp(14) })
        queueBtn = act.roundIcon(R.drawable.ic_list, 44, Skin.fg(0x14), Skin.fg(0xB3)) { toggleQueue() }.apply { contentDescription = tr("File de lecture") }
        foot.addView(queueBtn)
        shuffleBtn = act.roundIcon(R.drawable.ic_shuffle, 44, Skin.fg(0x14), Skin.fg(0xB3)) { toggleShuffle() }.apply { contentDescription = tr("Lecture aléatoire") }
        foot.addView(shuffleBtn)
        repeatBtn = act.roundIcon(R.drawable.ic_repeat, 44, Skin.fg(0x14), Skin.fg(0xB3)) { cycleRepeat() }.apply { contentDescription = tr("Répétition") }
        foot.addView(repeatBtn)
        foot.addView(act.roundIcon(R.drawable.ic_prev, 48, Skin.fg(0x1F), Skin.ink) { transport("media_previous_track") })
        playBtn = act.roundIcon(R.drawable.ic_play, 56, Skin.accent, Skin.onPrimary) { transport("media_play_pause") }
        foot.addView(playBtn)
        foot.addView(act.roundIcon(R.drawable.ic_next, 48, Skin.fg(0x1F), Skin.ink) { transport("media_next_track") })
        transferBtn.apply {
            typeface = Skin.medium
            setPadding(act.dp(18), act.dp(11), act.dp(18), act.dp(11))
            setOnClickListener { toggleTransfer() }
        }
        foot.addView(transferBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = act.dp(8) })
        panel.addView(foot, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(6) })

        addView(panel, LayoutParams(panelW, (dm.heightPixels * 0.90f).toInt(), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = (dm.heightPixels * 0.04f).toInt()
        })
    }

    fun show() {
        val players = cfg.musicPlayers
        if (players.isEmpty()) return
        targets.retainAll { it in players && Music.available(ha.states[it]) }
        if (targets.isEmpty()) (Music.current(emptyList(), players, ha.states)?.takeIf { Music.active(ha.states[it]) }
            ?: players.firstOrNull { Music.available(ha.states[it]) })?.let { targets.add(it) }
        transferring = false
        visibility = View.VISIBLE
        bringToFront()
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
        queueMode = false
        renderPlayers(); renderTabs(); renderNow()
        removeCallbacks(tick); post(tick)
        if (items.isEmpty()) load() else renderGrid()
    }

    fun hide() {
        if (!showing) return
        hideKeyboard()
        animate().alpha(0f).setDuration(150).withEndAction { visibility = View.GONE }.start()
    }

    /** L'état d'une enceinte a changé (lecture, volume, groupe). */
    fun onEntities(ids: Set<String>) {
        if (!showing || cfg.musicPlayers.none { it in ids }) return
        if (!dragging) renderPlayers()
        renderNow()
    }

    private fun hideKeyboard() {
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun name(id: String) = ha.states[id]?.name ?: id.substringAfter('.').replace('_', ' ')
    private fun current() = Music.current(targets, cfg.musicPlayers, ha.states)

    // ---------------------------------------------------------------- enceintes

    private fun renderPlayers() {
        val cur = current()
        playersTitle.text = if (transferring) tr("Transférer vers…") else tr("Où jouer")
        playersBox.removeAllViews()
        for (id in cfg.musicPlayers) {
            val e = ha.states[id]
            val ok = Music.available(e)
            val on = !transferring && id in targets
            val pick = transferring && ok && id != cur
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (on) Skin.fg(0x1F) else Skin.fg(0x0D)); cornerRadius = act.dpf(14)
                    if (on || pick) setStroke(act.dp(2), Skin.accent)
                }
                setPadding(act.dp(14), act.dp(11), act.dp(14), act.dp(11))
                alpha = if (!ok || (transferring && !pick)) 0.4f else 1f
            }
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ImageView(act).apply {
                setImageResource(if (e?.attrs?.str("device_class") == "tv") R.drawable.ic_tv else R.drawable.ic_speaker)
                imageTintList = android.content.res.ColorStateList.valueOf(if (Music.active(e)) Skin.accent else Skin.fg(0xCC))
            }, LinearLayout.LayoutParams(act.dp(24), act.dp(24)))
            val state = when {
                !ok -> tr("Hors ligne")
                e?.state == "playing" -> tr("En lecture")
                e?.state == "paused" -> tr("En pause")
                else -> ""
            }
            row.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(act.label(16f, Skin.ink, on, name(id)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                if (state.isNotEmpty()) addView(act.label(13f, Skin.fg(0xB3), text = state))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = act.dp(12) })
            box.addView(row)
            val vol = e?.attrs?.dbl("volume_level")
            if (on && vol != null) box.addView(SeekBar(act).apply {
                max = 100; progress = Math.round(vol * 100).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(Skin.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(Skin.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                    override fun onStopTrackingTouch(s: SeekBar) {
                        dragging = false
                        ha.callService("media_player", "volume_set", id, JSONObject().put("volume_level", s.progress / 100.0))
                    }
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(8) })
            if (ok && (!transferring || pick)) {
                box.isClickable = true
                box.setOnClickListener { if (transferring) transferTo(id) else toggleTarget(id) }
            }
            playersBox.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dp(8) })
        }
        val can = cur != null && Music.active(ha.states[cur]) && Music.transferTargets(cur, cfg.musicPlayers, ha.states).isNotEmpty()
        transferBtn.text = if (transferring) tr("Annuler") else tr("Transférer")
        transferBtn.background = roundRect(if (transferring) Skin.accent else Skin.fg(0x24), act.dpf(18))
        transferBtn.setTextColor(if (transferring) Skin.onPrimary else Skin.ink)
        transferBtn.alpha = if (can || transferring) 1f else 0.35f
        transferBtn.isEnabled = can || transferring
    }

    /** Coche ou décoche une enceinte. Si la musique joue déjà, l'enceinte rejoint ou quitte le groupe tout de suite. */
    private fun toggleTarget(id: String) {
        if (act.haOffline()) return
        if (id in targets) {
            if (targets.size == 1) return
            val wasLeader = targets.first() == id
            targets.remove(id)
            if (!wasLeader) ha.callService("media_player", "unjoin", id)
        } else {
            targets.add(id)
            val leader = targets.first()
            if (Music.active(ha.states[leader])) join(leader)
        }
        renderPlayers(); renderNow()
    }

    private fun join(leader: String) {
        val others = targets.filter { it != leader }
        if (others.isNotEmpty()) ha.callService("media_player", "join", leader, JSONObject().put("group_members", JSONArray(others)))
    }

    private fun toggleTransfer() {
        transferring = !transferring
        renderPlayers()
    }

    /** Déplace la file de lecture de l'enceinte en cours vers [dest] ; la musique reprend où elle en était. */
    private fun transferTo(dest: String) {
        val from = current()
        transferring = false
        if (from == null || act.haOffline()) { renderPlayers(); return }
        ha.callService("music_assistant", "transfer_queue", dest, JSONObject().put("source_player", from).put("auto_play", true))
        targets.clear(); targets.add(dest)
        renderPlayers(); renderNow()
    }

    // ---------------------------------------------------------------- bibliothèque

    private fun renderTabs() {
        tabsRow.removeAllViews()
        for ((key, title) in listOf("fav" to tr("Favoris"), "playlist" to tr("Listes de lecture"), "radio" to tr("Radios"), "album" to tr("Albums")))
            tabsRow.addView(act.pill(title, !queueMode && query.isEmpty() && key == tab) {
                hideKeyboard()
                queueMode = false
                search.setText(""); query = ""
                tab = key; renderTabs(); load()
            })
    }

    private fun runSearch() {
        hideKeyboard()
        queueMode = false
        query = search.text.toString().trim()
        renderTabs(); load()
    }

    /** Appel d'un service de Music Assistant qui renvoie une réponse (null en cas d'échec). */
    private fun service(name: String, data: JSONObject, cb: (JSONObject?) -> Unit) {
        val id = entryId
        if (id == null) {
            ha.request(JSONObject().put("type", "config_entries/get").put("domain", "music_assistant")) { r ->
                entryId = r.optJSONArray("result")?.optJSONObject(0)?.optString("entry_id")?.takeIf { it.isNotEmpty() }
                if (entryId == null) cb(null) else service(name, data, cb)
            }
            return
        }
        ha.request(JSONObject().put("type", "call_service").put("domain", "music_assistant").put("service", name)
            .put("service_data", data.put("config_entry_id", id)).put("return_response", true)) { r ->
            cb(if (r.optBoolean("success")) r.optJSONObject("result")?.optJSONObject("response") else null)
        }
    }

    private fun load() {
        val my = ++gen
        items = emptyList(); renderGrid()
        status.text = tr("Chargement…"); status.visibility = View.VISIBLE
        fun done(list: List<Music.Item>?) {
            if (my != gen) return
            items = list.orEmpty()
            status.text = when {
                list == null -> tr("Music Assistant ne répond pas.")
                items.isEmpty() && query.isNotEmpty() -> tr("Aucun résultat.")
                items.isEmpty() && tab == "fav" -> tr("Aucun favori dans Music Assistant pour l'instant.")
                items.isEmpty() -> tr("Rien dans cette section.")
                else -> ""
            }
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            renderGrid()
        }
        if (ha.demo) { done(DEMO); return }
        if (query.isNotEmpty()) {
            service("search", JSONObject().put("name", query).put("limit", 12)) { r -> done(r?.let { Music.searchItems(it) }) }
            return
        }
        if (tab != "fav") {
            service("get_library", JSONObject().put("media_type", tab).put("limit", 80)) { r -> done(r?.let { Music.items(it.optJSONArray("items")) }) }
            return
        }
        // Favoris : listes, radios, albums puis chansons, l'un après l'autre.
        val types = listOf("playlist", "radio", "album", "track")
        val all = ArrayList<Music.Item>()
        fun next(i: Int, failed: Boolean) {
            if (my != gen) return
            if (i == types.size) { done(if (failed && all.isEmpty()) null else all); return }
            service("get_library", JSONObject().put("media_type", types[i]).put("favorite", true).put("limit", 40)) { r ->
                if (r != null) all.addAll(Music.items(r.optJSONArray("items")))
                next(i + 1, failed || r == null)
            }
        }
        next(0, false)
    }

    private fun renderGrid() {
        if (queueMode) return
        grid.removeAllViews()
        val cols = 4
        val gap = act.dp(14)
        val side = (gridWidth - gap * (cols - 1)) / cols
        for (chunk in items.chunked(cols)) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, item ->
                row.addView(tile(item, side), LinearLayout.LayoutParams(side, WRAP_CONTENT).apply { if (i > 0) marginStart = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dp(16) })
        }
    }

    private fun tile(item: Music.Item, side: Int): View {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(if (item.type == "radio") R.drawable.ic_speaker else R.drawable.ic_music)
            imageTintList = android.content.res.ColorStateList.valueOf(Skin.fg(0x73))
            background = roundRect(Skin.fg(0x14), act.dpf(14))
            clipToOutline = true
        }
        box.addView(img, LinearLayout.LayoutParams(side, side))
        Covers.load(item.image, side) { bmp ->
            if (bmp != null) { img.imageTintList = null; img.scaleType = ImageView.ScaleType.CENTER_CROP; img.setImageBitmap(bmp) }
        }
        box.addView(act.label(15f, Skin.ink, text = item.name).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, act.dp(6), 0, 0) })
        val sub = if (tab == "fav" || query.isNotEmpty()) listOf(Music.typeLabel(item.type), item.sub).distinct().joinToString(" · ") else item.sub
        box.addView(act.label(13f, Skin.fg(0x99), text = sub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        box.setOnClickListener { play(item) }
        return box
    }

    private fun play(item: Music.Item) {
        if (act.haOffline()) return
        val leader = targets.firstOrNull() ?: return
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        nowTitle.text = item.name
        nowSub.setTextColor(Skin.fg(0xB3))
        nowSub.text = tr("Démarrage…") + " · " + targets.joinToString(" + ") { name(it) }
        if (ha.demo) { ha.callService("media_player", "media_play", leader); return }
        join(leader)
        failed = false
        // Music Assistant refuse parfois un titre (fournisseur de musique déconnecté, liste vide) : on le dit au lieu d'attendre.
        ha.request(JSONObject().put("type", "call_service").put("domain", "music_assistant").put("service", "play_media")
            .put("target", JSONObject().put("entity_id", leader))
            .put("service_data", JSONObject().put("media_id", item.uri).put("media_type", item.type).put("enqueue", "replace"))) { r ->
            if (r.optBoolean("success")) {
                // Music Assistant a accepté le titre, mais certaines enceintes ne démarrent jamais : on le dit après dix secondes.
                val my = ++playGen
                postDelayed({
                    if (my != playGen || !showing || ha.states[leader]?.state in setOf("playing", "buffering")) return@postDelayed
                    failed = true
                    nowTitle.text = tr("Lecture impossible") + " : " + item.name
                    nowSub.setTextColor(0xFFFF6B5E.toInt())
                    nowSub.text = name(leader) + " " + tr("a reçu la musique mais ne la joue pas. Essayez une autre enceinte, ou vérifiez celle-ci dans Music Assistant.")
                }, 10_000)
                return@request
            }
            playGen++
            failed = true
            nowTitle.text = tr("Lecture impossible") + " : " + item.name
            nowSub.setTextColor(0xFFFF6B5E.toInt())
            nowSub.text = Music.playError(r.optJSONObject("error")?.optString("message").orEmpty())
        }
    }

    // ---------------------------------------------------------------- lecture en cours

    private fun transport(service: String) {
        val id = current() ?: return
        if (act.haOffline()) return
        ha.callService("media_player", service, id)
    }

    private fun renderProgress() {
        val p = Music.position(current()?.let { ha.states[it] }?.takeIf { Music.active(it) }, System.currentTimeMillis())
        seekRow.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p == null || seeking) return
        seek.progress = (p.first * 1000L / maxOf(1, p.second)).toInt()
        elapsed.text = Music.clock(p.first); total.text = Music.clock(p.second)
    }

    private fun toggleQueue() {
        hideKeyboard()
        queueMode = !queueMode
        renderTabs()
        if (queueMode) loadQueueList() else { status.visibility = View.GONE; renderGrid() }
    }

    /** File de lecture de l'enceinte en cours, à partir du titre qui joue. Demande le jeton de Music Assistant. */
    private fun loadQueueList() {
        mode(queueBtn, R.drawable.ic_list, queueMode)
        if (!queueMode) return
        val my = ++gen
        grid.removeAllViews()
        fun say(text: String) { status.text = text; status.visibility = View.VISIBLE }
        val id = current()
        val queueId = id?.let { ha.states[it]?.attrs?.str("active_queue") }.orEmpty()
        if (cfg.musicToken.isEmpty()) { say(tr("Pour voir la file de lecture, ajoutez le jeton de Music Assistant dans la page de réglages (Outils de l'en-tête → Musique).")); return }
        if (id == null || queueId.isEmpty() || !Music.active(ha.states[id])) { say(tr("Rien ne joue : la file est vide.")); return }
        say(tr("Chargement…"))
        ha.request(JSONObject().put("type", "call_service").put("domain", "music_assistant").put("service", "get_queue")
            .put("target", JSONObject().put("entity_id", id)).put("return_response", true)) { r ->
            if (my != gen) return@request
            val q = r.optJSONObject("result")?.optJSONObject("response")?.optJSONObject(id)
            val index = if (q == null || q.isNull("current_index")) 0 else q.optInt("current_index", 0)
            MusicServer.call("player_queues/items", JSONObject().put("queue_id", queueId).put("limit", 100).put("offset", index)) { text, code ->
                if (my != gen || !queueMode) return@call
                val list = Music.queueItems(text)
                when {
                    code == 401 || code == 403 -> say(tr("Music Assistant refuse le jeton. Vérifiez-le dans la page de réglages."))
                    list == null -> say(tr("Le serveur Music Assistant ne répond pas à cette adresse :") + " " + Music.serverUrl(cfg.musicUrl, cfg.url))
                    list.isEmpty() -> say(tr("Rien ne joue : la file est vide."))
                    else -> {
                        status.visibility = View.GONE
                        val totalItems = q?.optInt("items", list.size) ?: list.size
                        list.forEachIndexed { i, it -> grid.addView(queueRow(queueId, it, index + i, i == 0)) }
                        if (index + list.size < totalItems) grid.addView(act.label(14f, Skin.fg(0x99), text = "… " + (totalItems - index - list.size) + " " + tr("autres titres")).apply { setPadding(act.dp(14), act.dp(10), 0, act.dp(10)) })
                    }
                }
            }
        }
    }

    private fun queueRow(queueId: String, item: Music.QueueItem, index: Int, playing: Boolean): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(14), act.dp(11), act.dp(14), act.dp(11))
            background = roundRect(if (playing) Skin.fg(0x1F) else Skin.fg(0x0A), act.dpf(12))
            isClickable = true
        }
        row.addView(act.label(14f, if (playing) Skin.accent else Skin.fg(0x99), playing, (index + 1).toString()), LinearLayout.LayoutParams(act.dp(44), WRAP_CONTENT))
        row.addView(act.label(16f, Skin.ink, playing, item.name).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (item.duration > 0) row.addView(act.label(14f, Skin.fg(0x99), text = Music.clock(item.duration)).apply { setPadding(act.dp(12), 0, 0, 0) })
        if (!playing) row.setOnClickListener {
            if (act.haOffline()) return@setOnClickListener
            MusicServer.call("player_queues/play_index", JSONObject().put("queue_id", queueId).put("index", item.id)) { _, _ -> }
        }
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dp(6) }
        return row
    }

    private fun toggleShuffle() {
        val id = current() ?: return
        if (act.haOffline()) return
        ha.callService("media_player", "shuffle_set", id, JSONObject().put("shuffle", ha.states[id]?.attrs?.optBoolean("shuffle") != true))
    }

    /** Répétition : non → toute la file → le titre en cours → non. */
    private fun cycleRepeat() {
        val id = current() ?: return
        if (act.haOffline()) return
        ha.callService("media_player", "repeat_set", id, JSONObject().put("repeat", Music.nextRepeat(ha.states[id]?.attrs?.str("repeat").orEmpty())))
    }

    private fun mode(btn: ImageView, icon: Int, on: Boolean) {
        btn.setImageResource(icon)
        btn.background = oval(if (on) Skin.accent else Skin.fg(0x14))
        btn.imageTintList = android.content.res.ColorStateList.valueOf(if (on) Skin.onPrimary else Skin.fg(0xB3))
    }

    /** « À suivre » : Home Assistant ne donne que le titre suivant et la position dans la file, pas la file entière. */
    private fun loadQueue(id: String, key: String) {
        if (key == queueKey) return
        queueKey = key
        nextUp.visibility = View.GONE
        if (queueMode) loadQueueList()
        if (ha.demo) return
        ha.request(JSONObject().put("type", "call_service").put("domain", "music_assistant").put("service", "get_queue")
            .put("target", JSONObject().put("entity_id", id)).put("return_response", true)) { r ->
            if (key != queueKey) return@request
            val text = Music.nextUp(r.optJSONObject("result")?.optJSONObject("response")?.optJSONObject(id))
            nextUp.text = text
            nextUp.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun renderNow() {
        // Un refus reste affiché tant que rien ne joue.
        if (failed && cfg.musicPlayers.none { ha.states[it]?.state == "playing" }) return
        failed = false
        nowSub.setTextColor(Skin.fg(0xB3))
        val id = current()
        val e = id?.let { ha.states[it] }
        val a = e?.attrs
        val playing = Music.active(e)
        nowTitle.text = if (playing) a?.str("media_title").orEmpty().ifEmpty { tr("En lecture") } else tr("Rien ne joue")
        val group = (a?.optJSONArray("group_members")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty() + listOfNotNull(id)).distinct()
        nowSub.text = if (!playing) (if (id == null) "" else tr("Touchez un titre pour le lancer sur") + " " + targets.joinToString(" + ") { name(it) })
        else listOf(a?.str("media_artist").orEmpty(), group.joinToString(" + ") { name(it) }).filter { it.isNotEmpty() }.joinToString(" · ")
        playBtn.setImageResource(if (e?.state == "playing") R.drawable.ic_pause else R.drawable.ic_play)
        val repeat = a?.str("repeat").orEmpty()
        mode(shuffleBtn, R.drawable.ic_shuffle, a?.optBoolean("shuffle") == true)
        mode(repeatBtn, if (repeat == "one") R.drawable.ic_repeat_one else R.drawable.ic_repeat, repeat == "all" || repeat == "one")
        if (playing && id != null) loadQueue(id, id + "|" + a?.str("media_content_id") + "|" + a?.str("media_title") + "|" + a?.optBoolean("shuffle") + "|" + repeat)
        else { queueKey = ""; nextUp.visibility = View.GONE }
        val url = if (playing) a?.str("entity_picture").orEmpty() else ""
        if (url != artUrl) {
            artUrl = url
            art.setImageDrawable(null)
            if (url.isNotEmpty()) Covers.load(url, act.dp(52)) { bmp -> if (artUrl == url) art.setImageBitmap(bmp) }
        }
        mode(queueBtn, R.drawable.ic_list, queueMode)
        renderProgress()
        act.updateMusicButton()
    }

    private companion object {
        val DEMO = listOf(
            Music.Item("playlist", "demo://1", "Dinner Jazz", "Playlist", ""), Music.Item("radio", "demo://2", "Classic FM", "Radio", ""),
            Music.Item("album", "demo://3", "Kind of Blue", "Miles Davis", ""), Music.Item("playlist", "demo://4", "Quiet Morning", "Playlist", ""),
        )
    }
}
