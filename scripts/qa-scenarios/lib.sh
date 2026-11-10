#!/usr/bin/env bash
# Shared helpers. Usage: source lib.sh; PID=<java pid of the window>; wd dump | wd click "<text>" | shot name
QA_WD="${QA_WD:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/windrive/wd.sh}"   # windrive11: windrive10 + drag, scrollto, typein
SHOTS="${SHOTS:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scratch/qa}"
mkdir -p "$SHOTS"
RESULTS=()
wd() { bash "$QA_WD" "$PID" "$@"; }
shot() { wd shot "$(cygpath -w "$SHOTS/$1.png")"; }
# step <scenario> <label> <command...>: PASS when the command exits 0
step() { local s="$1" l="$2"; shift 2; if "$@" >/dev/null 2>&1; then echo "PASS $s: $l"; RESULTS+=("PASS"); else echo "FAIL $s: $l"; RESULTS+=("FAIL"); fi; }
# has <text>: the UI tree contains text
has() { wd dump | grep -qiF -- "$1"; }
finish() { shot "$1-end" >/dev/null; for r in "${RESULTS[@]}"; do [ "$r" = FAIL ] && exit 1; done; exit 0; }
# reset: close any open overlay/modal (Escape) so a scenario starts from the main window
reset() { wd key 27 >/dev/null; sleep 1; wd key 27 >/dev/null; sleep 1; }
# at <substring>: "x,y" centre of the first dump line containing it (handles any window size/position)
at() { wd dump | grep -m1 -F -- "$1" | sed -E 's/.*@([0-9]+),([0-9]+) ([0-9]+)x([0-9]+).*/\1 \2 \3 \4/' | awk '{printf "%d,%d", $1+$3/2, $2+$4/2}'; }
# drag "<from>" "<to>": press on a node (or "x,y"), move, release on another. scrollto "<label>": wheel until on screen,
# prints "x,y" of its centre. typein "<label>[@dx,dy]" "<text>": click it to focus a field, then type.
drag() { wd drag "$1=>$2" | grep -q '^OK'; }
scrollto() { wd scrollto "$1" | sed -n 's/^OK //p' | grep .; }
typein() { wd typein "$1=>$2" | grep -q '^OK'; }
export PID
