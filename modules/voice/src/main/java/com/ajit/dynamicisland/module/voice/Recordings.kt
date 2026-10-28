package com.ajit.dynamicisland.module.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved meeting: filesDir/recordings/<id>/{audio.m4a, transcript.json}. App-private, excluded from backup. */
data class Recording(val id: String, val title: String, val startedAt: Long, val durationMs: Long, val lines: List<Line>, val model: String,
                     val language: String = "en", val imported: Boolean = false) {
    /** audio.m4a for recordings; imported files keep their own format (audio.mp3, audio.ogg, …). */
    fun audio(ctx: Context): File = Recordings.dir(ctx, id).listFiles { f -> f.name.startsWith("audio.") && !f.name.endsWith(".tmp") }?.firstOrNull()
        ?: File(Recordings.dir(ctx, id), "audio.m4a")
}

object Recordings {
    fun root(ctx: Context) = File(ctx.filesDir, "recordings").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id.filter { it.isLetterOrDigit() || it == '-' })

    fun newId(startedAt: Long) = startedAt.toString()

    fun save(ctx: Context, r: Recording) {
        val d = dir(ctx, r.id).apply { mkdirs() }
        val json = JSONObject()
            .put("title", r.title).put("startedAt", r.startedAt).put("durationMs", r.durationMs).put("model", r.model)
            .put("language", r.language).put("imported", r.imported)
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
            o.optString("model"), o.optString("language", "en"), o.optBoolean("imported", false),
        )
    } catch (e: Exception) { null }

    fun all(ctx: Context): List<Recording> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(ctx, it.name) }.sortedByDescending { it.startedAt }

    fun rename(ctx: Context, id: String, title: String) { load(ctx, id)?.let { save(ctx, it.copy(title = title.take(80))) } }
    fun delete(ctx: Context, id: String) { dir(ctx, id).deleteRecursively() }
}

/** AI notes are kept next to the transcript: recordings/<id>/notes.json. */
object NotesStore {
    private fun file(ctx: Context, id: String) = File(Recordings.dir(ctx, id), "notes.json")

    fun save(ctx: Context, id: String, n: AiNotes) {
        val o = JSONObject().put("template", n.template.name).put("title", n.title).put("model", n.model).put("createdAt", n.createdAt)
            .put("sections", JSONArray().apply { n.sections.forEach { (h, items) -> put(JSONObject().put("h", h).put("items", JSONArray(items))) } })
        val tmp = File(Recordings.dir(ctx, id), "notes.json.tmp")
        tmp.writeText(o.toString()); tmp.renameTo(file(ctx, id))
    }

    fun load(ctx: Context, id: String): AiNotes? = try {
        val o = JSONObject(file(ctx, id).readText())
        val arr = o.getJSONArray("sections")
        AiNotes(
            runCatching { Template.valueOf(o.getString("template")) }.getOrDefault(Template.MEETING),
            o.optString("title").takeIf { it.isNotBlank() && it != "null" },
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { s -> s.getString("h") to s.getJSONArray("items").let { a -> (0 until a.length()).map(a::getString) } } },
            o.optString("model").takeIf { it.isNotBlank() && it != "null" }, o.optLong("createdAt"),
        )
    } catch (e: Exception) { null }

    fun delete(ctx: Context, id: String) { file(ctx, id).delete() }
}

/** Questions asked about a meeting: recordings/<id>/qa.json (newest first). */
object QaStore {
    private fun file(ctx: Context, id: String) = File(Recordings.dir(ctx, id), "qa.json")

    fun load(ctx: Context, id: String): List<QaItem> = try {
        val a = JSONArray(file(ctx, id).readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> QaItem(o.getString("q"), o.getString("a"), o.optLong("at"), o.optString("by").takeIf { b -> b.isNotBlank() }) } }
    } catch (e: Exception) { emptyList() }

    fun add(ctx: Context, id: String, item: QaItem) {
        val list = listOf(item) + load(ctx, id)
        file(ctx, id).writeText(JSONArray().apply { list.take(50).forEach { put(JSONObject().put("q", it.question).put("a", it.answer).put("at", it.at).put("by", it.by)) } }.toString())
    }

    fun clear(ctx: Context, id: String) { file(ctx, id).delete() }
}
