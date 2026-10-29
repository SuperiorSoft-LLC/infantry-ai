# POLICY-1: named actions and the policy layer (ticket b6446a82)

Goal (owner): turn features off **by data**, never by a hand check in each pane, and turn the demo on and off **with a flick of
one switch**. The demo is the compass: it is now one *policy preset*, not a code path.

Layering rule (owner): the mechanism is core and SDK; the values are a plugin.

| piece | where | what |
|---|---|---|
| action / preset / matcher model | SDK `agenthqPlugins` `plugin/api/ActionPolicy.kt` | `ActionKind`, `ActionDeclaration` (module.json `actions`), `PolicyPreset` (a preset file), `PolicyWire` (what a client is sent), `ActionPolicyMatcher` (the ONE matcher both sides run) |
| frame -> action catalog | daemon core `policy/ActionCatalog.kt` | every client frame the daemon dispatches -> one action id + kind (+ scope) |
| presets + the switch | daemon core `policy/PolicyRuntime.kt`, `policy/PolicyProvider.kt` | load presets by id from data, resolve `extends`, pick one by ONE setting, enforce, broadcast |
| UI gate | UI core `shell/ActionGate.kt` | `rememberGate(id)`, `Gated(id) { gate -> }`, `ActionButton(id, ...)`, `LinkContext.canWrite` (generic write) |
| Settings | UI core `ConfigPolicySection` (trust section, General tab) | owner-only preset selector + the read-only list of what is disabled and why |
| demo preset (values) | plugin `plugins/demo/Demo_Lock/policies/demo.json` | which actions are off, the reason, the seats, the turn limit, the notice, the refusal text |

Core names no preset beyond `production` (the built-in empty one). `NoDemoInCoreGuardTest` (UI core) and
`NoPresetInCoreGuardTest` (daemon) fail when core/SDK source names the demo preset or its values.

## 1. Named actions

An action is `area.thing.verb` (e.g. `board.ticket.move`), with a kind: `read | write | destructive | spawn`.

* A plugin declares the actions its controls perform in **module.json** (new optional key, additive; older hosts ignore it):

  ```json
  "actions": [
    { "id": "board.ticket.move", "label": "Move a ticket", "kind": "write", "frames": ["ticket_move", "ticket_reorder"] }
  ]
  ```

  `frames` names the daemon frames the control sends. `PolicyActionParityTest` checks every declared frame maps (in the daemon
  catalog) to the same action id, so a declaration cannot drift from what the daemon refuses.
* Code gates through ONE helper: `Gated("board.ticket.move") { gate -> Button(enabled = gate.enabled, ...) }`, or
  `ActionButton("board.ticket.move", "Move", onClick)`, or `rememberGate(id).enabled` for a modifier. A gate is enabled when
  the link is live, the daemon's policy allows the action, and the nearest `WritePolicy` (kept, additive) allows writes. While
  the policy refuses it the gate carries the policy's reason (hover tooltip via `WritePolicyTooltip`, `gate.label(normal)`).
* **Undeclared actions follow their kind.** `link.canWrite` / `connection.canWrite` (DEMO-MERGE-1) is now "the generic write
  action": it is refused by any rule that matches an undeclared write (`*`, `kind:write`). So an old or community plugin that
  only checks `canWrite` is still gated by a policy that turns writes off.
* **What cannot be auto-gated** (documented, not hacked): a control that checks neither `canWrite` nor a gate (a raw
  `Modifier.clickable` / `Button` that sends a frame). The daemon still refuses its frame (the daemon gate is total: an
  unmapped frame is refused under any non-production policy), so the worst case is an enabled control whose click gets an
  error; the UI cannot dim what never asks.

## 2. Policy data

A preset is a JSON file (`PolicyPreset`):

```json
{
  "id": "read-only", "label": "Read only", "extends": [],
  "disable": ["*"], "allow": ["kind:read"],
  "reason": "Read-only deployment",
  "refusal": "policy: '{frame}' is disabled ({reason})",
  "selectWhenEnv": null,
  "restricted": false, "notices": [], "ready": {}, "notice": null,
  "allowedModels": [], "allowedRoles": [],
  "seats": null
}
```

* Rules: `*` (everything), `kind:<kind>`, `area.*` (prefix), or an exact id. An action is refused when a `disable` rule matches
  and no `allow` rule does (allow = the exceptions). Undeclared generic write = id `""`, kind `write`.
