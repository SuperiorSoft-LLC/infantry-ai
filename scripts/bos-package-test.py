#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
"""BOS-9 tests: python scripts/bos-package-test.py  (needs pyyaml and cryptography)."""
import base64, hashlib, json, subprocess, sys, tempfile, unittest, zipfile
from pathlib import Path

import yaml
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "scripts/bos-package.py"
WF = REPO / ".github/workflows"


def run_packager(tmp, version="9.8.7"):
    inp = Path(tmp) / "in"
    (inp / "plugins/official/Mod").mkdir(parents=True)
    (inp / "plugins/official/Mod/module.json").write_text("{}")
    (inp / "dist").mkdir()
    (inp / "dist/index.html").write_text("<html></html>")
    (inp / "d.dll").write_bytes(b"MZ library")
    (inp / "c.exe").write_bytes(b"MZ cli")
    out = Path(tmp) / "out"
    r = subprocess.run([sys.executable, str(SCRIPT), "--library", str(inp / "d.dll"), "--cli", str(inp / "c.exe"), "--plugins", str(inp / "plugins"),
                        "--dist", str(inp / "dist"), "--version", version, "--out", str(out)], capture_output=True, text=True)
    return r, out


def load(name):
    import importlib.util
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), REPO / "scripts" / f"{name}.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class Signing(unittest.TestCase):
    def test_verify_minisig_accepts_the_package_signature_and_refuses_others(self):
        pk = load("bos-package")
        with tempfile.TemporaryDirectory() as tmp:
            r, out = run_packager(tmp)
            self.assertEqual(r.returncode, 0, r.stderr)
            data = (out / "infantry-ai-9.8.7-windows-x86_64.bosapp").read_bytes()
            sig = (out / "infantry-ai-9.8.7-windows-x86_64.bosapp.sig").read_text()
            pub = (out / "public.key").read_text().strip()
            self.assertIsNone(pk.verify_minisig(pub, sig, data))
            self.assertIsNotNone(pk.verify_minisig(pub, sig, data + b"x"))
            other = base64.b64encode(b"Ed" + base64.b64decode(pub)[2:10] + bytes(32)).decode()
            self.assertIsNotNone(pk.verify_minisig(other, sig, data))

    def test_the_pinned_key_is_the_inner_line_of_the_tauri_pubkey(self):
        pk = load("bos-package")
        line = pk.tauri_pub_line()
        raw = base64.b64decode(line)
        self.assertEqual((raw[:2], len(raw)), (b"Ed", 42))
        cat = REPO.parent.parent.parent / "battalionOS-apps/catalog.json"
        if cat.is_file():  # the catalog must pin the line the OS reads, not the base64-wrapped .pub file
            self.assertEqual(json.loads(cat.read_text())["apps"][0]["publicKey"], line)

    def test_bos_feed_rebuilds_from_the_release_assets_and_refuses_a_mismatch(self):
        feedmod = load("bos-feed")
        with tempfile.TemporaryDirectory() as tmp:
            r, out = run_packager(tmp)
            self.assertEqual(r.returncode, 0, r.stderr)
            repo = "o/r"
            d = json.loads((out / "feed.json").read_text())
            p = d["platforms"]["windows-x86_64"]
            p["url"] = f"https://github.com/{repo}/releases/download/os-app-v9.8.7/infantry-ai-9.8.7-windows-x86_64.bosapp"
            (out / "feed.json").write_text(json.dumps(d))
            self.assertEqual(feedmod.build_feed(out, repo, (out / "public.key").read_text())["version"], "9.8.7")
            p["sha256"] = "0" * 64
            (out / "feed.json").write_text(json.dumps(d))
            with self.assertRaises(SystemExit):
                feedmod.build_feed(out, repo)


