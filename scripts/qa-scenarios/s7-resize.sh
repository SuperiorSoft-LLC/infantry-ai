#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s7
for i in 1 2 3 4; do wd clickxy "$(at "{Maximize}")" >/dev/null; sleep 1; step $S "maximize/restore $i, EDT responsive" bash -c 'source lib.sh; wd ping | grep -q "EDT ok"'; done
shot $S-after >/dev/null
finish $S
