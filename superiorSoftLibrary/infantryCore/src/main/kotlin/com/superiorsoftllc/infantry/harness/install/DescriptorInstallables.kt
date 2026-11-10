// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.install

import com.superiorsoftllc.infantry.harness.descriptor.HarnessDescriptor
import com.superiorsoftllc.infantry.plugin.api.HarnessCliDeclaration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * HARNESS-DESCRIPTOR-INSTALL-1: the installer's entries for JSON harness descriptors that carry an `install` field.
 * Descriptors in [userDir] come first; a descriptor in [samplesDir] whose id the user folder already holds is not
 * offered again, the rest are offered as "available, not installed" ([InstallableHarness.sample] set). An unreadable
 * or invalid file is skipped: the descriptor loader already reports it.
 */
class DescriptorInstallables(private val userDir: File, private val samplesDir: File?) {
    fun list(): List<InstallableHarness> {
        val userAll = read(userDir)
        val user = userAll.filter { it.second.install != null }.map { (_, d) -> entry(d, null) }
        val have = userAll.map { it.second.id }.toSet()
        val samples = samplesDir?.let { read(it) }.orEmpty()
            .filter { it.second.install != null && it.second.id !in have }
            .map { (f, d) -> entry(d, f) }
        return user + samples
    }

    /** Copy [h]'s shipped sample into [userDir]; never overwrites a file of the same name. */
    fun adopt(h: InstallableHarness) {
        val sample = h.sample ?: return
        userDir.mkdirs()
        val target = File(userDir, sample.name)
        check(!target.exists()) { "${target.name} already exists in the harnesses folder" }
        sample.copyTo(target)
    }

    private fun entry(d: HarnessDescriptor, sample: File?): InstallableHarness {
        val decl = HarnessCliDeclaration(id = d.id, command = d.command, install = d.install, label = d.name)
        return InstallableHarness(ManifestHarnessCli.tool(decl), d.install!!.npm!!, sample)
    }

    private fun read(dir: File): List<Pair<File, HarnessDescriptor>> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.length() <= MAX_BYTES }?.sortedBy { it.name }.orEmpty()
        return files.flatMap { f ->
            val root = runCatching { Json.parseToJsonElement(f.readText()) }.getOrNull()
            val entries = when (root) { is JsonArray -> root.toList(); is JsonObject -> listOf(root); else -> emptyList() }
            entries.mapNotNull { e -> runCatching { HarnessDescriptor.parse(e) }.getOrNull() }.map { f to it }
        }
    }

    private companion object { const val MAX_BYTES = 256 * 1024L }
}
