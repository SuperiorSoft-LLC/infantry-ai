package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCRIPT-BOOT-1: the host refuses (and disables the plugin) when start() registers a tool/frame/hook module.json does not declare. Start the real plugin against the real manifest. */
class ManifestDeclaresEverythingTest {
    private fun <T> stub(type: Class<T>, record: (String, Array<Any?>) -> Unit): T {
        val h = InvocationHandler { _, m, args ->
            record(m.name, args ?: emptyArray())
            val r = m.returnType
            when {
                r == Void.TYPE -> null
                r == java.lang.Boolean.TYPE -> false
                r == java.lang.Long.TYPE -> 0L
                r == Integer.TYPE -> 0
                r == AutoCloseable::class.java -> AutoCloseable { }
                r == String::class.java -> ""
                r == List::class.java -> emptyList<Any>()
                r == Map::class.java -> emptyMap<Any, Any>()
                r == Set::class.java -> emptySet<Any>()
                r.isInterface && r.name.startsWith("com.superiorsoftllc") -> stub(r) { _, _ -> }
                else -> null
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), h) as T
    }

    @Test fun everythingTheDaemonHalfRegistersIsDeclaredInModuleJson() {
        val daemon = Json.parseToJsonElement(File("../module.json").readText()).jsonObject["daemon"]!!.jsonObject
        fun names(key: String, field: String) = (daemon[key] as? JsonArray ?: JsonArray(emptyList())).map { (it as JsonObject)[field]!!.jsonPrimitive.content }.toSet()
        val tools = mutableSetOf<String>(); val frames = mutableSetOf<String>(); val hooks = mutableSetOf<String>(); val routes = mutableSetOf<String>()
        val toolsApi = Proxy.newProxyInstance(DaemonApi::class.java.classLoader, arrayOf(com.superiorsoftllc.infantry.daemon.api.Tools::class.java)) { _, m, a ->
            if (m.name == "register") tools += (a!![0] as DaemonTool).name
            if (m.returnType == List::class.java) emptyList<Any>() else null
        } as com.superiorsoftllc.infantry.daemon.api.Tools
        val framesApi = stub(com.superiorsoftllc.infantry.daemon.api.Frames::class.java) { n, a -> if (n == "handle") frames += a[0] as String }
        val hooksApi = stub(com.superiorsoftllc.infantry.daemon.api.Hooks::class.java) { n, _ -> hooks += n }
        val routesApi = stub(com.superiorsoftllc.infantry.daemon.api.Routes::class.java) { n, a -> if (n == "handle") routes += "${a[0]} ${a[1]}" }
        val tmp = kotlin.io.path.createTempDirectory("scr").toFile()
        val api = Proxy.newProxyInstance(DaemonApi::class.java.classLoader, arrayOf(DaemonApi::class.java)) { _, m, _ ->
            when (m.name) {
                "getTools" -> toolsApi; "getFrames" -> framesApi; "getHooks" -> hooksApi; "getRoutes" -> routesApi
                "getStateDir" -> tmp; "getModuleId" -> "Infantry_Scripting"; "getTier" -> "official"; "getApiVersion" -> DaemonApi.VERSION
                "config", "env", "secret" -> null
                "log" -> null
                "schedule" -> AutoCloseable { }
                else -> if (m.returnType.isInterface && m.returnType.name.startsWith("com.superiorsoftllc")) stub(m.returnType) { _, _ -> } else null
            }
        } as DaemonApi
        ScriptingDaemonPlugin().start(api)
        assertTrue(tools.isNotEmpty() && frames.isNotEmpty() && hooks.isNotEmpty(), "plugin registered nothing: tools=$tools frames=$frames hooks=$hooks")
        assertEquals(emptySet(), tools - names("tools", "name"), "tools registered but not in daemon.tools")
        assertEquals(emptySet(), frames - names("frames", "type"), "frames registered but not in daemon.frames")
        assertEquals(emptySet(), hooks - ((daemon["hooks"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()), "hooks registered but not in daemon.hooks")
        assertEquals(emptySet(), routes - ((daemon["routes"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()), "routes registered but not declared")
    }
}
