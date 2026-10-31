# Building a Dynamic Island module

A **module** is a separate Android app that gives the island new abilities: a card that counts down to your next meeting, a tool that triggers a smart-home scene, a live score, and so on. Users install only the modules they want, grant only the permissions each one needs, and approve every module in **Dynamic Island → Modules**.

```
┌────────────────────┐   binds (AIDL)    ┌─────────────────────────┐
│  Dynamic Island    │ ────────────────► │  Your module app        │
│  (host)            │ ◄──────────────── │  IslandModuleService    │
│  draws the island  │   cards / tools   │  your own permissions   │
└────────────────────┘                   └─────────────────────────┘
```

**Fastest start:** copy [`templates/module-template`](../templates/module-template) (a complete module with a tool, a card and a tile). **With an AI coding agent:** point it at the [agent guide](modules/AGENT_GUIDE.md) and describe your idea; it covers building, signing, releasing and submitting for listing.

Modules **never draw UI** in the island. They send **cards** (title, text, icon, accent, countdown, progress, up to 3 buttons, an optional reply field), and the island renders them in the user's theme. A module can't spoof the system or break the island, and it can't read notifications or the Inbox.

## 1. Add the SDK

The SDK is published through JitPack, so your module can live in its own repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
// module/build.gradle.kts (use the newest tag from https://jitpack.io/#ajitpal/dynamic-island-android)
implementation("com.github.ajitpal.dynamic-island-android:module-sdk:v2.0.0")
```
Inside this repository, first-party modules use `implementation(project(":module-sdk"))`.

The SDK adds two things to your manifest automatically: `<queries>` for the host package, and `uses-permission com.ajit.dynamicisland.permission.MODULE_API`.

## 2. Declare your module service

```xml
<service
    android:name=".WeatherModule"
    android:exported="true"
    android:label="Weather">
    <intent-filter>
        <action android:name="com.ajit.dynamicisland.action.MODULE" />
    </intent-filter>
    <meta-data android:name="com.ajit.dynamicisland.module.DESCRIPTION" android:value="Rain alerts and AQI in the island" />
    <meta-data android:name="com.ajit.dynamicisland.module.SETTINGS_ACTIVITY" android:value=".SettingsActivity" />
    <meta-data android:name="com.ajit.dynamicisland.module.DEVELOPER" android:value="Your name" />
</service>
```

## 3. Implement it

```kotlin
class WeatherModule : IslandModuleService() {

    override val tools = listOf(ModuleTool("aqi", "Air quality", R.drawable.ic_air))

    override fun onIslandConnected() {
        // The user approved you and the island is running: start posting.
    }

    override fun onToolTapped(toolId: String) {
        island.post(
            IslandCard.Builder(this, "aqi")
                .title("AQI 182 · Unhealthy")
                .text("Dwarka, Delhi · PM2.5 dominant")
                .compact("AQI 182")
                .icon(R.drawable.ic_air)
                .accent(0xFFFF9F0A.toInt())
                .action("map", "Details", url = "https://example.com/aqi")
                .expiresIn(10_000)
                .peek()
                .build()
        )
    }

    override fun onAction(cardId: String, actionId: String, input: String?) {
        // Buttons without a URL, reply fields and taps (with tapCallback()) arrive here.
    }
}
```

### Opening your own screens (and starting the microphone)
Some things Android only allows from a **visible screen**: starting a microphone or camera foreground service, asking for a runtime permission. For those, let the island open one of your activities instead of calling you in the background:

```kotlin
override val tools = listOf(ModuleTool("record", "Record meeting", R.drawable.ic_mic, activity = RecordActivity::class.java))
```

```xml
<!-- Only the genuine island app holds this permission, so no other app can start this screen. -->
<activity android:name=".RecordActivity" android:exported="true"
    android:permission="com.ajit.dynamicisland.permission.HOST"
    android:theme="@style/Theme.Translucent" android:excludeFromRecents="true" android:taskAffinity="" />
