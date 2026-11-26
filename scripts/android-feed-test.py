#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
"""PHONE-UPD-1 tests: python scripts/android-feed-test.py  (needs pyyaml and cryptography)."""
import importlib.util, hashlib, subprocess, sys, tempfile, unittest
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "scripts/android-feed.py"


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, REPO / "scripts" / file)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


bp, af = load("bos_package", "bos-package.py"), load("android_feed", "android-feed.py")


def signed_apk(tmp, name="InfantryAI_0.8.5_arm64.apk"):
    sk, kid, pk = bp.throwaway_key(tmp)
    apk = tmp / name
    apk.write_bytes(b"apk-bytes")
    Path(str(apk) + ".sig").write_text(bp.sign_throwaway(sk, kid, apk.read_bytes()))
    return apk, bp.pub_text(kid, pk)


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


class Feed(unittest.TestCase):
    def test_tauri_sign_takes_a_relative_apk_from_another_cwd(self):
        # AND-FEED-FIX-1: the signer runs from app/, so the APK path handed to it must be absolute.
        import base64, os
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            sk, kid, pk = bp.throwaway_key(t)
            (t / "dist").mkdir()
            (t / "dist/InfantryAI_0.8.5_arm64.apk").write_bytes(b"apk-bytes")
            seen = {}

            def fake_signer(cmd, cwd=None, **kw):  # behaves like `tauri signer sign <file>`: reads <file> against cwd
                f = Path(cwd) / cmd[-1]
                seen["arg"] = cmd[-1]
                if not f.is_file():
                    return subprocess.CompletedProcess(cmd, 1, "", f"failed to open data file {cmd[-1]}")
                Path(str(f) + ".sig").write_text(base64.b64encode(bp.sign_throwaway(sk, kid, f.read_bytes()).encode()).decode())
                return subprocess.CompletedProcess(cmd, 0, "", "")

            old = (os.getcwd(), bp.subprocess.run, bp.shutil.which, dict(os.environ))
            os.chdir(t)
            bp.subprocess.run, bp.shutil.which = fake_signer, lambda n: "pnpm"
            os.environ.update(TAURI_SIGNING_PRIVATE_KEY="x", TAURI_SIGNING_PRIVATE_KEY_PASSWORD="")
            af.load_packager = lambda: bp
            try:
                f = af.build_feed("dist/InfantryAI_0.8.5_arm64.apk", "o/r", pubkey=bp.pub_text(kid, pk), tauri_sign=True)
            finally:
                os.chdir(old[0]); bp.subprocess.run, bp.shutil.which = old[1], old[2]
                os.environ.clear(); os.environ.update(old[3])
            self.assertTrue(Path(seen["arg"]).is_absolute())
            self.assertEqual(f["version"], "0.8.5")
            self.assertTrue((t / "dist/InfantryAI_0.8.5_arm64.apk.sig").is_file())

    def test_the_feed_names_the_tag_download_and_agrees_with_the_apk(self):
        with tempfile.TemporaryDirectory() as t:
            apk, pub = signed_apk(Path(t))
            f = af.build_feed(apk, "o/r", pubkey=pub)
            self.assertEqual(f["version"], "0.8.5")
            p = f["platforms"]["android-aarch64"]
            self.assertEqual(p["url"], "https://github.com/o/r/releases/download/android-v0.8.5/InfantryAI_0.8.5_arm64.apk")
            self.assertEqual(p["sha256"], hashlib.sha256(b"apk-bytes").hexdigest())
            self.assertEqual(p["size"], 9)
            self.assertIn("untrusted comment", p["signature"])

    def test_a_wrong_key_a_tampered_apk_a_missing_sig_or_latest_json_is_refused(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            apk, pub = signed_apk(t)
            other = bp.pub_text(b"\x01" * 8, b"\x02" * 32)
            self.assertNotEqual(run("--apk", str(apk), "--repo", "o/r", "--out", str(t / "a.json"), "--pubkey", other).returncode, 0)
            self.assertEqual(run("--apk", str(apk), "--repo", "o/r", "--out", str(t / "android.json"), "--pubkey", pub).returncode, 0)
            self.assertNotEqual(run("--apk", str(apk), "--repo", "o/r", "--out", str(t / "latest.json"), "--pubkey", pub).returncode, 0)
            apk.write_bytes(b"tampered")
            self.assertNotEqual(run("--apk", str(apk), "--repo", "o/r", "--out", str(t / "b.json"), "--pubkey", pub).returncode, 0)
            Path(str(apk) + ".sig").unlink()
            self.assertNotEqual(run("--apk", str(apk), "--repo", "o/r", "--out", str(t / "c.json"), "--pubkey", pub).returncode, 0)
            bad = t / "app.apk"
            bad.write_bytes(b"x")
            self.assertNotEqual(run("--apk", str(bad), "--repo", "o/r", "--out", str(t / "d.json"), "--pubkey", pub).returncode, 0)


class Workflow(unittest.TestCase):
    def test_the_tag_run_replaces_android_json_on_android_stable_and_never_writes_latest_json(self):
        text = (REPO / ".github/workflows/android-release.yml").read_text(encoding="utf-8")
        steps = yaml.safe_load(text)["jobs"]["android"]["steps"]
        run_text = "\n".join(s.get("run", "") for s in steps)
        self.assertIn("gh release upload android-stable dist/android.json --clobber", run_text)
        self.assertIn("gh release create android-stable --prerelease --latest=false", run_text)
        self.assertNotIn("latest.json", run_text)
        self.assertIn("TAURI_SIGNING_PRIVATE_KEY", steps[0]["run"])


if __name__ == "__main__":
    unittest.main()
