# Instructions for AI coding agents

This repository is a **Dynamic Island module**: an Android app that adds cards, tools and a tile to the
open-source Dynamic Island app (https://github.com/ajitpal/dynamic-island-android).

**Before writing code, read the full guide:**
https://github.com/ajitpal/dynamic-island-android/blob/main/docs/modules/AGENT_GUIDE.md

It tells you, step by step, how to turn the user's idea into this module, test it, sign it with the
user's own key, publish a release and submit it for listing. Follow it in order and verify each step.

Quick facts:
- Build: `./gradlew assembleDebug testDebugUnitTest lintDebug` (JDK 17).
- The module is `module/`; start from `HelloModule.kt` and `AndroidManifest.xml`.
- Never commit `keystore.properties` or any `.jks` file. Signing secrets live in GitHub secrets.
- The submission is one JSON file (see `catalog-entry.example.json`) in a pull request to the Dynamic Island repo.
