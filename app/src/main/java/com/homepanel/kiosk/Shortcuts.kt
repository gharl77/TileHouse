package com.homepanel.kiosk

import org.json.JSONObject

/**
 * Où s'affiche un raccourci (script, scène, bouton) : dans la pièce qui lui est associée, sinon sur l'accueil.
 * La pièce est celle choisie dans les réglages, ou à défaut celle de la zone Home Assistant de l'entité.
 */
object Shortcuts {
    /** Choix « toujours sur l'accueil ». */
    const val HOME = "home"

    /**
     * Pièce d'un raccourci, ou null pour l'accueil. [choice] : "" = automatique, [HOME], ou l'identifiant d'une pièce
     * (une pièce supprimée depuis retombe sur l'automatique). [area] : zone Home Assistant de l'entité, si connue.
     */
    fun roomFor(rooms: List<Room>, choice: String, area: String?): Room? {
        if (choice == HOME) return null
        if (choice.isNotEmpty()) rooms.firstOrNull { it.id == choice }?.let { return it }
        if (area.isNullOrEmpty()) return null
        return rooms.firstOrNull { it.areaId == area }
    }

    fun roomFor(cfg: ConfigStore, id: String): Room? =
        roomFor(cfg.rooms, cfg.shortcutRooms.optString(id), cfg.shortcutAreas.optString(id).ifEmpty { null })

    /** Raccourcis de l'accueil : ceux qui n'ont pas de pièce. */
    fun forHome(cfg: ConfigStore): List<String> = cfg.shortcuts.filter { roomFor(cfg, it) == null }

    /** Raccourcis d'une pièce, sauf ceux qui y ont déjà leur tuile. */
    fun forRoom(cfg: ConfigStore, room: Room): List<String> = cfg.shortcuts.filter { roomFor(cfg, it)?.id == room.id && room.find(it) == null }

    /**
     * Demande à Home Assistant la zone de chaque raccourci (celle de l'entité, sinon celle de son appareil).
     * [done] reçoit la table entité → zone ; une entité sans zone n'y figure pas.
     */
    fun loadAreas(ha: HaClient, ids: List<String>, done: (JSONObject) -> Unit) {
        val out = JSONObject()
        val devices = HashMap<String, String>() // entité → appareil, quand l'entité n'a pas de zone à elle
        var left = ids.size
        if (left == 0) { done(out); return }
        fun finish() {
            if (devices.isEmpty()) { done(out); return }
            ha.request("config/device_registry/list") { dr ->
                val arr = dr.optJSONArray("result")
                val areaOf = HashMap<String, String>()
                if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> o.str("area_id").takeIf { it.isNotEmpty() }?.let { areaOf[o.str("id")] = it } }
                for ((entity, device) in devices) areaOf[device]?.let { out.put(entity, it) }
                done(out)
            }
        }
        for (id in ids) ha.request(JSONObject().put("type", "config/entity_registry/get").put("entity_id", id)) { res ->
            res.optJSONObject("result")?.let { r ->
                val area = r.str("area_id")
                if (area.isNotEmpty()) out.put(id, area) else r.str("device_id").takeIf { it.isNotEmpty() }?.let { devices[id] = it }
            }
            if (--left == 0) finish()
        }
    }
}
