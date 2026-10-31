package com.ajit.dynamicisland.module.weather

import android.Manifest
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.module.weather.hig.Hig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Weather & AQI: the weather now (big temperature, like the iPhone Weather app), rain coming, India's
 * AQI with what it means, the next 12 hours, and settings for the place, alerts and the island.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var hig: Hig
    private lateinit var content: LinearLayout
    private var locating = false
    private val listener: () -> Unit = { if (!isFinishing) render() }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) useCurrentLocation() else Toast.makeText(this, "Without location, choose a city instead.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        val page = hig.page(null, topPad = 4)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.column.addView(content, hig.mp())
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i
        }
    }

    override fun onResume() {
        super.onResume()
        Weather.addListener(listener)
        render()
        // "Current location" is only checked here, while you're looking.
        if (WPrefs.useLocation(this) && Here.allowed(this)) useCurrentLocation(quiet = true)
        else if (WPrefs.hasPlace(this) && Weather.ageMs(this) > 10 * 60_000L) Weather.refresh(this)
    }

    override fun onPause() { Weather.removeListener(listener); super.onPause() }

    // ------------------------------------------------------------------ screen

    private fun render() {
        content.removeAllViews()
        if (!WPrefs.hasPlace(this)) {
            content.addView(hig.text("Weather & AQI", Hig.Style.LARGE_TITLE).apply { setPadding(hig.dp(4), hig.dp(8), 0, hig.dp(4)); isAccessibilityHeading = true })
            content.addView(hig.text("The weather and India's air quality index in your island, with alerts before rain and when the air turns poor.", Hig.Style.SUBHEAD, hig.secondary).apply { setPadding(hig.dp(4), 0, hig.dp(4), 0) })
            place()
            footer()
            return
        }
        val s = Weather.snapshot(this)
        hero(s)
        if (s != null) {
            Weather.rainText(s.rainInMin)?.let { r ->
                hig.section(content).add(hig.row(r, "Within the next two hours", hig.tile(R.drawable.ic_rain, Hig.Tile.BLUE)))
            }
            air(s)
            hours(s)
        } else if (Weather.refreshing) {
            hig.section(content).add(hig.row("Getting the weather…"))
        }
        Weather.lastError?.let { e -> hig.section(content).add(hig.row(e, null, hig.tile(R.drawable.ic_info, Hig.Tile.GRAY), onClick = { Weather.refresh(this) })) }
        place()
        island()
        units()
        footer()
    }

    private fun hero(s: Snapshot?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, hig.dp(24), 0, hig.dp(8)) }
        box.addView(hig.text(WPrefs.placeName(this), Hig.Style.TITLE2).apply { gravity = Gravity.CENTER; isAccessibilityHeading = true })
        if (s != null) {
            box.addView(hig.text(Weather.temp(this, s.tempC), Hig.Style.LARGE_TITLE).apply {
                textSize = 72f; typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL); gravity = Gravity.CENTER
            })
            box.addView(hig.text(s.condition.text, Hig.Style.HEADLINE, hig.secondary).apply { gravity = Gravity.CENTER })
            box.addView(hig.text("H ${Weather.temp(this, s.maxC)}  L ${Weather.temp(this, s.minC)}  ·  Feels like ${Weather.temp(this, s.feelsC)}", Hig.Style.SUBHEAD).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(4), 0, 0) })
            box.contentDescription = "${WPrefs.placeName(this)}, ${Weather.temp(this, s.tempC)}, ${s.condition.text}, high ${Weather.temp(this, s.maxC)}, low ${Weather.temp(this, s.minC)}"
        }
        content.addView(box, hig.mp())
    }

    private fun air(s: Snapshot) {
        val sec = hig.section(content, "Air Quality",
            footer = "India's AQI (CPCB scale), estimated from Copernicus CAMS model data for this area (about 45 km), not read from a monitoring station.")
        val a = s.aqi
        if (a == null) { sec.add(hig.row("Not available right now", "Air quality data couldn't be loaded")); return }
        val dot = FrameLayout(this).apply {
            background = hig.rounded(a.category.color, 15)
            addView(hig.text(a.aqi.toString(), Hig.Style.CAPTION_BOLD, if (a.category.ordinal in 1..2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1))
            layoutParams = LinearLayout.LayoutParams(hig.dp(44), hig.dp(30))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sec.add(hig.row("${a.aqi} · ${a.category.label}", a.category.advice, dot))
        sec.add(hig.row("Main pollutant", null, value = a.main.label))
    }

    private fun hours(s: Snapshot) {
        val sec = hig.section(content, "Next 12 Hours")
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(hig.dp(8), hig.dp(12), hig.dp(8), hig.dp(12)) }
        val fmt = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(this)) "HH" else "ha", Locale.getDefault())
        s.hours.take(12).forEachIndexed { i, h ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; minimumWidth = hig.dp(56) }
            val c = Wmo.of(h.code, h.isDay)
            val label = if (i == 0) "Now" else fmt.format(Date(h.time * 1000))
            col.addView(hig.text(label, Hig.Style.FOOTNOTE, hig.secondary, lines = 1))
            col.addView(ImageView(this).apply {
                setImageResource(Weather.skyIcon(c.sky, h.isDay)); setColorFilter(Weather.skyColor(c.sky, h.isDay))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(hig.dp(26), hig.dp(26)).apply { topMargin = hig.dp(6); bottomMargin = hig.dp(2) })
            col.addView(hig.text(if (h.rainChance >= 20) "${h.rainChance}%" else " ", Hig.Style.CAPTION_BOLD, hig.blue))
            col.addView(hig.text(Weather.temp(this, h.tempC), Hig.Style.HEADLINE))
            col.contentDescription = "$label, ${c.text}, ${Weather.temp(this, h.tempC)}" + if (h.rainChance >= 20) ", ${h.rainChance}% chance of rain" else ""
            strip.addView(col)
        }
        sec.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
    }

    private fun place() {
        val here = WPrefs.useLocation(this)
        val has = WPrefs.hasPlace(this)
        val sec = hig.section(content, "Place",
            footer = "Current location is checked only while this screen is open, rounded to about 1 km. The 30-minute updates use the saved place; nothing tracks you in the background.")
        sec.add(hig.row(if (locating) "Finding You…" else "Current Location", if (here && has) WPrefs.placeName(this) else "Approximate location",
            hig.tile(R.drawable.ic_location, Hig.Tile.BLUE), trailing = if (here && has) check() else null, onClick = {
                if (Here.allowed(this)) useCurrentLocation() else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }))
        sec.add(hig.row("Choose City…", if (!here && has) WPrefs.placeName(this) else null, hig.tile(R.drawable.ic_list, Hig.Tile.GRAY),
            trailing = if (!here && has) check() else null, onClick = { search() }))
    }

    private fun island() {
        val sec = hig.section(content, "In the Island",
            footer = "Alerts pop up once when rain is due within ${Alerts.RAIN_WINDOW_MIN} minutes, and when the air turns Poor (AQI ${Alerts.AQI_ALERT}+) or gets worse. Checked every 30 minutes.")
        sec.add(hig.toggle("Keep in the Island", WPrefs.keepInIsland(this), "Temperature and AQI beside the camera", R.drawable.ic_island, Hig.Tile.BLACK) {
            WPrefs.setKeepInIsland(this, it); Weather.publish(this)
        })
        sec.add(hig.toggle("Rain Alerts", WPrefs.rainAlerts(this), null, R.drawable.ic_rain, Hig.Tile.BLUE) { WPrefs.setRainAlerts(this, it) })
        sec.add(hig.toggle("Poor Air Alerts", WPrefs.airAlerts(this), null, R.drawable.ic_leaf, Hig.Tile.ORANGE) { WPrefs.setAirAlerts(this, it) })
    }

    private fun units() {
        hig.section(content, "Units").add(hig.toggle("Fahrenheit", WPrefs.fahrenheit(this), null, R.drawable.ic_thermo, Hig.Tile.RED) {
            WPrefs.setFahrenheit(this, it); render(); Weather.publish(this)
        })
    }

    private fun footer() {
        val age = WPrefs.cached(this)?.third?.takeIf { it > 0 }?.let {
            "Updated " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + ". "
        } ?: ""
        if (WPrefs.hasPlace(this)) hig.section(content).add(hig.action(if (Weather.refreshing) "Updating…" else "Update Now") { Weather.refresh(this) })
        hig.note(content, age + "Weather and air-quality data from Open-Meteo.com (CC BY 4.0); air quality from the Copernicus Atmosphere Monitoring Service. Only the rounded position is sent, with no account or ID.")
    }

    private fun check() = ImageView(this).apply {
        setImageResource(R.drawable.ic_check); setColorFilter(hig.blue); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22))
    }

    // ------------------------------------------------------------------ place

    private fun useCurrentLocation(quiet: Boolean = false) {
        if (locating) return
        locating = true; render()
        Here.locate(this) { l ->
            if (l == null) {
                locating = false; render()
                if (!quiet) Toast.makeText(this, "Couldn't find your location. Turn on Location, or choose a city.", Toast.LENGTH_LONG).show()
                else if (Weather.ageMs(this) > 10 * 60_000L) Weather.refresh(this)
                return@locate
            }
            Here.name(this, l) { name ->
                locating = false
                val moved = !WPrefs.hasPlace(this) || !WPrefs.useLocation(this) ||
                    Math.abs(WPrefs.lat(this) - l.latitude) > 0.02 || Math.abs(WPrefs.lon(this) - l.longitude) > 0.02
                if (moved || name != null) WPrefs.setPlace(this, name ?: "Current Location", l.latitude, l.longitude, fromLocation = true)
                UpdateJob.schedule(this)
                if (moved || Weather.ageMs(this) > 10 * 60_000L) Weather.refresh(this) else render()
            }
        }
    }

    private fun search() {
        val input = EditText(this).apply {
            hint = "City or town"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH; isSingleLine = true
        }
        val box = FrameLayout(this).apply { setPadding(hig.dp(20), hig.dp(8), hig.dp(20), 0); addView(input) }
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Choose City").setView(box)
            .setPositiveButton("Search") { _, _ -> find(input.text.toString()) }
            .setNegativeButton("Cancel", null).create()
        input.setOnEditorActionListener { _, _, _ -> dlg.dismiss(); find(input.text.toString()); true }
        dlg.show()
        input.requestFocus()
    }

    private fun find(q: String) {
        if (q.isBlank()) return
        Thread {
            val res = runCatching { Forecast.parsePlaces(Weather.get(Forecast.geocodeUrl(q))) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val places = res.getOrNull()
                when {
                    places == null -> Toast.makeText(this, "Couldn't search. Check your connection.", Toast.LENGTH_LONG).show()
                    places.isEmpty() -> Toast.makeText(this, "No place called \"$q\" found", Toast.LENGTH_LONG).show()
                    else -> MaterialAlertDialogBuilder(this).setTitle("Results")
                        .setItems(places.map { if (it.detail.isBlank()) it.name else "${it.name} — ${it.detail}" }.toTypedArray()) { _, i ->
                            val p = places[i]
                            WPrefs.setPlace(this, p.name, p.lat, p.lon, fromLocation = false)
                            UpdateJob.schedule(this); render(); Weather.refresh(this)
                        }.setNegativeButton("Cancel", null).show()
                }
            }
        }.start()
    }
}
