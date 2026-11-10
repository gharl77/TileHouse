package com.homepanel.kiosk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Notification reçue et pas encore lue. [raw] = message tel que reçu (texte ou JSON). */
class StoredNotice(val id: Long, val time: Long, val raw: String) {
    val notice: Notice by lazy { Notice.parse(raw) }
}

/**
 * Centre de notifications : garde les notifications NON LUES (une notification lue est retirée).
 * Conservées dans les préférences, donc après un redémarrage ou une mise à jour. Au plus [MAX].
 */
class NoticeStore(ctx: Context) {
    companion object { const val MAX = 50 }

    private val prefs = ctx.getSharedPreferences("notices", Context.MODE_PRIVATE)
    private val items = ArrayList<StoredNotice>()
    val listeners = ArrayList<() -> Unit>()

    init {
        try {
            val a = JSONArray(prefs.getString("unread", "[]"))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(StoredNotice(it.optLong("id"), it.optLong("time"), it.optString("raw"))) }
        } catch (_: Exception) {}
    }

    /** Les plus récentes d'abord. */
    val unread: List<StoredNotice> get() = items.sortedByDescending { it.time }
    val count get() = items.size

    fun find(id: Long) = items.firstOrNull { it.id == id }

    fun add(raw: String): StoredNotice {
        val now = System.currentTimeMillis()
        val n = StoredNotice(maxOf(now, (items.maxOfOrNull { it.id } ?: 0) + 1), now, raw)
        items.add(n)
        while (items.size > MAX) items.remove(items.minBy { it.time })
        changed()
        return n
    }

    fun markRead(ids: Collection<Long>) { if (items.removeAll { it.id in ids }) changed() }
    fun markRead(id: Long) = markRead(listOf(id))
    fun markAllRead() { if (items.isNotEmpty()) { items.clear(); changed() } }

    private fun changed() {
        val a = JSONArray()
        for (n in items) a.put(JSONObject().put("id", n.id).put("time", n.time).put("raw", n.raw))
        prefs.edit().putString("unread", a.toString()).apply()
        for (l in listeners.toList()) l()
    }
}
