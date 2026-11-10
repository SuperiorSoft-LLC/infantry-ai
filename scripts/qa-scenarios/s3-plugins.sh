#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s3
on() { wd dump | grep -oE "[0-9]+ on" | head -1 | grep -oE "[0-9]+"; }
wd click Plugins >/dev/null; sleep 1; wd click Installed >/dev/null; sleep 1
step $S "list shows plugins" has "plugins ·"
N0=$(on); echo "on before: $N0"
wd clickxy "$(at "{Infantry Appearance}")" >/dev/null; sleep 5
N1=$(on); echo "on after disable: $N1"
step $S "disable lowers the on-count" test "$N1" -lt "$N0"
wd clickxy "$(at "{Infantry Appearance}")" >/dev/null; sleep 5
step $S "enable restores the on-count" test "$(on)" -eq "$N0"
step $S "Update/Roll back/Remove buttons present" has "Remove"
step $S "no unload-error notice" bash -c '! wd dump | grep -q "did not unload cleanly"'
shot $S-list >/dev/null
echo "BLOCKED $S: refresh/install/remove of a plugin changes the shared catalog; run manually (see README)"
finish $S
