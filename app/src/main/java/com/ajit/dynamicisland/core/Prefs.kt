package com.ajit.dynamicisland.core

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun set(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    fun setInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()

    // Features
    val fMessages get() = b(F_MESSAGES, true)
    val fMedia get() = b(F_MEDIA, true)
    val fCalls get() = b(F_CALLS, true)
    val fTimers get() = b(F_TIMERS, true)
    val fNav get() = b(F_NAV, true)
    val fProgress get() = b(F_PROGRESS, true)
    val fSystem get() = b(F_SYSTEM, true)
    val fPrivacyDots get() = b(F_PRIVACY_DOTS, true)
    val fAll get() = b(F_ALL, true)
    val fLive get() = b(F_LIVE, true)
    val fPayments get() = b(F_PAYMENTS, true)
    val fRecording get() = b(F_RECORDING, true)

    // Behaviour
    val showIdle get() = b(SHOW_IDLE, true)
    val peek get() = b(PEEK, true)
    /** Tap the compact island to preview the full notification instead of opening the app. */
    val tapPreview get() = b(TAP_PREVIEW, true)
    val unlockAnim get() = b(UNLOCK_ANIM, true)
    val connectivity get() = b(CONNECTIVITY, true)
    val haptics get() = b(HAPTICS, true)
    val privacyLock get() = b(PRIVACY_LOCK, true)
    val hideLandscape get() = b(HIDE_LANDSCAPE, true)
    val hideFullscreen get() = b(HIDE_FULLSCREEN, false)
    val hideForegroundApp get() = b(HIDE_FG_APP, true)

    // Geometry (dp offsets / percent scale) and timing
    val offsetX get() = sp.getInt(OFFSET_X, 0)
    val offsetY get() = sp.getInt(OFFSET_Y, 0)
    val widthScale get() = sp.getInt(WIDTH_SCALE, 100) / 100f
    val heightScale get() = sp.getInt(HEIGHT_SCALE, 100) / 100f
    /** 0 = square corners, 100 = full pill. */
    val roundness get() = sp.getInt(ROUNDNESS, 100) / 100f
    val islandColor get() = sp.getInt(ISLAND_COLOR, 0xFF000000.toInt())
    /** 20..100 % opacity of the island background (content stays fully visible). */
    val opacity get() = sp.getInt(OPACITY, 100) / 100f
    val outline get() = b(OUTLINE, false)
    val theme get() = sp.getString(THEME, "black")
    /** Fine-tune the resting island to the exact camera hole (px, can be negative). */
    val holeAdjust get() = sp.getInt(HOLE_ADJUST, 0)
    val calibrate get() = b(CALIBRATE, false)
    /** 50..200 % animation speed. */
    val animSpeed get() = sp.getInt(ANIM_SPEED, 100) / 100f

    // Per-app control
    fun seenApps(): Set<String> = sp.getStringSet(SEEN_APPS, emptySet()) ?: emptySet()
    fun blockedApps(): Set<String> = sp.getStringSet(BLOCKED_APPS, emptySet()) ?: emptySet()
    fun isBlocked(pkg: String) = pkg in blockedApps()
    fun markSeen(pkg: String) { val s = seenApps(); if (pkg !in s) sp.edit().putStringSet(SEEN_APPS, s + pkg).apply() }
    fun setBlocked(pkgs: Set<String>) = sp.edit().putStringSet(BLOCKED_APPS, pkgs).apply()

    fun resetLook() = sp.edit().remove(WIDTH_SCALE).remove(HEIGHT_SCALE).remove(ROUNDNESS)
        .remove(ISLAND_COLOR).remove(THEME).remove(HOLE_ADJUST).remove(ANIM_SPEED).remove(OPACITY).remove(OUTLINE).remove(OFFSET_X).remove(OFFSET_Y).apply()
    val peekSeconds get() = sp.getInt(PEEK_SECONDS, 5)

    // Island tools
    var tools: List<String>
        get() = com.ajit.dynamicisland.core.tools.ToolLayout.parse(sp.getString(TOOLS, null))
        set(v) = sp.edit().putString(TOOLS, com.ajit.dynamicisland.core.tools.ToolLayout.join(v)).apply()
    val toolsPerPage get() = sp.getInt(TOOLS_PER_PAGE, 6)

    /** The master switch: false hides the island (the Inbox keeps working). */
    val islandOn get() = b(ISLAND_ON, true)

    // Notification inbox
    val inboxEnabled get() = b(INBOX_ENABLED, true)
    val inboxDays get() = sp.getInt(INBOX_DAYS, 7).coerceIn(1, 90)

    // Smart Inbox (optional, uses the on-device AI Engine module)
    val smartDigest get() = b(SMART_DIGEST, false)
    val smartReplies get() = b(SMART_REPLIES, false)

    // Pop-up duplicates: apps whose notifications are moved out of the shade into the island
    fun moveApps(): Set<String> = sp.getStringSet(MOVE_APPS, emptySet()) ?: emptySet()
    fun setMoveApps(pkgs: Set<String>) = sp.edit().putStringSet(MOVE_APPS, pkgs).apply()

    // Modules
    fun approvedModules(): Set<String> = sp.getStringSet(APPROVED_MODULES, emptySet()) ?: emptySet()
    fun setApprovedModules(pkgs: Set<String>) = sp.edit().putStringSet(APPROVED_MODULES, pkgs).apply()
    val modulesDevMode get() = b(MODULES_DEV_MODE, false)
    /** New modules from the same developer as this app are switched on and their tools added. */
    val modulesAuto get() = b(MODULES_AUTO, true)
    /** Modules / module tools the island has already offered once (so removing one is respected). */
    fun seenModules(): Set<String> = sp.getStringSet(SEEN_MODULES, emptySet()) ?: emptySet()
    fun setSeenModules(v: Set<String>) = sp.edit().putStringSet(SEEN_MODULES, v).apply()
    fun seenModuleTools(): Set<String> = sp.getStringSet(SEEN_MODULE_TOOLS, emptySet()) ?: emptySet()
    fun setSeenModuleTools(v: Set<String>) = sp.edit().putStringSet(SEEN_MODULE_TOOLS, v).apply()

    var note: String
        get() = sp.getString(NOTE, "") ?: ""
        set(v) = sp.edit().putString(NOTE, v).apply()

    fun kindEnabled(k: Kind) = when (k) {
        Kind.CALL -> fCalls
        Kind.MEDIA -> fMedia
        Kind.MESSAGE -> fMessages
        Kind.TIMER -> fTimers
        Kind.NAVIGATION -> fNav
        Kind.PROGRESS -> fProgress
        Kind.SYSTEM, Kind.TOOL -> fSystem
        Kind.NOTE -> true
        Kind.GENERIC -> fAll
        Kind.LIVE -> fLive
    }

    companion object {
        const val F_MESSAGES = "f_messages"
        const val F_MEDIA = "f_media"
        const val F_CALLS = "f_calls"
        const val F_TIMERS = "f_timers"
        const val F_NAV = "f_nav"
        const val F_PROGRESS = "f_progress"
        const val F_SYSTEM = "f_system"
        const val F_PRIVACY_DOTS = "f_privacy_dots"
        const val F_ALL = "f_all"
        const val F_LIVE = "f_live"
        const val F_PAYMENTS = "f_payments"
        const val F_RECORDING = "f_recording"
        const val SHOW_IDLE = "show_idle"
        const val PEEK = "peek"
        const val TAP_PREVIEW = "tap_preview"
        const val UNLOCK_ANIM = "unlock_anim"
        const val CONNECTIVITY = "connectivity"
        const val HAPTICS = "haptics"
        const val PRIVACY_LOCK = "privacy_lock"
        const val HIDE_LANDSCAPE = "hide_landscape"
        const val HIDE_FULLSCREEN = "hide_fullscreen"
        const val HIDE_FG_APP = "hide_fg_app"
        const val OFFSET_X = "offset_x"
        const val OFFSET_Y = "offset_y"
        const val WIDTH_SCALE = "width_scale"
        const val HEIGHT_SCALE = "height_scale"
        const val ROUNDNESS = "roundness"
        const val ISLAND_COLOR = "island_color"
        const val OPACITY = "opacity"
        const val OUTLINE = "outline"
        const val THEME = "theme"
        const val HOLE_ADJUST = "hole_adjust"
        const val CALIBRATE = "calibrate"
        const val ANIM_SPEED = "anim_speed"
        const val SEEN_APPS = "seen_apps"
        const val BLOCKED_APPS = "blocked_apps"
        const val PEEK_SECONDS = "peek_seconds"
        const val NOTE = "note"
        const val TOOLS = "tools"
        const val TOOLS_PER_PAGE = "tools_per_page"
        const val INBOX_ENABLED = "inbox_enabled"
        const val ISLAND_ON = "island_on"
        const val INBOX_DAYS = "inbox_days"
        const val SMART_DIGEST = "smart_digest"
        const val SMART_REPLIES = "smart_replies"
        const val MOVE_APPS = "move_apps"
        const val APPROVED_MODULES = "approved_modules"
        const val MODULES_DEV_MODE = "modules_dev_mode"
        const val MODULES_AUTO = "modules_auto"
        const val SEEN_MODULES = "seen_modules"
        const val SEEN_MODULE_TOOLS = "seen_module_tools"
    }
}
