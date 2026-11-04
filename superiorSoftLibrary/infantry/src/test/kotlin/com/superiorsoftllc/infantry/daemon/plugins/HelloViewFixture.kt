// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** DV-6: loads the built Acme_HelloView exactly as a deployment would (community tier, granted) and asks it for views. */
class HelloViewFixture(coalesceMs: Long = 20) {
    val root: File = TestDaemonPlugins.root(
        "examples/Acme_HelloView", folder = "community", tier = "community",
        modulesJson = """{"Acme_HelloView":{"enabled":true,"allow":["daemon.code","daemon.view","daemon.tool"]}}""",
    )
    val got = CopyOnWriteArrayList<Envelope>()
    val conn = ViewConnection { got += it }
    val owner = Caller(owner = true)
    val logs = CopyOnWriteArrayList<String>()
    val host: DaemonPluginHost

    init {
        // TestDaemonPlugins.copy carries module.json, policies and the jar only; the static view's layout/ is copied here.
        File(TestDaemonPlugins.repo, "plugins/examples/Acme_HelloView/layout")
            .copyRecursively(File(root, "community/Acme_HelloView/layout"), overwrite = true)
        val services = object : DaemonServices by DaemonServices.NONE {
            override fun limit(def: LimitDef): Long = if (def == LimitRegistry.VIEWS_CHANGED_COALESCE_MS) coalesceMs else def.default
        }
        val config = PluginFolders.config(root)
        val res = ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false)
        host = DaemonPluginHost.load(res, config, TestDaemonPlugins.trustAll, services, File(root, "state")) { logs += it }
    }

    /** The raw `plugin_view_result` data for [view] ([params] is a JSON object literal). */
    fun open(view: String, params: String = "{}", subscribe: Boolean = true): JsonObject {
        val extra = if (subscribe) "" else ""","subscribe":false"""
        val e = runBlocking {
            host.answerView(Envelope(DaemonPluginHost.PLUGIN_VIEW, """{"module":"Acme_HelloView","view":"$view","params":$params$extra}"""), owner, conn)
        }
        return Json.parseToJsonElement(e.data).jsonObject
    }

    fun close() { host.close(); root.deleteRecursively() }
}
