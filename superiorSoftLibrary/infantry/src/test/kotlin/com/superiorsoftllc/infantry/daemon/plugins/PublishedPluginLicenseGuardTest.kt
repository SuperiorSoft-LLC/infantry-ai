// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MASCOT-LICENSE-1: the catalog shipped Infantry_Mascot 1.0.0 as a community package whose module.json had no license,
 * so it installed and the loader then refused it ("community module without a license"). A plugin this repo publishes
 * into the community catalog (every `registry/community/<id>.json` that names an in-repo `plugins/<tier>/<id>`, and every
 * `standalone` plugin in plugins/build-presets.json) must pass the loader's community check.
 */
class PublishedPluginLicenseGuardTest {
    private val repo: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "plugins/official").isDirectory && File(dir, "superiorSoftLibrary").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }

    private fun moduleDir(id: String): File? =
        File(repo, "plugins").listFiles().orEmpty().map { File(it, id) }.firstOrNull { File(it, "module.json").isFile }

    @Test fun `every plugin published as community passes the loader's community check`() {
        val presets = Json.parseToJsonElement(File(repo, "plugins/build-presets.json").readText()).jsonObject
        val standalone = (presets["classes"] as? JsonObject).orEmpty().filter { it.value.jsonPrimitive.content == "standalone" }.keys
        val community = File(repo, "registry/community").listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }
        val ids = (standalone + community).filter { moduleDir(it) != null }.toSortedSet()
        assertTrue("Infantry_Mascot" in ids, "the guard found no published plugin; it looks in the wrong place ($ids)")
        val problems = ids.mapNotNull { id ->
            val m = PluginJson.decodeFromString(ModuleManifest.serializer(), File(moduleDir(id), "module.json").readText())
            ModuleResolver.communityRefusal(m)?.let { "$id: $it (add it to its module.json)" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