```

A translucent activity can do its job (start the service, ask for the permission) and `finish()` straight away. `tapActivity(...)` and `activityAction(...)` work the same way for cards. Note: `HOST` is a `signature` permission, so a debug build of the island can't open these screens of a release-signed module; build both the same way while testing.

### Shortcuts, main screen, tile and the app-drawer icon (SDK API 2)

```kotlin
class NotesModule : IslandModuleService() {
    // Each shortcut is an island tool AND a button on your card in the app's Modules tab.
    override val tools = listOf(
        ModuleTool("record", "Record", R.drawable.ic_mic, RecordActivity::class.java),
        ModuleTool("list", "Recordings", R.drawable.ic_list, MainActivity::class.java),
    )
    // What "Open" on the Modules tab opens (a shortcut to the same screen replaces the Open button).
    override val mainActivity = MainActivity::class.java

    override fun onIslandConnected() {
        // One tile per module on the app's Island tab: a line of status and up to 2 buttons.
        island.postTile(IslandTile.Builder(this).title("Weekly sync").text("Notes ready · 3 action items")
            .icon(R.drawable.ic_notes).tapActivity(TranscriptActivity::class.java, "last")
            .activityAction("record", "Record", RecordActivity::class.java)
            .activityAction("list", "Recordings", MainActivity::class.java).build())
    }
}
```

Put your app-drawer entry on an **activity-alias named `.Launcher`** (not on the activity itself). The island then offers "Hide icon from app drawer" in your module's ⋯ menu, and the SDK handles it; your module stays reachable from the island.

```xml
<activity android:name=".MainActivity" android:exported="true" />
<activity-alias android:name=".Launcher" android:targetActivity=".MainActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity-alias>
```

**Turned on or off:** the island sends `IslandContract.ACTION_MODULE_STATE` (with `EXTRA_ENABLED`) to your package when the person switches your module on or off in the Modules tab. If your module does background work (like the Updater's daily check), stop it while off. Protect the receiver so only the island can send it:
```xml
<receiver android:name=".StateReceiver" android:exported="true" android:permission="com.ajit.dynamicisland.permission.HOST">
    <intent-filter><action android:name="com.ajit.dynamicisland.action.MODULE_STATE" /></intent-filter>
