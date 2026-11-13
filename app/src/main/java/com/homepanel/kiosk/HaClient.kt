package com.homepanel.kiosk

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class EntityState(val id: String, var state: String, var attrs: JSONObject) {
    val domain: String = id.substringBefore('.')
    val name: String get() = attrs.str("friendly_name").ifEmpty { id }
}

/**
 * Client WebSocket Home Assistant minimaliste.
 * Utilise `subscribe_entities` filtré : seules les entités affichées sont transmises,
 * sous forme compressée (diffs), ce qui limite fortement la mémoire et le CPU.
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
class HaClient {
    enum class Status { IDLE, CONNECTING, CONNECTED, AUTH_FAILED, DISCONNECTED }

    interface Listener {
        fun onHaStatus(status: Status) {}
        fun onHaEntities(ids: Set<String>) {}
    }

    private val main = Handler(Looper.getMainLooper())
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    val states = HashMap<String, EntityState>()
    var status = Status.IDLE
        private set
    /** Version de Home Assistant annoncée à la connexion. */
    @Volatile var haVersion: String? = null
        private set
    var lastError: String? = null
        private set

    private val listeners = ArrayList<Listener>()
    private var url = ""
    private var token = ""
    private var ws: WebSocket? = null
    private var gen = 0
    private var msgId = 1
    private val callbacks = HashMap<Int, (JSONObject) -> Unit>()
    private var entityIds: Set<String> = emptySet()
    private var subscriptionId = -1
    private var retryDelay = 2000L
    private var stopped = true
    private val reconnectRunnable = Runnable { connect() }

    fun addListener(l: Listener) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Mode démo : maison fictive, aucune connexion réseau. */
    var demo = false
        private set

    fun configureDemo() {
        disconnect()
        stopped = true
        demo = true
        url = ""; token = ""
        lastError = null
        states.clear()
        for (e in Demo.states()) states[e.id] = e
        setStatus(Status.CONNECTED)
        notifyEntities(states.keys.toSet())
    }

    fun notifyEntities(ids: Set<String>) { if (ids.isNotEmpty()) for (l in listeners.toList()) l.onHaEntities(ids) }

    fun configure(url: String, token: String) {
        if (demo) { demo = false; status = Status.IDLE }
        this.url = url.trim().trimEnd('/')
        this.token = token.trim()
        stopped = false
        disconnect()
        states.clear()
        retryDelay = 2000
        connect()
    }

    /** Relance immédiatement une connexion perdue (ex. au retour au premier plan). */
    fun ensureConnected() {
        if (!stopped && status == Status.DISCONNECTED) {
            main.removeCallbacks(reconnectRunnable)
            connect()
        }
    }

    private var requestedIds: Set<String> = emptySet()
    private var extraIds: Set<String> = emptySet()

    /** Entités affichées par l'écran courant. */
    fun setEntities(ids: Set<String>) { requestedIds = HashSet(ids); resubscribe() }

    /** Entités suivies en permanence en plus de l'écran (ex. détecteurs de personne des caméras). */
    fun setExtraEntities(ids: Set<String>) { extraIds = HashSet(ids); resubscribe() }

    private fun resubscribe() {
        val all = requestedIds + extraIds
        if (all == entityIds) return
        entityIds = all
        if (status == Status.CONNECTED && !demo) subscribe()
    }

    fun callService(domain: String, service: String, entityId: String, data: JSONObject? = null) {
        if (demo) {
            main.post { Demo.apply(states, domain, service, entityId, data)?.let { notifyEntities(setOf(it)) } }
            return
        }
        val msg = JSONObject()
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
        if (entityId.isNotEmpty()) msg.put("target", JSONObject().put("entity_id", entityId))
        if (data != null) msg.put("service_data", data)
        send(msg, null)
    }

    fun request(type: String, cb: (JSONObject) -> Unit) = send(JSONObject().put("type", type), cb)
    fun request(msg: JSONObject, cb: (JSONObject) -> Unit) = send(msg, cb)

    // ---------------------------------------------------------------- interne

    private fun wsUrl(): String {
        var u = url
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u.replaceFirst("http", "ws") + "/api/websocket"
    }

    private fun setStatus(s: Status) {
        if (s == status) return
        status = s
        for (l in listeners.toList()) l.onHaStatus(s)
    }

    private fun connect() {
        main.removeCallbacks(reconnectRunnable)
        if (url.isEmpty() || token.isEmpty()) { setStatus(Status.IDLE); return }
        val myGen = ++gen
        setStatus(Status.CONNECTING)
        val req = try {
            Request.Builder().url(wsUrl()).build()
        } catch (e: IllegalArgumentException) {
            lastError = tr("Adresse invalide")
            setStatus(Status.DISCONNECTED)
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Le décodage JSON se fait hors du thread principal. Les grosses réponses
                // (liste de toutes les entités) sont lues au fil de l'eau et allégées.
                val msg = try {
                    // La réponse d'un service (bibliothèque de Music Assistant) est gardée entière, même longue.
                    if (text.length > 50_000 && text.indexOf("\"type\":\"result\"").let { it in 0..120 } && text.indexOf("\"response\":").let { it !in 0..400 }) CompactJson.parse(text)
                    else JSONObject(text)
                } catch (_: Exception) { return }
                main.post { if (myGen == gen) handle(webSocket, msg) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (myGen == gen) { lastError = t.message ?: t.javaClass.simpleName; onSocketClosed() } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (myGen == gen) onSocketClosed() }
            }
        })
    }

    private fun handle(sock: WebSocket, msg: JSONObject) {
        when (msg.optString("type")) {
            "auth_required" -> sock.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            "auth_ok" -> {
                haVersion = msg.optString("ha_version").ifEmpty { null }
                retryDelay = 2000
                lastError = null
                msgId = 1
                subscriptionId = -1
                setStatus(Status.CONNECTED)
                subscribe()
            }
            "auth_invalid" -> {
                lastError = tr("Jeton refusé")
                gen++ // ignore la fermeture qui suit
                sock.close(1000, null)
                ws = null
                failPending()
                setStatus(Status.AUTH_FAILED)
            }
            "result" -> callbacks.remove(msg.optInt("id"))?.invoke(msg)
            "event" -> if (msg.optInt("id") == subscriptionId) msg.optJSONObject("event")?.let { applyEntities(it) }
        }
    }

    private fun onSocketClosed() {
        ws = null
        subscriptionId = -1
        failPending()
        setStatus(Status.DISCONNECTED)
        if (!stopped) {
            main.postDelayed(reconnectRunnable, retryDelay)
            retryDelay = min(retryDelay * 2, 30_000L)
        }
    }

    private fun disconnect() {
        gen++
        main.removeCallbacks(reconnectRunnable)
        // Fermeture propre (trame de fermeture), pas cancel() : couper la connexion pendant qu'OkHttp écrit
        // dessus pouvait faire planter son fil d'écriture, et l'app avec lui. Les rappels de cette ancienne
        // connexion sont ignorés (gen a changé) ; sans réponse du serveur, OkHttp la ferme lui-même après 60 s.
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        subscriptionId = -1
        failPending()
    }

    private fun failPending() {
        if (callbacks.isEmpty()) return
        val err = JSONObject().put("success", false)
            .put("error", JSONObject().put("message", tr("Connexion perdue")))
        val cbs = callbacks.values.toList()
        callbacks.clear()
        for (cb in cbs) cb(err)
    }

    private fun send(msg: JSONObject, cb: ((JSONObject) -> Unit)?): Int {
        if (demo) {
            if (cb != null) main.post { cb(Demo.respond(states, msg)) }
            return 0
        }
        val sock = ws
        if (sock == null || status != Status.CONNECTED) {
            cb?.invoke(JSONObject().put("success", false)
                .put("error", JSONObject().put("message", tr("Non connecté à Home Assistant"))))
            return -1
        }
        val id = msgId++
        msg.put("id", id)
        if (cb != null) callbacks[id] = cb
        if (!sock.send(msg.toString())) {
            callbacks.remove(id)
            cb?.invoke(JSONObject().put("success", false).put("error", JSONObject().put("message", tr("Envoi impossible"))))
        }
        return id
    }

    private fun subscribe() {
        if (subscriptionId > 0) {
            send(JSONObject().put("type", "unsubscribe_events").put("subscription", subscriptionId), null)
            subscriptionId = -1
        }
        states.keys.retainAll(entityIds)
        if (entityIds.isEmpty()) return
        subscriptionId = send(
            JSONObject().put("type", "subscribe_entities").put("entity_ids", JSONArray(entityIds.toList())),
            null
        )
    }

    /** Applique le format compressé de subscribe_entities : a = ajouts, c = changements, r = retraits. */
    private fun applyEntities(ev: JSONObject) {
        val changed = HashSet<String>()
        ev.optJSONObject("a")?.let { a ->
            for (k in a.keys()) {
                val o = a.optJSONObject(k) ?: continue
                states[k] = EntityState(k, o.optString("s"), o.optJSONObject("a") ?: JSONObject())
                changed.add(k)
            }
        }
        ev.optJSONObject("c")?.let { c ->
            for (k in c.keys()) {
                val e = states[k] ?: continue
                val d = c.optJSONObject(k) ?: continue
                d.optJSONObject("+")?.let { plus ->
                    if (plus.has("s")) e.state = plus.optString("s")
                    plus.optJSONObject("a")?.let { na -> for (ak in na.keys()) e.attrs.put(ak, na.get(ak)) }
                }
                d.optJSONObject("-")?.optJSONArray("a")?.let { arr ->
                    for (i in 0 until arr.length()) e.attrs.remove(arr.optString(i))
                }
                changed.add(k)
            }
        }
        ev.optJSONArray("r")?.let { r ->
            for (i in 0 until r.length()) {
                val id = r.optString(i)
                states.remove(id)
                changed.add(id)
            }
        }
        if (changed.isNotEmpty()) for (l in listeners.toList()) l.onHaEntities(changed)
    }
}
