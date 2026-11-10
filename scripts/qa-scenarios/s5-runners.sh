#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s5
wd clickxy "$(at "{Configuration}")" >/dev/null; sleep 2
wd click Plugins >/dev/null; sleep 1
step $S "Runners card present in Settings > Plugins" has "Infantry_Runners"
shot $S-runners >/dev/null
echo "BLOCKED $S: add-runner needs a reachable runner endpoint and a scroll to the card; check 6ee8342c manually"
wd clickxy "$(at "Done")" >/dev/null
finish $S
