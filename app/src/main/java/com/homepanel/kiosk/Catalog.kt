package com.homepanel.kiosk

import org.json.JSONObject

class CatalogEntity(
    val id: String,
    val name: String,
    val areaId: String?,
    val deviceClass: String,
    val hidden: Boolean,
) {
    val domain: String = id.substringBefore('.')
}

class Area(val id: String, val name: String)

class Catalog(
    val entities: List<CatalogEntity>,
    val areas: List<Area>,
    /** Entités utiles à l'accueil (weather, calendar, person) : id -> nom. */
    val extras: Map<String, List<Pair<String, String>>> = emptyMap(),
) {
    val byId: Map<String, CatalogEntity> = entities.associateBy { it.id }
    private val areaNames = areas.associate { it.id to it.name }
    fun areaName(id: String?): String? = id?.let { areaNames[it] }
}

/** Charge la liste des entités / zones de HA (utilisé uniquement dans les réglages). */
object CatalogLoader {
    fun load(app: App, cb: (Catalog?, String?) -> Unit) {
        app.catalog?.let { cb(it, null); return }
        val ha = app.ha
        ha.request("get_states") { st ->
            if (!st.optBoolean("success")) { cb(null, err(st)); return@request }
            ha.request("config/area_registry/list") { ar ->
                ha.request("config/device_registry/list") { dr ->
                    ha.request("config/entity_registry/list_for_display") { er ->
                        val cat = try { build(st, ar, dr, er) } catch (_: Exception) { null }
                        app.catalog = cat
                        cb(cat, if (cat == null) tr("Réponse inattendue de Home Assistant") else null)
                    }
                }
            }
        }
    }

    private fun err(msg: JSONObject) = msg.optJSONObject("error")?.optString("message") ?: tr("Erreur inconnue")

    private fun build(st: JSONObject, ar: JSONObject, dr: JSONObject, er: JSONObject): Catalog {
        val areas = ArrayList<Area>()
        ar.optJSONArray("result")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                areas.add(Area(o.getString("area_id"), o.str("name")))
            }
        }
        areas.sortBy { it.name.lowercase() }

        val devArea = HashMap<String, String>()
        dr.optJSONArray("result")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val a = o.str("area_id")
                if (a.isNotEmpty()) devArea[o.getString("id")] = a
            }
        }

        val regArea = HashMap<String, String>()
        val hidden = HashSet<String>()
        er.optJSONObject("result")?.optJSONArray("entities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("ei")
                if (id.isEmpty()) continue
                val area = o.str("ai").ifEmpty { devArea[o.str("di")] ?: "" }
                if (area.isNotEmpty()) regArea[id] = area
                if (o.has("ec") || o.optBoolean("hb")) hidden.add(id)
            }
        }

        val ents = ArrayList<CatalogEntity>()
        val extras = HashMap<String, ArrayList<Pair<String, String>>>()
        val arr = st.optJSONArray("result")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("entity_id")
                val dom = id.substringBefore('.')
                if (dom == "weather" || dom == "calendar" || dom == "person") {
                    val n = o.optJSONObject("attributes")?.str("friendly_name")?.ifEmpty { id } ?: id
                    extras.getOrPut(dom) { ArrayList() }.add(id to n)
                    continue
                }
                // Capteurs de présence (ESPresense, Bermuda…) proposés pour « Qui est à la maison »
                if (dom == "sensor" || dom == "binary_sensor" || dom == "device_tracker") {
                    val n = o.optJSONObject("attributes")?.str("friendly_name")?.ifEmpty { id } ?: id
                    val txt = (id + " " + n).lowercase()
                    val at = o.optJSONObject("attributes") ?: JSONObject()
                    // Un capteur de personne renvoie un lieu (pièce, not_home…), pas une mesure :
                    // on écarte les mesures (unité, classe), les entités de diagnostic et les masquées.
                    val measure = dom == "sensor" && (at.has("unit_of_measurement") || at.str("device_class").isNotEmpty())
                    if (!measure && id !in hidden && (dom == "device_tracker" || "presence" in txt || "présence" in txt))
                        extras.getOrPut("presence") { ArrayList() }.add(id to n)
                }
                if (dom !in Present.SUPPORTED) continue
                val at = o.optJSONObject("attributes") ?: JSONObject()
                ents.add(CatalogEntity(id, at.str("friendly_name").ifEmpty { id }, regArea[id], at.str("device_class"), id in hidden))
            }
        }
        ents.sortBy { it.name.lowercase() }
        for (l in extras.values) l.sortBy { it.second.lowercase() }
        return Catalog(ents, areas, extras)
    }

    /** Crée une pièce par zone HA absente. Retourne le nombre de pièces ajoutées. */
    fun importAreas(cfg: ConfigStore, cat: Catalog): Int {
        var added = 0
        for (area in cat.areas) {
            if (cfg.rooms.any { it.areaId == area.id }) continue
            val ents = cat.entities.filter { it.areaId == area.id && importable(it) }
                .sortedWith(compareBy({ Present.category(it.id) }, { it.name.lowercase() }))
            if (ents.isEmpty()) continue
            val room = cfg.newRoom(area.name, area.id)
            for (e in ents) room.entities.add(RoomEntity(e.id))
            cfg.rooms.add(room)
            added++
        }
        cfg.saveRooms()
        return added
    }

    /** Entités pertinentes lors d'un import automatique des zones. */
    fun importable(e: CatalogEntity): Boolean {
        if (e.hidden) return false
        return when (e.domain) {
            "sensor" -> e.deviceClass == "temperature" || e.deviceClass == "humidity"
            "binary_sensor" -> e.deviceClass in setOf("door", "window", "garage_door", "opening", "motion", "occupancy", "presence", "moisture", "smoke")
            // Les interrupteurs génériques sont souvent techniques (conteneurs, options d'appareils) :
            // seules les prises sont importées, le reste s'ajoute à la main.
            "switch" -> e.deviceClass == "outlet"
            "automation", "button", "input_button" -> false
            // Exclut les « caméras » qui n'en sont pas (aperçus LED, cartes d'aspirateur, captures d'écran).
            "camera" -> listOf("led", "map", "carte", "screenshot", "gradient").none { e.id.contains(it) || e.name.lowercase().contains(it) }
            else -> true
        }
    }
}
