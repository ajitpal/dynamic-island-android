package com.ajit.dynamicisland.module.weather

import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandContract
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/**
 * Connects to the island. Tools: Weather (shows the weather now in the island), Forecast (opens the app).
 * Tile on the Island tab: temperature, sky and AQI. Optional always-on card. Rain and air alerts.
 */
class WeatherModule : IslandModuleService() {
    companion object {
        const val CARD_NOW = "now"
        const val CARD_ALERT = "alert"
        val instance get() = current as? WeatherModule
    }

    override val tools get() = listOf(
        ModuleTool("now", "Weather", R.drawable.ic_partly),
        ModuleTool("forecast", "Forecast & AQI", R.drawable.ic_leaf, MainActivity::class.java),
    )
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() {
        UpdateJob.schedule(this)
        val s = Weather.snapshot(this)
        if (s != null) { postTile(s); postNow(s, peek = false) } else postTile(null)
        if (Weather.ageMs(this) > 20 * 60_000L) Weather.refresh(this)
    }

    override fun onToolTapped(toolId: String) {
        if (toolId != "now") return
        if (!WPrefs.hasPlace(this)) { postSetup(); return }
        val s = Weather.snapshot(this)
        if (s != null) postNow(s, peek = true)
        if (s == null || Weather.ageMs(this) > 15 * 60_000L) Weather.refresh(this) { ok -> if (ok) Weather.snapshot(this)?.let { postNow(it, peek = true) } }
    }

    private fun aqiText(s: Snapshot) = s.aqi?.let { "AQI ${it.aqi} ${it.category.label}" }

    fun postTile(s: Snapshot?) {
        val b = IslandTile.Builder(this).icon(R.drawable.ic_partly).tapActivity(MainActivity::class.java)
        if (s == null || !WPrefs.hasPlace(this)) b.title("Weather & AQI").text("Choose a place to see the weather and air quality.")
        else b.title("${Weather.temp(this, s.tempC)} · ${s.condition.text}")
            .text(listOfNotNull(WPrefs.placeName(this), aqiText(s), Weather.rainText(s.rainInMin)).joinToString(" · "))
        island.postTile(b.build())
    }

    /** The weather now. With [peek] it pops open for a few seconds; with "Keep in the island" it stays (compact). */
    fun postNow(s: Snapshot, peek: Boolean) {
        val keep = WPrefs.keepInIsland(this)
        if (!keep && !peek) { island.remove(CARD_NOW); return }
        val c = s.condition
        val b = IslandCard.Builder(this, CARD_NOW)
            .title("${Weather.temp(this, s.tempC)} · ${c.text}")
            .text(listOfNotNull(
                "${WPrefs.placeName(this)} · H ${Weather.temp(this, s.maxC)} L ${Weather.temp(this, s.minC)}",
                aqiText(s), Weather.rainText(s.rainInMin),
            ).joinToString("\n"))
            .compact(listOfNotNull(Weather.temp(this, s.tempC), s.aqi?.aqi?.toString()).joinToString(" · "))
            .icon(Weather.skyIcon(c.sky, s.isDay))
            .accent(s.aqi?.category?.color ?: Weather.skyColor(c.sky, s.isDay))
            .tapActivity(MainActivity::class.java)
            .version((s.time / 60).toInt())
        if (peek) b.peek(6000)
        if (!keep) b.expiresIn(12_000)
        island.post(b.build())
    }

    fun postRainAlert(s: Snapshot) = island.post(IslandCard.Builder(this, CARD_ALERT).style(IslandContract.STYLE_ALERT)
        .title(Weather.rainText(s.rainInMin) ?: "Rain soon").text("${WPrefs.placeName(this)} · take an umbrella")
        .compact("Rain").icon(R.drawable.ic_rain).accent(0xFF0A84FF.toInt())
        .peek(6000).expiresIn(20 * 60_000L).tapActivity(MainActivity::class.java).build())

    fun postAirAlert(s: Snapshot) {
        val a = s.aqi ?: return
        island.post(IslandCard.Builder(this, CARD_ALERT).style(IslandContract.STYLE_ALERT)
            .title("Air quality ${a.category.label} · AQI ${a.aqi}").text(a.category.advice)
            .compact("AQI ${a.aqi}").icon(R.drawable.ic_leaf).accent(a.category.color)
            .peek(6000).expiresIn(60 * 60_000L).tapActivity(MainActivity::class.java).build())
    }

    private fun postSetup() = island.post(IslandCard.Builder(this, CARD_NOW)
        .title("Weather & AQI").text("Choose a place first: open Weather and pick your location or a city.")
        .icon(R.drawable.ic_location).accent(0xFF0A84FF.toInt())
        .activityAction("open", "Choose Place", MainActivity::class.java)
        .peek(5000).expiresIn(12_000).build())
}
