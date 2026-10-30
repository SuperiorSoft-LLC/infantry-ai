package com.acme.daemonhello

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.HttpReply
import com.superiorsoftllc.agenthq.daemon.api.ReadyHook
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * DAEMON-PLUGINS-1: the reference daemon plugin. Everything it registers is declared in its module.json `daemon` object:
 *
 * - tool `acme_hello` (action `acme.hello.read`): listed to clients (`plugin_tools`) and agents (`agenthq mcp serve`);
 * - route `GET hello`: relative, so it answers at `/plugins/Acme_DaemonHello/hello`, behind the access policy;
 * - hook `ready`: adds the `acmeHello` key to every `ready` frame.
 *
 * With env `ACME_HELLO_FAIL=1` (declared in `daemon.env`) the ready hook throws, to show isolation: the host disables the
 * plugin (its tool and route go away, `GET /plugins` says `failed: ...`) and the daemon carries on.
 */
class HelloDaemon : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(Hello(api))
        api.routes.handle("GET", "hello") { HttpReply(body = """{"hello":"world","module":"${api.moduleId}"}""", contentType = "application/json") }
        val fail = api.env("ACME_HELLO_FAIL") == "1"
        api.hooks.ready(object : ReadyHook {
            override fun fields(): Map<String, String> {
                check(!fail) { "ACME_HELLO_FAIL is set" }
                return mapOf("acmeHello" to "hello")
            }
        })
        api.log("started")
    }

    private class Hello(private val api: DaemonApi) : DaemonTool {
        override val name = "acme_hello"
        override val description = "Greets the caller and counts the daemon's sessions."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "who to greet (default: world)") })
            })
            put("required", buildJsonArray {})
            put("additionalProperties", false)
        }

        override fun call(call: ToolCall): ToolResult {
            val who = (call.arguments["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "world"
            return ToolResult("Hello, $who! This daemon has ${api.sessions.list().size} session(s).")
        }
    }
}
