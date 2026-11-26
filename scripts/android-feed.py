#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
"""PHONE-UPD-1: write the phone's update feed (android.json, an asset of the fixed release android-stable). Writes a file only.

  scripts/android-feed.py --apk FILE --repo OWNER/REPO --out android.json [--notes TEXT] [--tauri-sign] [--pubkey LINE]

FILE is InfantryAI_<version>_<abi>.apk as released on android-v<version>, with FILE.sig beside it (or --tauri-sign makes
it with the TAURI_SIGNING_* key, the same key as every other feed). The signature is always verified against the pinned
key (plugins.updater.pubkey in tauri.conf.json, or --pubkey) before anything is written. The feed has the latest.json
shape the phone already reads (feed.rs `android_offer`): version, notes, pub_date and one platform entry per ABI with
url (that tag's download url), sha256, size and signature.

By hand (the workflow does the same on an android-v* tag):
  gh release download android-v<V> --dir d --pattern '*.apk*'
  python scripts/android-feed.py --apk d/InfantryAI_<V>_arm64.apk --repo SuperiorSoft-LLC/infantry-ai --out d/android.json
  gh release view android-stable >/dev/null 2>&1 || gh release create android-stable --prerelease --latest=false \
      --title "Infantry AI for Android: update feed" --notes "The phone app's update feed (android.json)."
  gh release upload android-stable d/android.json --clobber
The standalone "latest" release and its latest.json are never touched.
"""
import argparse, datetime, hashlib, importlib.util, json, re, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ABI = {"arm64": "android-aarch64", "armv7": "android-armv7", "x86_64": "android-x86_64", "universal": "android-universal"}


def fail(msg):
    print(f"android-feed: {msg}", file=sys.stderr)
    sys.exit(1)


def load_packager():
    spec = importlib.util.spec_from_file_location("bos_package", HERE / "bos-package.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def build_feed(apk, repo, notes=None, pubkey=None, tauri_sign=False, now=None):
    apk = Path(apk)
    m = re.fullmatch(r"InfantryAI_(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)_([a-z0-9_]+)\.apk", apk.name)
    if not m:
        fail(f"{apk.name} is not InfantryAI_<version>_<abi>.apk")
    version, abi = m.groups()
    if abi not in ABI:
        fail(f"unknown ABI {abi} (one of {', '.join(ABI)})")
    if not apk.is_file():
        fail(f"no file {apk}")
    pkg = load_packager()
    data = apk.read_bytes()
    sigf = Path(str(apk) + ".sig")
    if tauri_sign:
        sig = pkg.sign_with_tauri(apk)
        sigf.write_text(sig)
    elif not sigf.is_file():
        fail(f"no {sigf.name} beside the APK (sign it, or pass --tauri-sign)")
    sig = sigf.read_text()
    bad = pkg.verify_minisig((pubkey or pkg.tauri_pub_line()).strip(), sig, data)
    if bad:
        fail(bad)
    date = (now or datetime.datetime.now(datetime.timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "version": version,
        "notes": notes or f"Infantry AI {version} for Android.",
        "pub_date": date,
        "platforms": {ABI[abi]: {
            "url": f"https://github.com/{repo}/releases/download/android-v{version}/{apk.name}",
            "sha256": hashlib.sha256(data).hexdigest(),
            "size": len(data),
            "signature": sig.strip(),
        }},
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--notes")
    ap.add_argument("--pubkey")
    ap.add_argument("--tauri-sign", action="store_true")
    a = ap.parse_args(argv)
    if a.out.endswith("latest.json"):
        fail("the phone feed is android.json, never latest.json (the standalone updater's manifest)")
    feed = build_feed(a.apk, a.repo, a.notes, a.pubkey, a.tauri_sign)
    Path(a.out).write_text(json.dumps(feed, indent=2) + "\n", encoding="utf-8")
    print(f"android-feed: {a.out} offers {feed['version']}")


if __name__ == "__main__":
    main()
