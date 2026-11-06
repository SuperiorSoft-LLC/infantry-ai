#!/usr/bin/env sh
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
#
# MERGE-MARKERS-1: refuse a merge commit whose staged tree still has unresolved conflicts or conflict-marker lines.
# Called by the pre-merge-commit hook (`git merge` making its own commit) and the pre-commit hook (`git commit`
# concluding a merge, cherry-pick, revert or squash). A plain commit is not checked: a file may hold markers on purpose.
# Only lines the commit ADDS are checked, so a file that already carried such a line on the target branch passes.
# On refusal nothing is committed and the merge stays in progress: resolve the files, or `git merge --abort`.
# Enable for a repo: git config core.hooksPath scripts/git-hooks
set -u
mode="${1:-pre-commit}"
gitdir="$(git rev-parse --git-dir)" || exit 1

if [ "$mode" = "pre-commit" ]; then
  in_merge=
  for f in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD SQUASH_MSG; do
    [ -e "$gitdir/$f" ] && in_merge=1
  done
  [ -n "$in_merge" ] || exit 0
fi

unmerged="$(git diff --cached --name-only --diff-filter=U)"
if [ -n "$unmerged" ]; then
  echo "merge-marker-guard: refused, unresolved conflicts in:" >&2
  echo "$unmerged" | sed 's/^/  /' >&2
  echo "Resolve them, or run: git merge --abort" >&2
  exit 1
fi

# Against HEAD (the first parent). Before the first commit there is nothing to compare with: pass.
git rev-parse -q --verify HEAD >/dev/null || exit 0
markers="$(git diff --cached --no-color --no-ext-diff --no-renames -U0 HEAD -- \
  | awk '/^\+\+\+ / { sub(/^\+\+\+ (b\/)?/, ""); file = $0; next }
         /^\+(<<<<<<<|>>>>>>>|\|\|\|\|\|\|\|)( |$)/ { print "  " file ": " substr($0, 2) }')"
if [ -n "$markers" ]; then
  echo "merge-marker-guard: refused, conflict markers in the staged tree:" >&2
  echo "$markers" | head -n 20 >&2
  echo "Resolve them, or run: git merge --abort" >&2
  exit 1
fi
exit 0