class Package(unittest.TestCase):
    def test_package_feed_and_signature_agree(self):
        with tempfile.TemporaryDirectory() as tmp:
            r, out = run_packager(tmp)
            self.assertEqual(r.returncode, 0, r.stderr)
            pkg = out / "infantry-ai-9.8.7-windows-x86_64.bosapp"
            data = pkg.read_bytes()
            feed = json.loads((out / "feed.json").read_text())
            plat = feed["platforms"]["windows-x86_64"]
            self.assertEqual((feed["version"], feed["contract"]), ("9.8.7", 1))
            self.assertEqual((plat["sha256"], plat["size"]), (hashlib.sha256(data).hexdigest(), len(data)))
            self.assertEqual(plat["signature"], (out / "public.key").parent.joinpath(pkg.name + ".sig").read_text())
            # the minisign check, as minisign_verify does it: prehashed "ED", then the global signature
            pub = base64.b64decode((out / "public.key").read_text().strip())
            self.assertEqual(pub[:2], b"Ed")
            lines = plat["signature"].splitlines()
            sig = base64.b64decode(lines[1])
            self.assertEqual((sig[:2], sig[2:10]), (b"ED", pub[2:10]))
            key = Ed25519PublicKey.from_public_bytes(pub[10:])
            key.verify(sig[10:], hashlib.blake2b(data, digest_size=64).digest())
            key.verify(base64.b64decode(lines[3]), sig[10:] + lines[2].split("trusted comment: ", 1)[1].encode())
            # a changed byte no longer verifies
            from cryptography.exceptions import InvalidSignature
            with self.assertRaises(InvalidSignature):
                key.verify(sig[10:], hashlib.blake2b(data + b"x", digest_size=64).digest())

    def test_zip_holds_what_the_manifest_names_and_is_safe(self):
        with tempfile.TemporaryDirectory() as tmp:
            r, out = run_packager(tmp)
            self.assertEqual(r.returncode, 0, r.stderr)
            with zipfile.ZipFile(out / "infantry-ai-9.8.7-windows-x86_64.bosapp") as z:
                names = z.namelist()
                m = json.loads(z.read("app.json"))
            self.assertEqual((m["id"], m["version"], m["name"]), ("infantry-ai", "9.8.7", "Infantry AI"))
            self.assertIn(m["process"]["windows-x86_64"]["library"], names)
            self.assertNotIn("exec", m["process"]["windows-x86_64"])
            self.assertNotIn("onOsExit", m)
            self.assertFalse([n for n in names if n.endswith("infantry-daemon.exe")], "the daemon program is not in the package")
            self.assertIn(m["ui"]["entry"], names)
            self.assertIn(m["icon"], names)
            self.assertIn(m["cli"][0]["exec"], names)
            self.assertIn("plugins/official/Mod/module.json", names)
            for n in names:
                self.assertFalse(n.startswith("/") or ".." in n.split("/") or ":" in n or "\\" in n, n)
            # state.dir is the folder the 0.7 app already uses, so nothing is moved
            self.assertEqual(m["state"]["dir"]["windows"], "%LOCALAPPDATA%\\SuperiorSoft\\InfantryAI-Rust")

    def test_a_missing_input_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = subprocess.run([sys.executable, str(SCRIPT), "--library", str(Path(tmp) / "no.dll"), "--cli", "x", "--plugins", tmp, "--dist", tmp], capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("library not found", r.stderr)


class PageOnly(unittest.TestCase):
    """AND-10: the phone package: same app.json, the page only, listed under android-aarch64."""

    def test_page_only_package_holds_no_native_part_and_same_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            dist = Path(tmp) / "dist"
            dist.mkdir()
            (dist / "index.html").write_text("<html></html>")
            out = Path(tmp) / "out"
            r = subprocess.run([sys.executable, str(SCRIPT), "--page-only", "--dist", str(dist), "--version", "9.8.7", "--out", str(out)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            pkg = out / "infantry-ai-9.8.7-android-aarch64.bosapp"
            with zipfile.ZipFile(pkg) as z:
                names = z.namelist()
                m = json.loads(z.read("app.json"))
            self.assertFalse([n for n in names if n.startswith(("lib/", "bin/", "plugins/"))], names)
            self.assertIn("shell/dist/index.html", names)
            base = json.loads((REPO / "packaging/app.json").read_text(encoding="utf-8"))
            for k in ("id", "name", "publisher", "contract", "process", "state"):
                self.assertEqual(m[k], base[k], k)
            # PHONE-SCAN-1: the phone page declares the OS scanner and only what the page uses (no process.spawn, no files.user).
            self.assertEqual(m["permissions"], ["network", "login.window", "scan.code"])
            cat = json.loads((out / "catalog-entry.json").read_text())
            self.assertEqual(cat["permissions"], list(dict.fromkeys(base["permissions"] + m["permissions"])))
            for c in ("ws:", "wss:", "http:", "https:"):
                self.assertIn(c, m["ui"]["connect"])
            feed = json.loads((out / "feed.json").read_text())
            self.assertEqual(list(feed["platforms"]), ["android-aarch64"])
            self.assertEqual(feed["platforms"]["android-aarch64"]["sha256"], hashlib.sha256(pkg.read_bytes()).hexdigest())

    def test_windows_and_phone_packages_share_one_feed_that_bos_feed_checks(self):
        feedmod = load("bos-feed")
        with tempfile.TemporaryDirectory() as tmp:
            r, out = run_packager(tmp)
            self.assertEqual(r.returncode, 0, r.stderr)
            base = "https://github.com/o/r/releases/download/os-app-v9.8.7/"
            r = subprocess.run([sys.executable, str(SCRIPT), "--page-only", "--dist", str(Path(tmp) / "in/dist"), "--version", "9.8.7", "--out", str(out), "--base-url", base], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            d = json.loads((out / "feed.json").read_text())
            self.assertEqual(sorted(d["platforms"]), ["android-aarch64", "windows-x86_64"])
            d["platforms"]["windows-x86_64"]["url"] = base + "infantry-ai-9.8.7-windows-x86_64.bosapp"
            (out / "feed.json").write_text(json.dumps(d))
            self.assertEqual(feedmod.build_feed(out, "o/r", (out / "public.key").read_text())["version"], "9.8.7")
            d["platforms"]["android-aarch64"]["size"] = 1
            (out / "feed.json").write_text(json.dumps(d))
            with self.assertRaises(SystemExit):
                feedmod.build_feed(out, "o/r")

    def test_page_only_refuses_native_inputs_and_other_platforms(self):
        with tempfile.TemporaryDirectory() as tmp:
            dist = Path(tmp) / "dist"
            dist.mkdir()
            (dist / "index.html").write_text("x")
            for extra in (["--cli", "c.exe"], ["--platform", "windows-x86_64"]):
                r = subprocess.run([sys.executable, str(SCRIPT), "--page-only", "--dist", str(dist), "--out", str(Path(tmp) / "o"), *extra], capture_output=True, text=True)
                self.assertNotEqual(r.returncode, 0, extra)


class Workflows(unittest.TestCase):
    def test_every_workflow_file_parses(self):
        files = sorted(WF.glob("*.yml"))
        self.assertIn("os-app-release.yml", [f.name for f in files])
        for f in files:
            d = yaml.safe_load(f.read_text(encoding="utf-8"))
            self.assertIsInstance(d.get("jobs"), dict, f.name)

    def test_os_app_release_is_prerelease_never_latest_and_no_updater_manifest(self):
        text = (WF / "os-app-release.yml").read_text(encoding="utf-8")
        d = yaml.safe_load(text)
        self.assertEqual(d[True]["push"]["tags"], ["os-app-v*"])  # `on` parses as True
        steps = [s.get("run", "") for j in d["jobs"].values() for s in j.get("steps", [])]
        creates = [s for s in steps if "gh release create" in s]
        self.assertEqual(len(creates), 2)  # the os-app-v<version> release and (ARMORY-UPDATE-1) the bos-prerelease channel
        for c in creates:
            self.assertIn("--prerelease", c)
            self.assertNotIn("--draft", c)
            self.assertIn("--latest=false", c)
        self.assertNotIn("--latest=true", text)
        # no step publishes or uploads a Tauri updater manifest, and no other release call exists
        code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))
        self.assertNotIn("latest.json", code)
        self.assertNotIn("--daemon", code)
        self.assertIn("--library", code)
        self.assertIn("bos-crt-check.py", code)
        self.assertIn("TAURI_SIGNING_PRIVATE_KEY", code)
        self.assertNotIn("BOS_APP_SIGNING_KEY", code)
        for bad in ("gh release edit", "make_latest: true", "softprops/action-gh-release", "git push", "bos-stable", "bos-nightly"):
            self.assertNotIn(bad, code)
        # ARMORY-UPDATE-1: the one upload moves the prerelease channel feed only (bos-stable stays by hand)
        uploads = [l.strip() for l in code.splitlines() if "gh release upload" in l]
        self.assertEqual(len(uploads), 1)
        self.assertTrue(uploads[0].startswith('gh release upload "$ch" ') and uploads[0].endswith("feed.json\" --clobber"), uploads[0])
        self.assertIn("ch=bos-prerelease", code)
        for s in d["jobs"].values():
            self.assertNotIn("workflow_call", str(s))

    def test_rust_promote_still_reads_only_its_own_tags(self):
        # the OS app tag prefix must not start the standalone release or promote workflows
        for name in ("rust-release.yml", "rust-promote.yml"):
            text = (WF / name).read_text(encoding="utf-8")
            self.assertNotIn("os-app", text, name)


if __name__ == "__main__":
    unittest.main()
