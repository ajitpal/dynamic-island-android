# Dynamic Island module SDK

Build an add-on for Dynamic Island: a **module** is a small, separate Android app that adds cards, island tools and a tile to the island. People install only the modules they want and grant only the permissions each one needs. The island app itself stays offline.

| | |
|---|---|
| **Guide** | [Building a module](modules.html): manifest, cards, tools, tiles, opening your own screens, security model, getting listed |
| **With an AI coding agent** | [Agent guide](agents.html): point Claude, Codex or another agent at it, describe your idea, and it builds, signs and submits the module |
| **API reference** | [Every class and constant](api/index.html), generated from the SDK source on each push |
| **Start from code** | [`templates/module-template`](https://github.com/ajitpal/dynamic-island-android/tree/main/templates/module-template): a complete module with a tool, a card, a tile and a signed-release workflow |
| **Listed modules** | [Module catalogue](catalogue.html) |

## Quick start

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
// build.gradle.kts of your module app: use the newest tag from https://jitpack.io/#ajitpal/dynamic-island-android
implementation("com.github.ajitpal.dynamic-island-android:module-sdk:v2.0.0")
```

1. **Add the SDK** from JitPack, as above.
2. **Declare a service** with the `com.ajit.dynamicisland.action.MODULE` intent filter and a description.
3. **Extend `IslandModuleService`**: list your `tools`, post `IslandCard`s and an `IslandTile`, handle `onAction`.
4. **Test** against a debug island with the `TRUST_ANY_HOST` meta-data and **Modules → Allow unverified modules** on. Remove that meta-data before you release.
5. **Release** a signed APK on GitHub and open a pull request that adds `catalog/modules/<id>.json`. An automated review checks it; once a maintainer merges it, your module appears in **Modules → Get more** within a day for people with the Updater module, and in the next island release for everyone else.

## How a module talks to the island

```
 Dynamic Island (host, offline)                 Your module (own app, own permissions)
 ┌─────────────────────────────┐   binds    ┌──────────────────────────────────┐
 │ draws the island            │ ─────────► │ IslandModuleService              │
 │ checks your certificate     │ ◄───────── │ cards · tools · tile · actions   │
 │ asks the person to approve  │   Bundles  │ pins the island's certificate    │
 └─────────────────────────────┘            └──────────────────────────────────┘
```

Modules describe content; the island renders it in the person's theme. A module can't draw over the island, spoof the system, or read notifications or the Inbox.

## API versions

| SDK | Island | Added |
|---|---|---|
| API 1 | 1.5 | Cards, tools, actions, reply fields, peek, settings screen |
| API 1 | 1.6 | Tools, card taps and buttons can open your own activity (needed to start the microphone) |
| API 2 | 1.9 | `IslandTile`, `mainActivity`, launcher-icon toggle (`.Launcher` alias), module-level commands |
| 2.1 | 1.10 | `IslandContract.ACTION_MODULE_STATE`: told when you're switched on or off |
| 2.2 | 1.11 | `SpeechClient` / `SpeechContract` (first-party apps) |
| 2.3 | 2.0 | `SignedCatalog` (used by the island and the Updater for the live module catalogue). Up to three activities show at once; your card may sit in the circle or behind the "+1" badge, so keep `compact` short and the icon clear |

Everything travels as `Bundle`s: new keys are optional and unknown keys are ignored, so older modules keep working. A breaking change would bump `IslandContract.API_VERSION`, and the island keeps supporting older versions.

## Rules for listed modules

- No ads, analytics or trackers. Ask only for permissions the module uses, and explain each one in the catalogue entry.
- Never request the banned permissions (accessibility, notification listener, draw over apps, SMS, all-packages, install packages, all-files access). The review workflow rejects them.
- Sign with your own release key and keep it. The catalogue pins your certificate; a new key means a new review.
- Say plainly what leaves the phone, in the `privacy` field.
- Something wrong with a listed module? [Report it](https://github.com/ajitpal/dynamic-island-android/issues/new?template=module_report.yml).
