#!/usr/bin/env python3
"""Automated review of a module submitted for listing (catalog/modules/<id>.json).

Downloads the APK named in "apkUrl", then checks it WITHOUT running any of the submitter's code:
  - the APK is signed and its certificate SHA-256 matches "certSha256"
  - the package name matches "package"
  - it declares a service for com.ajit.dynamicisland.action.MODULE
  - every permission it requests is declared (with a reason) in the entry, and none is banned
  - targetSdk is recent enough

    python3 tools/review_module.py catalog/modules/hello.json [--report report.md] [--apk local.apk]
Exit code 1 if any check fails. Needs apksigner and aapt2 from the Android SDK build-tools.
"""
import glob, json, os, pathlib, re, subprocess, sys, tempfile, urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from gen_catalog import BANNED  # noqa: E402

IGNORED = {"com.ajit.dynamicisland.permission.MODULE_API"}
# Install-time "normal" permissions that don't need a reason in the entry (still shown in the report).
NORMAL = {"android.permission.ACCESS_NETWORK_STATE", "android.permission.WAKE_LOCK", "android.permission.RECEIVE_BOOT_COMPLETED",
          "android.permission.VIBRATE", "android.permission.FOREGROUND_SERVICE"}
MIN_TARGET_SDK = 34


def tool(name):
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", name)))
    if not found: sys.exit(f"{name} not found (set ANDROID_HOME)")
    return found[-1]


def main():
    entry_path = pathlib.Path(sys.argv[1])
    report_path = sys.argv[sys.argv.index("--report") + 1] if "--report" in sys.argv else None
    e = json.loads(entry_path.read_text(encoding="utf-8"))
    rows, ok = [], True

    def check(name, passed, detail=""):
        nonlocal ok
        ok &= passed
        rows.append(f"| {'✅' if passed else '❌'} | {name} | {detail} |")

    if e.get("firstParty"):
        check("First-party module", True, "built and signed in this repository")
        return finish(e, rows, ok, report_path)
    local = sys.argv[sys.argv.index("--apk") + 1] if "--apk" in sys.argv else None  # testing: a local APK
    url = e.get("apkUrl")
    if not local and (not url or not url.startswith("https://")):
        check("apkUrl", False, "add a direct https link to the signed APK of this version")
        return finish(e, rows, ok, report_path)
    with tempfile.TemporaryDirectory() as d:
        apk = os.path.join(d, "module.apk")
        try:
            if local: apk = local
            else: urllib.request.urlretrieve(url, apk)
            check("Download", True, f"{os.path.getsize(apk) // 1024} KB")
        except Exception as ex:
            check("Download", False, str(ex)); return finish(e, rows, ok, report_path)

        certs = subprocess.run([tool("apksigner"), "verify", "--print-certs", apk], capture_output=True, text=True)
        digests = re.findall(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", certs.stdout)
        check("Signature valid", certs.returncode == 0, "" if certs.returncode == 0 else certs.stderr.strip()[:200])
        check("Certificate matches certSha256", bool(digests) and digests[0] == e.get("certSha256"),
              f"APK: `{digests[0] if digests else '?'}`")
        check("Not signed with a debug key", "CN=Android Debug" not in certs.stdout, "sign with your own release key")

        badging = subprocess.run([tool("aapt2"), "dump", "badging", apk], capture_output=True, text=True).stdout
        pkg = re.search(r"package: name='([^']+)'", badging)
        check("Package name", bool(pkg) and pkg.group(1) == e.get("package"), pkg.group(1) if pkg else "?")
        tsdk = re.search(r"targetSdkVersion:'(\d+)'", badging)
        check(f"targetSdk ≥ {MIN_TARGET_SDK}", bool(tsdk) and int(tsdk.group(1)) >= MIN_TARGET_SDK, tsdk.group(1) if tsdk else "?")

        xml = subprocess.run([tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk], capture_output=True, text=True).stdout
        check("Declares a module service", "com.ajit.dynamicisland.action.MODULE" in xml)
        check("No development-only TRUST_ANY_HOST flag", "TRUST_ANY_HOST" not in xml, "remove the meta-data before release")
        requested = set(re.findall(r"uses-permission: name='([^']+)'", badging)) - IGNORED
        requested = {p for p in requested if not p.endswith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")}
        declared = {p["name"] for p in e.get("permissions", [])}
        banned = requested & BANNED
        check("No banned permissions", not banned, ", ".join(sorted(banned)))
        undeclared = {p for p in requested - declared if p not in NORMAL and not p.startswith("android.permission.FOREGROUND_SERVICE_")}
        check("Every permission is explained in the entry", not undeclared, ", ".join(sorted(undeclared)) or ", ".join(sorted(requested)) or "none requested")
    return finish(e, rows, ok, report_path)


def finish(e, rows, ok, report_path):
    report = "\n".join([
        f"## Module review: {e.get('name')} ({e.get('package')})", "",
        "Automated checks on the signed APK. The submitter's code was not run.", "",
        "| | Check | Detail |", "|---|---|---|", *rows, "",
        "**Next:** a maintainer reviews the purpose, the permission reasons and the privacy note." if ok
        else "**Please fix the ❌ items and push again.**",
    ])
    print(report)
    if report_path: pathlib.Path(report_path).write_text(report, encoding="utf-8")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
