package com.ajit.dynamicisland.module.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Downloads weather and air quality for the saved place from Open-Meteo (no account, no key, no
 * identifier sent), keeps the last answer, and tells the island: tile, the optional always-on card,
 * and alerts. Network on a worker thread; callbacks on the main thread.
 */
object Weather {
    private const val TAG = "Weather"
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "weather") }
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile var refreshing = false
        private set
    @Volatile var lastError: String? = null
        private set

    /** Screenshot tests pin "now" so the sample data looks current. */
    @Volatile var nowForTest: Long? = null
    private fun nowSec() = nowForTest ?: (System.currentTimeMillis() / 1000)

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** What we know now (from the last download), or null before the first one. */
    fun snapshot(ctx: Context): Snapshot? {
        val (f, a, _) = WPrefs.cached(ctx) ?: return null
        return runCatching { Forecast.parse(f, a, nowSec()) }.getOrNull()
    }

    fun ageMs(ctx: Context) = WPrefs.cached(ctx)?.let { System.currentTimeMillis() - it.third } ?: Long.MAX_VALUE

    /** Download now (any thread). [then] runs on the main thread with true if it worked. */
    fun refresh(ctx: Context, then: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        if (!WPrefs.hasPlace(app)) { main.post { then(false) }; return }
        refreshing = true; notifyListeners()
        worker.execute {
            val ok = try {
                val lat = WPrefs.lat(app); val lon = WPrefs.lon(app)
                val f = get(Forecast.forecastUrl(lat, lon))
                // Air quality is a separate service; weather still shows if it's unavailable.
                val a = runCatching { get(Forecast.airUrl(lat, lon)) }.getOrNull()
                Forecast.parse(f, a, System.currentTimeMillis() / 1000) // validates before saving
                WPrefs.setCached(app, f, a)
                lastError = null; true
            } catch (e: Exception) {
                Log.w(TAG, "refresh", e)
                lastError = when (e) {
                    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "No connection. Showing the last update."
                    else -> "Couldn't update: ${e.message}"
                }
                false
            }
            main.post {
                refreshing = false
                publish(app)
                notifyListeners()
                then(ok)
            }
        }
    }

    /** Tile, the always-on card and alerts, from the cached data. */
    fun publish(ctx: Context) {
        val s = snapshot(ctx) ?: return
        val m = WeatherModule.instance ?: return
        m.postTile(s)
        m.postNow(s, peek = false)
        val now = System.currentTimeMillis()
        if (WPrefs.rainAlerts(ctx) && Alerts.rain(s, WPrefs.lastRainAlert(ctx), now)) { WPrefs.setLastRainAlert(ctx, now); m.postRainAlert(s) }
        val (cat, day) = WPrefs.lastAir(ctx)
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (WPrefs.airAlerts(ctx) && Alerts.air(s, cat, day, today)) { WPrefs.setLastAir(ctx, s.aqi!!.category, today); m.postAirAlert(s) }
    }

    private fun notifyListeners() = main.post { listeners.toList().forEach { it() } }

    fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "DynamicIsland-Weather")
        }
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("the weather service answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }.also { require(it.length < 1_000_000) { "answer too large" } }
        } finally { c.disconnect() }
    }

    // ---- formatting ----
    fun temp(ctx: Context, c: Double): String {
        if (c.isNaN()) return "–"
        val v = if (WPrefs.fahrenheit(ctx)) c * 9 / 5 + 32 else c
        return "${v.roundToInt()}°"
    }

    fun skyIcon(sky: Wmo.Sky, isDay: Boolean) = when (sky) {
        Wmo.Sky.CLEAR -> if (isDay) R.drawable.ic_sun else R.drawable.ic_moon
        Wmo.Sky.PARTLY -> if (isDay) R.drawable.ic_partly else R.drawable.ic_cloud
        Wmo.Sky.CLOUDY -> R.drawable.ic_cloud
        Wmo.Sky.FOG -> R.drawable.ic_fog
        Wmo.Sky.DRIZZLE, Wmo.Sky.RAIN -> R.drawable.ic_rain
        Wmo.Sky.SNOW -> R.drawable.ic_snow
        Wmo.Sky.STORM -> R.drawable.ic_storm
    }

    fun skyColor(sky: Wmo.Sky, isDay: Boolean) = when (sky) {
        Wmo.Sky.CLEAR, Wmo.Sky.PARTLY -> if (isDay) 0xFFFFCC00.toInt() else 0xFF8E8EF0.toInt()
        Wmo.Sky.RAIN, Wmo.Sky.DRIZZLE, Wmo.Sky.STORM -> 0xFF0A84FF.toInt()
        else -> 0xFF8E8E93.toInt()
    }

    fun rainText(min: Int?) = when (min) {
        null -> null
        0 -> "Raining now"
        else -> "Rain in about ${((min + 4) / 5) * 5} min"
    }
}
