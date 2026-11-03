# Recipe: add a UI panel plugin

Goal: a new tab in the center area and a section in the left panel, from a plugin that is not part of the core.

The starting point is `plugins/_template`, which builds with the rest of the repository, so a copy of it is known
to compile.

## Files to touch

```
plugins/community/Acme_Counter/module.json
plugins/community/Acme_Counter/build.gradle.kts
plugins/community/Acme_Counter/src/main/kotlin/com/acme/counter/CounterPlugin.kt
plugins/community/Acme_Counter/src/test/kotlin/com/acme/counter/CounterPluginTest.kt
plugins/modules.json            (existing file: enable and grant the module)
```

Nothing in `settings.gradle.kts` changes: it scans `plugins/community/*` for a `build.gradle.kts`.

## Steps

1. Copy the folder and rename it. In PowerShell: `Copy-Item -Recurse plugins/_template plugins/community/Acme_Counter`.
   Delete the copied `daemon/` folder if you want a UI-only plugin (and the `daemon` object in `module.json`).
2. Edit `module.json` (reference: [Make a plugin](../make-a-plugin.md), section 3):

```json
{
  "name": "Acme_Counter",
  "version": "1.0.0",
  "apiVersion": 2,
  "author": "Acme",
  "license": "Apache-2.0",
  "description": "A tab and a left-panel section that count agents.",
  "dependencies": [],
  "sequence": [],
  "entry": "com.acme.counter.CounterPlugin",
  "jar": "build/libs/Acme_Counter.jar",
  "services": [],
  "tabs": [{ "id": "acme.counter", "container": "center" }],
  "panels": [{ "id": "acme.counter.side", "panel": "left" }]
}
```

The template's `dependencies` entry (`Infantry_Git`) is only there to show the field; remove it unless you need that
plugin. A community module needs `author` and `license` or it is refused.

3. Rename the package and class. Your plugin class implements `UiPlugin`; everything it registers must be declared in
   `module.json` under the same id, or the host refuses it:

```kotlin
package com.acme.counter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.plugin.PanelProvider
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

class CounterPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(
            TabProvider(
                id = "acme.counter", container = TabContainers.CENTER, order = 100,
                title = { "Counter" },
                icon = tabIcon("acme-counter", { circle(12f, 12f, 8f) }),
                testTag = "center-tab-ACME_COUNTER",
            ) { CounterTab(it) },
        )
        registrar.panel(
            PanelProvider(id = "acme.counter.side", panel = Panels.LEFT, order = 200, testTag = "acme-counter-side") {
                Text("Agents: ${it.state.agents.size}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            },
        )
    }

    @Composable
    private fun CounterTab(context: TabContext) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Agents: ${context.state.agents.size}")
        }
    }
}
```

   This is the template's `StarterPlugin.kt` (`plugins/_template/src/main/kotlin/com/example/starter/StarterPlugin.kt`)
   with the interceptor and the notification removed. `TabContext.state` is the app state the `Controller` keeps.

4. `build.gradle.kts`: keep the template's file (`plugins/_template/build.gradle.kts`). It names the jar from
   `module.json`'s `name`, so a copy needs no edit.
5. Copy the template's test and change the names (`plugins/_template/src/test/kotlin/com/example/starter/StarterPluginTest.kt`).
   It runs `registerModule(manifest, Tier.COMMUNITY, NoPluginServices, plugin)` and asserts `TabFactory(listOf(module)).problems`
   is empty: that proves the plugin registers exactly what `module.json` declares.
6. Enable it in `plugins/modules.json` under `modules`: `"Acme_Counter": { "enabled": true, "allow": [] }`. Add to
   `allow` only the services you list in `services` (`notifications`, `storage`, `events`, `daemon`, ...).

## Check

```
./gradlew :plugins:community:Acme_Counter:test :plugins:community:Acme_Counter:jar --console=plain
```

The jar lands in `plugins/community/Acme_Counter/build/libs/Acme_Counter.jar`. On desktop the app loads it at run
time; web and Android cannot load jars, so community plugins are desktop and daemon only.

## Related

[UI plugin API](../plugin-api.md) (`TabProvider`, `PanelProvider`, `TabContext`), [Plugins](../Plugins.md).
