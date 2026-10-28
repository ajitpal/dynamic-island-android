# Contributing

Thanks for helping! The most useful contributions are:

1. **Device reports:** does it work on your phone? Open a *Device report* issue with your model, Android version and skin, plus a screenshot of the app's **Diagnostics** card.
2. **Real screenshots and screen recordings** of the island on your phone (see `docs/screenshots/README.md`).
3. **Bug fixes and features:** open an issue first for anything big.
4. **Translations:** strings live in `app/src/main/res/values/strings.xml` and the settings screen.

## Development
- `./gradlew assembleDebug testDebugUnitTest lintDebug` must pass. CI runs it on every push and pull request.
- **Docs ship with the change.** Edit `docs/features.json` and run `python3 tools/gen_docs.py` (README and website sections between `GEN` markers are generated); add a `## <version>` entry to CHANGELOG.md and update ROADMAP.md. CI runs `tools/check_release.py` and fails if they're out of date.
- **Screens changed?** Run `./gradlew :app:testDebugUnitTest -Pscreenshots=record` to re-render `docs/screenshots/app/`, and look at the images.
- **UI follows Apple's Human Interface Guidelines** through `app/.../ui/hig/Hig.kt`: inset grouped sections, 48 dp touch targets, text in sp, TalkBack labels, colours from `res/values/colors.xml` (light and dark). See [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md).
- **Building a module?** See [docs/MODULES.md](docs/MODULES.md), or give your AI coding agent [docs/modules/AGENT_GUIDE.md](docs/modules/AGENT_GUIDE.md). Agents working on this repo read [AGENTS.md](AGENTS.md).
- Keep the app **offline**: don't add the INTERNET permission or analytics SDKs.
- Every new island state needs a **demo** in `core/Demo.kt` and a chip in "Try it".
- Prefer system-derived sizes (cutout, status-bar height, density) over hard-coded dp.
- Never commit signing keys. `keystore.properties` and `*.jks` are git-ignored.

## Code of conduct
Be kind, assume good intent, keep discussions technical.
