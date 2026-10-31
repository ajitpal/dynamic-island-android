package com.ajit.dynamicisland.module.scanner

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import java.util.Calendar

/** Straightening, cleaning up and saving scanned pages as a PDF in Documents/Dynamic Island. */
object Docs {
    const val FOLDER = "Documents/Dynamic Island"

    enum class Look(val label: String) { COLOR("Color"), GRAY("Grayscale"), DOCUMENT("Black & White") }

    /** The page inside [q], flattened to a rectangle (perspective corrected). */
    fun straighten(src: Bitmap, q: Quad): Bitmap {
        val (ow, oh) = q.outputSize(src.width, src.height)
        val from = q.points.flatMap { listOf(it.x * src.width, it.y * src.height) }.toFloatArray()
        val to = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix().apply { setPolyToPoly(from, 0, to, 0, 4) }
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        return out
    }

    /** Color: a little more contrast. Grayscale. Black & White: strong contrast so paper turns white and ink black. */
    fun apply(src: Bitmap, look: Look): Bitmap {
        val cm = when (look) {
            Look.COLOR -> contrast(1.15f, 8f)
            Look.GRAY -> ColorMatrix().apply { setSaturation(0f); postConcat(contrast(1.2f, 10f)) }
            Look.DOCUMENT -> ColorMatrix().apply { setSaturation(0f); postConcat(contrast(2.2f, -40f)) }
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private fun contrast(c: Float, brightness: Float): ColorMatrix {
        val t = (1f - c) * 128f + brightness
        return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    /** "Scan 2026-10-31 at 14.05.pdf" */
    fun name(): String {
        val c = Calendar.getInstance()
        return "Scan %04d-%02d-%02d at %02d.%02d.%02d.pdf".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    /** One A4-wide page per scan (height follows the scan), saved to Documents/Dynamic Island. */
    fun savePdf(ctx: Context, pages: List<java.io.File>): Uri? {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { i, f ->
                // One page in memory at a time.
                val b = android.graphics.BitmapFactory.decodeFile(f.path) ?: return@forEachIndexed
                val w = 595 // A4 width in points
                val h = (w * b.height.toFloat() / b.width).toInt().coerceIn(200, 1684)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val scale = minOf(w.toFloat() / b.width, h.toFloat() / b.height)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.save(); page.canvas.scale(scale, scale)
                page.canvas.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                page.canvas.restore()
                pdf.finishPage(page)
                b.recycle()
            }
            val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name())
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: return null
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: throw IllegalStateException("can't write")
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { ctx.contentResolver.delete(uri, null, null); throw e }
            return uri
        } finally { pdf.close() }
    }

    data class Saved(val uri: Uri, val name: String, val size: Long, val time: Long)

    /** PDFs this app saved (MediaStore shows an app its own files without any storage permission). */
    fun saved(ctx: Context, limit: Int = 20): List<Saved> = try {
        val col = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ctx.contentResolver.query(col, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
            arrayOf("$FOLDER%", ctx.packageName, "application/pdf"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val out = mutableListOf<Saved>()
            while (c.moveToNext() && out.size < limit) out += Saved(ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "Scan", c.getLong(2), c.getLong(3) * 1000)
            out
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun delete(ctx: Context, uri: Uri) = runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    /** Brightness of a small copy, for [Quad.detect]. */
    fun luminance(b: Bitmap, maxSide: Int = 320): Triple<IntArray, Int, Int> {
        val s = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        val small = Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(8), (b.height * s).toInt().coerceAtLeast(8), true)
        val px = IntArray(small.width * small.height); small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val lum = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
        return Triple(lum, small.width, small.height)
    }
}
