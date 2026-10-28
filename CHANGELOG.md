# Changelog

## 1.8.0 (28 Oct 2026): Gemini Nano, ask your meetings, catch up from the island
- **Gemini Nano (optional, on supported phones):** Meeting Notes can use the Gemini Nano model built into some recent phones (Android AICore), with nothing to download. Android only lets the app on screen use it, so it's used while Meeting Notes is open and the AI Engine takes over in the background or if Nano refuses. Off by default; the switch only appears on phones that have Nano. AI Engine shows whether your phone has it, can ask Android to download it, and can test it.
- **Ask about this meeting:** type a question on a meeting's page ("What did we decide about the budget?"). The answer comes from the notes and the most relevant parts of the transcript, cites timestamps you can tap to jump the player there, and is saved with the meeting (and included when you share).
- **Catch me up in the island:** a new island tool summarises your unread notifications right in the island (needs Smart Inbox and AI Engine; hidden on the lock screen), with Open Inbox and Mark read.
- New `:modules:nano` library; the island app itself still has no internet permission.

## 1.7.1 (27 Oct 2026)
- **New modules are ready to use straight away:** a module from the same developer as the island (signed with the same key) is switched on when you install it, its tools (e.g. **Record meeting**) are put on the first page of your island tools, and the island tells you. Modules from other developers still wait for your approval and get a "Review" glimpse.
- **Modules screen:** an on/off switch for each module tool ("Show Record meeting in island tools"), and "Add new modules automatically" to turn this behaviour off. Removing a tool is remembered; it won't be added back.

## 1.7.0 (27 Oct 2026): On-device AI
- **AI Engine module** (new add-on app): a small language model that runs on your phone with Google's LiteRT-LM. Pick **Qwen3 0.6B** (fastest), **Qwen3 1.7B** (recommended) or **Gemma 4 E2B** (best, large). Models download once from Hugging Face (pinned versions, SHA-256 checked, resumable), then everything works offline. Optional GPU. A "Try it" box shows the speed on your phone.
- **Meeting Notes 1.1: AI notes.** After a recording, notes are written automatically: **summary, decisions, action items, open questions**, plus a title. Templates: Meeting, Stand-up, 1:1, Call / interview, Lecture. Long meetings are summarised in parts and merged. Progress shows in the island with Cancel; "Notes ready" opens them. Rewrite with another template anytime; notes are included when you share.
- **Smart Inbox (opt-in, off by default):** "✨ Catch me up" in the Inbox summarises unread notifications; **✨ Suggest replies** in the island's reply field offers three short replies. Text goes only to the AI Engine on your phone; OTP codes are masked first.
- **SDK:** `AiClient` / `AiContract` for first-party apps to use the AI Engine (certificate-pinned both ways).
- Build: Kotlin 2.4. CI: works even when `gradlew` loses its executable bit (commits from Windows); builds, tests and lints every module.

## 1.6.0 (27 Oct 2026): Meeting Notes (English)
- **Meeting Notes module** (new add-on app): tap **Record meeting** in your island tools; the island shows a red REC timer, live captions and Pause / Stop. The English transcript is made **on the phone** by [Moonshine](https://github.com/moonshine-ai/moonshine) (MIT). Choose the **Small** (more accurate) or **Tiny** (lighter) model; it downloads once, then everything works offline.
- Saved meetings: playback, tap any line to jump there, **possible action items** (simple rules, clearly labelled), rename, share, copy, delete. Audio is kept as small AAC files (about 14 MB per hour) in the module's private storage.
- **SDK:** a tool, a card tap or a card button can now open one of the module's screens (`ModuleTool(activity = …)`, `tapActivity`, `activityAction`). Android only lets the microphone start from a visible screen, so this is how a module starts recording from the island.
- **SDK:** new `com.ajit.dynamicisland.permission.HOST` signature permission, held only by the genuine island app. Modules protect the screens the island opens with it, so no other app can, for example, switch the microphone on.
- Island app 1.6.0 (needed for the above).

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
