package com.homepanel.kiosk

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/**
 * Lecture en continu des grosses réponses de Home Assistant (get_states, registres).
 * Ne garde que les valeurs simples des éléments, plus quelques attributs utiles :
 * évite de construire en mémoire l'arbre JSON complet de milliers d'entités.
 */
object CompactJson {
    private val CONTAINERS = setOf("result", "entities")
    private val ATTRS = setOf("friendly_name", "device_class", "unit_of_measurement")

    fun parse(text: String): JSONObject = JsonReader(StringReader(text)).use { readObj(it, false) }

    private fun readObj(r: JsonReader, attrs: Boolean): JSONObject {
        val o = JSONObject()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                JsonToken.STRING -> {
                    val v = r.nextString()
                    if (!attrs || name in ATTRS) o.put(name, v)
                }
                JsonToken.NUMBER -> {
                    val v = r.nextString()
                    if (!attrs) o.put(name, v.toLongOrNull() ?: v.toDoubleOrNull() ?: v)
                }
                JsonToken.BOOLEAN -> {
                    val v = r.nextBoolean()
                    if (!attrs) o.put(name, v)
                }
                JsonToken.BEGIN_OBJECT -> when {
                    attrs -> r.skipValue()
                    name == "attributes" -> o.put(name, readObj(r, true))
                    name in CONTAINERS -> o.put(name, readObj(r, false))
                    else -> r.skipValue()
                }
                JsonToken.BEGIN_ARRAY -> if (!attrs && name in CONTAINERS) o.put(name, readArr(r)) else r.skipValue()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return o
    }

    private fun readArr(r: JsonReader): JSONArray {
        val a = JSONArray()
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == JsonToken.BEGIN_OBJECT) a.put(readObj(r, false)) else r.skipValue()
        }
        r.endArray()
        return a
    }
}
