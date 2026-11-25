#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
"""BOS-9: pack Infantry AI as a BattalionOS app.

  scripts/bos-package --library L --cli C --plugins P --dist S [--version V] [--out DIR] [--base-url URL]
                      [--tauri-sign | --minisign-key FILE] [--notes TEXT] [--platform windows-x86_64]

Output in --out (default <repo>/dist-bosapp):
  infantry-ai-<version>-<platform>.bosapp       a zip: app.json, assets/icon.png, lib/ (the app library), bin/ (the CLI), plugins/, shell/dist/
  infantry-ai-<version>-<platform>.bosapp.sig   minisign signature of the zip
  feed.json                                     feed v1 (one platform entry) the Armory reads
  catalog-entry.json                            the catalog app entry, with the public key to pin
  public.key                                    minisign public key (the pinned one)

--tauri-sign signs with the app's updater key (CI only): the private half comes from the environment
(TAURI_SIGNING_PRIVATE_KEY, TAURI_SIGNING_PRIVATE_KEY_PASSWORD, the secrets rust-release.yml uses), the public half is
plugins.updater.pubkey in app/src-tauri/tauri.conf.json. `tauri signer sign` writes the signature base64-wrapped; the OS
(battalion-host armory/package.rs verify_signature) wants the plain minisig text and the inner key line of the .pub file,
so both are unwrapped here, and the signature is verified against the pinned key before anything is written.
Without --minisign-key / --tauri-sign a THROWAWAY key is made (left in <out>/throwaway.key.json): fine locally, never trusted by a
real install. With --minisign-key the `minisign` binary signs (`minisign -S`; the .pub file sits beside the key).
The package holds the daemon as a LIBRARY (lib/infantry_distribution.dll: the infantry-distribution cdylib); the
infantry-daemon program is not in it. BattalionOS runs the library on its own worker. Nothing is uploaded or released here.
"""
import argparse, base64, hashlib, json, os, shutil, subprocess, sys, zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PLATFORM = "windows-x86_64"
EXE = {"windows-x86_64": ".exe"}
MAX_ENTRIES = 20000


def fail(msg):
    print(f"bos-package: {msg}", file=sys.stderr)
    sys.exit(1)


def b64(b):
    return base64.b64encode(b).decode()


def version_default():
    conf = json.loads((REPO / "app/src-tauri/tauri.conf.json").read_text(encoding="utf-8"))
    return conf["version"]


def stamp_manifest(version):
    m = json.loads((REPO / "packaging/app.json").read_text(encoding="utf-8"))
    m["version"] = version
    return m


# ---- minisign (prehashed "ED", what `minisign -S` writes) -----------------------------------
def throwaway_key(out):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization as s
    path = out / "throwaway.key.json"
    if path.exists():
        d = json.loads(path.read_text())
        seed, kid = bytes.fromhex(d["seed"]), bytes.fromhex(d["keyId"])
    else:
        seed, kid = os.urandom(32), os.urandom(8)
        path.write_text(json.dumps({"seed": seed.hex(), "keyId": kid.hex(), "note": "THROWAWAY test key, never trusted"}))
    sk = Ed25519PrivateKey.from_private_bytes(seed)
    pk = sk.public_key().public_bytes(s.Encoding.Raw, s.PublicFormat.Raw)
    return sk, kid, pk


def sign_throwaway(sk, kid, data):
    sig = sk.sign(hashlib.blake2b(data, digest_size=64).digest())
    trusted = "infantry-ai bosapp (throwaway key)"
    glob = sk.sign(sig + trusted.encode())
    return f"untrusted comment: signature from a throwaway key\n{b64(b'ED' + kid + sig)}\ntrusted comment: {trusted}\n{b64(glob)}\n"


def pub_text(kid, pk):
    return b64(b"Ed" + kid + pk)


def sign_with_minisign(key_file, pkg):
    exe = shutil.which("minisign")
    if not exe:
        fail("--minisign-key needs the `minisign` binary on PATH")
    sig = Path(str(pkg) + ".sig")
    r = subprocess.run([exe, "-S", "-s", str(key_file), "-m", str(pkg), "-x", str(sig)], capture_output=True, text=True)
    if r.returncode != 0:
        fail("minisign failed: " + r.stderr.strip()[:300])
    return sig.read_text()


