# Contributing

Thanks for helping! The most useful contributions are:

1. **Device reports:** does it work on your phone? Open a *Device report* issue with your model, Android version and skin, plus a screenshot of the app's **Diagnostics** card.
2. **Real screenshots and screen recordings** of the island on your phone (see `docs/screenshots/README.md`).
3. **Bug fixes and features:** open an issue first for anything big.
4. **Translations:** strings live in `app/src/main/res/values/strings.xml` and the settings screen.

## Development
- `./gradlew assembleDebug lintDebug` must pass. CI runs it on every pull request.
- Keep the app **offline**: don't add the INTERNET permission or analytics SDKs.
- Every new island state needs a **demo** in `core/Demo.kt` and a chip in "Try it".
- Prefer system-derived sizes (cutout, status-bar height, density) over hard-coded dp.
- Never commit signing keys. `keystore.properties` and `*.jks` are git-ignored.

## Code of conduct
Be kind, assume good intent, keep discussions technical.
