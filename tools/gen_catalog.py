#!/usr/bin/env python3
"""Build the module catalog the island ships with, from catalog/modules/*.json.

Writes app/src/main/assets/catalog.json (the in-app "Get more" list) and the approved-certificate
allow-list in app/src/main/res/values/modules.xml (between the GEN markers), so neither is edited by hand.

    python3 tools/gen_catalog.py          # write
    python3 tools/gen_catalog.py --check  # CI: validate entries and fail if outputs are stale
"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REQUIRED = ["id", "name", "package", "summary", "developer", "repo", "download", "certSha256", "permissions", "privacy"]
BANNED = {  # permissions a module may never request
    "android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.READ_SMS", "android.permission.RECEIVE_SMS",
    "android.permission.QUERY_ALL_PACKAGES", "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.MANAGE_EXTERNAL_STORAGE",
}
# The Updater (first-party, release key) installs the updates people confirm; no other module may.
RELEASE_CERT = "12178c6a653dd8e19c2414b0f4375270706820c14b0fc42bc5137c44d5416501"
FIRST_PARTY_ONLY = {"android.permission.REQUEST_INSTALL_PACKAGES"}


def load():
    entries, errors = [], []
    for f in sorted((ROOT / "catalog/modules").glob("*.json")):
        try:
            e = json.loads(f.read_text(encoding="utf-8"))
        except Exception as ex:
            errors.append(f"{f.name}: not valid JSON ({ex})"); continue
        missing = [k for k in REQUIRED if k not in e]
        if missing: errors.append(f"{f.name}: missing {', '.join(missing)}")
        if e.get("id") != f.stem: errors.append(f"{f.name}: id must be '{f.stem}'")
        if not re.fullmatch(r"[0-9a-f]{64}", e.get("certSha256", "")): errors.append(f"{f.name}: certSha256 must be 64 lowercase hex characters")
        if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z0-9_]+)+", e.get("package", "")): errors.append(f"{f.name}: package name looks wrong")
        for p in e.get("permissions", []):
            first_party = e.get("firstParty") is True and e.get("certSha256") == RELEASE_CERT
            if p.get("name") in FIRST_PARTY_ONLY and first_party: pass
            elif p.get("name") in BANNED: errors.append(f"{f.name}: permission {p['name']} isn't allowed for modules")
            if len(p.get("reason", "")) < 8: errors.append(f"{f.name}: give a real reason for {p.get('name')}")
        for k in ("repo", "download"):
            if not str(e.get(k, "")).startswith("https://"): errors.append(f"{f.name}: {k} must be an https link")
        entries.append(e)
    pkgs = [e.get("package") for e in entries]
    for p in {p for p in pkgs if pkgs.count(p) > 1}: errors.append(f"package {p} is listed twice")
    return entries, errors


def outputs(entries):
    public = [{k: e[k] for k in ("id", "name", "icon", "package", "summary", "developer", "download", "certSha256") if k in e} for e in entries]
    catalog = json.dumps(public, indent=1, ensure_ascii=False) + "\n"
    certs = []
    for e in entries:
        if e["certSha256"] not in [c for c, _ in certs]: certs.append((e["certSha256"], e["developer"]))
    xml_items = "\n".join(f"        <!-- {dev} -->\n        <item>{c}</item>" for c, dev in certs)
    return catalog, xml_items


def main():
    check = "--check" in sys.argv
    entries, errors = load()
    if errors:
        sys.exit("Catalog problems:\n" + "\n".join("✗ " + e for e in errors))
    catalog, xml_items = outputs(entries)
    cat_path = ROOT / "app/src/main/assets/catalog.json"
    xml_path = ROOT / "app/src/main/res/values/modules.xml"
    xml = xml_path.read_text(encoding="utf-8")
    pat = re.compile(r"(<!-- GEN:certs -->\n)(.*?)(\s*<!-- /GEN:certs -->)", re.S)
    if not pat.search(xml): sys.exit("modules.xml is missing the GEN:certs markers")
    new_xml = pat.sub(lambda m: m.group(1) + xml_items + "\n        <!-- /GEN:certs -->", xml)
    stale = []
    if not cat_path.exists() or cat_path.read_text(encoding="utf-8") != catalog: stale.append(str(cat_path.relative_to(ROOT)))
    if new_xml != xml: stale.append(str(xml_path.relative_to(ROOT)))
    if check:
        if stale: sys.exit("Out of date: " + ", ".join(stale) + "\nRun: python3 tools/gen_catalog.py")
        print(f"✓ catalog valid ({len(entries)} modules)"); return
    cat_path.parent.mkdir(parents=True, exist_ok=True)
    cat_path.write_text(catalog, encoding="utf-8"); xml_path.write_text(new_xml, encoding="utf-8")
    print(f"Wrote {len(entries)} modules" + (": " + ", ".join(stale) if stale else " (no changes)"))


if __name__ == "__main__":
    main()
