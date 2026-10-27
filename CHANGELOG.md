# Changelog

## 1.5.0 (27 Oct 2026): Modules
- **Module platform:** add-on apps extend the island through a small SDK (`:module-sdk`, AIDL + Bundle contract). Modules send cards (title, text, icon, countdown, progress, up to 3 buttons, reply field) and add tools; the island draws everything in your theme.
- **Modules screen:** see each module's developer, verification status, permissions and certificate; approve, disable, open settings, uninstall.
- **Trust model:** modules pin the island's certificate; the island checks each module's certificate and caller UID; approved third-party developers via `signature|knownSigner` allow-list; Developer mode for unverified modules.
- **Automation Bridge module:** Tasker / MacroDroid / adb → island cards (secret-key protected); two island tools that trigger automations; reply and button events back.
- **Calendar module:** next-meeting countdown, pop-up at T-5 min with one-tap Join (Meet, Zoom, Teams, Webex), progress during the meeting. Calendar access stays in the module.
- Developer guide: [docs/MODULES.md](docs/MODULES.md)

## 1.4.0 (27 Oct 2026)
- **Customisable Island tools**: 25+ built-in tools (flashlight, timers incl. custom & 25-min focus, stopwatch, screenshot, pin note, Inbox, copy latest OTP, play/pause/next/previous, volume / Wi-Fi / Bluetooth / NFC panels, ring-vibrate, Do Not Disturb, lock screen, power menu, recent apps, split screen, notification shade, quick settings) plus **any app** as a shortcut; reorder, remove, multiple pages, 3–8 per page
- **Notification Inbox**: every notification kept on the device, **encrypted** (Android Keystore AES-GCM), until you clear it; search, filters (Unread, Messages, Payments, OTPs), swipe to clear, tap to open, long-press to copy; retention 1–30 days; mini Inbox in the island (swipe down on the empty island); launcher shortcut
- **Duplicate pop-up controls**: per-app link to turn off system pop-ups, ADB command, opt-in "move notifications into the island"; the island never pops twice for the same message
- First unit tests (OTP detection, tool layout, de-duplication)
- Roadmap: modular add-on apps with approved third-party developers (see ROADMAP.md)

## 1.3.1 (26 Oct 2026)
- Brand-aware "keep it running" setup (Xiaomi, Samsung, OnePlus, OPPO/realme, vivo/iQOO, Huawei, Motorola, Pixel) with links to dontkillmyapp.com
- Open-source release: docs, website, CI

## 1.3.0
- **Invisible at rest:** the island shrinks to exactly the camera hole and grows out of it; calibrate mode
- Shows **every notification** (silent ones don't pop open)
- 7 themes, per-app control, animation speed

## 1.2.0
- **Tap to preview** with an Open button, inline quick reply
- Unlock animation, earbuds + battery, payments (Wallet / UPI), DND, offline/online, hotspot, NFC, screen/voice recording, live scores & deliveries, Android 16 Live Updates
- Swipe sideways shrinks back to the pill

## 1.1.0
- Sizes derived from the status bar and camera cutout; width/height/roundness/opacity/outline controls

## 1.0.2
- Fixed: island hidden on skins that misreport the status bar to overlays; Diagnostics card

## 1.0.1
- Own release signing, removed QUERY_ALL_PACKAGES and battery-optimisation request, prominent disclosure, R8

## 1.0.0
- First version: compact/expanded island, music, calls, timers, navigation, progress, messages with quick reply, system glimpses, island tools, pinned note
