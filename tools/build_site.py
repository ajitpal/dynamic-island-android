#!/usr/bin/env python3
"""Assemble the website into an output folder: docs/ + changelog + developer pages (SDK guide, API
reference, module catalogue) + a link check.

    python3 tools/build_site.py _site
"""
import html, pathlib, re, shutil, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
out = ROOT / (sys.argv[1] if len(sys.argv) > 1 else "_site")
if out.exists():
    shutil.rmtree(out)
shutil.copytree(ROOT / "docs", out, ignore=shutil.ignore_patterns("*.md", "features.json"))

REPO = "https://github.com/ajitpal/dynamic-island-android"
index = (out / "index.html").read_text(encoding="utf-8")
style = re.search(r"<style>.*?</style>", index, re.S).group(0)


def md(path):
    text = (ROOT / path).read_text(encoding="utf-8")
    try:
        import markdown
        return markdown.markdown(text, extensions=["tables", "fenced_code", "toc"])
    except ImportError:  # local runs without the package: plain text is fine
        return "<pre>" + html.escape(text) + "</pre>"


def page(name, title, body, depth=0, extra_css="", nav_links=""):
    up = "../" * depth
    (out / name).parent.mkdir(parents=True, exist_ok=True)
    (out / name).write_text(
        f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
        f'<title>{html.escape(title)} · Dynamic Island for Android</title>{style}<style>.log{{max-width:820px;margin:40px auto;padding:0 20px}}'
        f'.log h1{{font-size:clamp(30px,5vw,44px);margin-bottom:14px}}.log h2{{font-size:22px;margin:32px 0 8px}}.log h3{{font-size:17px;margin:22px 0 6px}}'
        f'.log p,.log li{{color:var(--mut)}}.log li{{margin:6px 0 6px 18px}}.log b,.log strong{{color:var(--fg)}}.log table{{margin:12px 0}}'
        f'.log pre code{{background:none;border:0;padding:0}}.log a{{color:var(--acc)}}'
        f'@media (max-width:720px){{nav .links{{display:flex;overflow-x:auto;gap:14px;font-size:13px;min-width:0}}nav .links a{{white-space:nowrap}}.brand{{flex:none}}nav .wrap{{gap:14px}}}}{extra_css}</style></head>'
        f'<body><nav><div class="wrap"><a class="brand" href="{up}index.html"><span class="logo"></span>Dynamic Island</a>'
        f'<div class="links">{nav_links}</div></div></nav><main class="log">{body}</main></body></html>',
        encoding="utf-8")


def to_github(body, mapping):
    """Links between the SDK pages stay on the site; other relative links point at the repo on GitHub."""
    def fix(m):
        ref = m.group(1)
        key = ref.split("#")[0]
        for src, dst in mapping.items():
            if key.endswith(src):
                return f'href="{dst}{ref[len(key):]}"'
        clean = re.sub(r"^(\.\./)+", "", ref)
        return f'href="{REPO}/blob/main/{clean}"'
    return re.sub(r'href="(?!https?:|#|mailto:)([^"]+)"', fix, body)


# Changelog page, styled like the site.
page("changelog.html", "Changelog", to_github(md("CHANGELOG.md"), {}))

# Developer site (2.0): SDK overview, guide, agent guide, API reference, module catalogue.
sdk_nav = '<a href="index.html">SDK</a><a href="modules.html">Guide</a><a href="agents.html">AI agents</a><a href="api/index.html">API</a><a href="catalogue.html">Catalogue</a>'
links = {"MODULES.md": "modules.html", "AGENT_GUIDE.md": "agents.html", "SDK.md": "index.html"}
page("sdk/index.html", "Module SDK", to_github(md("docs/SDK.md"), {"modules.html": "modules.html", "agents.html": "agents.html",
     "api/index.html": "api/index.html", "catalogue.html": "catalogue.html", **links}), 1, nav_links=sdk_nav)
page("sdk/modules.html", "Building a module", to_github(md("docs/MODULES.md"), links), 1, nav_links=sdk_nav)
page("sdk/agents.html", "Agent guide", to_github(md("docs/modules/AGENT_GUIDE.md"), links), 1, nav_links=sdk_nav)

# API reference: Dokka output (CI runs ./gradlew :module-sdk:dokkaGeneratePublicationHtml first).
dokka = ROOT / "module-sdk/build/dokka/html"
if dokka.is_dir():
    shutil.copytree(dokka, out / "sdk/api")
