package com.homepanel.kiosk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mini serveur HTTP (sans dépendance) qui sert la page de configuration
 * et une petite API JSON. Toutes les modifications passent par le thread principal.
 */
class WebServer(private val app: App) {
    private class Reply(val code: Int, val type: String, val body: String)

    @Volatile private var server: ServerSocket? = null
    @Volatile var port = 0
        private set
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var failures = 0
    private var lockedUntil = 0L
    private val releaseCatalog = Runnable { app.catalog = null }

    val isRunning get() = server != null

    fun start() {
        if (server != null) return
        val ss = bind(8080) ?: bind(8081) ?: return
        server = ss
        port = ss.localPort
        Thread({ acceptLoop(ss) }, "web-config").apply { isDaemon = true; start() }
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    fun url(): String? = if (isRunning) localIp()?.let { "http://$it:$port" } else null

    private fun bind(p: Int): ServerSocket? = try {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
    } catch (_: IOException) { null }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: IOException) { break }
            Thread({ handle(s) }, "web-req").apply { isDaemon = true; start() }
        }
    }

    // ------------------------------------------------------------ HTTP

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val parts = (readLine(input) ?: return).split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (method == "POST" && path == "/api/update/upload") { respond(s, upload(headers, input, len)); return }
                if (len > 2_000_000) { respond(s, Reply(413, JSON, err(tr("Requête trop grosse")))); return }
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(body, off, len - off)
                    if (n < 0) break
                    off += n
                }
                val reply = try {
                    route(method, path, headers, String(body, 0, off, Charsets.UTF_8))
                } catch (e: Exception) {
                    Reply(400, JSON, err(e.message ?: tr("Requête invalide")))
                }
                respond(s, reply)
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun respond(s: Socket, r: Reply) {
        val bytes = r.body.toByteArray(Charsets.UTF_8)
        val reason = when (r.code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = "HTTP/1.1 ${r.code} $reason\r\n" +
            "Content-Type: ${r.type}; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            // Seul Home Assistant (et la page elle-même) peut intégrer cette page dans un cadre.
            "Content-Security-Policy: frame-ancestors 'self' ${haOrigin()}\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    // ------------------------------------------------------------ API

    private fun route(method: String, path: String, headers: Map<String, String>, body: String): Reply {
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val html = app.assets.open("config.html").use { it.readBytes().toString(Charsets.UTF_8) }
            return Reply(200, "text/html", html)
        }
        if (!path.startsWith("/api/")) return Reply(404, "text/plain", "Introuvable")
        if (method == "GET" && path == "/api/i18n") {
            val dict = JSONObject()
            if (I18n.english) for ((k, v) in I18n.EN) dict.put(k, v)
            return ok(JSONObject().put("english", I18n.english).put("dict", dict))
        }

        checkPin(headers["x-pin"])?.let { return it }
        // Protection CSRF simple : l'API n'accepte que les requêtes JSON de la page elle-même.
        if (method == "POST" && headers["content-type"]?.startsWith("application/json") != true) {
            return Reply(400, JSON, err(tr("Type de contenu invalide")))
        }
        val req = if (body.isBlank()) JSONObject() else JSONObject(body)

        return when ("$method $path") {
            "GET /api/state" -> ok(onMain { state() })
            "POST /api/connection" -> ok(onMain {
                val cfg = app.config
                var u = req.str("url").trim().trimEnd('/')
                if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
                val t = req.str("token").trim()
                if (u.isNotEmpty()) cfg.url = u
                if (t.isNotEmpty()) cfg.token = t
                app.catalog = null
                if (cfg.demo) cfg.demo = false // se connecter pour de vrai quitte la démo
                if (cfg.isConfigured || app.ha.demo) app.ha.configure(cfg.url, cfg.token)
                app.ha.setEntities(cfg.allEntityIds())
                app.notifyConfigChanged()
                state()
            })
            "POST /api/update/check" -> {
                val latch = CountDownLatch(1)
                main.post { app.updater.check { latch.countDown() } }
                latch.await(20, TimeUnit.SECONDS)
                ok(onMain { updateState() })
            }
            "POST /api/update/install" -> ok(onMain { app.updater.installLatest(); updateState() })
            "POST /api/demo" -> ok(onMain { app.setDemo(req.optBoolean("on")); state() })
            "POST /api/rooms" -> {
                val cfg = app.config
                // Refuse une liste basée sur une ancienne version (page périmée ou autre appareil).
                if (req.optInt("rev", -1) != cfg.roomsRev) {
                    return Reply(409, JSON, onMain { state() }.put("error", tr("Les pièces ont été modifiées ailleurs : liste rechargée.")).toString())
                }
                ok(onMain {
                val parsed = cfg.parseRooms(req.getJSONArray("rooms"))
                cfg.rooms.clear()
                cfg.rooms.addAll(parsed)
                cfg.saveRooms()
                app.ha.setEntities(cfg.allEntityIds())
                app.notifyConfigChanged()
                state()
            })
            }
            "POST /api/settings" -> ok(onMain {
                val cfg = app.config
                if (req.has("wallpaper")) cfg.wallpaper = req.str("wallpaper")
                if (req.has("language")) cfg.language = req.str("language").let { if (it in setOf("auto", "fr", "en")) it else "auto" }
                if (req.has("theme")) cfg.theme = req.str("theme").let { k -> if (Themes.get(k) != null) k else "" }
                if (req.has("dim")) cfg.dimMinutes = req.optInt("dim", 0).coerceIn(0, 240)
                if (req.has("group")) cfg.groupByCategory = req.optBoolean("group")
                if (req.has("clock")) cfg.showClock = req.optBoolean("clock")
                if (req.has("pin")) cfg.pin = req.str("pin").filter { it.isDigit() }.take(12)
                // Accueil : météo, calendriers, personnes, ordre des paliers
                var subs = false
                if (req.has("weather")) { cfg.weatherEntity = req.str("weather"); subs = true }
                req.optJSONArray("calendars")?.let { a -> cfg.calendars = (0 until a.length()).map { a.getString(it) } }
                req.optJSONArray("persons")?.let { a -> cfg.persons = (0 until a.length()).map { a.getString(it) }; subs = true }
                req.optJSONArray("floorOrder")?.let { a -> cfg.setFloorOrder((0 until a.length()).map { a.getString(it) }) }
                if (subs) app.ha.setEntities(cfg.allEntityIds())
                // MQTT : connexion au broker (le mot de passe n'est jamais renvoyé)
                var mqttRestart = false
                if (req.has("mqttEnabled")) { cfg.mqttEnabled = req.optBoolean("mqttEnabled"); mqttRestart = true }
                if (req.has("mqttHost")) { cfg.mqttHost = req.str("mqttHost").trim(); mqttRestart = true }
                if (req.has("mqttPort")) { cfg.mqttPort = req.optInt("mqttPort", 1883).coerceIn(1, 65535); mqttRestart = true }
                if (req.has("mqttUser")) { cfg.mqttUser = req.str("mqttUser").trim(); mqttRestart = true }
                if (req.str("mqttPass").isNotEmpty()) { cfg.mqttPass = req.str("mqttPass"); mqttRestart = true }
                if (req.has("deviceName")) {
                    val n = req.str("deviceName").trim().take(60)
                    if (n.isNotEmpty() && n != cfg.deviceName) { cfg.deviceName = n; mqttRestart = true }
                }
                if (mqttRestart) app.mqtt.restart()
                app.notifyConfigChanged()
                state()
            })
            "POST /api/export" -> {
                if (app.config.demo) return Reply(409, JSON, err(tr("Quittez le mode démo pour exporter votre configuration.")))
                ok(onMain { app.config.exportJson(req.optBoolean("secrets")) })
            }
            "POST /api/restore" -> {
                if (app.config.demo) return Reply(409, JSON, err(tr("Quittez le mode démo pour importer une sauvegarde.")))
                val data = req.optJSONObject("config") ?: return Reply(400, JSON, err(tr("Fichier vide")))
                ok(onMain {
                    val cfg = app.config
                    val oldUrl = cfg.url; val oldToken = cfg.token
                    cfg.importJson(data)
                    if (cfg.isConfigured && (cfg.url != oldUrl || cfg.token != oldToken)) app.ha.configure(cfg.url, cfg.token)
                    app.ha.setEntities(cfg.allEntityIds())
                    app.mqtt.restart()
                    app.updateWebServer()
                    app.notifyConfigChanged()
                    state()
                })
            }
            "GET /api/catalog" -> {
                val cat = loadCatalog() ?: return Reply(502, JSON, err(lastCatalogError ?: tr("Liste indisponible")))
                ok(catalogJson(cat))
            }
            "POST /api/import" -> {
                val cat = loadCatalog() ?: return Reply(502, JSON, err(lastCatalogError ?: tr("Liste indisponible")))
                ok(onMain {
                    val n = CatalogLoader.importAreas(app.config, cat)
                    app.ha.setEntities(app.config.allEntityIds())
                    app.notifyConfigChanged()
                    state().put("imported", n)
                })
            }
            else -> Reply(404, JSON, err("Introuvable"))
        }
    }

    private fun checkPin(given: String?): Reply? {
        val pin = app.config.pin
        if (pin.isEmpty()) return null
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now < lockedUntil) return Reply(429, JSON, err(tr("Trop d'essais. Patientez une minute.")))
            if (given != pin) {
                failures++
                if (failures >= 5) { failures = 0; lockedUntil = now + 60_000 }
                return Reply(401, JSON, err(tr("Code PIN requis")))
            }
            failures = 0
        }
        return null
    }

    private fun state(): JSONObject {
        val cfg = app.config
        val ha = app.ha
        val wp = JSONArray()
        Wallpapers.keys.forEachIndexed { i, k -> wp.put(JSONArray().put(k).put(Wallpapers.names[i])) }
        return JSONObject()
            .put("url", if (cfg.demo) "" else cfg.url)
            .put("hasToken", cfg.token.isNotEmpty())
            .put("status", ha.status.name)
            .put("demo", cfg.demo)
            .put("update", updateState())
            .put("error", ha.lastError ?: "")
            .put("rooms", cfg.roomsToJson())
            .put("rev", cfg.roomsRev)
            .put("mqtt", JSONObject()
                .put("enabled", cfg.mqttEnabled)
                .put("host", cfg.mqttHost)
                .put("port", cfg.mqttPort)
                .put("user", cfg.mqttUser)
                .put("hasPass", cfg.mqttPass.isNotEmpty())
                .put("connected", app.mqtt.client.connected)
                .put("status", app.mqtt.status)
                .put("deviceId", app.mqtt.id)
                .put("name", cfg.deviceName))
            .put("wallpapers", wp)
            .put("themes", JSONArray().also { a -> for (t in Themes.all) a.put(JSONObject().put("key", t.key).put("name", t.name).put("group", t.group).put("emoji", t.emoji)) })
            .put("home", JSONObject()
                .put("weather", cfg.weatherEntity)
                .put("calendars", JSONArray(cfg.calendars))
                .put("persons", JSONArray(cfg.persons))
                .put("floors", JSONArray().also { a -> for (f in cfg.floors) a.put(JSONObject().put("id", f.id).put("name", f.name)) }))
            .put("settings", JSONObject()
                .put("wallpaper", cfg.wallpaper)
                .put("theme", cfg.theme)
                .put("language", cfg.language)
                .put("dim", cfg.dimMinutes)
                .put("group", cfg.groupByCategory)
                .put("clock", cfg.showClock)
                .put("pinSet", cfg.pin.isNotEmpty()))
    }

    @Volatile private var lastCatalogError: String? = null

    private fun loadCatalog(): Catalog? {
        val latch = CountDownLatch(1)
        var result: Catalog? = null
        main.post {
            CatalogLoader.load(app) { c, e ->
                result = c
                lastCatalogError = e
                latch.countDown()
            }
        }
        if (!latch.await(45, TimeUnit.SECONDS)) { lastCatalogError = tr("Home Assistant ne répond pas"); return null }
        // La liste complète est libérée après 3 min sans utilisation.
        main.removeCallbacks(releaseCatalog)
        main.postDelayed(releaseCatalog, 3 * 60_000L)
        return result
    }

    private fun catalogJson(cat: Catalog): JSONObject {
        val areas = JSONArray()
        for (a in cat.areas) areas.put(JSONObject().put("id", a.id).put("name", a.name))
        val ents = JSONArray()
        for (e in cat.entities) {
            val o = JSONObject().put("id", e.id).put("name", e.name)
            e.areaId?.let { o.put("area", it) }
            if (e.deviceClass.isNotEmpty()) o.put("dc", e.deviceClass)
            if (e.hidden) o.put("hidden", true)
            ents.put(o)
        }
        val extras = JSONObject()
        for ((dom, list) in cat.extras) {
            val a = JSONArray()
            for ((id, name) in list) a.put(JSONObject().put("id", id).put("name", name))
            extras.put(dom, a)
        }
        return JSONObject().put("areas", areas).put("entities", ents).put("extras", extras)
    }

    private fun <T> onMain(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IOException(tr("Délai dépassé"))
        return result!!.getOrThrow()
    }

    private fun haOrigin(): String {
        val u = android.net.Uri.parse(app.config.url)
        val host = u.host ?: return ""
        return "${u.scheme ?: "http"}://$host" + (if (u.port > 0) ":${u.port}" else "")
    }

    private fun ok(o: JSONObject) = Reply(200, JSON, o.toString())
    private fun updateState(): JSONObject {
        val u = app.updater
        return JSONObject().put("installed", u.installed).put("latest", u.latest?.version ?: JSONObject.NULL)
            .put("available", u.updateAvailable).put("busy", u.busy).put("status", u.status)
            .put("url", u.latest?.url ?: Updater.RELEASES_URL)
    }

    /**
     * Réception d'un APK (corps binaire). Le type de contenu particulier oblige le navigateur
     * à une vérification préalable (CORS) qu'aucun autre site ne peut passer : protection CSRF.
     */
    private fun upload(headers: Map<String, String>, input: InputStream, len: Int): Reply {
        checkPin(headers["x-pin"])?.let { return it }
        if (headers["content-type"]?.startsWith("application/vnd.android.package-archive") != true)
            return Reply(400, JSON, err(tr("Type de contenu invalide")))
        if (len <= 0 || len > 150_000_000) return Reply(413, JSON, err(tr("Fichier vide ou trop gros")))
        val file = java.io.File(app.cacheDir, "upload.apk")
        file.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size, left))
                if (n < 0) break
                out.write(buf, 0, n); left -= n
            }
            if (left > 0) { file.delete(); return Reply(400, JSON, err(tr("Envoi interrompu"))) }
        }
        val error = onMain { app.updater.installFile(file) }
        return if (error != null) Reply(400, JSON, err(error)) else ok(onMain { updateState() })
    }

    private fun err(msg: String) = JSONObject().put("error", msg).toString()

    companion object {
        private const val JSON = "application/json"

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}
