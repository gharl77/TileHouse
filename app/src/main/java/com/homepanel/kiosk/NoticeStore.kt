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
        // Même étiquette : la nouvelle remplace l'ancienne (une seule « Léon est coincé » à la fois).
        val tag = n.notice.tag
        if (tag.isNotEmpty()) discard(items.filter { it.notice.tag == tag })
        items.add(n)
        while (items.size > MAX) discard(listOf(items.minBy { it.time }))
        changed()
        return n
    }

    fun markRead(ids: Collection<Long>) { val gone = items.filter { it.id in ids }; if (gone.isNotEmpty()) { discard(gone); changed() } }
    fun markRead(id: Long) = markRead(listOf(id))
    /** Efface les notifications portant cette étiquette (le problème est réglé). */
    fun clearTag(tag: String) { val gone = items.filter { tag.isNotEmpty() && it.notice.tag == tag }; if (gone.isNotEmpty()) { discard(gone); changed() } }

    fun markAllRead() { if (items.isNotEmpty()) { discard(items.toList()); changed() } }

    /** Retire ces notifications et supprime leurs captures d'écran enregistrées. */
    private fun discard(list: List<StoredNotice>) {
        items.removeAll(list.toSet())
        for (n in list) n.notice.imageFile?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
    }

    private fun changed() {
        val a = JSONArray()
        for (n in items) a.put(JSONObject().put("id", n.id).put("time", n.time).put("raw", n.raw))
        prefs.edit().putString("unread", a.toString()).apply()
        for (l in listeners.toList()) l()
    }
}
