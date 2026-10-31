package com.ajit.dynamicisland.module.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Point the camera at a QR code or barcode. As soon as one is read the camera closes and the island
 * shows what it is, with the right buttons (open the link, join the Wi-Fi, pay with UPI…). Decoding
 * happens on the phone (ZXing); no picture is saved.
 */
class CodeActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private var camera: Camera? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val done = AtomicBoolean(false)
    private val reader = Reader()

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { Toast.makeText(this, "Scanner needs the camera to read codes. You can still choose a photo.", Toast.LENGTH_LONG).show() }
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        worker.execute {
            val r = runCatching {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; d.setTargetSampleSize(2)
                }
                reader.decode(bmp)
            }.getOrNull()
            runOnUiThread { if (r != null) found(r) else Toast.makeText(this, "No code found in that photo", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(Viewfinder(this), FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(40), dp(12), 0) }
        top.addView(round(R.drawable.ic_close, "Close") { finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(round(R.drawable.ic_flash, "Flashlight") { v ->
            val c = camera ?: return@round
            val on = c.cameraInfo.torchState.value != 1
            c.cameraControl.enableTorch(on); v.alpha = if (on) 1f else 0.7f
        })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), dp(48)) }
        bottom.addView(TextView(this).apply {
            text = "Point at a QR code or barcode"; setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        })
        bottom.addView(TextView(this).apply {
            text = "Choose Photo"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; minHeight = dp(48)
            setPadding(dp(20), 0, dp(20), 0); background = android.graphics.drawable.GradientDrawable().apply { setColor(0x66000000); cornerRadius = dp(24).toFloat() }
            setOnClickListener { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { img -> analyze(img) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
            } catch (e: Exception) { Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private var frame = 0
    private fun analyze(img: ImageProxy) {
        try {
            if (done.get()) return
            val plane = img.planes[0]
            val w = img.width; val h = img.height; val stride = plane.rowStride
            val buf = plane.buffer
            val y = ByteArray(w * h)
            for (row in 0 until h) { buf.position(row * stride); buf.get(y, row * w, w) }
            // Every other frame, try the picture turned a quarter: 1D barcodes need to lie along the scan lines.
            val src: LuminanceSource = if (frame++ % 2 == 0) PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
                else PlanarYUVLuminanceSource(rotate(y, w, h), h, w, 0, 0, h, w, false)
            reader.decode(src)?.let { r -> if (done.compareAndSet(false, true)) runOnUiThread { found(r) } }
        } catch (_: Exception) {
        } finally { img.close() }
    }

    private fun rotate(y: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(y.size)
        for (row in 0 until h) for (col in 0 until w) out[col * h + (h - 1 - row)] = y[row * w + col]
        return out
    }

    private fun found(r: Result) {
        done.set(true)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val item = History.add(this, r.text, r.barcodeFormat.name)
        // The island shows it; without the island (or before it connects) the Scanner app does.
        if (ScannerModule.instance?.postCode(item) != true)
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW, item.id))
        finish()
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(icon: Int, label: String, onClick: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon); contentDescription = label; setColorFilter(Color.WHITE)
        val p = dp(12); setPadding(p, p, p, p)
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x66000000) }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick(it) }
    }

    /** Dims the picture except a rounded square, like the iPhone Code Scanner. */
    private class Viewfinder(ctx: Context) : View(ctx) {
        private val dim = Paint().apply { color = 0x88000000.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3 * ctx.resources.displayMetrics.density }
        init { setLayerType(LAYER_TYPE_HARDWARE, null); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height) * 0.68f
            r.set((width - s) / 2, (height - s) / 2 - height * 0.04f, (width + s) / 2, (height + s) / 2 - height * 0.04f)
            val rad = s * 0.08f
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            c.drawRoundRect(r, rad, rad, clear)
            c.drawRoundRect(r, rad, rad, edge)
        }
    }

    /** ZXing with the formats people actually meet: QR, Data Matrix, Aztec, PDF417 and product/shipping barcodes. */
    class Reader {
        private val r = MultiFormatReader().apply {
            setHints(mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF),
                DecodeHintType.TRY_HARDER to true,
            ))
        }

        fun decode(src: LuminanceSource): Result? = try { r.decodeWithState(BinaryBitmap(HybridBinarizer(src))) } catch (e: Exception) { null } finally { r.reset() }

        fun decode(b: Bitmap): Result? {
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val src = RGBLuminanceSource(b.width, b.height, px)
            return decode(src) ?: if (src.isRotateSupported) decode(src.rotateCounterClockwise()) else null
        }
    }
}
