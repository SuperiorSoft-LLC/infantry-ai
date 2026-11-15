// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.plugin.api.CapabilityRuns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** ACCOUNT-CAP-ID-1: every capability id a repo module.json declares passes the loader's own id rule, so a bad id fails the build, not the running daemon. */
class ModuleCapabilityIdsGuardTest {
    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "plugins/official").isDirectory) d = d.parentFile
        return requireNotNull(d) { "repo root (a folder holding plugins/official) not found" }
    }

    @Test
    fun everyDeclaredCapabilityIdPassesTheLoaderRule() {
        val manifests = File(repoRoot(), "plugins").walkTopDown()
            .onEnter { it.name != "build" && it.name != "node_modules" }
            .filter { it.name == "module.json" }.toList()
        assertTrue(manifests.isNotEmpty(), "no module.json found")
        val bad = manifests.flatMap { f ->
            val caps = Json.parseToJsonElement(f.readText()).jsonObject["capabilities"] as? JsonArray ?: return@flatMap emptyList()
            caps.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                .filter { !CapabilityRuns.ID.matches(it) }.map { "${f.parentFile.name}: '$it'" }
        }
        assertTrue(bad.isEmpty(), "capability ids refused by the loader: $bad")
    }
}
