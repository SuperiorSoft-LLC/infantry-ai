#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-UPD-1: lays out what the installer ships beside InfantryAI-Rust.exe (tauri.bundle.conf.json `resources`):
#   bundle-stage/infantry-daemon.exe  infantry.exe  infantry-migrate.exe   (the rust/ workspace binaries)
#   bundle-stage/plugins/official/<Module>/...                             (rust/plugins/stage.sh)
# Profile: INFANTRY_BUNDLE_PROFILE (debug | release), default debug.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
rust="$here/../../rust"
profile="${INFANTRY_BUNDLE_PROFILE:-debug}"
flag=""; [ "$profile" = "release" ] && flag="--release"
out="$here/bundle-stage"
rm -rf "$out"; mkdir -p "$out"
( cd "$rust" && cargo build -p infantry-daemon -p infantry-cli -p infantry-migrate $flag )
target_dir="$(cd "$rust" && cargo metadata --format-version 1 --no-deps | python -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])')"
ext=""; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ext=".exe";; esac
for b in infantry-daemon infantry infantry-migrate; do cp "$target_dir/$profile/$b$ext" "$out/$b$ext"; done
bash "$rust/plugins/stage.sh" "$out/plugins" "$profile"
# The installer config: tauri.bundle.conf.json plus one resources entry per staged plugin folder (a `**` glob would
# flatten the tree). `pnpm installer` passes this generated file to `tauri build --config`.
python - "$here" "$ext" <<'PY'
import json, os, sys
root, ext = sys.argv[1], sys.argv[2]
conf = json.load(open(os.path.join(root, "tauri.bundle.conf.json")))
# The checked-in config names the Windows binaries; macOS and Linux ship them without `.exe`.
res = {k.removesuffix(".exe") + ext if k.endswith(".exe") else k: (v.removesuffix(".exe") + ext if v.endswith(".exe") else v) for k, v in conf["bundle"]["resources"].items()}
conf["bundle"]["resources"] = res
base = os.path.join(root, "bundle-stage", "plugins")
for d, _, files in os.walk(base):
    if files:
        rel = os.path.relpath(d, base).replace(os.sep, "/")
        res[f"bundle-stage/plugins/{rel}/*"] = f"plugins/{rel}/"
json.dump(conf, open(os.path.join(root, "bundle-stage", "tauri.bundle.conf.json"), "w"), indent=2)
PY
echo "staged $profile bundle in $out"
