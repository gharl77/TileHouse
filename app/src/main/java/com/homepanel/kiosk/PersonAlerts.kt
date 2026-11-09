package com.homepanel.kiosk

import android.content.Intent
import android.os.SystemClock

/**
 * Alerte « personne détectée » : quand le détecteur de personne d'une caméra des pièces passe
 * à « on », la vue live de cette caméra s'ouvre en plein écran pendant quelques secondes.
 * Les détecteurs sont trouvés sur l'appareil de chaque caméra (Eufy : binary_sensor.<cam>_person)
 * et suivis en permanence, même si la pièce n'est pas affichée.
 */
class PersonAlerts(private val app: App) : HaClient.Listener {
    /** Détecteur → caméra. */
    private var sensors: Map<String, String> = emptyMap()
    private val last = HashMap<String, String>()
    private val lastAlert = HashMap<String, Long>()
    private var scanning = false

    init { app.ha.addListener(this) }

    override fun onHaStatus(status: HaClient.Status) {
        if (status == HaClient.Status.CONNECTED) rescan()
    }

    /** Recherche les détecteurs des caméras présentes dans les pièces (à la connexion et quand les pièces changent). */
    fun rescan() {
        if (app.ha.demo || scanning) return
        val cams = app.config.rooms.flatMap { r -> r.entities.map { it.id } }.filter { it.startsWith("camera.") }.distinct()
        if (cams.isEmpty()) { apply(emptyMap()); return }
        scanning = true
        val found = HashMap<String, String>()
        var left = cams.size
        for (c in cams) CameraResolver.resolve(app, c) { info ->
            for (s in info.personSensors) found.putIfAbsent(s, c)
            if (--left == 0) { scanning = false; apply(found) }
        }
    }

    private fun apply(map: Map<String, String>) {
        sensors = map
        last.keys.retainAll(map.keys)
        app.ha.setExtraEntities(map.keys)
    }

    override fun onHaEntities(ids: Set<String>) {
        for (id in ids) {
            val cam = sensors[id] ?: continue
            val st = app.ha.states[id]?.state ?: continue
            val before = last.put(id, st)
            // Seulement une vraie transition off → on (pas l'état reçu à la connexion).
            if (st == "on" && before == "off") trigger(cam)
        }
    }

    private fun trigger(camera: String) {
        val cfg = app.config
        if (!cfg.personAlert || app.ha.demo) return
        val now = SystemClock.elapsedRealtime()
        // Une alerte par caméra au plus toutes les 30 s ; jamais par-dessus un live déjà ouvert.
        if (now - (lastAlert[camera] ?: -60_000L) < 30_000L) return
        if (LiveActivity.showing != null) return
        lastAlert[camera] = now
        val room = cfg.rooms.firstOrNull { r -> r.find(camera) != null }
        val re = room?.find(camera)
        val name = if (re != null) Present.displayName(re, app.ha.states[camera], room.name) else camera.substringAfter('.')
        app.startActivity(Intent(app, LiveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(LiveActivity.EXTRA_ID, camera)
            .putExtra(LiveActivity.EXTRA_NAME, name)
            .putExtra(LiveActivity.EXTRA_ALERT, cfg.alertSeconds))
    }

    /** Pour tester depuis la page web. */
    fun test(camera: String) { lastAlert.remove(camera); trigger(camera) }
}
