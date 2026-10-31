package com.ajit.dynamicisland.module.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Scan a document: take a photo (or choose one), check the page corners it found, pick Color /
 * Grayscale / Black & White, add more pages, then Save as one PDF in Documents/Dynamic Island.
 * Everything stays on the phone; pages wait in the app's cache until saved.
 */
class DocActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var camera: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var count: TextView
    private lateinit var save: TextView
    private var capture: ImageCapture? = null
    private val pages = mutableListOf<File>()
    private var busy = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, "No camera access: choose photos of the document instead.", Toast.LENGTH_LONG).show()
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = true
        worker.execute {
            val b = runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = maxOf(info.size.width, info.size.height); if (s > 3000) d.setTargetSampleSize(2)
            } }.getOrNull()
            runOnUiThread { busy = false; if (b != null) crop(b) else Toast.makeText(this, "Couldn't open that photo", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        buildCamera()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (camera.visibility != View.VISIBLE) showCamera() else close() }
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------------ camera

    private fun buildCamera() {
        camera = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        camera.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(40), dp(16), 0) }
        top.addView(round(R.drawable.ic_close, "Close") { close() })
        count = label("", 15f).apply { gravity = Gravity.END }
        top.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        camera.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), dp(40)) }
        bar.addView(round(R.drawable.ic_photo, "Choose photo") { if (!busy) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(View(this).apply {
            contentDescription = "Take photo"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(4), 0xFF8E8E93.toInt()) }
            setOnClickListener { shoot() }
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        save = pill("Save") { savePdf() }
        bar.addView(save)
        camera.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(camera, FrameLayout.LayoutParams(-1, -1))
        updateCount()
    }

    private fun updateCount() {
        count.text = when (pages.size) { 0 -> "Position the document in view"; 1 -> "1 page"; else -> "${pages.size} pages" }
        save.visibility = if (pages.isEmpty()) View.INVISIBLE else View.VISIBLE
        save.text = "Save (${pages.size})"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val c = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, c)
                capture = c
            } catch (e: Exception) { Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shoot() {
        val c = capture ?: return
        if (busy) return
        busy = true
        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        c.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val b = runCatching {
                    val raw = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    val s = minOf(1f, 3000f / maxOf(raw.width, raw.height))
                    val m = Matrix().apply { postScale(s, s); postRotate(rot.toFloat()) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                }.getOrNull()
                image.close()
                runOnUiThread { busy = false; if (b != null) crop(b) else Toast.makeText(this@DocActivity, "Couldn't take the photo", Toast.LENGTH_LONG).show() }
            }
            override fun onError(e: ImageCaptureException) { runOnUiThread { busy = false; Toast.makeText(this@DocActivity, "Couldn't take the photo", Toast.LENGTH_LONG).show() } }
        })
    }

    // ------------------------------------------------------------------ check the corners

    private fun crop(b: Bitmap) {
        val (lum, w, h) = Docs.luminance(b)
        val view = CropView(this).apply { bitmap = b; quad = Quad.detect(lum, w, h) }
        var look = Look.get(this)
        val screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        screen.addView(view, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(88); bottomMargin = dp(150) })
        screen.addView(label(if (view.quad.found) "Drag the corners if they're off" else "Drag the corners onto the page", 15f).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(48), dp(16), 0)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val looks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun drawLooks() {
            looks.removeAllViews()
            Docs.Look.entries.forEach { l ->
                looks.addView(pill(l.label, filled = l == look) { look = l; Look.set(this, l); drawLooks() }.apply {
                    contentDescription = l.label + if (l == look) ", selected" else ""
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) })
            }
        }
        drawLooks()
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(36)) }
        bottom.addView(looks)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
        row.addView(pill("Retake") { showCamera() })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(pill("Keep Scan", filled = true) { keep(b, view.quad, look) })
        bottom.addView(row)
        screen.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        camera.visibility = View.GONE
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        screen.tag = "crop"
    }

    private fun showCamera() {
        root.findViewWithTag<View>("crop")?.let { root.removeView(it) }
        camera.visibility = View.VISIBLE
        updateCount()
    }

    private fun keep(b: Bitmap, q: Quad, look: Docs.Look) {
        if (busy) return
        busy = true
        worker.execute {
            val f = runCatching {
                val page = Docs.apply(Docs.straighten(b, q), look)
                File(cacheDir, "scan-pages").apply { mkdirs() }.let { dir -> File(dir, "p${System.nanoTime()}.jpg") }.also { out ->
                    out.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                }
            }.getOrNull()
            runOnUiThread {
                busy = false
                if (f != null) { pages += f; window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                else Toast.makeText(this, "Couldn't process that page", Toast.LENGTH_LONG).show()
                showCamera()
            }
        }
    }

    // ------------------------------------------------------------------ save

    private fun savePdf() {
        if (busy || pages.isEmpty()) return
        busy = true; save.text = "Saving…"
        val list = pages.toList()
        worker.execute {
            val uri = runCatching { Docs.savePdf(this, list) }.getOrNull()
            runOnUiThread {
                busy = false
                if (uri == null) { Toast.makeText(this, "Couldn't save the PDF", Toast.LENGTH_LONG).show(); updateCount(); return@runOnUiThread }
                list.forEach { it.delete() }; pages.clear()
                if (ScannerModule.instance?.postDoc(uri, list.size) != true) openPdf(this, uri, share = false)
                finish()
            }
        }
    }

    private fun close() {
        if (pages.isEmpty()) { finish(); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Discard ${pages.size} scanned ${if (pages.size == 1) "page" else "pages"}?")
            .setPositiveButton("Discard") { _, _ -> pages.forEach { it.delete() }; pages.clear(); finish() }
            .setNegativeButton("Keep Scanning", null).show()
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    // ------------------------------------------------------------------ small views

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String, sp: Float) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = sp }

    private fun pill(t: String, filled: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; gravity = Gravity.CENTER; minHeight = dp(44); minWidth = dp(64)
        setPadding(dp(18), 0, dp(18), 0)
        setTextColor(if (filled) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(if (filled) 0xFFFFCC00.toInt() else 0x33FFFFFF) }
        setOnClickListener { onClick() }
    }

    private fun round(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); contentDescription = label; setColorFilter(Color.WHITE)
        val p = dp(12); setPadding(p, p, p, p)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    /** The look you used last time. */
    private object Look {
        fun get(a: AppCompatActivity) = runCatching { Docs.Look.valueOf(a.getSharedPreferences("doc", MODE_PRIVATE).getString("look", "COLOR")!!) }.getOrDefault(Docs.Look.COLOR)
        fun set(a: AppCompatActivity, l: Docs.Look) = a.getSharedPreferences("doc", MODE_PRIVATE).edit().putString("look", l.name).apply()
    }

}
