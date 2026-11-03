#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# Adds or corrects the SPDX header on every tracked source file (.kt .kts .sh .ps1 .mjs).
# Idempotent: a second run changes nothing. The path -> license rule is scripts/license-paths.txt.
# Usage: scripts/license-headers.sh [--check]   (--check changes nothing, exits 1 if a file needs work)
cd "$(dirname "$0")/.." || exit 2
check=0; [ "${1:-}" = "--check" ] && check=1
COPY="Copyright (c) SuperiorSoft LLC"
declare -a P L
while IFS=$'\t' read -r p l; do
  case "$p" in ''|'#'*) continue;; esac
  P+=("$p"); L+=("$l")
done < scripts/license-paths.txt
declare -A count; skipped=0; changed=0
while IFS= read -r f; do
  case "$f" in *.kt|*.kts|*.sh|*.ps1|*.mjs) ;; *) continue;; esac
  case "$f" in */build/*|*/generated/*|.worktrees/*|gradle/wrapper/*) skipped=$((skipped+1)); continue;; esac
  lic=Apache-2.0
  for i in "${!P[@]}"; do case "$f" in "${P[$i]}"*) lic="${L[$i]}"; break;; esac; done
  count[$lic]=$(( ${count[$lic]:-0} + 1 ))
  case "$f" in *.sh|*.ps1) c='#';; *) c='//';; esac
  want="$c SPDX-License-Identifier: $lic"
  eol=$'\n'; head -c 400 "$f" | grep -q $'\r' && eol=$'\r\n'
  if head -n 6 "$f" | tr -d '\r' | grep -qxF "$want" && head -n 6 "$f" | tr -d '\r' | grep -qxF "$c $COPY"; then continue; fi
  changed=$((changed+1))
  [ $check = 1 ] && { echo "needs header: $f"; continue; }
  tmp="$f.lh.tmp"
  # drop any existing SPDX/copyright header lines in the first 6 lines, keep a shebang first
  {
    first=$(head -n 1 "$f" | tr -d '\r')
    start=1
    case "$first" in '#!'*) printf '%s%s' "$first" "$eol"; start=2;; esac
    printf '%s%s%s%s%s' "$want" "$eol" "$c $COPY" "$eol" ""
    tail -n +"$start" "$f" | awk -v lim=$((6-start+1)) 'NR<=lim && (/SPDX-License-Identifier:/ || /Copyright \(c\) SuperiorSoft LLC/) {next} {print}'
  } > "$tmp"
  # awk emits LF; restore CRLF when the file used it
  if [ "$eol" = $'\r\n' ]; then sed -i 's/\r$//; s/$/\r/' "$tmp"; fi
  mv "$tmp" "$f"
done < <(git ls-files)
echo "MPL-2.0: ${count[MPL-2.0]:-0}  Apache-2.0: ${count[Apache-2.0]:-0}  skipped: $skipped  changed: $changed"
[ $check = 1 ] && [ $changed -gt 0 ] && exit 1
exit 0
