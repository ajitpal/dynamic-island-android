# Changelog

## 2.0.0 (31 Oct 2026): Three at once, and an open module ecosystem
- **Three activities at once.** When several things run together (a recording, a delivery and a live score, say), the island shows one and the small circle beside it shows the next, with a **+1** badge for each one behind it. This keeps Apple's two-shape look while showing that more is running.
  - **Tap the circle** to switch: what's in the circle moves into the island, and the next one moves into the circle. Taps go through every activity and wrap around.
  - **Touch and hold the circle** to see them all in a list ("3 Activities"), with the one in the island marked. Tap a row to switch to it and open its card.
  - **A new call always takes the island back**, even if you switched to something else; you can still switch away from it.
  - TalkBack reads the circle as "Switch to Delivery. 1 more activity. Touch and hold to see all".
  - Try it: **Island → Try it → Three at once**.
- **Modules from other developers appear without an island update.** The module list (**Modules → Get more**) is now also published on the website, signed with the Dynamic Island release key. The **Updater 1.1.0** fetches it with its daily check, and the island checks the signature against its own key before using it, so a newly approved module shows up, and is trusted as an approved developer, within a day. The island still has no internet permission, and without the Updater the list that comes with the app is used. **Get more** says when the list was last updated.
- **Developer site:** [ajitpal.github.io/dynamic-island-android/sdk](https://ajitpal.github.io/dynamic-island-android/sdk/) with an SDK overview and quick start, API version history, the module guide, the AI-agent guide, an **API reference generated from the SDK source** on every push, and a **module catalogue** page with search, permissions, privacy notes and certificate fingerprints. New issue forms to ask about a module or report a listed one.
- **Obtainium:** an **Add to Obtainium** link on the website installs the island and keeps it updated straight from GitHub Releases.
- **SDK 2.3:** `SignedCatalog` (the signed catalogue format the island and the Updater share). No changes for existing modules; keep `compact` text short, since a module's card may now sit in the circle or behind the badge.
- **Not doing: IzzyOnDroid.** Its inclusion policy rejects apps written with AI tools and apps that integrate LLMs, so the planned listing is dropped.
- Modules: **Updater 1.1.0** (catalogue download and a read-only provider only the island can read). The other modules are unchanged.

## 1.11.0 (28 Oct 2026): Meeting Notes in Hindi and Hinglish, and importing audio files
- **Meeting Notes 1.4.0 understands Hindi and Hinglish.** Pick **English**, **हिन्दी (Hindi)** or **Hindi + English (Hinglish)** under **Language**. Hindi is written in Devanagari and English words stay in Latin letters, the way people actually mix them ("कल तक presentation ready करनी है"). Notes and action items are written in English, and action items are found in Hindi too ("भेज दूंगा", "करना है").
  - **Live, when your phone can:** if Android's own on-device speech recognition has Hindi (Android 13 or later, often a one-time language download that Meeting Notes offers to start), you get live captions in the island, like English.
  - **Otherwise with the new Speech Pack**, after the recording: the island shows "Transcribing…" with progress and a Stop button, and the transcript appears line by line. You can also choose **Use Speech Pack for Hindi** if it's more accurate on your phone.
  - If neither is set up, Meeting Notes says so and shows what to do instead of recording audio it can't transcribe.
- **Import Audio File…** in Meeting Notes: transcribe a recording you already have (voice note, call recording, meeting audio: MP3, M4A, WAV, OGG, FLAC, AAC) in the chosen language. Imported meetings get playback, AI notes and Ask, like recorded ones.
- **New Speech Pack module 1.0.0:** Hindi and Hinglish speech-to-text on your phone with Meta's **Omnilingual ASR** (two models: **Standard**, 365 MB, and **Best**, 1.0 GB). It downloads one model once from Hugging Face, checked against a fixed checksum, and then works offline. Its screen has **Try It** (pick an audio file and watch the transcript appear) and shows which apps used it. Only apps signed with the Dynamic Island key can use it.
  - **How it was chosen:** on 40 Hindi test recordings (Google's FLEURS set), measured on a computer with the same model files: Standard got about 18% of words wrong, Best about 10%, while Whisper Small (the first plan) got about 78% wrong and often repeated itself. So Whisper was dropped. These numbers are from clean read speech; real meetings will be harder.
  - **Safer output:** the Speech Pack only writes Devanagari, Latin letters, digits and punctuation (the model otherwise sometimes switched to Urdu or Gujarati script), splits audio at pauses using Silero voice detection, and retries a quiet or mixed-volume stretch that came out empty.
- **Meeting Notes screen redesigned** like the rest of the app: status, Record, Language, Import, English speech model, AI notes and saved meetings (which now show the language).
- **SDK 2.2:** `SpeechClient` / `SpeechContract` for first-party apps to transcribe a file with the Speech Pack (lines stream back with timestamps; one job at a time; busy and cancel like the AI Engine).
- The island app is unchanged apart from listing the Speech Pack under **Modules → Get more**; it still has no internet permission.

## 1.10.1 (28 Oct 2026): AI that answers, or tells you why
- **AI Engine 1.3.0 fixes "Loading the model…" forever.** The engine runs one request at a time, and in earlier versions it waited for the model with no time limit. If one load or answer never finished on a phone, every later request, and even switching models, queued behind it for good. Now:
  - **Every wait has a limit:** loading (longer the first time, while the model is prepared), the first word, gaps between words and the whole answer. You get a clear message instead of an endless wait.
  - **It recovers by itself:** if the AI runtime doesn't respond, AI Engine tells every app that's waiting, then restarts so the next try starts clean.
  - **Prepared models are handled safely:** the first load of a model writes a "prepared" copy about the size of the model. AI Engine now checks there's room first, keeps it in app storage (Android no longer deletes it to free space), and removes a copy left half-written by a load that timed out or closed the app.
  - **Diagnostics** on the AI Engine screen: **Test AI Engine** (shows each stage with seconds), **Copy Report** (phone, chip, memory, storage, models, recent runs and the engine's own log lines, which stay on your phone unless you share them), **Clear Prepared Models** and **Restart AI Engine**. Recent runs show how long loading and answering took.
- **AI Engine screen redesigned** like the rest of the app: model list with checkmarks, download progress, Try It with live stages, Diagnostics, Gemini Nano and Used By.
- **Catch Me Up reads small models' answers better:** headings written inside bullets or in bold, and doubled bullets (seen with Qwen3 0.6B), no longer end up in the summary.
- Verified on a computer with the same LiteRT-LM 0.18.1 runtime and the exact model files: Qwen3 0.6B and 1.7B load and answer, and every time limit, cancel and recovery path behaves as intended.

## 1.10.0 (28 Oct 2026): Software Update
- **New Updater module** keeps Dynamic Island and every module up to date from GitHub Releases. It's the recommended first module (listed first in **Modules → Get more**) and switches itself on when installed, like the other first-party modules.
  - **Software Update** screen, like on an iPhone: what's installed, what's new, **Update All**, and a download bar for each app. Also in **Settings → Software Update**, as a tile on the Island tab and as an island tool.
  - Checks **once a day** (you can turn **Check Automatically** off). When a new version is out you get one notification and one island card per release.
  - **Nothing installs on its own:** you tap Update, then Android asks you to confirm each app. Modules update first, then the island, then Updater itself.
  - **Safe:** an update is installed only if its SHA-256 matches the release, it's signed with the same key as the app on your phone and with the Dynamic Island release key, and its package and version are the expected ones. If the installed copy was signed with another key (for example a test build), it tells you instead of failing silently.
  - **Your choice:** turning Updater off in **Modules** stops every check (the island now tells modules when they're turned on or off). Uninstall it any time.
  - **The Dynamic Island app still has no internet permission;** only the Updater talks to GitHub, and only to read which versions exist and to download what you choose.
- **Releases** now include `update.json` (versions, file names, checksums) generated by CI.
- **SDK 2.1:** `IslandContract.ACTION_MODULE_STATE` tells a module when it's turned on or off; AI API 2 (progress, busy) is documented.

## 1.9.2 (28 Oct 2026): Catch Me Up that shows its work, one AI request at a time, and an on/off switch
- **Fixed: Catch Me Up could sit on "Summarising…" forever.** If the AI Engine didn't answer the connection, or its process stopped before replying, nothing ever told the app. Now a connection that doesn't answer within 15 seconds ends with a clear message ("AI Engine didn't respond. Open the AI Engine app once, then try again").
- **Catch Me Up shows what it's doing**, step by step with checkmarks and a timer: Gathered 23 notifications → Load Qwen3 1.7B → Read them → Write summary. The summary streams in as it's written, in two sections (Needs Attention, Everything Else), with **Mark All as Read**. It's a proper sheet now: **Cancel** while it works, **Done** when it's finished. In the island the card shows the current step and seconds, with **Cancel**.
- **One AI request at a time.** Tapping Catch Me Up while it's running shows the running one instead of starting another. If Suggest replies or another app (for example Meeting Notes writing notes) is using the AI Engine, you get "AI Engine is busy with Meeting Notes. Try again when it's finished." with **Try Again**, instead of a silent wait.
- **Faster:** notifications are grouped by app (at most four per app, plus "and N more", repeats removed), so the model has about half as much to read. If nothing changed since the last catch-up, the last summary appears instantly.
- **On/off switch:** turn Dynamic Island off and on at the top of the Island tab and Settings, or with the new **Dynamic Island** Quick Settings tile. Off hides the island immediately; your Inbox keeps collecting notifications.
- **AI Engine 1.2.1:** reports progress (waiting, loading the model, reading, writing), names the app it's busy with, and supports "don't queue" requests. Older apps keep working. Install it together with the island update.

## 1.9.1 (28 Oct 2026): fix for the Modules tab freezing
- **Fixed:** opening the **Modules** tab with a module installed made the app stop responding ("Dynamic Island isn't responding"). Showing the tab rescanned the installed modules, and every rescan looked like a change because each module's icon was loaded again as a new object, so the tab refreshed and rescanned over and over. Icons are now kept per installed version, the tab never rescans while drawing, and back-to-back refreshes are merged. A unit test reproduces the loop.
- Modules are unchanged; keep Meeting Notes 1.3.0, AI Engine 1.2.0, Calendar 1.1.0 and Automation Bridge 1.1.0.

## 1.9.0 (28 Oct 2026): A new home for the island, module shortcuts, and modules from other developers
- **Redesigned app, following Apple's Human Interface Guidelines:** three tabs, **Island** (status, your Inbox, module tiles, your tools, the pinned note, "Try it"), **Modules** and **Settings**, with inset grouped lists, larger touch targets, text that follows your font size, and full light and dark themes. A 3-step **welcome** walks new users through permissions and keeping the island running.
- **Module shortcuts:** every module card on the Modules tab shows that module's direct links. For example, Meeting Notes has **Record**, **Recordings** and **Ask last meeting**, and AI Engine has **AI Engine** and **Try AI**. A ⋯ menu offers Settings, Permissions, Hide/Show app-drawer icon, Add/Remove tools, Turn off and Uninstall.
- **Module tiles on the Island tab:** modules can show one line of status with up to two buttons ("Weekly sync · notes ready · Record · Recordings", "Qwen3 1.7B ready").
- **Long-press a module tool in the island** for a card with that module's shortcuts and **Open · Manage · Remove**.
- **Hide a module's icon from the app drawer** (it stays reachable from the island). Icons stay in the drawer by default.
- **Get more modules:** a catalog of listed modules in the Modules tab and on the website (bundled with the app; the island still has no internet permission).
- **Accessibility:** TalkBack labels throughout, heading navigation, pop-ups stay up longer when Android's "Time to take action" asks for it, and "Remove animations" turns off the island's bounce.
- **Modules from other developers, built with AI coding agents:** the module SDK is on JitPack (`com.github.ajitpal.dynamic-island-android:module-sdk`), a standalone [module template](templates/module-template) with signed-release CI, an [agent guide](docs/modules/AGENT_GUIDE.md) plus `AGENTS.md` / `CLAUDE.md` for Claude, Codex and others, and listing through a catalog pull request that a review workflow checks automatically (signature, permissions, package, without running submitted code).
- **SDK API 2:** `IslandTile`, `mainActivity`, launcher-icon toggle (`.Launcher` activity-alias), module-level commands. API 1 modules keep working.
- **Modules updated:** Meeting Notes 1.3.0, AI Engine 1.2.0, Calendar 1.1.0, Automation Bridge 1.1.0 (shortcuts, tiles, drawer-icon toggle).
- **Docs that stay current:** README and website sections are generated from `docs/features.json`, app screenshots are rendered from code in CI, and the website now deploys from GitHub Actions with a changelog page. Tagged releases build signed APKs in CI ([docs/RELEASING.md](docs/RELEASING.md)).

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
