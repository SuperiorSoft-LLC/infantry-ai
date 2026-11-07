// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** LAUNCH-VERIFY-2: a fresh install (setting unset) still has the documented aliases; a stored value replaces them. */
class ScriptAliasesTest {
    private fun findDefault(e: JsonElement): String? = when (e) {
        is JsonObject -> if ((e["key"] as? JsonPrimitive)?.contentOrNull == "scripting.aliases") (e["default"] as? JsonPrimitive)?.contentOrNull
            else e.values.firstNotNullOfOrNull { findDefault(it) }
        is JsonArray -> e.firstNotNullOfOrNull { findDefault(it) }
        else -> null
    }

    @Test fun unsetSettingGivesTheShippedTable() {
        assertEquals("infantry_board", ScriptAliases.effective(null)["board"])
        assertEquals("infantry_ticket_move", ScriptAliases.effective("  ")["move"])
    }

    @Test fun storedValueReplacesTheTable() {
        assertEquals(mapOf("go" to "infantry_ticket_start"), ScriptAliases.effective("""{"go":"infantry_ticket_start"}"""))
        assertEquals(emptyMap(), ScriptAliases.effective("{}"))
    }

    @Test fun codeDefaultsEqualTheModuleJsonDefault() {
        val declared = assertNotNull(findDefault(Json.parseToJsonElement(File("../module.json").readText())), "module.json declares no scripting.aliases default")
        assertEquals(ScriptAliases.parse(declared), ScriptAliases.DEFAULTS)
    }
}
