#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# SDK-PUB-4: Central is immutable and fails the WHOLE bundle when one component version already exists. This rewrites
# build/central-bundle.zip without the components (group/artifact/version) whose .pom is already on Central, and prints one
# line per skipped and per kept component. Nothing is deleted or overwritten on Central; it only reads.
# Usage: sdk-central-prune.sh [bundle.zip]    Env: CENTRAL_CHECK_BASE (default https://repo1.maven.org/maven2),
#   CENTRAL_EXISTING_FILE (a file of "group/path/artifact/version" lines treated as present; skips the network, for dry runs)
# Exits 1 when nothing is left to upload (the caller should not upload), 0 otherwise.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
bundle="${1:-$root/build/central-bundle.zip}"
[ -f "$bundle" ] || { echo "::error::sdk-central-prune: $bundle not found" >&2; exit 1; }
CENTRAL_CHECK_BASE="${CENTRAL_CHECK_BASE:-https://repo1.maven.org/maven2}" \
CENTRAL_EXISTING_FILE="${CENTRAL_EXISTING_FILE:-}" \
"${PYTHON:-python3}" -I - "$bundle" <<'PY'
import os, sys, zipfile, shutil, urllib.request, urllib.error
bundle = sys.argv[1]
base = os.environ["CENTRAL_CHECK_BASE"].rstrip("/")
known = None
if os.environ.get("CENTRAL_EXISTING_FILE"):
    known = {l.strip() for l in open(os.environ["CENTRAL_EXISTING_FILE"]) if l.strip()}
def exists(comp):
    if known is not None:
        return comp in known
    art, ver = comp.split("/")[-2:]
    req = urllib.request.Request(f"{base}/{comp}/{art}-{ver}.pom", method="HEAD")
    try:
        urllib.request.urlopen(req, timeout=30); return True
    except urllib.error.HTTPError as e:
        if e.code == 404: return False
        raise SystemExit(f"::error::sdk-central-prune: cannot tell if {comp} is on Central (HTTP {e.code})")
    except Exception as e:
        raise SystemExit(f"::error::sdk-central-prune: cannot reach Central for {comp}: {e}")
with zipfile.ZipFile(bundle) as z:
    names = [n for n in z.namelist() if not n.endswith("/")]
    comps = sorted({n.rsplit("/", 1)[0] for n in names})
    skip = {c for c in comps if exists(c)}
    for c in comps:
        print(("skip (already on Central): " if c in skip else "upload: ") + c)
    keep = [n for n in names if n.rsplit("/", 1)[0] not in skip]
    if skip and keep:
        tmp = bundle + ".pruned"
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as out:
            for n in keep: out.writestr(n, z.read(n))
        z.close(); shutil.move(tmp, bundle)
print(f"sdk-central-prune: {len(comps)-len(skip)} to upload, {len(skip)} already published")
sys.exit(0 if len(comps) - len(skip) else 1)
PY
