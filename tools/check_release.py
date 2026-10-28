#!/usr/bin/env python3
"""CI guard: every version in the build files is documented.

- the island app's versionName has a "## <version>" heading in CHANGELOG.md
- ROADMAP.md mentions the app's major.minor (e.g. 1.9)
- each module's versionName matches docs/features.json (so the website shows the right version)
- every screenshot referenced from README.md and docs/index.html exists
"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
errors = []


def version_of(build_file):
    m = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / build_file).read_text(encoding="utf-8"))
    return m.group(1) if m else None


app = version_of("app/build.gradle.kts")
changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
if not re.search(rf"^## {re.escape(app)}\b", changelog, re.M):
    errors.append(f"CHANGELOG.md has no '## {app}' entry for the app's versionName")
mm = ".".join(app.split(".")[:2])
if mm not in (ROOT / "ROADMAP.md").read_text(encoding="utf-8"):
    errors.append(f"ROADMAP.md doesn't mention {mm}")

features = json.loads((ROOT / "docs/features.json").read_text(encoding="utf-8"))
for m in features["modules"]:
    build = ROOT / "modules" / m["id"] / "build.gradle.kts"
    if not build.exists():
        errors.append(f"docs/features.json lists module '{m['id']}' but modules/{m['id']} doesn't exist"); continue
    v = version_of(build.relative_to(ROOT))
    if v != m["version"]:
        errors.append(f"modules/{m['id']} is {v} but docs/features.json says {m['version']}")
listed = {m["id"] for m in features["modules"]}
for d in (ROOT / "modules").iterdir():
    if (d / "src/main/AndroidManifest.xml").exists() and "com.android.application" in (d / "build.gradle.kts").read_text() and d.name not in listed:
        errors.append(f"module app modules/{d.name} is missing from docs/features.json")

for rel in ["README.md", "docs/index.html"]:
    text = (ROOT / rel).read_text(encoding="utf-8")
    for ref in re.findall(r'(?:src="|\]\()((?:docs/)?screenshots/[^")]+)', text):
        p = (ROOT / rel).parent / ref
        if not p.exists():
            errors.append(f"{rel} references missing {ref}")

if errors:
    print("\n".join("✗ " + e for e in errors)); sys.exit(1)
print(f"✓ versions and docs consistent (app {app})")
