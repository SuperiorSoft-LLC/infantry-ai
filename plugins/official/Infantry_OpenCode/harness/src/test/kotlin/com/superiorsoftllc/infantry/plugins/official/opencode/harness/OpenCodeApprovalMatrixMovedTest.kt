// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HARNESS H8b-3: the opencode rows of the daemon's ToolApprovalMatrixTest, moved with the code: per Tool approval mode the one-shot run (flag + inline
 * config) and the serve session's answers to each permission ask; plus openCodeAllowEditsMergesIntoTheUsersInlineConfigInsteadOfReplacingIt (PERM-2/3).
 */
class OpenCodeApprovalMatrixMovedTest {
    private fun cfg(mode: ToolApproval) = OpenCodePermissions.runEnv(mode, existing = null)[OpenCodePermissions.CONFIG_CONTENT_ENV]
    private fun permission(config: String?) = runCatching { Json.parseToJsonElement(config!!).jsonObject["permission"] as JsonObject }.getOrNull()
    private fun editAllowed(config: String?) = (permission(config)?.get("edit") as? JsonPrimitive)?.content == "allow"
    private fun infantryAllowed(config: String?) = runCatching { ((permission(config)!!["bash"] as JsonObject)["infantry *"] as JsonPrimitive).content == "allow" }.getOrDefault(false)

    @Test fun `one-shot - every mode allows the infantry CLI, and each mode maps its flag and edit rule`() {
        for (mode in ToolApproval.entries) {
            val c = cfg(mode)
            val auto = "--auto" in OpenCodeCommand.approvalArgs(mode)
            assertEquals(true, infantryAllowed(c), "every mode must allow bash infantry * ($mode): $c")
            when (mode) {
                ToolApproval.DENY, ToolApproval.ASK -> { assertEquals(false, auto, "$mode adds no --auto"); assertEquals(false, editAllowed(c), "$mode: $c") }
                ToolApproval.ALLOW_EDITS -> { assertEquals(false, auto); assertEquals(true, editAllowed(c), c) }
                ToolApproval.ALLOW_ALL -> { assertEquals(true, auto, "allowAll adds --auto"); assertEquals(false, editAllowed(c), "allowAll has --auto, no edit rule: $c") }
            }
        }
    }

    @Test fun `serve - each mode answers edit and bash asks as the mapping says, infantry bash once`() {
        for (mode in ToolApproval.entries) {
            val infantry = buildJsonObject { put("id", "per_matrix"); put("permission", "bash"); put("patterns", JsonArray(listOf(JsonPrimitive("infantry ticket create --title x")))) }
            val a = OpenCodeServeSession.permissionDecision(infantry, mode)!!
            assertEquals("once", (Json.parseToJsonElement(a.replyBody).jsonObject["reply"] as JsonPrimitive).content, "infantry bash in $mode")
            assertNull(a.deniedTool)
            val expected = mapOf("edit" to (mode != ToolApproval.DENY && mode != ToolApproval.ASK), "bash" to (mode == ToolApproval.ALLOW_ALL))
            for ((permission, allowed) in expected) {
                val props = buildJsonObject { put("id", "per_matrix"); put("permission", permission); if (permission == "bash") put("patterns", JsonArray(listOf(JsonPrimitive("git status")))) }
                val d = OpenCodeServeSession.permissionDecision(props, mode) ?: continue
                val reply = (Json.parseToJsonElement(d.replyBody).jsonObject["reply"] as JsonPrimitive).content
                if (mode == ToolApproval.ASK) continue // ask prompts the owner (needsOwner); covered by OpenCodePermissionsTest
                if (allowed) { assertEquals("once", reply, "$permission in $mode"); assertNull(d.deniedTool) }
                else { assertEquals("reject", reply, "$permission in $mode"); assertEquals(permission, d.deniedTool) }
            }
        }
    }

    @Test fun `allowEdits merges into the user's inline config instead of replacing it`() {
        val merged = Json.parseToJsonElement(OpenCodePermissions.mergeAllowEdits("""{"model":"x/y","permission":{"bash":"ask"}}""")).jsonObject
        assertEquals("\"x/y\"", merged["model"].toString())
        assertEquals("""{"bash":"ask","edit":"allow"}""", merged["permission"].toString())
        assertEquals("""{"*":"deny","edit":"allow"}""", Json.parseToJsonElement(OpenCodePermissions.mergeAllowEdits("""{"permission":"deny"}""")).jsonObject["permission"].toString())
        for (bad in listOf(null, "", "  ", "not json", "[1]")) assertEquals(OpenCodePermissions.ALLOW_EDITS_CONFIG, OpenCodePermissions.mergeAllowEdits(bad))
    }
}
