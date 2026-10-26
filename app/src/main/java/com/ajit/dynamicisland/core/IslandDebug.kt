package com.ajit.dynamicisland.core

/** Live state shown in the app's Diagnostics card, to troubleshoot on-device. */
object IslandDebug {
    @Volatile var overlayAttached = "no"
    @Volatile var mode = "-"
    @Volatile var statusBarReported = "unknown"
    @Volatile var hiddenReason = "-"
    @Volatile var foreground = "-"
    @Volatile var lastPeek = "-"
    @Volatile var lastNotification = "-"
    @Volatile var geometry = "-"
}
