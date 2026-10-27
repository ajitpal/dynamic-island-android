# Building a Dynamic Island module

A **module** is a separate Android app that gives the island new abilities: a card that counts down to your next meeting, a tool that triggers a smart-home scene, a live score, and so on. Users install only the modules they want, grant only the permissions each one needs, and approve every module in **Dynamic Island → Modules**.

```
┌────────────────────┐   binds (AIDL)    ┌─────────────────────────┐
│  Dynamic Island    │ ────────────────► │  Your module app        │
│  (host)            │ ◄──────────────── │  IslandModuleService    │
│  draws the island  │   cards / tools   │  your own permissions   │
└────────────────────┘                   └─────────────────────────┘
```

Modules **never draw UI** in the island. They send **cards** (title, text, icon, accent, countdown, progress, up to 3 buttons, an optional reply field), and the island renders them in the user's theme. A module can't spoof the system or break the island, and it can't read notifications or the Inbox.

## 1. Add the SDK

The SDK lives in this repository as `:module-sdk`. Until it's published to Maven, either:
- build your module inside this repo under `modules/your-module` (copy `modules/automation` as a template), or
- copy the `module-sdk` folder into your project and `implementation(project(":module-sdk"))`.

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
| `version(n)` | Bump to mark content as new |

Limits enforced by the island: 3 cards per module, 80-character titles, 400-character text, and only `https`, `http`, `content`, `tel`, `geo` and `mailto` links. Module cards never outrank calls.

## 4. Security model

| Layer | What it does |
|---|---|
| **Host → module** | The SDK accepts calls only from the real Dynamic Island app (package name **and** signing certificate pinned in `IslandContract.TRUSTED_HOST_CERTS`). For local development against a debug build, add `<meta-data android:name="com.ajit.dynamicisland.module.TRUST_ANY_HOST" android:value="true"/>` and **remove it before release**. |
| **Module → host** | Each module gets its own host binder, and every call is checked against that module's UID, so one module can't post as another. |
| **Approved developers** | The island defines `com.ajit.dynamicisland.permission.MODULE_API` with `protectionLevel="signature\|knownSigner"`. Android grants it automatically to modules signed by a certificate on the island's allow-list (`app/src/main/res/values/modules.xml`). The island also checks the same list in code. |
| **User approval** | Every module must be approved once in **Modules**. The screen shows the developer, verification status, permissions and certificate fingerprint. |
| **Unverified modules** | Modules from developers not on the allow-list only run if the user turns on **Developer mode**, with a warning. Use this while you build. |
| **Data** | Modules never receive notifications or Inbox data. A future, separate opt-in will allow it per module. |

### Getting approved
1. Sign your release APK with your own key (never the debug key).
2. Get your certificate digest by running `apksigner verify` with the `--print-certs` option on your APK and copying the "Signer #1 certificate SHA-256 digest".
3. Open a pull request that adds the digest to `app/src/main/res/values/modules.xml`, with a link to your module's source code.
4. Once merged and released, your module shows as **Verified · approved developer**.

## 5. Reference modules in this repo
- **`modules/automation`**, Automation Bridge: bridges Tasker, MacroDroid and adb into the island using a secret key. Shows queued cards, tools and reply buttons.
- **`modules/calendar`**, Calendar: uses the `READ_CALENDAR` permission (in the module only), a countdown, a progress phase, a Join link and `peek` at T-5 minutes.

## Contract stability
`IslandContract.API_VERSION = 1`. Everything travels as `Bundle`s: new keys are optional, and unknown keys are ignored. Breaking changes will bump the API version, and the island will keep supporting older versions.
