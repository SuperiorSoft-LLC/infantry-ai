#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
"""Tests for scripts/bos-crt-check.py: synthetic PE files, a folder and a zip. Run: python scripts/bos-crt-check-test.py"""
import struct, subprocess, sys, tempfile, unittest, zipfile
from pathlib import Path

CHECK = Path(__file__).resolve().parent / "bos-crt-check.py"


def pe(dlls, delay=()):
    """A minimal PE32+ file whose import table (and delay-import table) names `dlls`."""
    va, raw, sec = 0x1000, 0x400, bytearray()
    def table(names, step, name_field):
        base = len(sec)
        sec.extend(b"\0" * (step * (len(names) + 1)))
        for i, n in enumerate(names):
            at = len(sec)
            sec.extend(n.encode() + b"\0")
            struct.pack_into("<I", sec, base + i * step + name_field, va + at)
        return va + base
    imp = table(dlls, 20, 12) if dlls else 0
    dly = table(delay, 32, 4) if delay else 0
    opt = bytearray(240)
    struct.pack_into("<H", opt, 0, 0x20B)
    struct.pack_into("<II", opt, 112 + 8, imp, 40)
    struct.pack_into("<II", opt, 112 + 13 * 8, dly, 64)
    coff = struct.pack("<HHIIIHH", 0x8664, 1, 0, 0, 0, len(opt), 0x22)
    s = b".idata\0\0" + struct.pack("<IIIIIIHHI", len(sec), va, len(sec), raw, 0, 0, 0, 0, 0x40000040)
    head = bytearray(0x40); head[:2] = b"MZ"; struct.pack_into("<I", head, 0x3C, 0x40)
    out = bytes(head) + b"PE\0\0" + coff + bytes(opt) + s
    return out.ljust(raw, b"\0") + bytes(sec)


def run(*paths):
    r = subprocess.run([sys.executable, str(CHECK), *map(str, paths)], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


class T(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp())

    def write(self, name, data):
        p = self.d / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        return p

    def test_static_crt_file_passes(self):
        code, out = run(self.write("a.exe", pe(["KERNEL32.dll", "api-ms-win-crt-heap-l1-1-0.dll", "ucrtbase.dll"])))
        self.assertEqual(code, 0, out)

    def test_vcruntime_import_fails(self):
        code, out = run(self.write("a.exe", pe(["KERNEL32.dll", "VCRUNTIME140.dll"])))
        self.assertEqual(code, 1, out)
        self.assertIn("VCRUNTIME140.dll", out)

    def test_msvcp_and_delay_load_fail(self):
        self.assertEqual(run(self.write("b.dll", pe(["MSVCP140.dll"])))[0], 1)
        self.assertEqual(run(self.write("c.dll", pe(["KERNEL32.dll"], delay=["VCRUNTIME140_1.dll"])))[0], 1)

    def test_zip_and_folder_are_walked_and_non_pe_ignored(self):
        z = self.d / "app.bosapp"
        with zipfile.ZipFile(z, "w") as f:
            f.writestr("lib/ok.dll", pe(["KERNEL32.dll"]))
            f.writestr("plugins/x/bin/helper.exe", pe(["VCRUNTIME140.dll"]))
            f.writestr("shell/dist/index.html", b"<html>")
        code, out = run(z)
        self.assertEqual(code, 1, out)
        self.assertIn("helper.exe", out)
        self.assertIn("2 native file(s), 1 import", out)

    def test_no_native_file_is_an_error(self):
        self.write("readme.txt", b"hi")
        self.assertEqual(run(self.d)[0], 2)


if __name__ == "__main__":
    unittest.main()
