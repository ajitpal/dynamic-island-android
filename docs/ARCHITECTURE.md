# How it works

```
NotificationListenerService ──► NotificationParser ─┐
MediaSessionManager ─────────► MediaTracker ────────┤
Broadcasts / Camera / Audio ─► SystemEventMonitor ──┼─► IslandRepository (StateFlow) ─► IslandOverlay
IslandTools / Demo ─────────────────────────────────┘                                  (accessibility overlay window)
```

| File | Role |
|---|---|
| `core/Model.kt` | `IslandActivity` (what to show), `Kind` (priority), `IslandAction` (buttons, inline input) |
| `core/IslandStack.kt` | Which activity is in the island, which in the circle, how many behind the "+N" badge; tap-to-cycle order (pure Kotlin, unit-tested) |
| `core/modules/Catalog.kt` | "Get more" list: bundled copy, or the newer signed catalogue the Updater downloaded (verified with the app's own key) |
| `core/IslandRepository.kt` | Single in-process source of truth: activities, peeks, camera/mic/torch flags, auto-expiry |
| `core/NotificationParser.kt` | Classifies notifications: call (CallStyle), payment, recording, live updates, navigation, timer, progress, message (MessagingStyle + RemoteInput quick reply), generic |
| `core/MediaTracker.kt` | Follows every active `MediaSession` and publishes the one playing |
| `core/SystemEventMonitor.kt` | Battery, ringer, DND, Bluetooth + earbud battery, headset, airplane, network, hotspot, NFC, unlock, torch, camera availability, audio recording |
| `core/IslandTools.kt` | Built-in timer, stopwatch, flashlight, pinned note |
| `ui/IslandOverlay.kt` | The island: geometry from the real camera cutout and status-bar height, state machine (HIDDEN/IDLE/COMPACT/EXPANDED), spring animation, gestures, inline-input focus, launching apps |
| `ui/ContentBinder.kt` | Builds compact, expanded and tools content |
| `ui/Theme.kt` | Theme palettes |
| `service/IslandAccessibilityService.kt` | Hosts the overlay; tracks the foreground app via window-state events only |
| `service/IslandNotificationListener.kt` | Feeds notifications and media sessions to the parser |
| `MainActivity.kt` | Onboarding, brand-aware keep-alive help, demos, settings, Diagnostics |

## Design decisions
- **Accessibility overlay** (`TYPE_ACCESSIBILITY_OVERLAY`) is the only way for a normal app to draw over the status bar and lock screen without "display over other apps". The service subscribes only to `typeWindowStateChanged` and has `canRetrieveWindowContent=false`.
- **Geometry from the system:** the camera hole comes from `Display.getCutout()`, sanity-checked against the status-bar height, because some skins pad the cutout rectangle. The resting island equals the hole. Larger shapes grow from the camera centre inside a fixed window, so the window isn't re-laid-out every frame.
- **Heuristics off by default:** some skins misreport status-bar visibility to overlays, so "hide in full screen" is opt-in and guarded.
- **No network:** no INTERNET permission, so the app can't send data anywhere.
- **Diagnostics built in:** the settings screen shows service state, the last notification and how it was classified, why the island is hidden, and the computed geometry.

## Adding a new activity type
1. Add a branch in `NotificationParser.parse()` (or publish from a monitor) that returns an `IslandActivity` with a `Kind`.
2. If it needs a new look, extend `ContentBinder.bindCompact()` / `buildExpanded()`.
3. Add a toggle in `Prefs` + `MainActivity` and a **demo** in `Demo.kt`, so it can be tested without waiting for a real event.
