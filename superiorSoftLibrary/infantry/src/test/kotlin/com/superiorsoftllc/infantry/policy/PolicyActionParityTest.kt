// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.daemon.DemoPresetFixture
import com.superiorsoftllc.infantry.plugin.api.ActionKind
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * POLICY-1: every action a plugin declares in module.json names frames the daemon's catalog maps to THAT action id (and
 * kind), so the control the UI dims and the frame the daemon refuses cannot drift apart. A declared id the catalog does not
 * know is a plugin-only action (it may declare no frames: the daemon has nothing to refuse).
 */
class PolicyActionParityTest {
    private val repo = generateSequence(DemoPresetFixture.file.absoluteFile) { it.parentFile }.drop(5).first()
    private val json = Json { ignoreUnknownKeys = true }
    private val manifests: List<Pair<File, ModuleManifest>> = File(repo, "plugins").walkTopDown()
        .onEnter { it.name != "build" && it.name != "node_modules" }
        .filter { it.isFile && it.name == "module.json" }
        .map { it to json.decodeFromString(ModuleManifest.serializer(), it.readText()) }.toList()

    @Test fun `the official plugins declare their actions`() {
        val declared = manifests.flatMap { it.second.actions }
        assertTrue(declared.size >= 20, "only ${declared.size} actions declared")
        assertTrue(declared.any { it.id == "board.ticket.save" } && declared.any { it.id == "agents.spawn" })
    }

    @Test fun `every declared frame maps to the declared action`() {
        val problems = manifests.flatMap { (file, m) ->
            m.actions.flatMap { a ->
                val kind = if (a.kind in ActionKind.ALL) emptyList() else listOf("${m.name}: ${a.id} has unknown kind '${a.kind}'")
                val catalog = ActionCatalog.forId(a.id)
                val known = if (catalog == null && a.frames.isNotEmpty()) listOf("${m.name}: ${a.id} names frames but the daemon has no such action") else emptyList()
                val kindDrift = if (catalog != null && catalog.kind != a.kind) listOf("${m.name}: ${a.id} is '${a.kind}', the daemon says '${catalog.kind}'") else emptyList()
                kind + known + kindDrift + a.frames.mapNotNull { f ->
                    val mapped = ActionCatalog.forFrame(f)?.id
                    if (mapped == a.id) null else "${file.parentFile.name}: ${a.id} declares frame '$f', which the daemon maps to ${mapped ?: "nothing"}"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
