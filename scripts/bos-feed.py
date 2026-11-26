#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
"""BOS-PUB: write a channel feed (bos-stable, bos-nightly) from a published os-app-v<version> release. Writes a file only.

  scripts/bos-feed.py --assets DIR --repo OWNER/REPO --out feed.json [--pubkey LINE]

DIR holds the release's assets as downloaded (gh release download os-app-v<version> --dir DIR): the .bosapp, its .sig and
the feed.json the packager wrote. The feed is rebuilt from the package itself and refused unless sha256, size, signature
and url all agree with the release (url must be that tag's download url of that file). With --pubkey the signature is also
verified against that key (the inner line of the pinned .pub), exactly as the OS does. Nothing is uploaded here.
"""
import argparse, hashlib, importlib.util, json, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def fail(msg):
    print(f"bos-feed: {msg}", file=sys.stderr)
    sys.exit(1)


def load_packager():
    spec = importlib.util.spec_from_file_location("bos_package", HERE / "bos-package.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def build_feed(assets, repo, pubkey=None):
    """Every platform of feed.json is checked against its own package (AND-10: windows-x86_64 and the page-only android-aarch64)."""
    assets = Path(assets)
    if not (assets / "feed.json").is_file():
        fail("the release assets need the feed.json beside the .bosapp")
    src = json.loads((assets / "feed.json").read_text(encoding="utf-8"))
    version = src.get("version", "")
    plat = src.get("platforms", {})
    pk = sorted(assets.glob("*.bosapp"))
    if not plat:
        fail("feed.json holds no platform")
    if len(pk) != len(plat):
        fail(f"{assets} must hold one .bosapp per feed platform ({len(plat)}), found {len(pk)}")
    for name, p in plat.items():
        pkg = assets / f"infantry-ai-{version}-{name}.bosapp"
        if not pkg.is_file():
            fail(f"package name does not match version and platform: no {pkg.name}")
        sigf = Path(str(pkg) + ".sig")
        if not sigf.is_file():
            fail("the release assets need the .sig and feed.json beside the .bosapp")
        data, sig = pkg.read_bytes(), sigf.read_text()
        want = f"https://github.com/{repo}/releases/download/os-app-v{version}/{pkg.name}"
        checks = [(p.get("sha256") == hashlib.sha256(data).hexdigest(), f"{name}: sha256 differs from the package"),
                  (p.get("size") == len(data), f"{name}: size differs from the package"),
                  (p.get("signature", "").strip() == sig.strip(), f"{name}: signature differs from the .sig"),
                  (p.get("url") == want, f"{name}: url is {p.get('url')}, want {want}")]
        for ok, why in checks:
            if not ok:
                fail(why)
        if pubkey:
            bad = load_packager().verify_minisig(pubkey.strip(), sig, data)
            if bad:
                fail(f"{name}: {bad}")
    return src


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--assets", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--pubkey")
    a = ap.parse_args(argv)
    feed = build_feed(a.assets, a.repo, a.pubkey)
    Path(a.out).write_text(json.dumps(feed, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {a.out}: version {feed['version']}")


if __name__ == "__main__":
    main()
