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
    /** Sonnettes : entités de coup de sonnette → caméra. */
    private var rings: Map<String, String> = emptyMap()
    private val last = HashMap<String, String>()
    private val lastAlert = HashMap<String, Long>()
    private val lastRing = HashMap<String, Long>()
    private var scanning = false

    init { app.ha.addListener(this) }

    override fun onHaStatus(status: HaClient.Status) {
        if (status == HaClient.Status.CONNECTED) rescan()
    }

    /** Recherche les détecteurs des caméras présentes dans les pièces (à la connexion et quand les pièces changent). */
    fun rescan() {
        if (app.ha.demo || scanning) return
        val cams = app.config.rooms.flatMap { r -> r.entities.map { it.id } }.filter { it.startsWith("camera.") }.distinct()
        if (cams.isEmpty()) { apply(emptyMap(), emptyMap()); return }
        scanning = true
        val found = HashMap<String, String>()
        val ring = HashMap<String, String>()
        var left = cams.size
        for (c in cams) CameraResolver.resolve(app, c) { info ->
            for (s in info.personSensors) found.putIfAbsent(s, c)
            for (s in info.ringSensors) ring.putIfAbsent(s, c)
            if (--left == 0) { scanning = false; apply(found, ring) }
        }
    }

    private fun apply(map: Map<String, String>, ring: Map<String, String>) {
        sensors = map
        rings = ring
        last.keys.retainAll(map.keys + ring.keys)
        app.ha.setExtraEntities(map.keys + ring.keys)
    }

    private val PERSON = setOf("person", "human", "personne", "face", "pet_person")

    override fun onHaEntities(ids: Set<String>) {
        val motionToo = app.config.alertTrigger == "motion"
        for (id in ids) {
            rings[id]?.let { cam -> checkRing(id, cam); return@let }
            val cam = sensors[id] ?: continue
            val e = app.ha.states[id] ?: continue
            val st = e.state
            val before = last.put(id, st)
            if (before == null) continue // état reçu à la connexion : pas une nouvelle détection
            if (id.startsWith("event.")) {
                // Entité event : l'état (horodatage) change à chaque détection ; event_type dit quoi.
                if (st == before || st == "unknown" || st == "unavailable") continue
                val type = e.attrs.str("event_type").lowercase()
                if (type in PERSON) trigger(cam, true) else if (motionToo && type.isNotEmpty()) trigger(cam, false)
            } else if (st == "on" && before == "off") {
                val isPerson = id.substringAfter('.').split('_').any { it in PERSON }
                if (isPerson || motionToo) trigger(cam, isPerson)
            }
        }
    }

    /** Coup de sonnette : event (event_type ring) ou binary_sensor qui passe à « on ». */
    private fun checkRing(id: String, cam: String) {
        val e = app.ha.states[id] ?: return
        val st = e.state
        val before = last.put(id, st) ?: return
        val rang = if (id.startsWith("event.")) st != before && st != "unknown" && st != "unavailable" &&
            e.attrs.str("event_type").lowercase().let { it.isEmpty() || it == "ring" || it == "press" || it == "doorbell" }
        else st == "on" && before == "off"
        if (!rang || !app.config.doorbellAlert) return
        // L'event et le binary_sensor d'une même sonnette arrivent ensemble : une seule alerte.
        val now = SystemClock.elapsedRealtime()
        if (now - (lastRing[cam] ?: -60_000L) < 15_000L) return
        lastRing[cam] = now
        launch(cam, "🔔  " + tr("On sonne à la porte"))
    }

    private fun trigger(camera: String, person: Boolean = true) {
        val cfg = app.config
        if (!cfg.personAlert || app.ha.demo) return
        val now = SystemClock.elapsedRealtime()
        // Une alerte par caméra au plus toutes les 30 s ; jamais par-dessus un live déjà ouvert.
        if (now - (lastAlert[camera] ?: -60_000L) < 30_000L) return
        if (LiveActivity.showing != null) return
        lastAlert[camera] = now
        launch(camera, if (person) "🚶  " + tr("Personne détectée") else "〰  " + tr("Mouvement détecté"))
    }

    private fun launch(camera: String, reason: String) {
        val cfg = app.config
        if (app.ha.demo || LiveActivity.showing == camera) return
        val now = SystemClock.elapsedRealtime()
        lastAlert[camera] = now
        val room = cfg.rooms.firstOrNull { r -> r.find(camera) != null }
        val re = room?.find(camera)
        val name = if (re != null) Present.displayName(re, app.ha.states[camera], room.name) else camera.substringAfter('.')
        app.startActivity(Intent(app, LiveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(LiveActivity.EXTRA_ID, camera)
            .putExtra(LiveActivity.EXTRA_NAME, name)
            .putExtra(LiveActivity.EXTRA_ALERT, cfg.alertSeconds)
            .putExtra(LiveActivity.EXTRA_REASON, reason))
    }

    /** Pour tester depuis la page web. */
    fun test(camera: String, ring: Boolean = false) {
        lastAlert.remove(camera)
        if (ring) launch(camera, "🔔  " + tr("On sonne à la porte")) else trigger(camera)
    }
}
