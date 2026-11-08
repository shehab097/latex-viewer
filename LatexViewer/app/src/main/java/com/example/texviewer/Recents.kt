package com.example.texviewer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Doc(val uri: String, val name: String, val thumb: String? = null, val dir: DocumentFile? = null)

object Recents {
    private fun prefs(c: Context) = c.getSharedPreferences("recents", 0)
    fun thumbFile(c: Context, uri: String) = File(c.filesDir, "th_${uri.hashCode()}.jpg")
    fun load(c: Context): List<Doc> {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Doc(o.getString("uri"), o.getString("name"), thumbFile(c, o.getString("uri")).path) }
    }
    fun add(c: Context, d: Doc) {
        val l = load(c).filter { it.uri != d.uri }.toMutableList().apply { add(0, d) }.take(30)
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs(c).edit().putString("list", a.toString()).apply()
    }
    fun remove(c: Context, uri: String) {
        val a = JSONArray(); load(c).filter { it.uri != uri }.forEach { a.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs(c).edit().putString("list", a.toString()).apply()
        thumbFile(c, uri).delete()
    }
}
