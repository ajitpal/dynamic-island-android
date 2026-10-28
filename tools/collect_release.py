#!/usr/bin/env python3
"""Copy release APKs into dist/ with readable names and write dist/NOTES.md from CHANGELOG.md.

    python3 tools/collect_release.py 1.9.0 dist
"""
import json, pathlib, re, shutil, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
version, out = sys.argv[1], ROOT / sys.argv[2]
out.mkdir(parents=True, exist_ok=True)

app_version = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
if app_version != version:
    sys.exit(f"Tag v{version} doesn't match the app's versionName {app_version}")
shutil.copy(ROOT / "app/build/outputs/apk/release/app-release.apk", out / f"DynamicIsland-v{version}.apk")
for m in json.loads((ROOT / "docs/features.json").read_text(encoding="utf-8"))["modules"]:
    src = ROOT / f"modules/{m['id']}/build/outputs/apk/release/{m['id']}-release.apk"
    shutil.copy(src, out / f"{m['apk']}-v{m['version']}.apk")

log = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
m = re.search(rf"^## {re.escape(version)}\b.*?$(.*?)(?=^## |\Z)", log, re.M | re.S)
notes = (m.group(1).strip() if m else "") + "\n\n**Install:** download `DynamicIsland-v%s.apk` plus any modules you want. Guide: docs/INSTALL.md" % version
(out / "NOTES.md").write_text(notes, encoding="utf-8")
print("\n".join(sorted(p.name for p in out.iterdir())))
