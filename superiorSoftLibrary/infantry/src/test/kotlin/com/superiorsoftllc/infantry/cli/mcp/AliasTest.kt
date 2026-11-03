// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RENAME-2 AliasTest: the legacy tool names, project folder and CLI launcher alias still work (env: see InfantryEnvTest). */
class AliasTest {
    @Test fun `agenthq tool names are read as infantry tool names`() {
        assertEquals("infantry_ticket_get", LegacyToolNames.canonical("agenthq_ticket_get"))
        assertEquals("infantry_ticket_get", LegacyToolNames.canonical("infantry_ticket_get"))
        assertEquals("other_tool", LegacyToolNames.canonical("other_tool"))
    }

    @Test fun `every listed tool is named infantry_`() {
        val names = McpTools.NAMES
        assertTrue(names.isNotEmpty() && names.all { it.startsWith("infantry_") }, names.filterNot { it.startsWith("infantry_") }.toString())
    }

    @Test fun `project files read the legacy agenthq folder and write to infantry`() {
        val dir = Files.createTempDirectory("rename2-pf").toFile()
        File(dir, ".agenthq/scripts").mkdirs()
        File(dir, ".agenthq/scripts/old.lua").writeText("old")
        val files = DirectoryProjectFiles(dir)
        assertEquals("old", files.get("scripts/old.lua")?.decodeToString())
        assertEquals(listOf("old.lua"), files.list("scripts").map { it.name })
        files.put("scripts/new.lua", "new".toByteArray())
        assertTrue(File(dir, ".infantry/scripts/new.lua").isFile)
        assertEquals(listOf("new.lua", "old.lua"), files.list("scripts").map { it.name }.sorted())
        assertTrue(files.delete("scripts/old.lua"))
        assertEquals(null, files.get("scripts/old.lua"))
        dir.deleteRecursively()
    }

    @Test fun `the agenthq launcher alias ships next to infantry`() {
        val res = File("../infantryUi/appResources")
        if (res.isDirectory) for (os in listOf("linux", "macos", "windows")) assertTrue(File(res, "$os/agenthq").isFile, os)
    }
}
