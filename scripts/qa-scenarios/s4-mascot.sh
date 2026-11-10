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
echo "BLOCKED $S: Motion options sit far below the fold (y>7000); driver has no scroll-into-view, so Motion switching is not driven"
wd clickxy "$(at "Done")" >/dev/null
finish $S
