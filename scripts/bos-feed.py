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
    assets = Path(assets)
    pk = sorted(assets.glob("*.bosapp"))
    if len(pk) != 1:
        fail(f"{assets} must hold exactly one .bosapp, found {len(pk)}")
    pkg = pk[0]
    sigf = Path(str(pkg) + ".sig")
    if not sigf.is_file() or not (assets / "feed.json").is_file():
        fail("the release assets need the .sig and feed.json beside the .bosapp")
    src = json.loads((assets / "feed.json").read_text(encoding="utf-8"))
    data, sig = pkg.read_bytes(), sigf.read_text()
    version = src.get("version", "")
    plat = src.get("platforms", {})
    if len(plat) != 1:
        fail("feed.json must hold exactly one platform")
    (name, p), = plat.items()
    want = f"https://github.com/{repo}/releases/download/os-app-v{version}/{pkg.name}"
    checks = [(p.get("sha256") == hashlib.sha256(data).hexdigest(), "sha256 differs from the package"),
              (p.get("size") == len(data), "size differs from the package"),
              (p.get("signature", "").strip() == sig.strip(), "signature differs from the .sig"),
              (p.get("url") == want, f"url is {p.get('url')}, want {want}"),
              (pkg.name == f"infantry-ai-{version}-{name}.bosapp", "package name does not match version and platform")]
    for ok, why in checks:
        if not ok:
            fail(why)
    if pubkey:
        bad = load_packager().verify_minisig(pubkey.strip(), sig, data)
        if bad:
            fail(bad)
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
