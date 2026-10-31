package com.ajit.dynamicisland.module.weather

import android.content.Context

/** Weather & AQI settings and the last download (so the tile and screen show something offline). */
object WPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("weather", Context.MODE_PRIVATE)

    // ---- place: chosen city, or the approximate position saved when you last opened the app ----
    fun hasPlace(c: Context) = sp(c).contains("lat")
    fun placeName(c: Context) = sp(c).getString("place", null) ?: "Current Location"
    fun lat(c: Context) = sp(c).getFloat("lat", 0f).toDouble()
    fun lon(c: Context) = sp(c).getFloat("lon", 0f).toDouble()
    fun useLocation(c: Context) = sp(c).getBoolean("use_location", false)
    fun setPlace(c: Context, name: String, lat: Double, lon: Double, fromLocation: Boolean) = sp(c).edit()
        .putString("place", name).putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).putBoolean("use_location", fromLocation)
        .remove("forecast").remove("air").apply()

    // ---- options ----
    fun fahrenheit(c: Context) = sp(c).getBoolean("fahrenheit", java.util.Locale.getDefault().country in setOf("US", "LR", "MM"))
    fun setFahrenheit(c: Context, on: Boolean) = sp(c).edit().putBoolean("fahrenheit", on).apply()
    fun keepInIsland(c: Context) = sp(c).getBoolean("keep", false)
    fun setKeepInIsland(c: Context, on: Boolean) = sp(c).edit().putBoolean("keep", on).apply()
    fun rainAlerts(c: Context) = sp(c).getBoolean("alert_rain", true)
    fun setRainAlerts(c: Context, on: Boolean) = sp(c).edit().putBoolean("alert_rain", on).apply()
    fun airAlerts(c: Context) = sp(c).getBoolean("alert_air", true)
    fun setAirAlerts(c: Context, on: Boolean) = sp(c).edit().putBoolean("alert_air", on).apply()
    /** False while the person has turned the module off in the island's Modules tab: no downloads then. */
    fun enabledByIsland(c: Context) = sp(c).getBoolean("island_on", true)
    fun setEnabledByIsland(c: Context, on: Boolean) { sp(c).edit().putBoolean("island_on", on).apply(); UpdateJob.schedule(c) }

    // ---- cache ----
    fun cached(c: Context): Triple<String, String?, Long>? {
        val f = sp(c).getString("forecast", null) ?: return null
        return Triple(f, sp(c).getString("air", null), sp(c).getLong("fetched", 0))
    }
    fun setCached(c: Context, forecast: String, air: String?) =
        sp(c).edit().putString("forecast", forecast).putString("air", air).putLong("fetched", System.currentTimeMillis()).apply()

    // ---- alert memory ----
    fun lastRainAlert(c: Context) = sp(c).getLong("rain_at", 0)
    fun setLastRainAlert(c: Context, t: Long) = sp(c).edit().putLong("rain_at", t).apply()
    fun lastAir(c: Context): Pair<Aqi.Category?, Int> =
        (sp(c).getString("air_cat", null)?.let { runCatching { Aqi.Category.valueOf(it) }.getOrNull() }) to sp(c).getInt("air_day", -1)
    fun setLastAir(c: Context, cat: Aqi.Category, day: Int) = sp(c).edit().putString("air_cat", cat.name).putInt("air_day", day).apply()
}