</receiver>
```

Long-pressing your tool in the island shows a card with your shortcuts and **Open · Manage · Remove**.

### Card cheat-sheet
| Builder call | Effect |
|---|---|
| `title`, `text`, `compact` | Expanded title / body; short text beside the camera |
| `icon(res, tint = true)` | Monochrome icon tinted with the accent (or `tint = false` for full colour) |
| `accent(argb)` | Colour of the icon, timer and progress |
| `countdownTo(ms)` / `countUpFrom(ms)` | Live timer (wall-clock ms) |
| `progress(0f..1f)` + `style(STYLE_PROGRESS)` | Progress ring and bar |
| `style(STYLE_ALERT)` | Short system-style glimpse (higher priority, use with `expiresIn`) |
| `action(id, title, style, url?)` | Up to 3 buttons; with a `url` the island opens it, otherwise you get `onAction` |
| `inputAction(id, title, hint)` | A reply-style text field; the text arrives in `onAction` |
| `peek(ms)` | Pop the island open (re-pops only when `version` changes) |
| `expiresIn(ms)` | Remove automatically |
| `tapUrl(url)` / `tapCallback()` | What tapping the card does |
| `tapActivity(Class, arg)` | Tapping opens one of your exported activities (`arg` arrives as `IslandContract.EXTRA_ARG`) |
| `activityAction(id, title, Class, arg, style)` | A button that opens one of your activities |
| `version(n)` | Bump to mark content as new |

Limits enforced by the island: 3 cards per module, 80-character titles, 400-character text, and only `https`, `http`, `content`, `tel`, `geo` and `mailto` links. Module cards never outrank calls.

## 4. Security model

| Layer | What it does |
|---|---|
| **Host → module** | The SDK accepts calls only from the real Dynamic Island app (package name **and** signing certificate pinned in `IslandContract.TRUSTED_HOST_CERTS`). For local development against a debug build, add `<meta-data android:name="com.ajit.dynamicisland.module.TRUST_ANY_HOST" android:value="true"/>` and **remove it before release**. |
| **Module → host** | Each module gets its own host binder, and every call is checked against that module's UID, so one module can't post as another. |
| **Approved developers** | The island defines `com.ajit.dynamicisland.permission.MODULE_API` with `protectionLevel="signature\|knownSigner"`. Android grants it automatically to modules signed by a certificate on the island's allow-list (`app/src/main/res/values/modules.xml`). The island also checks the same list in code. |
| **User approval** | Modules from other developers must be approved once in **Modules**. The screen shows the developer, verification status, permissions and certificate fingerprint. First-party modules (signed with the island's own key) are switched on at install, with a glimpse in the island, unless the user turned off "Add new modules automatically". A module's tools are added to the island tools the first time they appear, and each can be switched off. |
| **Unverified modules** | Modules from developers not on the allow-list only run if the user turns on **Developer mode**, with a warning. Use this while you build. |
| **Data** | Modules never receive notifications or Inbox data. A future, separate opt-in will allow it per module. |

### Getting listed
1. Sign your release APK with your own key (never the debug key) and publish it as a GitHub release (the template's `Release` workflow does both).
2. Open a pull request that adds **`catalog/modules/<id>.json`** ([schema](../catalog/schema.json), [example](../templates/module-template/catalog-entry.example.json)) and the files `python3 tools/gen_catalog.py` regenerates. Don't edit `modules.xml` by hand: the allow-list is generated from the catalog.
3. The **Module review** workflow downloads your APK and checks the signature against `certSha256`, the package name, the module service, that every permission is explained and none is banned, and that no development flag is left on. It posts a report on the PR; it never runs your code.
4. A maintainer reviews the purpose, the permission reasons and the privacy note. Once merged, the website rebuilds and publishes the catalogue signed with the release key. People with the **Updater** module get it with the next daily check: your module appears in **Modules → Get more**, and the island verifies your certificate from that signed list, as **Verified · approved developer**, without waiting for an island release. Everyone else gets it with the next island release, which also adds your certificate to the manifest allow-list.

The [agent guide](modules/AGENT_GUIDE.md) walks through all of this step by step.

## 5. Using the on-device AI Engine
First-party apps (signed with the same key as the island) can use the AI Engine module through the SDK:

```kotlin
val ai = AiClient(context)
if (AiClient.isTrusted(context)) {
    ai.generate(system = "You write short notes.", prompt = text, maxTokens = 400,
        onPartial = { preview(it) }, onDone = { show(it) }, onError = { code, msg -> warn(msg) })
}
// On a worker thread: val r = ai.generateBlocking(system, prompt, 400)
ai.close()
```
**Requests a person is watching** should pass `wait = false` and show progress (AI API 2):
```kotlin
ai.generate(system, prompt, wait = false,
    onProgress = { p -> when (p.getString(AiContract.P_STAGE)) {   // queued · loading · reading · writing
        AiContract.STAGE_LOADING -> show("Loading ${p.getString(AiContract.P_MODEL)}")
        AiContract.STAGE_READING -> show("Reading…")
    } },
    onPartial = { preview(it) }, onDone = { show(it) },
    onError = { code, msg -> if (code == AiContract.E_BUSY) show(msg) /* "AI Engine is busy with Meeting Notes…" */ else warn(msg) })
