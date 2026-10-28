# Starter module template

A plugin you can copy. It has one tab, one interceptor and one test. The loader skips this folder because its name
starts with `_`. The build compiles and tests it anyway, so the starting point always builds.

## Make it yours

1. Copy the folder to `plugins/community/<Vendor_Module>/`, for example `plugins/community/Acme_Notes/`.
2. In `module.json`, set `name` to that folder name and `jar` to `build/libs/<Vendor_Module>.jar`. Set `author` and
   `license` too.
3. Rename the package `com.example.starter` and set `entry` to the new class name.
4. Build and test it: `./gradlew :plugins:community:Acme_Notes:jar :plugins:community:Acme_Notes:test`.
5. Enable it in Settings > Plugins > Modules (or set `"Acme_Notes": { "enabled": true }` in `plugins/modules.json`),
   then restart.

The full walkthrough is in [../GUIDE.md](../GUIDE.md).

## module.json, field by field

JSON has no comments, so the fields are explained here. Unknown keys are ignored.

| Field | Required | What it means |
|---|---|---|
| `name` | yes | `Vendor_Module`. Each part starts with a capital letter, and there is no other underscore. It must equal the folder name. |
| `version` | yes | The module's semantic version, `MAJOR.MINOR.PATCH`. Other modules' `dependencies` ranges are checked against it. |
| `apiVersion` | no (2) | The plugin API the module is written for. A build with a different API version refuses the module. |
| `author` | community: yes | Who wrote it. It is shown on the module's tabs and in Settings. Without it, a community module is refused. |
| `license` | community: yes | The module's license id (SPDX, or `NOASSERTION`). Without it, a community module is refused. |
| `description` | no | One line on what the module does. |
| `tier` | no | Ignored. The folder decides the tier (`core/` or `community/`), and a claimed tier is only logged. |
| `dependencies` | no | `[{ "id": "Vendor_Module", "version": ">=1.2 <2" }]`. These are the modules this one cannot run without. Each must be installed, enabled and inside its range, or the module is refused. They also load first. `version` defaults to `*` (any version). |
| `sequence` | no | `["Vendor_Module"]`. This only sets load order: if a named module loads, it loads first. A name that is absent or off is ignored. |
| `entry` | community: yes | The class implementing `com.superiorsoftllc.agenthq.ui.plugin.UiPlugin`, with a no-argument constructor. |
| `jar` | community: yes | The path of the built jar, relative to the module folder. It must stay inside the folder. |
| `services` | no | The platform services the module uses: `notifications`, `storage`, `settings`, `events`, `daemon`. A service that is not listed here is `null` at run time. A community module also needs each one under `allow` in modules.json. |
| `routes` | no | The daemon routes the `daemon` service may call, as `METHOD /path` (for example `GET /health`). Any other route is refused. |
| `events` | no | The event names the module may publish or observe. The window publishes `tab.opened`, `session.selected`, `modules.loaded` and `ticket.changed` (see [../README.md](../README.md) for each one's data). |
| `tabs` | no | `[{ "id": "...", "container": "center" or "right" }]`. These are the tabs the module registers. A registered tab that is not declared here is refused. |
| `panels` | no | `[{ "id": "...", "panel": "left" or "right" }]`. These are the side panel sections the module registers with `registrar.panel(PanelProvider(...))`. A section stacks by `order`: on the left, below 100 sits above the agents list and 100 or more below it; on the right, the same split around the tab strip. A registered section that is not declared here is refused. For a tab in the right panel's strip, declare a tab with `"container": "right"` instead. |
| `interceptors` | no | `[{ "target": "<tab id>", "name": "...", "kind": "around" }]`. Each one is a hook on a tab, registered under `name`. `kind` decides what runs: `before` draws only `before` above the content, `after` draws only `after` below it, `around` (the default) runs every part (`title` and `content` wrap the tab's own through `proceed`). An unknown kind is refused. |
| `observers` | no | `[{ "event": "...", "name": "..." }]`. Each one is a listener registered under `name`. |
| `blocks` | no | `[{ "slot": "center.top", "name": "...", "order": 0 }]`. Each one adds content to a slot: `center.top`, `center.bottom`, `right.top` or `right.bottom` (above or below every tab of that container). Any other slot is logged and not drawn. |
| `preferences` | no | `[{ "replace": "<tab or panel section id>", "with": "<your tab or section id>" }]`. Each one replaces another module's tab with your tab, or its panel section with your section. A community module may replace a core one only if modules.json lists its id under `allowOverride`. |

## What is in here

```
module.json                                   the declarations above
build.gradle.kts                              builds build/libs/<name>.jar, named after module.json
src/main/kotlin/com/example/starter/StarterPlugin.kt      the tab and the interceptor
src/test/kotlin/com/example/starter/StarterPluginTest.kt  registers the module and checks the app accepts it
```
