#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s2
REPO="${QA_REPO:-C:/t83/qarepo}"
[ -d "$REPO/.git" ] || { mkdir -p "$REPO"; (cd "$REPO" && git init -q && git commit -q --allow-empty -m init); }
wd click "New project" >/dev/null; sleep 1
wd clickxy "700,400" >/dev/null; wd type "QA Proj $$" >/dev/null
wd clickxy "650,497" >/dev/null; wd type "$(cygpath -w "$REPO")" >/dev/null
wd click Create >/dev/null; sleep 2
step $S "project created and selected" has "QA Proj"
wd clickxy "$(at "▦, Board")" >/dev/null; sleep 3   # label click on Board misses; use the button position
shot $S-board >/dev/null
step $S "board opened (columns visible)" has "TO DO"

wd click "+ New ticket" >/dev/null; sleep 1
wd clickxy "700,259" >/dev/null; wd type "QA ticket $$" >/dev/null
wd click "Create ticket" >/dev/null; sleep 2
step $S "ticket created in TO DO" has "Select QA ticket"
# Moving a card across columns is drag-only; windrive10 has no drag command (see README, ticket filed).
echo "BLOCKED $S: move ticket across columns (driver has no drag)"
finish $S