* `extends` layers presets: lists are unioned parent-first, scalars are the child's when set.
* `seats` (optional): the seated lockdown mechanism (DEMO-LOCK's): a project directory + name, role seats
  (`role/provider/model`), warm-up, a per-IP turn limit, a public visitor token env name. Present = the daemon seeds the
  project and seats, scopes prompts/resume/replay to the seats, mail to between seats, project reads to the seat project.
* `allowedModels` / `allowedRoles`: used when the `limits.models.allowed` / `limits.roles.allowed` settings (and their env)
  are empty.
* Presets come from **providers** (`PolicyProvider`, a one-method interface, so DAEMON-PLUGINS-1 can register more):
  built-in `production` (empty) and `DirectoryPolicyProvider`, which reads `*.json` from
  `AGENTHQ_POLICY_DIRS` (path list), and every `<plugins root>/<folder>/<Module>/policies/` (the dev run's plugins root is the
  repo's `plugins/`, so `plugins/demo/Demo_Lock/policies/demo.json` is found). The docker image copies the demo plugin's
  `policies/` to `/opt/agenthq/policies` and sets `AGENTHQ_POLICY_DIRS`.

### The ONE switch

Selection, first that applies:

1. `AGENTHQ_POLICY=<preset id>` (env; **pinned**: the Settings selector is read-only, no token can change it).
2. Any preset whose `selectWhenEnv` env var is truthy (pinned). The demo preset says `"selectWhenEnv": "AGENTHQ_DEMO_LOCK"`,
   so **`AGENTHQ_DEMO_LOCK=1` is shorthand for selecting the `demo` preset**, exactly as before.
3. The stored setting `<state dir>/policy.preset` (one line: the preset id), written by the owner from
   **Configuration > General > Policy** (the `policy_set` frame, owner credential only, refused while pinned).
4. Otherwise `production` (nothing disabled, nothing added to `ready`).

A pinned id that no provider has resolves to **deny-everything** (fail closed); `AGENTHQ_DEMO_LOCK=1` without the demo preset
installed also keeps SEC-FIX-1's `FrameGate` read-only list.

Wire: after `ready` (and to every client on each change) the daemon sends a `policy` frame, JSON `PolicyWire`: the active
preset id/label, `pinned`, `canSwitch` (this connection is the owner and it is not pinned), the selectable presets, the
resolved rules, reason, notice, `restricted`, `notices`, `ready` extras. The UI folds `restricted` / `notices` / `ready` into
`ConnectionInfo.readyFields`, so the modules that read ready fields (Demo_Lock, Demo_Banner) follow a runtime flip too. `ready`
still carries the same keys for older clients.

## 3. Daemon enforcement from the same data

The WebSocket loop asks `PolicyRuntime.frameRefusal(envelope, active)` after `FrameGate` (owner-only, SEC-FIX-1 fallback):
frame -> `ActionCatalog` action -> `ActionPolicyMatcher` with the active preset. The UI's gate runs the same matcher on the same
action id, so the dimming and the refusal cannot disagree. An unknown frame under a non-production preset is refused
(default deny). With `seats` the seated scoping (`DemoLock`'s, now values-free) runs next. `policy_set` / `policy_get` are
always reachable by the owner (the escape hatch: the switch itself is never policy-gated; pinning is how a deployment locks it).

`ActionCatalogCoverageTest` reads the daemon's dispatch `when` and fails when a dispatched frame is not in the catalog, and
when a write frame is in neither the catalog nor `FrameGate.OWNER_ONLY`.

## 4. The demo on it

* The demo preset `plugins/demo/Demo_Lock/policies/demo.json` holds every demo value: the allowed actions (the former
  `DemoLock.READ_FRAMES` / `PROJECT_READ_FRAMES` as action ids), the reason "Disabled in the demo", the refusal text
  (`demo: '{frame}' is disabled in this demo`, so `docker/demo-lock-attack.mjs` reads the same), the seats, project dir/name,
  turn limit, notice, `ready` `demo=lock`, `restricted`, `notices: [demo_limit]`. The `AGENTHQ_DEMO_*` env vars still override
  the preset's seat values (deployment API, docs/docker-deploy.md).
* `DemoLock` (daemon core) keeps the seated mechanism and no demo value; it is built from the active preset's `seats`.
* The UI's demo dimming is core's gate following the daemon's policy, in every build. `plugins/demo/Demo_Lock` no longer
  provides a `WritePolicy`; it keeps the notice (`LocalDemoLock`) for `Demo_Banner`.
* `DemoLockTest` loads the demo preset file and keeps its assertions; `DemoPresetParityTest` proves the preset allows exactly
  the frames the old hand lists allowed.

## 5. One-switch proof

`PolicyOneSwitchRealDaemonUiTest` (agentHqUi): real in-process daemon (temp state dir, port 0, owner token), the real App over
the real WebSocket, plugins root = a temp copy of the demo preset. Boots `production`: New ticket / New agent enabled, a
`ticket_save` is answered. Flips ONLY the setting to `demo` from the Settings Policy card (owner): the same controls dim with
"Disabled in the demo" and the daemon refuses `ticket_save` / `kill`. Flips back to `production`: enabled and answered again.
No rebuild, no plugin edited.

## 6. Settings and plugins

Configuration > General > **Policy** (core trust section, locked): the active preset, the selector (owner and not pinned), why
it is pinned, and the read-only list of disabled rules with the reason. Community plugins declare `actions` the same way and
are gated by the same matcher; see plugins/README.md and docs/PLUGIN-OVERRIDES.md.
