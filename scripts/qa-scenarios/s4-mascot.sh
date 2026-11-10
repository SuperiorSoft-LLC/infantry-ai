#!/usr/bin/env bash
# Re-runnable: PID=$(bash pid.sh) bash <this file>   (window from launch.sh must be up; Infantry_Mascot's jar must be built:
# ./gradlew :plugins:official:Infantry_Mascot:jvmJar, else the desktop refuses it at load and Appearance offers only Portrait)
cd "$(dirname "$0")"; PID="${PID:-$(bash pid.sh)}"; source lib.sh; reset
S=s4
# Device-scope settings (avatar style, Motion) live in the Windows user prefs, shared with every other window (UI-PREFS-ISOLATION-1).
UIPREFS='HKCU\Software\JavaSoft\Prefs\com\superiorsoftllc\infantry\ui'
style() { reg query "$UIPREFS" | grep -i 'Avatar/Style' | grep -qi "\"$1\""; }
# open Configuration and select a tab. The dialog remembers its last tab and the strip scrolls, so scroll it back first; Appearance
# is then half clipped at the left edge: click its right part, not its centre.
open_tab() {
  wd clickxy "$(at "{Configuration}")" >/dev/null; sleep 2; wheel -10 "$(at "[page tab] General")"; sleep 1
  if [ "$1" = Appearance ]; then wd clickxy "315,$(at "[page tab] Appearance" | cut -d, -f2)" >/dev/null
  else wd clickxy "$(at "[page tab] $1")" >/dev/null; fi; sleep 1
}
open_tab Appearance
wheel 20 "700,560"; sleep 1   # Avatar style is below the fold inside the dialog's own scroll area (scrollto cannot see that clip)
step $S "Avatar style offers Mascot" has "Mascot (Infantry_Mascot)"
wd clickxy "$(at "check box] Portrait")" >/dev/null; sleep 2; shot $S-1-portrait >/dev/null
step $S "Portrait stored as the avatar style" style portrait
wd clickxy "$(at "check box] Mascot")" >/dev/null; sleep 2; shot $S-2-mascot >/dev/null
step $S "Mascot stored as the avatar style" style mascot
wd clickxy "$(at "Done")" >/dev/null; sleep 2; shot $S-3-header-mascot >/dev/null   # the Assistant chip in the title bar draws the mascot
# Motion: Settings > Plugins > Mascot avatars. The value on first view is whatever an earlier run left in the shared prefs; the scenario
# reports it and does not assume the manifest default (static).
open_tab Plugins
scrollto "Mascot avatars" >/dev/null; wheel 4 "700,560"; sleep 1; shot $S-4-motion >/dev/null
step $S "Mascot Motion control reachable (Static / Focus only / Everywhere)" has "check box] Everywhere"
echo "INFO $S: Motion stored: $(reg query "$UIPREFS" | grep -i 'mascot.motion' | head -1 | sed -E 's/.*REG_SZ *//')"
wd clickxy "$(at "Done")" >/dev/null
finish $S
