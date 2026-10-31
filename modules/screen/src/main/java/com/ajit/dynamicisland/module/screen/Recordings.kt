package com.ajit.dynamicisland.module.screen

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Recordings this app saved in Movies/Dynamic Island (MediaStore; no storage permission needed for its own files). */
object Recordings {
    data class Item(val uri: Uri, val name: String, val durationMs: Long, val size: Long, val addedMs: Long)

    private val collection: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** A new, hidden-until-finished entry. */
    fun create(ctx: Context, name: String): Uri? = ctx.contentResolver.insert(collection, ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Plan.FOLDER)
        put(MediaStore.Video.Media.IS_PENDING, 1)
    })

    fun publish(ctx: Context, uri: Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }

    fun delete(ctx: Context, uri: Uri) = runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    fun all(ctx: Context, limit: Int = 30): List<Item> = try {
        val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED)
        ctx.contentResolver.query(collection, cols, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ?",
            arrayOf("${Plan.FOLDER}%", ctx.packageName), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val out = mutableListOf<Item>()
            while (c.moveToNext() && out.size < limit) {
                out += Item(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1) ?: "Recording",
                    c.getLong(2), c.getLong(3), c.getLong(4) * 1000)
            }
            out
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }
}
