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
typein "Assignee@200,-140" "QA ticket $$" >/dev/null   # Title field is not in the a11y tree: anchor on the Assignee label below it
wheel 10 "$(at "Assignee")"; sleep 1   # the Create button sits under the dialog clip until the form is scrolled
wd click "Create ticket" >/dev/null; sleep 2
step $S "ticket created in TO DO" has "Select QA ticket"
# Moving a card across columns is drag-only: drag the card handle onto the next column header.
CARD="Drag QA ticket $$"
for COL in "IN PROGRESS" "DONE"; do
  step $S "drag ticket to $COL" drag "$CARD" "$COL"; sleep 2
done
step $S "ticket moved (card still on the board)" has "QA ticket $$"
finish $S