```
The engine runs one request at a time. With `wait = false` it refuses at once with `E_BUSY` (naming the app it's busy with) instead of queueing behind, say, a long meeting summary. If the engine doesn't bind within 15 seconds you get `E_UNAVAILABLE` with a message, never silence.
The client checks the engine's signing certificate before sending anything, and the engine checks every caller's certificate. `status()` tells you whether a model is ready and roughly how much text fits in one request (`S_MAX_INPUT_CHARS`); split longer inputs, as Meeting Notes does. Third-party access will need an explicit per-app approval, planned for Phase 5.

**Speech (Hindi / Hinglish):** first-party apps can transcribe an audio file with the Speech Pack module the same way:
```kotlin
val speech = SpeechClient(context)
if (speech.isInstalled()) speech.transcribe(file, language = "hi",
    onLine = { startMs, endMs, text -> add(startMs, text) },          // streamed as each stretch of speech is done
    onProgress = { stage, fraction -> show(stage, fraction) },        // STAGE_LOADING, then STAGE_TRANSCRIBING 0..1
    onDone = { finish() },
    onError = { code, msg -> if (code == SpeechContract.E_BUSY) show(msg) else warn(msg) })
```
Any format Android can decode works (it's converted to 16 kHz mono inside the Speech Pack). One job runs at a time; `cancel()` stops it. Both sides check signing certificates, as with the AI Engine.

**Gemini Nano:** `modules/nano` wraps ML Kit's GenAI Prompt API. Android only accepts Nano requests from the **top foreground app** (even a foreground service gets `BACKGROUND_USE_BLOCKED`), inputs are limited to about 4000 tokens, and AICore applies a per-app quota. So a module should call Nano in its own process while its screen is open and fall back to the AI Engine otherwise; see `Brain.kt` in Meeting Notes. ML Kit adds the INTERNET permission (for Google's usage statistics), which is why the island app never links it.

## 6. Reference modules in this repo
- **`templates/module-template`**: the smallest complete module (tool, card, tile, launcher alias, signed release workflow).
- **`modules/automation`**, Automation Bridge: bridges Tasker, MacroDroid and adb into the island using a secret key. Shows queued cards, tools and reply buttons.
- **`modules/calendar`**, Calendar: uses the `READ_CALENDAR` permission (in the module only), a countdown, a progress phase, a Join link and `peek` at T-5 minutes.
- **`modules/ai`**, AI Engine: LiteRT-LM, pinned model downloads with checksum, a bound AIDL service with caller checks, one request at a time with progress stages and a busy rule, idle unloading.
- **`modules/voice`**, Meeting Notes: a tool that opens a translucent activity to start a `microphone` foreground service, a count-up REC card with live text and Pause/Stop buttons, on-device speech-to-text with Moonshine (English) or Android's on-device recognizer (Hindi, fed through `EXTRA_AUDIO_SOURCE`), file import, and a "saved" card that opens the transcript.
- **`modules/screen`**, Screen Recorder: a tool that opens a translucent activity for Android's screen-capture consent, a `mediaProjection` (+ `microphone`) foreground service started before the projection (Android 14 rule), a virtual display into a hardware H.264 encoder plus AAC from the microphone and/or `AudioPlaybackCapture`, pause by dropping samples and closing the timestamp gap, MediaStore output, and Play / Share buttons that open your own HOST-protected activity.
- **`modules/weather`**, Weather & AQI: a network module with a periodic `JobScheduler` refresh that stops when the island turns it off (`ACTION_MODULE_STATE`), a tool that posts a short-lived card and an optional always-on one (`version` bumps it), `STYLE_ALERT` cards for rain and air, pure-Kotlin CPCB AQI and alert rules with tests, and location used only while its screen is open.
- **`modules/speech`**, Speech Pack: ONNX Runtime with a CTC model, decoding restricted to the scripts you want, Silero voice detection to split audio, a file-in / lines-out AIDL service, and resumable checksum-verified downloads.

## Contract stability
`IslandContract.API_VERSION = 2` (API 2 added tiles, `mainActivity` and the launcher toggle). Since 2.0 the island can show up to three activities at once: yours may be in the island, in the small circle beside it, or behind the "+1" badge, so keep `compact` text short and use a clear icon. Version history: [SDK overview](SDK.md#api-versions). Everything travels as `Bundle`s: new keys are optional, and unknown keys are ignored. Breaking changes will bump the API version, and the island will keep supporting older versions.
