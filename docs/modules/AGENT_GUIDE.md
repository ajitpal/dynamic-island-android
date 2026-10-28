# Building a Dynamic Island module with an AI coding agent

**For the agent:** you are helping a developer turn an idea into a module for
[Dynamic Island for Android](https://github.com/ajitpal/dynamic-island-android), then get it listed.
Work through the steps in order. Each step ends with a check you must run before moving on.
Ask the developer only for what you can't decide yourself (their idea, their name, their GitHub repo, a password for their key).

**For the developer:** open an empty folder in your agent (Claude Code, Codex, Copilot…) and say:
> Build a Dynamic Island module that <your idea>. Follow https://github.com/ajitpal/dynamic-island-android/blob/main/docs/modules/AGENT_GUIDE.md

---

## 0. What a module is (read first)

- A separate Android app (min SDK 31, target 35) with **its own permissions**. The island app itself never gets new permissions.
- It extends `IslandModuleService` from the module SDK. The island binds to it after the user turns it on.
- It can:
  - **post cards** (`island.post(IslandCard…)`): title, text, icon, accent colour, countdown/count-up, progress, up to 3 buttons, a reply field. The island draws them in the user's theme. Modules never draw their own views in the island.
  - **add shortcuts** (`override val tools`): each one is an island tool and a button on the module's card in the app's Modules tab. A shortcut can open one of your activities (`ModuleTool(..., activity = X::class.java)`); that's required for anything that must start from a visible screen (microphone, camera, permission requests).
  - **post a tile** (`island.postTile(IslandTile…)`): one line of status plus up to 2 buttons on the app's Island tab.
  - **declare a main screen** (`override val mainActivity`): "Open" on the Modules tab.
- It can **not** read notifications, the Inbox, or other modules' data. Don't try to work around this.
- Banned permissions (the review rejects them): accessibility service, notification listener, `SYSTEM_ALERT_WINDOW`, SMS, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`.
- Full API: [docs/MODULES.md](../MODULES.md). Working examples: `modules/calendar`, `modules/voice`, `modules/ai` in the Dynamic Island repo.

## 1. Start from the template

```bash
git clone --depth 1 https://github.com/ajitpal/dynamic-island-android.git /tmp/di
cp -r /tmp/di/templates/module-template/. ./
git init
```

Then rename the example:
- `module/build.gradle.kts`: `namespace` and `applicationId` → the developer's package, e.g. `io.github.<user>.island.<idea>`.
- Move `module/src/main/java/com/example/islandmodule/` to the new package path and update `package` lines.
- `module/src/main/res/values/strings.xml`: app name (start with "Island · "), description, developer name.

**Check:** `./gradlew assembleDebug` succeeds (JDK 17). If the SDK can't be resolved, use the newest tag from https://jitpack.io/#ajitpal/dynamic-island-android in `module/build.gradle.kts`.

## 2. Build the idea

- Put the logic in the service (`HelloModule.kt`, rename it). Keep cards short: title ≤ 80 characters, text ≤ 400.
- Prefer one shortcut per action the user will repeat. Give each a clear 1–3 word label.
- Post a tile that says something useful at a glance ("Next train 18:42 · platform 3").
- Anything that needs a visible screen goes in an activity opened by a shortcut or a card button, and that activity is protected:
  `android:permission="com.ajit.dynamicisland.permission.HOST"` (only the island can open it).
- Request runtime permissions in your own activity, with a sentence explaining why, only when the feature is first used.
- Don't add analytics, ads or trackers. If you use the network, say exactly what for in the privacy note.

**Check:** `./gradlew assembleDebug testDebugUnitTest lintDebug` passes. Add a unit test for any parsing or logic you wrote.

## 3. Try it on a phone (developer does this)

1. Install Dynamic Island and the debug APK (`module/build/outputs/apk/debug/module-debug.apk`).
2. In Dynamic Island → **Modules → Developers**, turn on **Allow unverified modules**, then turn your module on.
3. Tap its shortcuts and check its card and tile.

During development the SDK only talks to the official, release-signed Dynamic Island. To test with a debug build of the island, add
`<meta-data android:name="com.ajit.dynamicisland.module.TRUST_ANY_HOST" android:value="true"/>` to the module service and **remove it before release** (the review checks).

## 4. Sign it with the developer's own key

Create the key **on the developer's machine** and never commit it:
```bash
keytool -genkeypair -v -keystore release.jks -alias module -keyalg RSA -keysize 4096 -validity 10000
```
Create `keystore.properties` (git-ignored) with `storeFile=release.jks`, `storePassword=…`, `keyAlias=module`, `keyPassword=…`.

Tell the developer to back up `release.jks` and its passwords somewhere safe: every update must be signed with the same key, and a new key means a new review.

Add the same values as GitHub repository secrets so CI can sign releases (with the GitHub CLI):
```bash
base64 -w0 release.jks | gh secret set KEYSTORE_BASE64     # macOS: base64 -i release.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body module
gh secret set KEY_PASSWORD
```

**Check:** `./gradlew assembleRelease` signs locally, and
`apksigner verify --print-certs module/build/outputs/apk/release/module-release.apk` prints a SHA-256 digest whose DN is **not** "CN=Android Debug".

## 5. Publish a release

```bash
gh repo create <user>/island-<idea> --public --source . --push
git tag v1.0.0 && git push origin v1.0.0
```
The template's `Release` workflow builds, signs, prints the certificate digest in the run summary and attaches the APK to a GitHub release.

**Check:** the release page has the APK, and you have its direct download URL and the certificate SHA-256 (lowercase, no colons).

## 6. Submit it for listing

1. Fork https://github.com/ajitpal/dynamic-island-android and create a branch.
2. Add `catalog/modules/<id>.json` (copy `templates/module-template/catalog-entry.example.json`). Rules (schema: `catalog/schema.json`):
   - `id`: lowercase, same as the file name. `package`: exactly the app's applicationId.
   - `apkUrl`: the direct link to the signed APK of this version. `download`: the releases page.
   - `certSha256`: from step 5.
   - `permissions`: every non-trivial permission the APK requests, each with a plain reason ("Reads your calendar to show the next meeting").
   - `privacy`: what data is used, where it goes (nowhere, ideally).
3. Run `python3 tools/gen_catalog.py` and commit the updated `app/src/main/assets/catalog.json` and `app/src/main/res/values/modules.xml` too.
4. Open the pull request: title "Module: <name>", body with what it does and a screenshot.

**Check:** the **Module review** workflow comments a report on the PR. Fix anything marked ❌ (most often an unexplained permission or a certificate mismatch) and push again.

## 7. After review

A maintainer checks the purpose, the permission reasons and the privacy note. Once merged, the module's certificate is on the island's allow-list and it appears in **Modules → Get more** and on the website **with the next island release** (the island has no internet permission, so its list ships inside the app).

Updates: publish a new version signed with the same key and open a small PR that updates `version` and `apkUrl` in the entry.
