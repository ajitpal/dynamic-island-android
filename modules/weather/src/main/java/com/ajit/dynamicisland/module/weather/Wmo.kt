package com.ajit.dynamicisland.module.weather

/** WMO weather interpretation codes (what Open-Meteo returns as weather_code) → words and an icon. */
object Wmo {
    enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

    data class Condition(val text: String, val sky: Sky)

    fun of(code: Int, isDay: Boolean = true): Condition = when (code) {
        0 -> Condition(if (isDay) "Sunny" else "Clear", Sky.CLEAR)
        1 -> Condition(if (isDay) "Mostly Sunny" else "Mostly Clear", Sky.CLEAR)
        2 -> Condition("Partly Cloudy", Sky.PARTLY)
        3 -> Condition("Cloudy", Sky.CLOUDY)
        45, 48 -> Condition("Fog", Sky.FOG)
        51, 53, 55 -> Condition("Drizzle", Sky.DRIZZLE)
        56, 57 -> Condition("Freezing Drizzle", Sky.DRIZZLE)
        61 -> Condition("Light Rain", Sky.RAIN)
        63 -> Condition("Rain", Sky.RAIN)
        65 -> Condition("Heavy Rain", Sky.RAIN)
        66, 67 -> Condition("Freezing Rain", Sky.RAIN)
        71, 73, 75, 77 -> Condition("Snow", Sky.SNOW)
        80, 81 -> Condition("Showers", Sky.RAIN)
        82 -> Condition("Heavy Showers", Sky.RAIN)
        85, 86 -> Condition("Snow Showers", Sky.SNOW)
        95 -> Condition("Thunderstorm", Sky.STORM)
        96, 99 -> Condition("Thunderstorm with Hail", Sky.STORM)
        else -> Condition("—", Sky.CLOUDY)
    }

    fun isWet(code: Int) = of(code).sky in setOf(Sky.DRIZZLE, Sky.RAIN, Sky.STORM, Sky.SNOW)
}