def tauri_pub_line():
    """The pinned key in the form the OS reads: the base64 line of the .pub file (tauri.conf.json holds the whole .pub, base64-wrapped)."""
    conf = json.loads((REPO / "app/src-tauri/tauri.conf.json").read_text(encoding="utf-8"))
    raw = conf["plugins"]["updater"]["pubkey"]
    try:
        text = base64.b64decode(raw).decode()
        return text.strip().splitlines()[-1].strip()
    except Exception:
        fail("plugins.updater.pubkey in tauri.conf.json is not a base64 minisign .pub file")


def verify_minisig(pub_line, sig_text, data):
    """Check a prehashed ('ED') minisign signature the way the OS does; returns an error text or None."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    from cryptography.exceptions import InvalidSignature
    try:
        pub = base64.b64decode(pub_line)
        lines = sig_text.strip().splitlines()
        sig = base64.b64decode(lines[1])
        if pub[:2] != b"Ed" or sig[:2] != b"ED":
            return "not a prehashed ED signature over an Ed key"
        if pub[2:10] != sig[2:10]:
            return "the signature's key id is not the pinned key's"
        Ed25519PublicKey.from_public_bytes(pub[10:]).verify(sig[10:], hashlib.blake2b(data, digest_size=64).digest())
    except InvalidSignature:
        return "the signature does not verify against the pinned key"
    except Exception as e:
        return f"unreadable signature or key: {e}"
    return None


def sign_with_tauri(pkg):
    for v in ("TAURI_SIGNING_PRIVATE_KEY", "TAURI_SIGNING_PRIVATE_KEY_PASSWORD"):
        if v not in os.environ:
            fail(f"--tauri-sign needs {v} in the environment")
    pnpm = shutil.which("pnpm")
    if not pnpm:
        fail("--tauri-sign needs pnpm on PATH")
    r = subprocess.run([pnpm, "tauri", "signer", "sign", str(pkg)], cwd=REPO / "app", capture_output=True, text=True)
    sigfile = Path(str(pkg) + ".sig")
    if r.returncode != 0 or not sigfile.is_file():
        fail("tauri signer sign failed: " + (r.stderr or r.stdout).strip()[-300:])
    return base64.b64decode(sigfile.read_text().strip()).decode()


# ---- the zip ---------------------------------------------------------------------------------
def add_tree(z, src, prefix):
    n = 0
    for p in sorted(Path(src).rglob("*")):
        if p.is_file() and not p.is_symlink():
            z.write(p, f"{prefix}/{p.relative_to(src).as_posix()}")
            n += 1
    return n


def build_zip(pkg, manifest, platform, library, cli, plugins, dist, icon):
    ext = EXE[platform]
    with zipfile.ZipFile(pkg, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("app.json", json.dumps(manifest, indent=2) + "\n")
        z.write(icon, manifest["icon"])
        z.write(library, manifest["process"][platform]["library"])
        z.write(cli, f"bin/infantry{ext}")
        if not add_tree(z, plugins, "plugins"):
            fail(f"{plugins} holds no files")
        if not add_tree(z, dist, "shell/dist"):
            fail(f"{dist} holds no files")
        if len(z.namelist()) > MAX_ENTRIES:
            fail("package has too many files")


def need_file(p, what):
    p = Path(p)
    if not p.is_file():
        fail(f"{what} not found: {p}")
    return p


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--library", required=True, help="the infantry-distribution library (target/<profile>/infantry_distribution.dll)")
    ap.add_argument("--cli", required=True)
    ap.add_argument("--plugins", required=True, help="output of rust/plugins/stage.sh (official/<Module>/...)")
    ap.add_argument("--dist", required=True, help="the built shell (app/shell/dist)")
    ap.add_argument("--version")
    ap.add_argument("--platform", default=PLATFORM)
    ap.add_argument("--out", default=str(REPO / "dist-bosapp"))
    ap.add_argument("--base-url", help="where the package will be served; default is a file: url of --out")
    ap.add_argument("--minisign-key")
    ap.add_argument("--tauri-sign", action="store_true", help="sign with the updater key from the environment (CI)")
    ap.add_argument("--notes", default="")
    a = ap.parse_args(argv)
    if a.platform not in EXE:
        fail(f"platform {a.platform} is not packed yet (windows-x86_64 only)")
    version = a.version or version_default()
    manifest = stamp_manifest(version)
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    library, cli = need_file(a.library, "library"), need_file(a.cli, "cli")
    plugins, dist = Path(a.plugins), Path(a.dist)
    for d, w in ((plugins, "plugins dir"), (dist, "shell dist")):
        if not d.is_dir():
            fail(f"{w} not found: {d}")
    if not (dist / "index.html").is_file():
        fail(f"{dist} has no index.html (ui.entry is shell/dist/index.html)")
    icon = need_file(REPO / "app/src-tauri/icons/128x128@2x.png", "the Infantry AI logo")

    name = f"infantry-ai-{version}-{a.platform}.bosapp"
    pkg = out / name
    build_zip(pkg, manifest, a.platform, library, cli, plugins, dist, icon)
    # BOS-CRT: a native file that imports the dynamic VC++ runtime stops on a clean Windows (STATUS_DLL_NOT_FOUND).
    chk = subprocess.run([sys.executable, str(REPO / "scripts/bos-crt-check.py"), str(pkg)], capture_output=True, text=True)
    if chk.returncode == 1:  # 2 = no PE file at all (a test fixture), not a failure
        pkg.unlink()
        fail("a native file in the package imports the dynamic VC++ runtime:\n" + chk.stdout + chk.stderr)
    data = pkg.read_bytes()
    if a.tauri_sign:
        public = tauri_pub_line()
        sig = sign_with_tauri(pkg)
        bad = verify_minisig(public, sig, data)
        if bad:
            pkg.unlink()
            Path(str(pkg) + ".sig").unlink(missing_ok=True)
            fail("signed with the Tauri key but " + bad + " (is TAURI_SIGNING_PRIVATE_KEY the pair of tauri.conf.json's pubkey?)")
    elif a.minisign_key:
        sig = sign_with_minisign(a.minisign_key, pkg)
        pubfile = Path(a.minisign_key).with_suffix(".pub")
        if not pubfile.is_file():
            fail("--minisign-key needs the .pub file beside it")
        public = pubfile.read_text().splitlines()[-1].strip()
    else:
        sk, kid, pk = throwaway_key(out)
        sig = sign_throwaway(sk, kid, data)
        public = pub_text(kid, pk)
    Path(str(pkg) + ".sig").write_text(sig)
    (out / "public.key").write_text(public + "\n")

    base = a.base_url or (out.as_uri() + "/")
    if not base.endswith("/"):
        base += "/"
    feed = {
        "version": version,
        "notes": a.notes or None,
        "contract": manifest["contract"]["max"],
        "stateSchema": manifest["state"]["schema"],
        "platforms": {a.platform: {"url": base + name, "signature": sig, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}},
    }
    feed = {k: v for k, v in feed.items() if v is not None}
    (out / "feed.json").write_text(json.dumps(feed, indent=2) + "\n")
    entry = {
        "id": manifest["id"], "name": manifest["name"], "publisher": manifest["publisher"], "license": manifest["license"],
        "summary": "A command post for coding agents: ticket board, agents on their own branches, mail and shared project knowledge.",
        "publicKey": public, "contract": manifest["contract"], "permissions": manifest["permissions"],
        "channels": {"stable": base + "feed.json"},
    }
    (out / "catalog-entry.json").write_text(json.dumps(entry, indent=2) + "\n")
    print(f"packed {pkg} ({len(data)} bytes, sha256 {feed['platforms'][a.platform]['sha256'][:12]}), key: {'tauri' if a.tauri_sign else 'given' if a.minisign_key else 'THROWAWAY'}")


if __name__ == "__main__":
    main()
