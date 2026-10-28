# Instructions for AI coding agents

**Building a module (an add-on app) for Dynamic Island?** Follow [docs/modules/AGENT_GUIDE.md](docs/modules/AGENT_GUIDE.md). Start from `templates/module-template/`.

**Working on this repository itself:**
- Layout: `app/` (the island), `module-sdk/` (library for modules), `modules/*` (first-party modules), `templates/module-template/` (standalone template), `catalog/` (listed modules), `tools/` (docs and catalog generators), `docs/` (website + guides).
- Build and test: `./gradlew assembleDebug testDebugUnitTest lintDebug` (JDK 17).
- The island app must stay **offline**: never add the INTERNET permission or a library that brings it (ML Kit does; it lives in `modules/nano`).
- UI follows Apple's Human Interface Guidelines through `app/.../ui/hig/Hig.kt`: inset grouped sections, 48 dp targets, text in sp, TalkBack labels, colours from `res/values/colors.xml`.
- Every change that users can see ships with its docs in the same commit:
  1. `docs/features.json` (features and modules) → `python3 tools/gen_docs.py` regenerates README and website sections.
  2. `catalog/modules/*.json` → `python3 tools/gen_catalog.py`.
  3. Screens changed → `./gradlew :app:testDebugUnitTest :modules:updater:testDebugUnitTest :modules:ai:testDebugUnitTest :modules:voice:testDebugUnitTest :modules:speech:testDebugUnitTest -Pscreenshots=record` updates `docs/screenshots/app/`.
  4. Bump `versionName`, add a `## <version>` section to CHANGELOG.md, update ROADMAP.md.
  CI fails if any of these is out of date (`tools/check_release.py`, `--check` flags, `-Pscreenshots=verify`).
- Never commit `keystore.properties` or `*.jks`. Never add attribution trailers to commit messages.
