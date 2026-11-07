// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RUNNER-POOL-9: the Runners card shows only its enable switch while `runners.enabled` is false. */
class RunnersCardManifestTest {
    private fun manifest(): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "plugins/official/Infantry_Runners/module.json").exists()) dir = dir.parentFile
        val file = File(dir ?: error("repo root not found"), "plugins/official/Infantry_Runners/module.json")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test fun everyFieldButTheSwitchIsHiddenWhileRunnersAreDisabled() {
        val fields = (manifest()["config"] as JsonArray).flatMap { it.jsonObject["fields"]!!.jsonArray }.map { it.jsonObject }
        val sw = fields.single { (it["key"] as JsonPrimitive).content == "runners.enabled" }
        assertTrue(sw["visibleWhen"] == null)
        val rest = fields - sw
        assertTrue(rest.isNotEmpty())
        rest.forEach {
            val w = it["visibleWhen"]!!.jsonObject
            assertEquals("runners.enabled", (w["key"] as JsonPrimitive).content)
            assertEquals("true", (w["eq"] as JsonPrimitive).content)
        }
    }
}
