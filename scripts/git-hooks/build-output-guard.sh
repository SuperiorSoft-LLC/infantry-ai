#!/usr/bin/env sh
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
#
# GITIGNORE-1: refuse a commit that ADDS build output: a file under a cargo target dir (target/ or target-*/), or any
# added file over the size limit (INFANTRY_COMMIT_MAX_BYTES, default 5 MB). Deleting such files is always allowed.
# Called by the pre-commit hook. Enable for a repo: git config core.hooksPath scripts/git-hooks
# Bypass for a deliberate large file: INFANTRY_COMMIT_MAX_BYTES=<bytes> git commit ...
set -u
max="${INFANTRY_COMMIT_MAX_BYTES:-5242880}"
bad=""
added="$(git diff --cached --name-only --diff-filter=AM -z | tr '\0' '\n')"
[ -n "$added" ] || exit 0
targets="$(printf '%s\n' "$added" | grep -E '(^|/)target(-[^/]*)?/' || true)"
[ -n "$targets" ] && bad="$bad$(printf '%s\n' "$targets" | head -n 10 | sed 's/^/  build dir: /')
"
big="$(printf '%s\n' "$added" | while IFS= read -r f; do
  [ -n "$f" ] || continue
  s="$(git cat-file -s ":$f" 2>/dev/null || echo 0)"
  [ "$s" -gt "$max" ] && echo "  too big ($s bytes > $max): $f"
done)"
[ -n "$big" ] && bad="$bad$big
"
if [ -n "$bad" ]; then
  echo "build-output-guard: refused, the commit adds build output:" >&2
  printf '%s' "$bad" >&2
  echo "Unstage it (git restore --staged <path>) and ignore the dir in .gitignore." >&2
  exit 1
fi
exit 0