else:
    page("sdk/api/index.html", "API reference", "<h1>API reference</h1><p>Generated in CI from the SDK source. "
         f'Run <code>./gradlew :module-sdk:dokkaGeneratePublicationHtml</code> to build it locally, or read the '
         f'<a href="{REPO}/tree/main/module-sdk/src/main/java/com/ajit/dynamicisland/sdk">source</a>.</p>', 2, nav_links=sdk_nav.replace('href="', 'href="../'))

# Module catalogue, from catalog/modules/*.json (the same entries the app lists in Get more).
import json
entries = [json.loads(f.read_text(encoding="utf-8")) for f in sorted((ROOT / "catalog/modules").glob("*.json"))]
entries.sort(key=lambda e: (not e.get("firstParty", False), e["name"].lower()))
cards = []
for e in entries:
    perms = ", ".join(html.escape(p["name"].split(".")[-1].replace("_", " ").title()) for p in e["permissions"]) or "None"
    who = "Dynamic Island" if e.get("firstParty") else "Reviewed third-party developer"
    search = html.escape(" ".join([e["name"], e["summary"], e.get("developer", ""), perms]).lower())
    cards.append(
        f'<div class="card mod" data-q="{search}"><div class="ic">{html.escape(e.get("icon", "🧩"))}</div>'
        f'<h3>{html.escape(e["name"])} <small>{html.escape(e.get("version", ""))}</small></h3>'
        f'<p>{html.escape(e["summary"])}</p><p class="perm"><b>{who}</b> · {html.escape(e.get("developer", ""))}</p>'
        f'<p class="perm">Permissions: {perms}</p><p class="perm">{html.escape(e["privacy"])}</p>'
        f'<p class="perm"><a href="{html.escape(e["download"])}">Download</a> · <a href="{html.escape(e["repo"])}">Source</a> · '
        f'Certificate <code>{e["certSha256"][:16]}…</code></p></div>')
body = (
    f'<h1>Module catalogue</h1><p>{len(entries)} reviewed modules. Every listing is checked automatically (signature, permissions, package, no '
    f'development flags) and by a maintainer. People with the Updater module see new listings in <b>Modules → Get more</b> within a day.</p>'
    f'<p><input id="q" type="search" placeholder="Search modules" aria-label="Search modules" '
    f'style="width:100%;margin:18px 0;padding:12px 16px;border-radius:12px;border:1px solid var(--line);background:var(--panel);color:var(--fg);font-size:16px"></p>'
    f'<div class="grid" id="list">{"".join(cards)}</div><p id="none" class="note" hidden>No module matches.</p>'
    f'<h2>List your module</h2><p>Follow the <a href="modules.html#getting-listed">guide</a> (or the <a href="agents.html">agent guide</a>), then open a pull request '
    f'that adds <code>catalog/modules/&lt;id&gt;.json</code>. Not sure yet? <a href="{REPO}/issues/new?template=module_submission.yml">Tell us about it</a>. '
    f'Problem with a listed module? <a href="{REPO}/issues/new?template=module_report.yml">Report it</a>.</p>'
    '<script>const q=document.getElementById("q");q.addEventListener("input",()=>{const t=q.value.trim().toLowerCase();let n=0;'
    'document.querySelectorAll("#list .card").forEach(c=>{const ok=!t||c.dataset.q.includes(t);c.hidden=!ok;if(ok)n++});'
    'document.getElementById("none").hidden=n>0;});</script>')
page("sdk/catalogue.html", "Module catalogue", body, 1, extra_css=".log .grid{margin-top:8px}.card[hidden]{display:none}", nav_links=sdk_nav)

# Link check: every local href/src must exist.
bad = []
for f in [*out.glob("*.html"), *(out / "sdk").glob("*.html")]:
    for ref in re.findall(r'(?:href|src)="([^"#:?]+)"', f.read_text(encoding="utf-8")):
        if ref.startswith(("//",)) or ref.endswith("/"):
            continue
        if not (f.parent / ref).resolve().exists():
            bad.append(f"{f.relative_to(out)} → {ref}")
if bad:
    sys.exit("Broken local links:\n" + "\n".join(bad))
print(f"Site built in {out} ({sum(1 for _ in out.rglob('*'))} files)")
