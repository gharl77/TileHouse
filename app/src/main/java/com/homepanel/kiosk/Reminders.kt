package com.homepanel.kiosk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rappels d'événements de l'agenda : « préviens-moi 15 minutes avant ». Gardés sur la tablette (ils survivent à un
 * redémarrage de l'app) ; à l'heure dite, une notification arrive dans le centre de notifications, avec un son.
 */
object Reminders {
    class Reminder(val key: String, val title: String, val at: Long, val eventStart: Long, val allDay: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** Identifie un événement : son titre et son début (l'agenda de Home Assistant ne donne pas toujours d'identifiant). */
    fun keyOf(title: String, start: Long) = "$start|$title"

    fun all(ctx: Context): List<Reminder> = parse(prefs(ctx).getString("list", "[]") ?: "[]")

    fun parse(text: String): List<Reminder> {
        val arr = try { JSONArray(text) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val at = o.optLong("at"); if (at <= 0) return@mapNotNull null
            Reminder(o.optString("key"), o.optString("title"), at, o.optLong("start"), o.optBoolean("allDay"))
        }
    }

    private fun save(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().put("key", r.key).put("title", r.title).put("at", r.at).put("start", r.eventStart).put("allDay", r.allDay))
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun find(ctx: Context, key: String) = all(ctx).firstOrNull { it.key == key }

    /** Un seul rappel par événement : le nouveau remplace l'ancien. */
    fun set(ctx: Context, r: Reminder) = save(ctx, all(ctx).filter { it.key != r.key } + r)

    fun cancel(ctx: Context, key: String) = save(ctx, all(ctx).filter { it.key != key })

    /**
     * Rappels arrivés à échéance à [now] ; ils sont retirés de la liste. Un rappel dont l'événement est déjà
     * commencé depuis plus d'une heure (tablette éteinte entre-temps) est jeté sans être annoncé.
     */
    fun takeDue(ctx: Context, now: Long): List<Reminder> {
        val list = all(ctx)
        val due = list.filter { it.at <= now }
        if (due.isEmpty()) return emptyList()
        save(ctx, list - due.toSet())
        return due.filter { fresh(it, now) }
    }

    fun fresh(r: Reminder, now: Long) = if (r.allDay) now < r.eventStart + 86_400_000L else now < r.eventStart + 3_600_000L
}
