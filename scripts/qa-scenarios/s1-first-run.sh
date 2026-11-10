#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s1
step $S "empty shell shows welcome / no project" has "No project"
step $S "Plugins tab lists plugins" bash -c 'source lib.sh; wd click Plugins >/dev/null; sleep 1; wd dump | grep -q "plugins ·"'
step $S "Browse tab opens (root plugin installs)" bash -c 'source lib.sh; wd click Browse >/dev/null; sleep 2; wd dump | grep -qiE "Install|Browse"'
shot $S-browse >/dev/null
finish $S
