# pi tool bridge (experiment, ticket c62a975a)

Attaches the infantry MCP to **pi** sessions: `deferred` exposure plus six `direct` read/mail tools
(`inbox read message ticket_get board progress`). Destructive tools are `hidden`; mutating ones are `hidden` except for the
`pm` / `architect` roles. **OFF by default.** Nothing about a pi start changes when it is off.

## Switch it on / off
- Project: `infantry project config set <projectId> experiment.piToolBridge on|off` (or the `infantry_project_config_set`
  tool with `experiment` + `value`).
- Override: env `INFANTRY_EXPERIMENT_PI_TOOL_BRIDGE=on|off` (session env, then the daemon's) beats the project setting.
- Takes effect at the NEXT pi session start. No daemon restart; running sessions are untouched.
- On = pi gets `<cwd>/.pi/mcp.json` (marked `_infantryExperiment`) and `--approve`. The stats tag `experiment:piToolBridge`
  shows in the Stats tab (Experimental badge + the A/B table).

## How to remove it completely
1. Delete this package (`experiments/pitoolbridge/`) and its tests (`experiments/pitoolbridge/` under `src/test`).
2. Delete the one registration line in `DaemonMain` (`PiToolBridgeRegistration.register(...)`) and its import.
3. Optional: `project config set <id> experiment.piToolBridge off`; a leftover key is inert. The generic seams
   (`PiLaunchExtras`, `SessionStatsService.addTagger`, `Project.experiments`) are harmless and may stay.
4. Stale `.pi/mcp.json` files containing `_infantryExperiment`: turn the switch off once before removing, and the next pi
   start in each worktree deletes its own file.

`PiToolBridgeIsolationTest` fails if anything outside this package references it besides the registration line.
