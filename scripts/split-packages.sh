#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# UPDATER-1 release gate: no NEW package of ours split over two jars of one class path.
#
# A split package is how a half-updated install breaks: an updater that replaces one jar but leaves the old copy next to
# it makes the JVM load half a package from each. Two copies of one jar (same name once the version / hash suffix is cut)
# always fail. A package shared by two DIFFERENT modules fails unless it is in the baseline file: the module split left
# some (infantryCore / infantryBoard / infantry share package names); the baseline lists them so the gate stops new ones
# while they are cleaned up. Remove a baseline line when its split is gone; never add one to make a release pass.
#
# Usage: scripts/split-packages.sh <lib dir> [baseline file, default scripts/split-packages.baseline]
# Exits 1 and lists every offending package with its jars; 0 otherwise.
set -euo pipefail
dir="${1:?usage: split-packages.sh <lib dir> [baseline]}"
baseline="${2:-$(cd "$(dirname "$0")" && pwd)/split-packages.baseline}"
prefix="com/superiorsoftllc/"
[ -d "$dir" ] || { echo "split-packages: no folder $dir" >&2; exit 2; }
command -v unzip >/dev/null || { echo "split-packages: needs unzip" >&2; exit 2; }

# A jar's module name: `infantryCore-6e1e96be...jar`, `infantryProtocol-1.0.3-82de...jar`, `infantryUiCore-jvm-1.0.3.jar` -> the name before the version/hash.
module() { basename "$1" .jar | sed -E 's/-[0-9a-f]{16,}$//; s/-[0-9]+\.[0-9]+\.[0-9]+.*$//; s/-[0-9a-f]{16,}$//'; }

pairs="$(mktemp)"
trap 'rm -f "$pairs"' EXIT
shopt -s nullglob
jars=("$dir"/*.jar)
[ "${#jars[@]}" -gt 0 ] || { echo "split-packages: no jars in $dir" >&2; exit 2; }
fail=0

dups="$(for jar in "${jars[@]}"; do module "$jar"; done | sort | uniq -d)"
if [ -n "$dups" ]; then
    echo "split-packages: two copies of one module in $dir (a stale jar left by an update?): $(echo "$dups" | paste -sd' ' -)" >&2
    fail=1
fi

for jar in "${jars[@]}"; do
    m="$(module "$jar")"
    unzip -Z1 "$jar" 2>/dev/null | grep -E "^${prefix}.*\.class$" | sed -E 's|/[^/]*$||' | sort -u | sed "s|\$|	$m|" >> "$pairs" || true
done

known=0
while read -r pkg; do
    [ -n "$pkg" ] || continue
    mods="$(awk -F'\t' -v p="$pkg" '$1 == p { print $2 }' "$pairs" | sort -u | paste -sd' ' -)"
    line="${pkg//\//.} $mods"
    if [ -f "$baseline" ] && grep -qxF "$line" "$baseline"; then
        known=$((known + 1))
    else
        echo "split-packages: NEW split package: ${pkg//\//.} in $mods" >&2
        fail=1
    fi
done <<< "$(cut -f1 "$pairs" | sort | uniq -d)"

[ "$fail" -eq 0 ] || exit 1
echo "split-packages: ${#jars[@]} jars in $dir; no new split package ($known known, listed in $(basename "$baseline"))"
