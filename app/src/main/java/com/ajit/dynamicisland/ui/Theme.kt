package com.ajit.dynamicisland.ui

/** Island colour themes (inspired by "iPhone Black / Glass / Light / Rose / Ocean / Lavender" styles). */
data class Palette(
    val id: String, val name: String,
    val bg: Int, val fg: Int, val fg2: Int, val fg3: Int, val surface: Int,
    val stroke: Int = 0, val bgAlpha: Float = 1f,
)

object Themes {
    val all = listOf(
        Palette("black", "iPhone Black", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFAEAEB2.toInt(), 0xFF8E8E93.toInt(), 0xFF2C2C2E.toInt()),
        Palette("glass", "iPhone Glass", 0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), 0xFFD1D1D6.toInt(), 0xFFAEAEB2.toInt(), 0x33FFFFFF, stroke = 0x40FFFFFF, bgAlpha = 0.62f),
        Palette("light", "Light", 0xFFF2F2F7.toInt(), 0xFF000000.toInt(), 0xFF3C3C43.toInt(), 0xFF6C6C70.toInt(), 0xFFE0E0E6.toInt(), stroke = 0x22000000),
        Palette("rose", "Rose", 0xFF2B0A16.toInt(), 0xFFFFE4EC.toInt(), 0xFFF7B6C8.toInt(), 0xFFD58CA2.toInt(), 0xFF4A1426.toInt()),
        Palette("ocean", "Ocean", 0xFF04213A.toInt(), 0xFFE0F2FF.toInt(), 0xFF9FD3F5.toInt(), 0xFF6FA9CF.toInt(), 0xFF0B3A5C.toInt()),
        Palette("lavender", "Lavender", 0xFF1E1433.toInt(), 0xFFEEE6FF.toInt(), 0xFFCDBAF5.toInt(), 0xFFA591D6.toInt(), 0xFF33255A.toInt()),
        Palette("graphite", "Graphite", 0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), 0xFFAEAEB2.toInt(), 0xFF8E8E93.toInt(), 0xFF3A3A3C.toInt()),
    )
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}
