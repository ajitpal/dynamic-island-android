# Releasing

Every push to `main` runs CI (build, tests, lint, docs check) and republishes the website. A **tag** publishes a release.

## One-time setup

### 1. Website from GitHub Actions
GitHub → **Settings → Pages → Build and deployment → Source: GitHub Actions**. The `Website` workflow then publishes `docs/` on every push.

### 2. Signing secrets (so CI can publish signed APKs)
Modules only trust an island signed with the release key, so releases must be signed with it. CI reads the key from encrypted repository secrets; it is never stored in the repo.

1. On your computer, turn the keystore into one line of text (PowerShell):
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Projects\app\Dynamic-island\dynamic-island-release.jks")) | Set-Clipboard
   ```
   (macOS / Linux: `base64 -w0 dynamic-island-release.jks`, or `base64 -i … | pbcopy` on macOS.)
2. GitHub → your repo → **Settings → Secrets and variables → Actions → New repository secret**. Add four secrets:

   | Name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the text you just copied |
   | `KEYSTORE_PASSWORD` | `storePassword` from your private `keystore.properties` |
   | `KEY_ALIAS` | `keyAlias` from `keystore.properties` |
   | `KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

   Or with the GitHub CLI: `gh secret set KEYSTORE_BASE64 < keystore.b64` and `gh secret set KEYSTORE_PASSWORD` (it prompts for the value).
3. Keep your offline backup of the `.jks` file. If the key is lost, existing installs can't be updated and every module would need a new key.

## Each release
1. Bump `versionName`/`versionCode` in `app/build.gradle.kts` (and in any module you changed, plus its entry in `docs/features.json`).
2. Add a `## <version>` section to `CHANGELOG.md` and update `ROADMAP.md`.
3. `python3 tools/gen_docs.py`, commit, push to `main`. CI must be green.
4. Tag and push: `git tag v1.9.0 && git push origin v1.9.0`.
5. The `Release` workflow builds every APK, signs it, checks the certificate, and publishes the GitHub release with notes from the changelog and `update.json` (each app's package, versionCode, file, SHA-256 and size). The Updater module reads `releases/latest/download/update.json`, so **every release must come from this workflow** (a release made by hand without `update.json` is invisible to the Updater). Bump a module's `versionCode` whenever it changes, or the Updater won't offer it. If the secrets are missing it stops instead of publishing unsigned APKs.
