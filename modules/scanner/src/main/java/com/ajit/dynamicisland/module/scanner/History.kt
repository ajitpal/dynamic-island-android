package com.ajit.dynamicisland.module.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last 50 codes you scanned, on this phone only (Wi-Fi passwords included, so never backed up). */
object History {
    data class Item(val id: Long, val time: Long, val text: String, val format: String) {
        val payload by lazy { Payload.parse(text, format) }
    }

    private const val MAX = 50
    private fun sp(c: Context) = c.getSharedPreferences("codes", Context.MODE_PRIVATE)

    fun all(c: Context): List<Item> = try {
        val arr = JSONArray(sp(c).getString("items", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Item(it.getLong("id"), it.getLong("t"), it.getString("text"), it.optString("f", "QR_CODE")) } }
    } catch (e: Exception) { emptyList() }

    fun get(c: Context, id: Long) = all(c).firstOrNull { it.id == id }

    /** Adds a scan (the same code scanned again moves to the top). */
    fun add(c: Context, text: String, format: String): Item {
        val now = System.currentTimeMillis()
        val item = Item(now, now, text, format)
        val list = listOf(item) + all(c).filter { it.text != text }
        save(c, list.take(MAX))
        return item
    }

    fun remove(c: Context, id: Long) = save(c, all(c).filter { it.id != id })
    fun clear(c: Context) = save(c, emptyList())

    private fun save(c: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.time).put("text", it.text).put("f", it.format)) }
        sp(c).edit().putString("items", arr.toString()).apply()
    }
}
