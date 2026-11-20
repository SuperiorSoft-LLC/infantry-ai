#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-WIRE-1: builds every official daemon plugin as a WASM component and lays it out the way the daemon loads it:
#   <out>/official/<Module>/module.json
#   <out>/official/<Module>/<module.json daemon.component>
#   <out>/official/<Module>/ui/dist/...   (WEBUI-1: a web UI half, built by app/packages/ui-plugin-api; needs
#                                          app/node_modules, i.e. `pnpm install` in app/)
# Point the daemon at <out> with INFANTRY_PLUGINS_DIR (default: `plugins` beside the daemon exe).
# Usage: rust/plugins/stage.sh <out-dir> [release]
# INFANTRY_STAGE_TEST=1 also stages the test plugins (rust/plugins/test, such as Acme_UiFault), laid out as official ones
# because the daemon loads no other tier, for a preview or a test run. Never set it for a build that ships.
set -euo pipefail
shopt -s nullglob
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:?usage: stage.sh <out-dir> [release]}"
profile="${2:-debug}"
flag=""; [ "$profile" = "release" ] && flag="--release"
cd "$here/.."
target_dir="$(cargo metadata --format-version 1 --no-deps | python -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])')"
manifests=(plugins/official/*/module.json)
if [ "${INFANTRY_STAGE_TEST:-}" = "1" ]; then manifests+=(plugins/test/*/module.json); fi

# WEBUI-1: the web UI halves are built first, in one run, so each module below copies its ui/dist.
ui_dirs=()
for mj in "${manifests[@]}"; do
  if [ -f "$(dirname "$mj")/ui/src/index.tsx" ]; then ui_dirs+=("$(dirname "$mj")"); fi
done
if [ ${#ui_dirs[@]} -gt 0 ]; then
  [ -d "$here/../../app/node_modules" ] || { echo "refuse: the web UI halves need app/node_modules (run pnpm install in app/)" >&2; exit 1; }
  node "$here/../../app/packages/ui-plugin-api/build/build-plugins.mjs" "${ui_dirs[@]}"
fi

for mj in "${manifests[@]}"; do
  mod_dir="$(dirname "$mj")"; mod="$(basename "$mod_dir")"
  dest="$out/official/$mod"
  staged=""
  # PM-ROLE-1: a data-only module (module.json `data`: folders it ships, e.g. a role catalog) is copied as is.
  data="$(python -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1])).get("data") or []))' "$mj")"
  if [ -n "$data" ]; then
    mkdir -p "$dest"; cp "$mj" "$dest/module.json"
    for d in $data; do case "$d" in *..*|/*) echo "refuse $mod: data folder '$d' is outside the module" >&2; exit 1;; esac; cp -r "$mod_dir/$d" "$dest/"; done
    echo "staged $mod (data: $data) -> $dest"
    staged=1
  fi
  # WEBUI-1: a web UI half (module.json `ui`) ships its built ui/dist, which the daemon serves at /ui-plugins/<Module>/.
  if python -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1])).get("ui") else 1)' "$mj"; then
    [ -d "$mod_dir/ui/dist" ] || { echo "refuse $mod: module.json has a ui half but $mod_dir/ui/dist was not built" >&2; exit 1; }
    mkdir -p "$dest/ui"; cp "$mj" "$dest/module.json"; rm -rf "$dest/ui/dist"; cp -r "$mod_dir/ui/dist" "$dest/ui/dist"
    echo "staged $mod (web UI) -> $dest/ui/dist"
    staged=1
  fi
  manifest="$mod_dir/daemon/Cargo.toml"; [ -f "$manifest" ] || manifest="$mod_dir/Cargo.toml"
  [ -f "$manifest" ] || { [ -n "$staged" ] || echo "skip $mod: no daemon crate"; continue; }
  crate="$(sed -n 's/^name = "\(.*\)"/\1/p' "$manifest" | head -1)"
  component="$(python -c 'import json,sys; print((json.load(open(sys.argv[1])).get("daemon") or {}).get("component",""))' "$mj")"
  [ -n "$component" ] || { echo "skip $mod: module.json names no daemon component"; continue; }
  cargo build -p "$crate" --lib --target wasm32-wasip2 $flag
  wasm="$target_dir/wasm32-wasip2/$profile/${crate//-/_}.wasm"
  mkdir -p "$dest/$(dirname "$component")"
  cp "$mj" "$dest/module.json"
  cp "$wasm" "$dest/$component"
  echo "staged $mod -> $dest/$component"
done
