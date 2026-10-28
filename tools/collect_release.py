#!/usr/bin/env python3
"""Copy release APKs into dist/ with readable names, write dist/NOTES.md from CHANGELOG.md, and write
dist/update.json for the Updater module (package, versionCode, file name, SHA-256 and size of every APK).

    python3 tools/collect_release.py 1.9.0 dist
"""
import hashlib, json, pathlib, re, shutil, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
version, out = sys.argv[1], ROOT / sys.argv[2]
out.mkdir(parents=True, exist_ok=True)

app_version = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
if app_version != version:
    sys.exit(f"Tag v{version} doesn't match the app's versionName {app_version}")
def gradle(path, key):
    return re.search(rf'{key}\s*=\s*"?([^"\n]+)"?', (ROOT / path).read_text()).group(1).strip()

apps = []
def add(src, name, package, version_code, version_name, file):
    shutil.copy(src, out / file)
    data = (out / file).read_bytes()
    apps.append({"package": package, "name": name, "versionCode": int(version_code), "versionName": version_name,
                 "file": file, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})

add(ROOT / "app/build/outputs/apk/release/app-release.apk", "Dynamic Island", gradle("app/build.gradle.kts", "applicationId"),
    gradle("app/build.gradle.kts", "versionCode"), version, f"DynamicIsland-v{version}.apk")
for mod in json.loads((ROOT / "docs/features.json").read_text(encoding="utf-8"))["modules"]:
    build = f"modules/{mod['id']}/build.gradle.kts"
    if gradle(build, "versionName") != mod["version"]:
        sys.exit(f"{mod['name']}: docs/features.json says {mod['version']} but {build} says {gradle(build, 'versionName')}")
    add(ROOT / f"modules/{mod['id']}/build/outputs/apk/release/{mod['id']}-release.apk", mod["name"], mod["package"],
        gradle(build, "versionCode"), mod["version"], f"{mod['apk']}-v{mod['version']}.apk")

log = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
m = re.search(rf"^## {re.escape(version)}\b.*?$(.*?)(?=^## |\Z)", log, re.M | re.S)
notes = (m.group(1).strip() if m else "") + "\n\n**Install:** download `DynamicIsland-v%s.apk` plus any modules you want. Guide: docs/INSTALL.md" % version
(out / "NOTES.md").write_text(notes, encoding="utf-8")
(out / "update.json").write_text(json.dumps({"schema": 1, "release": version, "tag": f"v{version}",
    "notes": m.group(1).strip() if m else "", "apps": apps}, indent=1), encoding="utf-8")
print("\n".join(sorted(p.name for p in out.iterdir())))
