# Recipe: add a daemon tool

Goal: a named tool, with a JSON schema, that agents (through `infantry mcp serve`) and clients (`plugin_tools`) can
call. It runs in the daemon, in your plugin's own class loader, behind the access policy.

The reference is `plugins/examples/Acme_DaemonHello` (a tool, a route and a hook). Its files:
`plugins/examples/Acme_DaemonHello/module.json`,
`plugins/examples/Acme_DaemonHello/daemon/build.gradle.kts` and
`plugins/examples/Acme_DaemonHello/daemon/src/main/kotlin/com/acme/daemonhello/HelloDaemon.kt`.

## Files to touch

```
plugins/community/Acme_Sessions/module.json
plugins/community/Acme_Sessions/daemon/build.gradle.kts
plugins/community/Acme_Sessions/daemon/src/main/kotlin/com/acme/sessions/SessionsDaemon.kt
plugins/modules.json            (existing file: enable and grant the module)
```

A daemon-only plugin needs no UI half: there is no root `build.gradle.kts` and no `entry`.

## Steps

1. Copy `plugins/examples/Acme_DaemonHello` to `plugins/community/Acme_Sessions`. `settings.gradle.kts` finds any
   `plugins/<folder>/<Module>/daemon/build.gradle.kts` and includes it as `:daemon-plugin-<folder>-<Module>`.
2. `module.json`: every tool, route and hook must be **declared** here; an undeclared registration throws and the
   host disables the plugin. A community tool name must start with the vendor prefix (`acme_`):

```json
{
  "name": "Acme_Sessions",
  "version": "1.0.0",
  "apiVersion": 2,
  "author": "Acme",
  "license": "Apache-2.0",
  "description": "One read-only tool: how many sessions the daemon runs.",
  "daemon": {
    "entry": "com.acme.sessions.SessionsDaemon",
    "jar": "daemon/build/libs/Acme_Sessions-daemon.jar",
    "apiAtLeast": "1.0.0",
    "tools": [
      { "name": "acme_session_count", "action": "acme.sessions.read", "kind": "read", "description": "How many sessions the daemon runs." }
    ]
  }
}
```

   `action` is what the access policy checks; `kind` is `read`, `write` or `destructive`.
3. `daemon/build.gradle.kts`: the template's (`plugins/_template/daemon/build.gradle.kts`): `compileOnly` on
   `:superiorSoftLibrary:infantryDaemonApi`, and a jar named exactly like `daemon.jar` in `module.json`
   (`Acme_Sessions-daemon.jar`), with `module.json` copied in.
4. The class. It implements `DaemonPlugin`, receives a `DaemonApi` in `start`, and registers a `DaemonTool`:

```kotlin
package com.acme.sessions

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SessionsDaemon : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(SessionCount(api))
        api.log("started")
    }

    private class SessionCount(private val api: DaemonApi) : DaemonTool {
        override val name = "acme_session_count"
        override val description = "How many sessions the daemon runs."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
            put("additionalProperties", false)
        }

        override fun call(call: ToolCall): ToolResult =
            ToolResult("This daemon has ${api.sessions.list().size} session(s).")
    }
}
```

   This is the pattern of `HelloDaemon.kt` with the route, hook and arguments removed. What `DaemonApi` offers
   (`tools`, `frames`, `routes`, `hooks`, `events`, `sessions`, `projects`, `config`, `secret`, `schedule`, `stateDir`)
   is listed in `superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/DaemonApi.kt`.
5. Grant it in `plugins/modules.json`. A community daemon half needs `daemon.code` and `daemon.tool` in `allow` (see the
   grants table in [Daemon plugin API](../daemon-plugin-api.md), section 8).

## Check

```
./gradlew :daemon-plugin-community-Acme_Sessions:jar --console=plain
```

Restart the daemon. The tool is listed to clients as a plugin tool and to agents by `infantry mcp serve`
(`superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/mcp/McpPluginTools.kt` exposes plugin
tools). A tool that throws or times out fails only its own call; repeated timeouts disable the plugin (the guard).

## Related

[Daemon plugin API](../daemon-plugin-api.md) (routes, frames, hooks, events, limits), `plugins/examples/Acme_PolicyHook`
(a tool that asks the policy about a frame and a frameGate hook), `plugins/examples/Acme_NewsAgent` (an agent with a feed-fetch tool and a schedule).
