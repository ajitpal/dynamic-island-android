package com.ajit.dynamicisland.module.weather

import kotlin.math.roundToInt

/**
 * India's National Air Quality Index (CPCB), computed from concentrations.
 *
 * Breakpoints are CPCB's (24-hour averages for PM10, PM2.5, NO2 and SO2; 8-hour averages for O3 and
 * CO, CO in mg/m³). Each pollutant gets a sub-index by straight-line interpolation inside its band; the
 * AQI is the highest sub-index, and only when at least three pollutants are known, one of them PM2.5 or
 * PM10 (CPCB's rule). CPCB gives no upper limit for "Severe": above the last breakpoint the index
 * continues at the slope of the band below and stops at 500.
 *
 * Our inputs are model estimates (Copernicus CAMS through Open-Meteo, about 45 km grid), not readings
 * from a CPCB monitoring station, and the app says so. Pure Kotlin, unit-tested.
 */
object Aqi {
    enum class Pollutant(val label: String, val unit: String) {
        PM25("PM2.5", "µg/m³"), PM10("PM10", "µg/m³"), NO2("NO₂", "µg/m³"), SO2("SO₂", "µg/m³"), O3("O₃", "µg/m³"), CO("CO", "mg/m³")
    }

    enum class Category(val label: String, val low: Int, val color: Int, val advice: String) {
        GOOD("Good", 0, 0xFF34C759.toInt(), "Minimal impact."),
        SATISFACTORY("Satisfactory", 51, 0xFF9ACD32.toInt(), "Minor breathing discomfort for sensitive people."),
        MODERATE("Moderate", 101, 0xFFFFCC00.toInt(), "Breathing discomfort for people with lung or heart disease, children and older adults."),
        POOR("Poor", 201, 0xFFFF9500.toInt(), "Breathing discomfort for most people on prolonged exposure."),
        VERY_POOR("Very Poor", 301, 0xFFFF3B30.toInt(), "Respiratory illness on prolonged exposure."),
        SEVERE("Severe", 401, 0xFFAF52DE.toInt(), "Affects healthy people; serious for those with lung or heart disease. Avoid outdoor activity.");

        companion object { fun of(aqi: Int) = entries.last { aqi >= it.low } }
    }

    /** Upper concentration of each band (Good … Very Poor), CPCB table. "Severe" is above the last value. */
    private val BANDS = mapOf(
        Pollutant.PM10 to doubleArrayOf(50.0, 100.0, 250.0, 350.0, 430.0),
        Pollutant.PM25 to doubleArrayOf(30.0, 60.0, 90.0, 120.0, 250.0),
        Pollutant.NO2 to doubleArrayOf(40.0, 80.0, 180.0, 280.0, 400.0),
        Pollutant.O3 to doubleArrayOf(50.0, 100.0, 168.0, 208.0, 748.0),
        Pollutant.CO to doubleArrayOf(1.0, 2.0, 10.0, 17.0, 34.0),
        Pollutant.SO2 to doubleArrayOf(40.0, 80.0, 380.0, 800.0, 1600.0),
    )
    private val INDEX = intArrayOf(0, 50, 100, 200, 300, 400, 500)

    /** Sub-index for one pollutant, 0..500. */
    fun subIndex(p: Pollutant, c: Double): Int {
        if (c.isNaN() || c < 0) return 0
        val hi = BANDS.getValue(p)
        var lo = 0.0
        for (i in hi.indices) {
            if (c <= hi[i]) return (INDEX[i] + (INDEX[i + 1] - INDEX[i]) * (c - lo) / (hi[i] - lo)).roundToInt()
            lo = hi[i]
        }
        // Above "Very Poor": continue at the previous band's slope, capped at 500.
        val n = hi.size
        val slope = (INDEX[n] - INDEX[n - 1]) / (hi[n - 1] - hi[n - 2])
        return (INDEX[n] + slope * (c - hi[n - 1])).roundToInt().coerceAtMost(500)
    }

    data class Result(val aqi: Int, val category: Category, val main: Pollutant, val subIndices: Map<Pollutant, Int>)

    /** Overall AQI from averaged concentrations, or null when CPCB's minimum (3 pollutants incl. PM) isn't met. */
    fun of(concentrations: Map<Pollutant, Double>): Result? {
        val known = concentrations.filterValues { !it.isNaN() && it >= 0 }
        if (known.size < 3 || (Pollutant.PM25 !in known && Pollutant.PM10 !in known)) return null
        val subs = known.mapValues { (p, c) -> subIndex(p, c) }
        val (main, aqi) = subs.maxBy { it.value }
        return Result(aqi, Category.of(aqi), main, subs)
    }

    /**
     * CPCB averaging from hourly values (newest last): 24 hours for PM, NO2, SO2; 8 hours for O3 and CO.
     * At least 16 of 24 (or 6 of 8) hours must be present, as CPCB requires most of the period.
     */
    fun average(hourly: List<Double?>, hours: Int): Double {
        val window = hourly.takeLast(hours).filterNotNull().filter { !it.isNaN() }
        val need = if (hours >= 24) 16 else (hours * 3 + 3) / 4
        return if (window.size < need) Double.NaN else window.average()
    }

    fun hoursFor(p: Pollutant) = if (p == Pollutant.O3 || p == Pollutant.CO) 8 else 24
}
