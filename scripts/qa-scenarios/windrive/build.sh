#!/usr/bin/env bash
# Builds windrive11.jar (windrive10 + drag/scrollto/typein). Needs the windrive10 jar (default C:/t83/wd/windrive10.jar).
set -e
D="$(cd "$(dirname "$0")" && pwd)"; BASE="${WD10:-C:/t83/wd/windrive10.jar}"
JDK="${JDK:-/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot/bin}"
OUT="$D/build"; rm -rf "$OUT"; mkdir -p "$OUT"
"$JDK/javac" -cp "$BASE" -d "$OUT" "$D/WinDrive11.java"
(cd "$OUT" && "$JDK/jar" xf "$BASE" WinDrive10.class 'WinDrive10$Node.class')
printf 'Agent-Class: WinDrive11\nPremain-Class: WinDrive11\n' > "$OUT/manifest.txt"
"$JDK/jar" cfm "$D/windrive11.jar" "$OUT/manifest.txt" -C "$OUT" .
echo "built $D/windrive11.jar"
