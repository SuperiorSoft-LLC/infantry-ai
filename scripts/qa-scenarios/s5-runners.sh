#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s5
wd clickxy "$(at "{Configuration}")" >/dev/null; sleep 2
wd click Plugins >/dev/null; sleep 1
step $S "Runners card present in Settings > Plugins" has "Infantry_Runners"
shot $S-runners >/dev/null
RUN="$(scrollto "Infantry_Runners")"
step $S "Runners card scrolled into view" test -n "$RUN"
echo "NOTE $S: adding a runner needs a reachable runner endpoint; typein \"<label>@dx,dy\" \"<text>\" now reaches its fields"
wd clickxy "$(at "Done")" >/dev/null
finish $S
