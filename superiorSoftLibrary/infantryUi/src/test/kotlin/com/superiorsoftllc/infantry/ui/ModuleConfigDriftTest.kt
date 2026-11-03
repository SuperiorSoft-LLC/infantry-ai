package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import kotlinx.serialization.json.Json
import java.io.File
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FIX-CONFIG-1: module.json is the source of an official plugin's config (desktop reads it; web and Android compile the Kotlin
 * declaration in). This pins the two equal for every official plugin, so the Kotlin side cannot drift from the file again:
 * the same nodes, groups, tabs, orders, blocks, field keys, types, defaults and scopes (data-class equality).
 */
class ModuleConfigDriftTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun officialDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "plugins/official").isDirectory) dir = dir.parentFile
        return File(checkNotNull(dir) { "plugins/official not found above ${File("").absolutePath}" }, "plugins/official")
    }

    private val compiled: Map<String, ModuleManifest> =
        ServiceLoader.load(ModuleSource::class.java).flatMap { it.modules() }.associate { it.manifest.name to it.manifest }

    @Test fun `every official plugin's compiled-in config equals its module json config`() {
        val dirs = officialDir().listFiles { f -> File(f, "module.json").isFile }.orEmpty().sortedBy { it.name }
        assertTrue(dirs.isNotEmpty())
        val problems = mutableListOf<String>()
        for (dir in dirs) {
            val file = json.decodeFromString(ModuleManifest.serializer(), File(dir, "module.json").readText())
            val kotlin = compiled[file.name]
            if (kotlin == null) {
                // A module with no compiled-in form (not on this class path) can only be a problem when it declares config.
                if (file.config.isNotEmpty()) problems += "${file.name}: declares config but no compiled-in module is on the class path"
                continue
            }
            if (kotlin.config != file.config) problems += "${file.name}: compiled-in config differs from module.json\n  json:   ${file.config}\n  kotlin: ${kotlin.config}"
        }
        assertEquals(emptyList<String>(), problems)
    }
}
