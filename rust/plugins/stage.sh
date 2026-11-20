#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-WIRE-1: builds every official daemon plugin as a WASM component and lays it out the way the daemon loads it:
#   <out>/official/<Module>/module.json
#   <out>/official/<Module>/<module.json daemon.component>
# Point the daemon at <out> with INFANTRY_PLUGINS_DIR (default: `plugins` beside the daemon exe).
# Usage: rust/plugins/stage.sh <out-dir> [release]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:?usage: stage.sh <out-dir> [release]}"
profile="${2:-debug}"
flag=""; [ "$profile" = "release" ] && flag="--release"
cd "$here/.."
target_dir="$(cargo metadata --format-version 1 --no-deps | python -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])')"
for mj in plugins/official/*/module.json; do
  mod_dir="$(dirname "$mj")"; mod="$(basename "$mod_dir")"
  # PM-ROLE-1: a data-only module (module.json `data`: folders it ships, e.g. a role catalog) is copied as is.
  data="$(python -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1])).get("data") or []))' "$mj")"
  if [ -n "$data" ]; then
    dest="$out/official/$mod"; mkdir -p "$dest"; cp "$mj" "$dest/module.json"
    for d in $data; do case "$d" in *..*|/*) echo "refuse $mod: data folder '$d' is outside the module" >&2; exit 1;; esac; cp -r "$mod_dir/$d" "$dest/"; done
    echo "staged $mod (data: $data) -> $dest"
  fi
  manifest="$mod_dir/daemon/Cargo.toml"; [ -f "$manifest" ] || manifest="$mod_dir/Cargo.toml"
  [ -f "$manifest" ] || { [ -n "$data" ] || echo "skip $mod: no daemon crate"; continue; }
  crate="$(sed -n 's/^name = "\(.*\)"/\1/p' "$manifest" | head -1)"
  component="$(python -c 'import json,sys; print((json.load(open(sys.argv[1])).get("daemon") or {}).get("component",""))' "$mj")"
  [ -n "$component" ] || { echo "skip $mod: module.json names no daemon component"; continue; }
  cargo build -p "$crate" --lib --target wasm32-wasip2 $flag
  wasm="$target_dir/wasm32-wasip2/$profile/${crate//-/_}.wasm"
  dest="$out/official/$mod"
  mkdir -p "$dest/$(dirname "$component")"
  cp "$mj" "$dest/module.json"
  cp "$wasm" "$dest/$component"
  echo "staged $mod -> $dest/$component"
done
