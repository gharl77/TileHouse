package com.homepanel.kiosk

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Informations « À propos », partagées par les réglages de la tablette et la page web. */
object About {
    const val REPO_URL = "https://github.com/${Updater.REPO}"
    const val RELEASES_URL = Updater.RELEASES_URL
    const val ISSUES_URL = "$REPO_URL/issues"
    const val LICENSE = "MIT"
    const val AUTHOR = "gharl77"
    private val started = System.currentTimeMillis()

    class Info(val label: String, val value: String)

    fun version(app: App): String = app.updater.installed

    fun build(app: App): Long = try {
        val p = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    } catch (_: Exception) { 0 }

    private fun updatedAt(app: App): String = try {
        val t = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, I18n.locale).format(Date(t))
    } catch (_: Exception) { "—" }

    private fun duration(ms: Long): String {
        val m = ms / 60_000
        val d = m / 1440; val h = (m % 1440) / 60; val mm = m % 60
        return when {
            d > 0 -> "$d " + tr("j") + " $h h"
            h > 0 -> "$h h ${"%02d".format(mm)}"
            else -> "$mm min"
        }
    }

    /** Lignes d'information, dans l'ordre d'affichage. */
    fun info(app: App): List<Info> {
        val ha = app.ha
        val haText = when {
            app.config.demo -> tr("Mode démo")
            ha.status == HaClient.Status.CONNECTED -> tr("Connecté") + (ha.haVersion?.let { " · $it" } ?: "")
            else -> tr("Non connecté")
        }
        return listOf(
            Info(tr("Version"), version(app)),
            Info(tr("Build"), build(app).toString()),
            Info(tr("Installée le"), updatedAt(app)),
            Info(tr("Identifiant"), app.packageName),
            Info(tr("Tablette"), "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"),
            Info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            Info("Home Assistant", haText),
            Info("MQTT", if (!app.config.mqttEnabled) tr("Désactivé") else if (app.mqtt.client.connected) tr("Connecté") else tr("Non connecté")),
            Info(tr("Page web"), app.web.url() ?: tr("Désactivée")),
            Info(tr("En marche depuis"), duration(System.currentTimeMillis() - started)),
            Info(tr("Licence"), "$LICENSE · © 2026 $AUTHOR"),
        )
    }

    fun json(app: App): JSONObject = JSONObject()
        .put("version", version(app))
        .put("repo", REPO_URL).put("releases", RELEASES_URL).put("issues", ISSUES_URL)
        .put("info", JSONArray().also { a -> for (i in info(app)) a.put(JSONArray().put(i.label).put(i.value)) })
}
