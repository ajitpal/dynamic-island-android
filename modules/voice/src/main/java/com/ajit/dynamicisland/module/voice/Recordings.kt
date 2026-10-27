package com.ajit.dynamicisland.module.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved meeting: filesDir/recordings/<id>/{audio.m4a, transcript.json}. App-private, excluded from backup. */
data class Recording(val id: String, val title: String, val startedAt: Long, val durationMs: Long, val lines: List<Line>, val model: String) {
    fun audio(ctx: Context) = File(Recordings.dir(ctx, id), "audio.m4a")
}

object Recordings {
    fun root(ctx: Context) = File(ctx.filesDir, "recordings").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id.filter { it.isLetterOrDigit() || it == '-' })

    fun newId(startedAt: Long) = startedAt.toString()

    fun save(ctx: Context, r: Recording) {
        val d = dir(ctx, r.id).apply { mkdirs() }
        val json = JSONObject()
            .put("title", r.title).put("startedAt", r.startedAt).put("durationMs", r.durationMs).put("model", r.model)
            .put("lines", JSONArray().apply { r.lines.forEach { put(JSONObject().put("t", it.startMs).put("s", it.text)) } })
        val tmp = File(d, "transcript.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(d, "transcript.json"))
    }

    fun load(ctx: Context, id: String): Recording? = try {
        val o = JSONObject(File(dir(ctx, id), "transcript.json").readText())
        val arr = o.optJSONArray("lines") ?: JSONArray()
        Recording(
            id, o.optString("title", "Meeting"), o.optLong("startedAt"), o.optLong("durationMs"),
            (0 until arr.length()).map { arr.getJSONObject(it).let { l -> Line(l.optLong("t"), l.optString("s")) } },
            o.optString("model"),
        )
    } catch (e: Exception) { null }

    fun all(ctx: Context): List<Recording> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(ctx, it.name) }.sortedByDescending { it.startedAt }

    fun rename(ctx: Context, id: String, title: String) { load(ctx, id)?.let { save(ctx, it.copy(title = title.take(80))) } }
    fun delete(ctx: Context, id: String) { dir(ctx, id).deleteRecursively() }
}
