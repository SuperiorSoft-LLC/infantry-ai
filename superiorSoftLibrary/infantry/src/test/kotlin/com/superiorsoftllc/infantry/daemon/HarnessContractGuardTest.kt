// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HARNESS-CONTRACT-1: a harness plugin in this repo whose module.json `harness.contract` falls behind the harness API fails
 * the BUILD, not the user. A plugin states `contractVersion = HARNESS_API_VERSION`, a const inlined at its compile time, so
 * its jar always claims the API it was built against; the daemon refuses a jar whose claim differs from module.json
 * (`HarnessModules.load`). Infantry_OpenCode and Infantry_Pi shipped 2.12.0 while the API moved to 2.15.0 and were refused.
 *
 * Rule: every module under `plugins/<folder>/` whose harness sources inline [HARNESS_API_VERSION] declares exactly that
 * version; an official or example module with a harness block declares it whatever its sources say (they are built with
 * this repo's API, so there is no older contract for them to keep).
 */
class HarnessContractGuardTest {
    private val plugins = File(TestDaemonPlugins.repo, "plugins")
    private val json = Json { ignoreUnknownKeys = true }

    private fun harnessModules(): List<Pair<File, JsonObject>> =
        plugins.listFiles { f -> f.isDirectory }.orEmpty().flatMap { folder -> folder.listFiles { f -> f.isDirectory }.orEmpty().toList() }
            .mapNotNull { dir -> File(dir, "module.json").takeIf { it.isFile }?.let { dir to json.parseToJsonElement(it.readText()).jsonObject } }
            .mapNotNull { (dir, m) -> (m["harness"] as? JsonObject)?.let { dir to it } }

    private fun inlinesApiVersion(dir: File) = File(dir, "harness/src/main").walkTopDown()
        .any { it.isFile && it.extension == "kt" && Regex("""contractVersion\s*(:\s*String\s*)?=\s*HARNESS_API_VERSION""").containsMatchIn(it.readText()) }

    @Test fun `every harness plugin in the repo declares the harness API version its jar is built against`() {
        val modules = harnessModules()
        assertTrue(modules.any { it.first.name == "Infantry_OpenCode" } && modules.any { it.first.name == "Infantry_Pi" }, "scan found no harness modules under $plugins: ${modules.map { it.first.name }}")
        val behind = modules.filter { (dir, h) ->
            val mustMatch = dir.parentFile.name in setOf("official", "examples") || inlinesApiVersion(dir)
            mustMatch && h["contract"]?.jsonPrimitive?.content != HARNESS_API_VERSION
        }.map { (dir, h) -> "plugins/${dir.parentFile.name}/${dir.name}/module.json harness.contract=${h["contract"]}" }
        assertTrue(behind.isEmpty(), "harness API is $HARNESS_API_VERSION; bring these modules to it (implement what the new minors added, then bump harness.contract): $behind")
    }
}
