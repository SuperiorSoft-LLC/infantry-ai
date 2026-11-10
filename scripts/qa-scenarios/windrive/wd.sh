#!/usr/bin/env bash
# wd.sh <pid> <cmd> [arg] [index]  -> the driver's answer (windrive11: windrive10 + drag, scrollto, typein).
# Uses the Attach class of windrive10 (C:/t83/wd) and the windrive11.jar that build.sh makes next to this file.
D="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)"
A="${WD10_DIR:-C:/t83/wd}"
[ -f "$D/windrive11.jar" ] || bash "$D/build.sh" >&2 || exit 1
JDK="/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot/bin"
out="$D/out-$$.txt"; rm -f "$out"
"$JDK/java" -cp "$A" Attach "$1" "$D/windrive11.jar" "$out|$2|${3:-}|${4:-}" || { echo "attach failed"; exit 1; }
for i in $(seq 1 90); do [ -s "$out" ] && break; sleep 0.5; done
cat "$out" 2>/dev/null || echo "no answer (driver still running or JVM hung)"
rm -f "$out"
