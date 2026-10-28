#!/usr/bin/env python3
"""Regenerate the README feature table and the website's generated sections from docs/features.json.

    python3 tools/gen_docs.py          # rewrite the files
    python3 tools/gen_docs.py --check  # CI: fail if anything is out of date

Generated regions sit between <!-- GEN:name --> and <!-- /GEN:name --> markers; everything else is hand-written.
"""
import html, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = json.loads((ROOT / "docs/features.json").read_text(encoding="utf-8"))


def version_of(build_file):
    m = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / build_file).read_text(encoding="utf-8"))
    return m.group(1) if m else "?"


APP_VERSION = version_of("app/build.gradle.kts")


def readme_features():
    rows = ["| | |", "|---|---|"]
    for f in DATA["features"]:
        rows.append(f"| {f['icon']} **{f['title']}** | {f['summary']} |")
    for m in DATA["modules"]:
        rows.append(f"| {m['icon']} **{m['name']}** (module) | {m['summary']} |")
    return "\n".join(rows)


def readme_modules():
    rows = ["| Module | What it adds | Shortcuts it puts in the island and the app | Permissions |", "|---|---|---|---|"]
    for m in DATA["modules"]:
        rows.append(f"| {m['icon']} **{m['name']}** {m['version']} | {m['summary']} | {', '.join(m['links'])} | {', '.join(m['permissions'])} |")
    return "\n".join(rows)


def site_features():
    e = html.escape
    cards = [f'    <div class="card"><div class="ic">{f["icon"]}</div><h3>{e(f["title"])}</h3><p>{e(f["summary"])}.</p></div>' for f in DATA["features"]]
    return '  <div class="grid">\n' + "\n".join(cards) + "\n  </div>"


def site_modules():
    e = html.escape
    out = ['  <div class="grid">']
    for m in DATA["modules"]:
        links = "".join(f"<span>{e(l)}</span>" for l in m["links"])
        out.append(
            f'    <div class="card mod"><div class="ic">{m["icon"]}</div><h3>{e(m["name"])} <small>v{e(m["version"])}</small></h3>'
            f'<p>{e(m["summary"])}</p><div class="links">{links}</div>'
            f'<p class="perm">Permissions: {e(", ".join(m["permissions"]))}</p></div>')
    out.append("  </div>")
    return "\n".join(out)


def site_version():
    return f'  <span class="ver">Version {APP_VERSION}</span>'


TARGETS = {
    "README.md": {"features": readme_features, "modules": readme_modules},
    "docs/index.html": {"features": site_features, "modules": site_modules, "version": site_version},
}


def render(text, gens, path):
    for name, fn in gens.items():
        pat = re.compile(rf"(<!-- GEN:{name} -->\n)(.*?)(\n?<!-- /GEN:{name} -->)", re.S)
        if not pat.search(text):
            sys.exit(f"{path}: missing <!-- GEN:{name} --> markers")
        text = pat.sub(lambda m: m.group(1) + fn() + "\n" + m.group(3).lstrip("\n"), text)
    return text


def main():
    check = "--check" in sys.argv
    stale = []
    for rel, gens in TARGETS.items():
        p = ROOT / rel
        old = p.read_text(encoding="utf-8")
        new = render(old, gens, rel)
        if new != old:
            stale.append(rel)
            if not check:
                p.write_text(new, encoding="utf-8")
    if check and stale:
        sys.exit("Out of date: " + ", ".join(stale) + "\nRun: python3 tools/gen_docs.py and commit the result.")
    print(("Up to date" if check else "Wrote") + (": " + ", ".join(stale) if stale else ""))


if __name__ == "__main__":
    main()
