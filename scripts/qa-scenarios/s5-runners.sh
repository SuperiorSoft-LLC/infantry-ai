#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up; `installDist` built for ik.sh)
# Starts a second daemon in runner mode (own state dir C:/t83b/runner-state, port 18851, link port 18843), adds it through the card,
# and checks the row, the Use runners toggle, `runner check` and `runner test`.
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s5; EMPTY="${QA_DIR:-C:/t83b}/empty"; mkdir -p "$EMPTY"
RUN() { (cd "$EMPTY" && IK_PORT=18851 IK_STATE="${QA_DIR:-C:/t83b}/runner-state" IK_LINK_PORT=18843 "$@"); }
IK="$(pwd)/ik.sh"
(cd "$EMPTY" && bash "$IK" runner detach qa-runner >/dev/null 2>&1 </dev/null)
# a fresh link from the runner (`runner pair` prints it and then keeps running: kill it after a few seconds)
RUN timeout 30 bash "$IK" runner pair --name qa-runner --host 127.0.0.1 --allow-projects '*' --allow-harnesses '*' >"$EMPTY/pair.out" 2>&1 </dev/null
LINK="$(grep -o "infantry://[^' ]*" "$EMPTY/pair.out" | head -1)"
step $S "runner link issued" test -n "$LINK"
wd clickxy "$(at "{Configuration}")" >/dev/null; sleep 2
wd typein "Search settings=>runner" >/dev/null; sleep 2
wd clickxy "$(at "Runners, Applies to")" >/dev/null; sleep 2
step $S "Runners card present (Use runners, Add runner)" has "Add runner"
shot $S-1-before >/dev/null
wd click "Add runner" >/dev/null; sleep 2
typein "Connect@-216,-132" "$LINK" >/dev/null; sleep 1
wd click Connect >/dev/null; sleep 8; shot $S-2-connected >/dev/null
step $S "health rows shown after Connect" has "Runner qa-runner"
wd click Close >/dev/null; sleep 4; shot $S-3-after-close >/dev/null
step $S "runner row is up without reopening Configuration" has "up · 0/1 workers · enabled"
step $S "Use runners toggle follows (daemon has runners.enabled=true)" has "{Use runners} <CHECKED>"
step $S "runner check: every check passed" bash -c "cd '$EMPTY' && bash '$IK' runner check qa-runner 2>&1 | grep -q 'every check passed'"
OUT="$(cd "$EMPTY" && timeout 100 bash "$IK" runner test qa-runner --harness opencode 2>&1 </dev/null)"; echo "$OUT" | head -3
step $S "runner test prints the model's answer, not the server banner" bash -c "! echo '$OUT' | grep -q 'server listening'"
wd clickxy "$(at "Done")" >/dev/null
finish $S
