package com.homepanel.kiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mises à jour par Wi-Fi, depuis les releases GitHub ou un APK envoyé par la page web.
 * Android demande toujours une confirmation à l'écran (un toucher) avant d'installer.
 * L'APK doit être signé avec la même clé que l'app installée, sinon Android le refuse.
 */
class Updater(private val app: App) {
    companion object {
        const val REPO = "gharl77/TileHouse"
        const val RELEASES_URL = "https://github.com/$REPO/releases"
        private const val CHECK_EVERY_MS = 6 * 3_600_000L
        const val ACTION_RESULT = "com.tilehouse.app.INSTALL_RESULT"

        /** Compare deux versions "2026.11.1" composante par composante. */
        fun compare(a: String, b: String): Int {
            val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }

    class Release(val version: String, val apkUrl: String?, val notes: String, val url: String)

    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { app.ha.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build() }

    val installed: String = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }
    var latest: Release? = null
        private set
    /** Texte d'état affiché dans les réglages et la page web ("" = rien de particulier). */
    var status = ""
        private set
    var busy = false
        private set
    var lastCheck = 0L
        private set

    val updateAvailable get() = latest?.let { compare(it.version, installed) > 0 && it.apkUrl != null } == true

    val listeners = ArrayList<() -> Unit>()
    private fun changed() {
        for (l in listeners.toList()) l()
        app.mqtt.publishUpdate()
    }

    private val periodic = object : Runnable {
        override fun run() { check(); main.postDelayed(this, CHECK_EVERY_MS) }
    }

    fun start() { main.postDelayed(periodic, 60_000L) }

    // ------------------------------------------------------------ vérification

    fun check(cb: (() -> Unit)? = null) {
        if (busy) { cb?.invoke(); return }
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "TileHouse/$installed").build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                main.post { status = tr("Vérification impossible") + " (${e.message ?: "réseau"})"; changed(); cb?.invoke() }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val text = response.use { if (it.isSuccessful) it.body?.string() else null }
                val rel = try {
                    text?.let { JSONObject(it) }?.let { o ->
                        var apk: String? = null
                        val assets = o.optJSONArray("assets")
                        if (assets != null) for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                        }
                        Release(o.optString("tag_name").removePrefix("v"), apk, o.optString("body"), o.optString("html_url"))
                    }
                } catch (_: Exception) { null }
                main.post {
                    lastCheck = System.currentTimeMillis()
                    if (rel == null) status = tr("Vérification impossible")
                    else { latest = rel; status = "" }
                    changed(); cb?.invoke()
                }
            }
        })
    }

    // ------------------------------------------------------------ installation

    /** Télécharge la dernière release puis lance l'installation. */
    fun installLatest() {
        val rel = latest
        if (rel?.apkUrl == null || !updateAvailable) { check { if (updateAvailable) installLatest() }; return }
        if (busy) return
        if (!canInstall()) return
        busy = true; status = tr("Téléchargement…"); changed()
        val dest = File(app.cacheDir, "update.apk")
        val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "TileHouse/$installed").build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { fail(e.message ?: "réseau") }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use { r ->
                        if (!r.isSuccessful) { fail("HTTP ${r.code}"); return }
                        r.body!!.byteStream().use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                    }
                    main.post { installFile(dest) }
                } catch (e: Exception) { fail(e.message ?: "?") }
            }
        })
    }

    private fun fail(msg: String) {
        main.post { busy = false; status = tr("Échec de la mise à jour") + " : $msg"; changed() }
    }

    /** Android 8+ : l'utilisateur doit autoriser TileHouse à installer des applications (une seule fois). */
    fun canInstall(): Boolean {
        if (app.packageManager.canRequestPackageInstalls()) return true
        status = tr("Autorisez TileHouse à installer des applications, puis réessayez.")
        changed()
        try {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        return false
    }

    /** Vérifie l'APK (même application, version) puis l'installe via PackageInstaller. Retourne un message d'erreur ou null. */
    fun installFile(file: File): String? {
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
        val err = when {
            info == null -> tr("Ce fichier n'est pas un APK valide.")
            info.packageName != app.packageName -> tr("Cet APK n'est pas TileHouse") + " (${info.packageName})."
            else -> null
        }
        if (err != null) { file.delete(); busy = false; status = err; changed(); return err }
        if (!canInstall()) { busy = false; return status }
        busy = true; status = tr("Installation de la version") + " ${info!!.versionName}…"; changed()
        try {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(app.packageName) }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                file.inputStream().use { inp -> s.openWrite("tilehouse.apk", 0, file.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
                val intent = Intent(ACTION_RESULT).setPackage(app.packageName)
                val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            busy = false; status = tr("Échec de la mise à jour") + " : ${e.message}"; changed()
            return status
        } finally { file.delete() }
        return null
    }

    /** Résultat renvoyé par PackageInstaller. */
    fun onResult(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                status = tr("Confirmez l'installation sur la tablette.")
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let {
                    try { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { busy = false; status = "" } // l'app redémarre d'elle-même
            else -> {
                busy = false
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                status = tr("Installation annulée ou refusée") + if (msg.isNotEmpty()) " ($msg)" else ""
            }
        }
        changed()
    }

    /** État pour l'entité « update » de Home Assistant. */
    fun mqttState(): JSONObject = JSONObject()
        .put("installed_version", installed)
        .put("latest_version", latest?.version?.takeIf { compare(it, installed) > 0 } ?: installed)
        .put("title", "TileHouse")
        .put("release_url", latest?.url?.ifEmpty { null } ?: RELEASES_URL)
        .put("release_summary", (latest?.notes ?: "").take(250))
        .put("in_progress", busy)
}

/** Reçoit le résultat de l'installation (déclaré dans le manifeste, non exporté). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Updater.ACTION_RESULT) App.instance.updater.onResult(intent)
    }
}
