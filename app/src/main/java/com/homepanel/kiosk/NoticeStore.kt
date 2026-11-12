package com.homepanel.kiosk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Notification reçue et pas encore lue. [raw] = message tel que reçu (texte ou JSON). */
class StoredNotice(val id: Long, val time: Long, val raw: String) {
    val notice: Notice by lazy { Notice.parse(raw) }
}

/**
 * Centre de notifications : les notifications NON LUES (au plus [MAX]) et l'historique des [HISTORY] dernières lues.
 * Conservées dans les préférences, donc après un redémarrage ou une mise à jour.
 */
class NoticeStore(ctx: Context) {
    companion object { const val MAX = 50; const val HISTORY = 20 }

    private val prefs = ctx.getSharedPreferences("notices", Context.MODE_PRIVATE)
    private val items = ArrayList<StoredNotice>()
    /** Notifications lues, gardées pour pouvoir les relire. */
    private val read = ArrayList<StoredNotice>()
    val listeners = ArrayList<() -> Unit>()

    init {
        try {
            val a = JSONArray(prefs.getString("unread", "[]"))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(StoredNotice(it.optLong("id"), it.optLong("time"), it.optString("raw"))) }
        } catch (_: Exception) {}
        try {
            val a = JSONArray(prefs.getString("read", "[]"))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { read.add(StoredNotice(it.optLong("id"), it.optLong("time"), it.optString("raw"))) }
        } catch (_: Exception) {}
    }

    /** Historique des notifications lues, les plus récentes d'abord. */
    val history: List<StoredNotice> get() = read.sortedByDescending { it.time }
    val historyCount get() = read.size

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

    /** Lue : elle quitte la liste des non lues et rejoint l'historique (sa capture d'écran est gardée avec elle). */
    fun markRead(ids: Collection<Long>) {
        val gone = items.filter { it.id in ids }
        if (gone.isEmpty()) return
        items.removeAll(gone.toSet())
        remember(gone)
        changed()
    }

    private fun remember(list: List<StoredNotice>) {
        read.addAll(list)
        // Les plus anciennes sortent de l'historique, avec leur capture.
        while (read.size > HISTORY) read.minByOrNull { it.time }?.let { old -> read.remove(old); deleteImage(old) }
    }

    fun clearHistory() { if (read.isNotEmpty()) { for (n in read) deleteImage(n); read.clear(); changed() } }

    private fun deleteImage(n: StoredNotice) { n.notice.imageFile?.let { try { java.io.File(it).delete() } catch (_: Exception) {} } }
    fun markRead(id: Long) = markRead(listOf(id))
    /** Efface les notifications portant cette étiquette (le problème est réglé). */
    fun clearTag(tag: String) {
        if (tag.isEmpty()) return
        val gone = items.filter { it.notice.tag == tag }
        // Le problème est réglé : elle disparaît aussi de l'historique des notifications lues.
        val old = read.filter { it.notice.tag == tag }
        if (gone.isEmpty() && old.isEmpty()) return
        discard(gone)
        for (n in old) { read.remove(n); deleteImage(n) }
        changed()
    }

    fun markAllRead() { if (items.isNotEmpty()) { val all = items.toList(); items.clear(); remember(all); changed() } }

    /** Retire ces notifications et supprime leurs captures d'écran enregistrées. */
    private fun discard(list: List<StoredNotice>) {
        items.removeAll(list.toSet())
        for (n in list) deleteImage(n)
    }

    private fun changed() {
        val a = JSONArray()
        for (n in items) a.put(JSONObject().put("id", n.id).put("time", n.time).put("raw", n.raw))
        val r = JSONArray()
        for (n in read) r.put(JSONObject().put("id", n.id).put("time", n.time).put("raw", n.raw))
        prefs.edit().putString("unread", a.toString()).putString("read", r.toString()).apply()
        for (l in listeners.toList()) l()
    }
}
