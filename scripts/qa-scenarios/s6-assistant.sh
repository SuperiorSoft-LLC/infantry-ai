#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s6
wd clickxy "$(at "{Assistant, Assistant}")" >/dev/null; sleep 2
shot $S-open >/dev/null
step $S "assistant popup opens" bash -c 'source lib.sh; wd dump | grep -qiE "Ask|Assistant"'
wd clickxy "$(at "Message ")" >/dev/null; wd type "What is this app?" >/dev/null
wd key 10 >/dev/null; sleep 5
shot $S-asked >/dev/null
step $S "question shown in transcript" has "What is this app?"
finish $S
