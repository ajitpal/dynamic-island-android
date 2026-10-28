#!/usr/bin/env python3
"""Assemble the website into an output folder: docs/ + a changelog page + a link check.

    python3 tools/build_site.py _site
"""
import html, pathlib, re, shutil, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
out = ROOT / (sys.argv[1] if len(sys.argv) > 1 else "_site")
if out.exists():
    shutil.rmtree(out)
shutil.copytree(ROOT / "docs", out, ignore=shutil.ignore_patterns("*.md", "features.json"))

# Changelog page, styled like the site.
try:
    import markdown
    body = markdown.markdown((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"))
except ImportError:  # local runs without the package: plain text is fine
    body = "<pre>" + html.escape((ROOT / "CHANGELOG.md").read_text(encoding="utf-8")) + "</pre>"
# Relative links to repo files (docs/MODULES.md, PRIVACY.md…) point at GitHub.
body = re.sub(r'href="(?!https?:|#)([^"]+)"', lambda m: f'href="https://github.com/ajitpal/dynamic-island-android/blob/main/{m.group(1)}"', body)
index = (out / "index.html").read_text(encoding="utf-8")
style = re.search(r"<style>.*?</style>", index, re.S).group(0)
(out / "changelog.html").write_text(
    f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
    f'<title>Changelog · Dynamic Island for Android</title>{style}<style>.log{{max-width:760px;margin:40px auto;padding:0 20px}}.log h2{{font-size:22px;margin:32px 0 8px}}.log li{{margin:6px 0 6px 18px;color:var(--mut)}}.log b,.log strong{{color:var(--fg)}}</style></head>'
    f'<body><nav><div class="wrap"><a class="brand" href="./"><span class="logo"></span>Dynamic Island</a></div></nav><main class="log">{body}</main></body></html>',
    encoding="utf-8")

# Link check: every local href/src must exist.
bad = []
for page in out.glob("*.html"):
    for ref in re.findall(r'(?:href|src)="([^"#:?]+)"', page.read_text(encoding="utf-8")):
        if ref.startswith(("//",)) or ref.endswith("/"):
            continue
        if not (out / ref).exists():
            bad.append(f"{page.name} → {ref}")
if bad:
    sys.exit("Broken local links:\n" + "\n".join(bad))
print(f"Site built in {out} ({sum(1 for _ in out.rglob('*'))} files)")
