#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s4
wd clickxy "$(at "{Configuration}")" >/dev/null; sleep 2
wd click Appearance >/dev/null; sleep 1
step $S "Avatar style offers Mascot" has "Mascot (Infantry_Mascot)"
wd clickxy "$(at "Mascot (Infantry_Mascot)")" >/dev/null; sleep 2
shot $S-mascot-selected >/dev/null
step $S "Mascot avatars card has Motion control" has "Motion"
MOTION="$(scrollto "Motion")"; shot $S-motion >/dev/null
step $S "Motion control scrolled into view" test -n "$MOTION"
[ -n "$MOTION" ] && { wd clickxy "$MOTION" >/dev/null; sleep 1; shot $S-motion-clicked >/dev/null; }
wd clickxy "$(at "Done")" >/dev/null
finish $S
