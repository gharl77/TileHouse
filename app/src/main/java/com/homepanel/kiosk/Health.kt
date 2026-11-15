package com.homepanel.kiosk

import org.json.JSONArray

/**
 * Santé de la maison, pour l'accueil : piles faibles (demandées à Home Assistant, toutes pièces confondues)
 * et appareils des pièces que Home Assistant ne joint plus. Deux pastilles qui n'existent que s'il y a un problème.
 */
object Health {
    /** [pct] : pourcentage restant, ou -1 quand l'appareil dit seulement « pile faible ». */
    class Battery(val name: String, val pct: Int)

    /**
     * Gabarit calculé par Home Assistant : les piles à [threshold] % ou moins, une ligne par appareil.
     * Les téléphones et tablettes de l'application mobile ne comptent pas (ils se rechargent tous les jours).
     */
    fun template(threshold: Int) = "{% set skip = integration_entities('mobile_app') %}{% set ns = namespace(o=[], seen=[]) %}" +
        "{% for s in states.sensor if s.attributes.get('device_class') == 'battery' and s.attributes.get('unit_of_measurement') == '%' and s.entity_id not in skip and s.state | float(999) <= $threshold %}" +
        "{% set d = device_id(s.entity_id) or s.entity_id %}{% if d not in ns.seen %}{% set ns.seen = ns.seen + [d] %}" +
        "{% set ns.o = ns.o + [[(device_attr(d, 'name_by_user') or device_attr(d, 'name') or s.name), s.state | float | round | int]] %}{% endif %}{% endfor %}" +
        "{% for s in states.binary_sensor if s.attributes.get('device_class') == 'battery' and s.state == 'on' %}" +
        "{% set d = device_id(s.entity_id) or s.entity_id %}{% if d not in ns.seen %}{% set ns.seen = ns.seen + [d] %}" +
        "{% set ns.o = ns.o + [[(device_attr(d, 'name_by_user') or device_attr(d, 'name') or s.name), -1]] %}{% endif %}{% endfor %}{{ ns.o | to_json }}"

    /** Réponse du gabarit ([[nom, %], …]) ; [self] est le nom de la tablette, qui a déjà son propre indicateur. Les plus faibles d'abord. */
    fun parse(text: String?, self: String = ""): List<Battery> {
        val arr = try { JSONArray(text ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONArray(i) ?: return@mapNotNull null
            val name = o.optString(0).trim()
            if (name.isEmpty() || (self.isNotEmpty() && name.equals(self, ignoreCase = true))) null else Battery(name, o.optInt(1, -1))
        }.sortedBy { if (it.pct < 0) 0 else it.pct }
    }

    /** Pièce d'usure d'un aspirateur en fin de vie ([pct] restant), ou alerte ([pct] = -1, ex. réservoir d'eau vide). */
    class Wear(val device: String, val item: String, val pct: Int)

    /**
     * Gabarit calculé par Home Assistant : pour chaque aspirateur, ses capteurs d'usure (« …_left », en %) à [threshold] %
     * ou moins, et son avertissement de niveau d'eau. Ce sont les capteurs de l'intégration Dreame ; un aspirateur qui n'en
     * a pas ne donne simplement rien.
     */
    fun wearTemplate(threshold: Int) = "{% set ns = namespace(o=[]) %}{% for v in states.vacuum %}{% set d = device_id(v.entity_id) %}{% if d %}{% set dn = device_attr(d, 'name_by_user') or device_attr(d, 'name') or v.name %}" +
        "{% for id in device_entities(d) %}{% if id.startswith('sensor.') and id.endswith('_left') and not id.endswith('_time_left') and not id.endswith('drying_left') and state_attr(id, 'unit_of_measurement') == '%' and states(id) | float(999) <= $threshold %}" +
        "{% set ns.o = ns.o + [[dn, (state_attr(id, 'friendly_name') or id) | replace(dn ~ ' ', ''), states(id) | float | round | int]] %}" +
        "{% elif id.endswith('_low_water_warning') and states(id) not in ['no_warning', 'unknown', 'unavailable'] %}" +
        "{% set ns.o = ns.o + [[dn, (state_attr(id, 'friendly_name') or id) | replace(dn ~ ' ', ''), -1]] %}{% endif %}{% endfor %}{% endif %}{% endfor %}{{ ns.o | to_json }}"

    fun parseWear(text: String?): List<Wear> {
        val arr = try { JSONArray(text ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONArray(i) ?: return@mapNotNull null
            if (o.optString(0).isBlank() || o.optString(1).isBlank()) null else Wear(o.optString(0).trim(), o.optString(1).trim(), o.optInt(2, -1))
        }.sortedBy { it.pct }
    }

    /** « Entretien : Léon » (un seul aspirateur) ou « Entretien : 2 aspirateurs ». */
    fun wearChip(list: List<Wear>): Present.Chip? {
        val devices = list.map { it.device }.distinct()
        return when (devices.size) {
            0 -> null
            1 -> Present.Chip(R.drawable.ic_vacuum, tr("Entretien :") + " " + devices[0], 1, "wear")
            else -> Present.Chip(R.drawable.ic_vacuum, tr("Entretien :") + " ${devices.size} " + tr("aspirateurs"), 1, "wear")
        }
    }

    fun batteryChip(list: List<Battery>): Present.Chip? = when (list.size) {
        0 -> null
        1 -> Present.Chip(R.drawable.ic_battery_low, tr("1 pile faible"), 1, "battery")
        else -> Present.Chip(R.drawable.ic_battery_low, "${list.size} " + tr("piles faibles"), 1, "battery")
    }

    /**
     * Appareils des pièces hors ligne. Les capteurs d'en-tête comptent ; les lecteurs et les caméras non,
     * parce que beaucoup se déclarent « indisponibles » simplement quand ils sont éteints ou en veille.
     */
    fun offline(room: Room, states: Map<String, EntityState>, ignored: Set<String> = emptySet()): List<RoomEntity> =
        room.entities.filter { re ->
            re.id !in ignored && Present.domain(re.id) !in setOf("media_player", "camera") && states[re.id]?.state == "unavailable"
        }

    fun offlineChip(n: Int): Present.Chip? = when (n) {
        0 -> null
        1 -> Present.Chip(R.drawable.ic_offline, tr("1 appareil hors ligne"), 1, "offline")
        else -> Present.Chip(R.drawable.ic_offline, "$n " + tr("appareils hors ligne"), 1, "offline")
    }
}
