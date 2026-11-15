// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ENDPOINT-ENV-1: where a service lives is developer config (module.json `daemon.endpoints`, picked by the build environment),
 * never a user setting. No official plugin's settings declaration (`config` or `daemon.config`) may hold a field that asks the
 * user for a service address: a key ending in url / uri / endpoint / address, or a label asking for an API or service address.
 */
class NoEndpointUserSettingGuardTest {
    private val official = File(TestDaemonPlugins.repo, "plugins/official")
    private val addressKey = Regex("""(?i)(url|uri|endpoints?|address)$""")
    private val addressLabel = Regex("""(?i)\b(api|service|server|hosting)\s+(address|url|endpoint)\b|\(https?\)""")

    @Test fun `no official plugin asks the user for a service address`() {
        val failures = mutableListOf<String>()
        official.listFiles { f -> File(f, "module.json").isFile }.orEmpty().sortedBy { it.name }.forEach { plugin ->
            val m = Json.parseToJsonElement(File(plugin, "module.json").readText()) as JsonObject
            val decls = (m["config"] as? JsonArray).orEmpty() + ((m["daemon"] as? JsonObject)?.get("config") as? JsonArray).orEmpty()
            decls.mapNotNull { it as? JsonObject }.flatMap { (it["fields"] as? JsonArray).orEmpty() }.mapNotNull { it as? JsonObject }.forEach { f ->
                val key = (f["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val label = (f["label"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (addressKey.containsMatchIn(key.substringAfterLast('.')) || addressLabel.containsMatchIn(label))
                    failures += "${plugin.name}: setting '$key' ($label) asks for a service address; declare it in module.json daemon.endpoints"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
