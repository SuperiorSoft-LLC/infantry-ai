#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
"""BOS-CRT: fail when a native Windows file in a package imports the dynamic Visual C++ runtime.

  scripts/bos-crt-check.py PATH [PATH ...] [--list]

PATH is a .bosapp / .zip, a folder, or one .exe/.dll. Every PE file found (.exe .dll .sys .ocx .node .pyd, plus any file
with an MZ header) has its import table (normal and delay-load) read; an import of VCRUNTIME*, MSVCP*, MSVCR*, VCOMP*,
CONCRT* or VCCORLIB* fails the run (exit 1). A clean Windows has none of those, so such a file stops with
STATUS_DLL_NOT_FOUND. The Universal CRT (api-ms-win-crt-*, ucrtbase.dll) ships with Windows 10+ and is allowed.
The same facts as `dumpbin /dependents`, with no Visual Studio needed. --list prints every import of every file.
"""
import argparse, re, struct, sys, zipfile
from pathlib import Path

BAD = re.compile(r"^(vcruntime|msvcp|msvcr|vcomp|concrt|vccorlib)", re.I)
EXTS = {".exe", ".dll", ".sys", ".ocx", ".node", ".pyd", ".cpl", ".scr"}


def imports(data):
    """DLL names a PE file imports (normal and delay-load), or None when it is not a PE file."""
    if data[:2] != b"MZ" or len(data) < 0x40:
        return None
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        return None
    nsec, = struct.unpack_from("<H", data, pe + 6)
    optsz, = struct.unpack_from("<H", data, pe + 20)
    opt = pe + 24
    magic, = struct.unpack_from("<H", data, opt)
    dd = opt + (96 if magic == 0x10B else 112)
    secs = []
    for i in range(nsec):
        o = opt + optsz + i * 40
        vsz, va, rsz, raw = struct.unpack_from("<IIII", data, o + 8)
        secs.append((va, max(vsz, rsz), raw))

    def off(rva):
        for va, sz, raw in secs:
            if va <= rva < va + sz:
                return raw + rva - va
        return None

    def cstr(rva):
        o = off(rva)
        if o is None:
            return ""
        return data[o:data.index(b"\0", o)].decode("latin-1")

    names = []
    for idx, size, name_field, step in ((1, 20, 12, 20), (13, 32, 4, 32)):
        rva, sz = struct.unpack_from("<II", data, dd + idx * 8)
        o = off(rva) if rva else None
        while o is not None and o + step <= len(data) and any(data[o:o + step]):
            n = struct.unpack_from("<I", data, o + name_field)[0]
            if n:
                names.append(cstr(n))
            o += step
    return names


def files(path):
    p = Path(path)
    if p.is_dir():
        for f in sorted(p.rglob("*")):
            if f.is_file():
                yield str(f.relative_to(p)), f.read_bytes
    elif zipfile.is_zipfile(p):
        z = zipfile.ZipFile(p)
        for n in z.namelist():
            if not n.endswith("/"):
                yield n, (lambda n=n: z.read(n))
    else:
        yield p.name, p.read_bytes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    bad = checked = 0
    for path in a.paths:
        if not Path(path).exists():
            print(f"bos-crt-check: {path} does not exist", file=sys.stderr)
            return 2
        for name, read in files(path):
            if Path(name).suffix.lower() not in EXTS:
                head = read()[:2] if Path(name).suffix.lower() in {"", ".bin"} else b""
                if head != b"MZ":
                    continue
            names = imports(read())
            if names is None:
                continue
            checked += 1
            hit = sorted({n for n in names if BAD.match(n)}, key=str.lower)
            if hit:
                bad += 1
                print(f"FAIL {path}!{name}: imports {', '.join(hit)}")
            elif a.list:
                print(f"ok   {path}!{name}: {', '.join(sorted(set(names), key=str.lower))}")
            else:
                print(f"ok   {path}!{name}")
    print(f"bos-crt-check: {checked} native file(s), {bad} import the dynamic VC++ runtime")
    if checked == 0:
        print("bos-crt-check: no native file found: wrong path?", file=sys.stderr)
        return 2
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
