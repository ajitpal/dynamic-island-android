package com.ajit.dynamicisland.module.weather

import com.ajit.dynamicisland.module.weather.Aqi.Pollutant.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLogicTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private val now = 1793440800L

    // ---- CPCB sub-indices at the published breakpoints ----
    @Test fun breakpointsMatchCpcb() {
        assertEquals(50, Aqi.subIndex(PM25, 30.0)); assertEquals(100, Aqi.subIndex(PM25, 60.0))
        assertEquals(200, Aqi.subIndex(PM25, 90.0)); assertEquals(300, Aqi.subIndex(PM25, 120.0)); assertEquals(400, Aqi.subIndex(PM25, 250.0))
        assertEquals(100, Aqi.subIndex(PM10, 100.0)); assertEquals(200, Aqi.subIndex(PM10, 250.0)); assertEquals(400, Aqi.subIndex(PM10, 430.0))
        assertEquals(100, Aqi.subIndex(CO, 2.0)); assertEquals(200, Aqi.subIndex(O3, 168.0)); assertEquals(200, Aqi.subIndex(SO2, 380.0))
        assertEquals(0, Aqi.subIndex(NO2, 0.0))
    }

    @Test fun interpolatesInsideABand() {
        assertEquals(150, Aqi.subIndex(PM25, 75.0))   // halfway through 60–90 → halfway through 100–200
        assertEquals(25, Aqi.subIndex(PM10, 25.0))
    }

    @Test fun severeContinuesAndStopsAt500() {
        assertTrue(Aqi.subIndex(PM25, 300.0) in 401..500)
        assertEquals(500, Aqi.subIndex(PM25, 2000.0))
    }

    @Test fun overallIsTheWorstPollutant() {
        val r = Aqi.of(mapOf(PM25 to 75.0, PM10 to 120.0, NO2 to 30.0))!!
        assertEquals(150, r.aqi); assertEquals(PM25, r.main); assertEquals(Aqi.Category.MODERATE, r.category)
    }

    @Test fun needsThreePollutantsIncludingPm() {
        assertNull(Aqi.of(mapOf(PM25 to 75.0, NO2 to 30.0)))
        assertNull(Aqi.of(mapOf(NO2 to 30.0, SO2 to 10.0, O3 to 60.0)))
        assertNull(Aqi.of(mapOf(PM25 to 75.0, NO2 to Double.NaN, O3 to Double.NaN)))
    }

    @Test fun categories() {
        assertEquals(Aqi.Category.GOOD, Aqi.Category.of(50)); assertEquals(Aqi.Category.SATISFACTORY, Aqi.Category.of(51))
        assertEquals(Aqi.Category.POOR, Aqi.Category.of(201)); assertEquals(Aqi.Category.SEVERE, Aqi.Category.of(500))
    }

    @Test fun averagingNeedsMostOfThePeriod() {
        assertEquals(10.0, Aqi.average(List(24) { 10.0 }, 24), 0.0)
        assertTrue(Aqi.average(List(10) { 10.0 } + List(14) { null }, 24).isNaN())
        assertEquals(20.0, Aqi.average(List(30) { 5.0 } + List(8) { 20.0 }, 8), 0.0)
    }

    // ---- parsing saved responses ----
    @Test fun parsesForecastAndAir() {
        val s = Forecast.parse(res("forecast.json"), res("air.json"), now)
        assertEquals(31.4, s.tempC, 0.01); assertEquals("Partly Cloudy", s.condition.text)
        assertEquals(33.8, s.maxC, 0.01); assertEquals(26.2, s.minC, 0.01)
        assertEquals(45, s.rainInMin)
        assertEquals(150, s.aqi!!.aqi); assertEquals(Aqi.Category.MODERATE, s.aqi!!.category)
        assertEquals(now, s.hours.first().time)          // the hour that has ended is dropped, the current one kept
        assertEquals(24, s.hours.size)
    }

    @Test fun carbonMonoxideIsConvertedToMilligrams() {
        val s = Forecast.parse(res("forecast.json"), res("air.json"), now)
        assertEquals(45, s.aqi?.subIndices?.get(CO))          // 900 µg/m³ = 0.9 mg/m³ → 45
    }

    @Test fun weatherWithoutAirStillWorks() {
        val s = Forecast.parse(res("forecast.json"), null, now)
        assertNull(s.aqi); assertEquals(2, s.code)
    }

    @Test fun geocodeResults() {
        val p = Forecast.parsePlaces("""{"results":[{"name":"Pune","latitude":18.52,"longitude":73.86,"country":"India","admin1":"Maharashtra"}]}""")
        assertEquals("Pune", p.single().name); assertEquals("Maharashtra, India", p.single().detail)
        assertTrue(Forecast.parsePlaces("{}").isEmpty())
    }

    @Test fun urlsSendOnlyARoundedPosition() {
        val u = Forecast.forecastUrl(19.076090, 72.877426)
        assertTrue(u.contains("latitude=19.08&longitude=72.88"))
        assertFalse(u.contains("19.0760"))
    }

    // ---- alerts ----
    @Test fun rainAlertOnceInTwoHours() {
        val s = Forecast.parse(res("forecast.json"), null, now)
        assertTrue(Alerts.rain(s, 0, now * 1000))
        assertFalse(Alerts.rain(s, now * 1000 - 3600_000, now * 1000))
        assertFalse(Alerts.rain(s.copy(code = 63), 0, now * 1000))    // already raining
        assertFalse(Alerts.rain(s.copy(rainInMin = null), 0, now * 1000))
    }

    @Test fun airAlertWhenPoorAndOnlyIfWorse() {
        val base = Forecast.parse(res("forecast.json"), res("air.json"), now)
        val poor = base.copy(aqi = Aqi.of(mapOf(PM25 to 100.0, PM10 to 100.0, NO2 to 10.0)))
        assertFalse(Alerts.air(base, null, -1, 10))                         // Moderate: no alert
        assertTrue(Alerts.air(poor, null, -1, 10))
        assertFalse(Alerts.air(poor, Aqi.Category.POOR, 10, 10))           // already told today
        assertTrue(Alerts.air(poor, Aqi.Category.POOR, 9, 10))             // new day
        val veryPoor = base.copy(aqi = Aqi.of(mapOf(PM25 to 200.0, PM10 to 100.0, NO2 to 10.0)))
        assertTrue(Alerts.air(veryPoor, Aqi.Category.POOR, 10, 10))        // got worse
    }

    @Test fun weatherCodes() {
        assertEquals("Clear", Wmo.of(0, isDay = false).text)
        assertEquals(Wmo.Sky.STORM, Wmo.of(95).sky)
        assertTrue(Wmo.isWet(61)); assertFalse(Wmo.isWet(3))
    }
}
