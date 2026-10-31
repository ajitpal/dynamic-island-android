package com.ajit.dynamicisland.module.weather

import org.json.JSONArray
import org.json.JSONObject

/**
 * Open-Meteo responses (forecast + air quality, `timeformat=unixtime`) → what the module shows.
 * Pure Kotlin + org.json, so it is unit-tested with saved responses.
 */
data class Snapshot(
    val tempC: Double, val feelsC: Double, val humidity: Int, val windKmh: Double,
    val code: Int, val isDay: Boolean,
    val maxC: Double, val minC: Double,
    val hours: List<Hour>,
    /** Minutes until rain starts (0 = raining now), within the next two hours; null if none expected. */
    val rainInMin: Int?,
    val aqi: Aqi.Result?,
    val time: Long,
) {
    data class Hour(val time: Long, val tempC: Double, val rainChance: Int, val code: Int, val isDay: Boolean)
    val condition get() = Wmo.of(code, isDay)
}

object Forecast {
    const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    const val AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"
    const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

    fun forecastUrl(lat: Double, lon: Double) = "$FORECAST?latitude=${fmt(lat)}&longitude=${fmt(lon)}" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m" +
        "&hourly=temperature_2m,precipitation_probability,weather_code,is_day&minutely_15=precipitation" +
        "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&forecast_minutely_15=8&timezone=auto&timeformat=unixtime"

    fun airUrl(lat: Double, lon: Double) = "$AIR?latitude=${fmt(lat)}&longitude=${fmt(lon)}" +
        "&hourly=pm10,pm2_5,nitrogen_dioxide,sulphur_dioxide,ozone,carbon_monoxide&past_days=1&forecast_days=1&timezone=auto&timeformat=unixtime"

    fun geocodeUrl(name: String) = "$GEOCODE?name=${java.net.URLEncoder.encode(name.trim(), "UTF-8")}&count=8&language=en&format=json"

    /** Two decimals (~1 km) is plenty for weather and keeps the exact position off the network. */
    private fun fmt(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

    private fun JSONArray.d(i: Int): Double = if (isNull(i)) Double.NaN else optDouble(i, Double.NaN)
    private fun JSONArray.l(i: Int): Long = optLong(i)

    fun parse(forecastJson: String, airJson: String?, nowSec: Long): Snapshot {
        val f = JSONObject(forecastJson)
        val cur = f.getJSONObject("current")
        val h = f.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = (0 until ht.length()).map { i ->
            Snapshot.Hour(ht.l(i), h.getJSONArray("temperature_2m").d(i), h.optJSONArray("precipitation_probability")?.optInt(i, 0) ?: 0,
                h.getJSONArray("weather_code").optInt(i), (h.optJSONArray("is_day")?.optInt(i, 1) ?: 1) == 1)
        }.filter { it.time + 3600 > nowSec }.take(24)
        val daily = f.optJSONObject("daily")
        val m = f.optJSONObject("minutely_15")
        val rain = m?.let { rainInMin(it.getJSONArray("time"), it.getJSONArray("precipitation"), nowSec) }
        return Snapshot(
            tempC = cur.optDouble("temperature_2m"), feelsC = cur.optDouble("apparent_temperature"),
            humidity = cur.optInt("relative_humidity_2m"), windKmh = cur.optDouble("wind_speed_10m"),
            code = cur.optInt("weather_code"), isDay = cur.optInt("is_day", 1) == 1,
            maxC = daily?.getJSONArray("temperature_2m_max")?.d(0) ?: Double.NaN,
            minC = daily?.getJSONArray("temperature_2m_min")?.d(0) ?: Double.NaN,
            hours = hours, rainInMin = rain, aqi = airJson?.let { aqiNow(it, nowSec) }, time = cur.optLong("time", nowSec),
        )
    }

    /** First 15-minute slot from now with at least 0.1 mm of rain, in minutes (0 if it's raining now). */
    fun rainInMin(times: JSONArray, mm: JSONArray, nowSec: Long): Int? {
        for (i in 0 until times.length()) {
            val t = times.l(i)
            if (t + 15 * 60 <= nowSec) continue
            if (mm.d(i) >= 0.1) return ((t - nowSec) / 60).toInt().coerceAtLeast(0)
        }
        return null
    }

    /** CPCB AQI for "now" from the hourly concentrations up to the current hour. */
    fun aqiNow(airJson: String, nowSec: Long): Aqi.Result? = try {
        val h = JSONObject(airJson).getJSONObject("hourly")
        val t = h.getJSONArray("time")
        val upTo = (0 until t.length()).lastOrNull { t.l(it) <= nowSec } ?: -1
        if (upTo < 0) null else {
            fun series(key: String, scale: Double = 1.0) = h.optJSONArray(key)?.let { a -> (0..upTo).map { a.d(it).takeIf { v -> !v.isNaN() }?.times(scale) } } ?: emptyList()
            val src = mapOf(
                Aqi.Pollutant.PM25 to series("pm2_5"), Aqi.Pollutant.PM10 to series("pm10"),
                Aqi.Pollutant.NO2 to series("nitrogen_dioxide"), Aqi.Pollutant.SO2 to series("sulphur_dioxide"),
                Aqi.Pollutant.O3 to series("ozone"), Aqi.Pollutant.CO to series("carbon_monoxide", 0.001), // µg/m³ → mg/m³
            )
            Aqi.of(src.mapValues { (p, s) -> Aqi.average(s, Aqi.hoursFor(p)) })
        }
    } catch (e: Exception) { null }

    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    fun parsePlaces(json: String): List<Place> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(o.getString("name"), listOfNotNull(o.optString("admin1").takeIf { it.isNotBlank() }, o.optString("country").takeIf { it.isNotBlank() }).joinToString(", "),
                o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }
}

/** When the island should speak up. Pure, so the rules are tested. */
object Alerts {
    /** Rain starting within this many minutes is worth a heads-up. */
    const val RAIN_WINDOW_MIN = 45
    /** CPCB "Poor" and worse. */
    const val AQI_ALERT = 201

    /** A rain alert: rain is coming soon, it isn't raining yet, and we haven't said so in the last 2 hours. */
    fun rain(s: Snapshot, lastAlertMs: Long, nowMs: Long): Boolean {
        val m = s.rainInMin ?: return false
        return m in 1..RAIN_WINDOW_MIN && !Wmo.isWet(s.code) && nowMs - lastAlertMs > 2 * 3600_000L
    }

    /** An air alert when the air turns Poor or worse, once per category per day (again only if it gets worse). */
    fun air(s: Snapshot, lastCategory: Aqi.Category?, lastDay: Int, today: Int): Boolean {
        val a = s.aqi ?: return false
        if (a.aqi < AQI_ALERT) return false
        if (lastDay != today || lastCategory == null) return true
        return a.category.ordinal > lastCategory.ordinal
    }
}
