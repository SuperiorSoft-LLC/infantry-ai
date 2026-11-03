// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.daemon.DemoPresetFixture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * POLICY-1 layering rule: the mechanism is core/SDK, the demo preset's VALUES are the plugin's. Fails when core (daemon
 * `policy/`, the daemon plugin host) or the SDK's policy model names the demo preset or one of its values.
 */
class NoPresetInCoreGuardTest {
    /** plugins/demo/Demo_Lock/policies/demo.json -> the repo root. */
    private val repo = generateSequence(DemoPresetFixture.file.absoluteFile) { it.parentFile }.drop(5).first()
    private val core = listOf(
        "superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/policy",
        "superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins",
        "superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/ActionPolicy.kt",
    ).map { File(repo, it) }.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else listOf(f) }

    private val values: List<String> = DemoPresetFixture.preset.let { p ->
        listOfNotNull(
            "\"${p.id}\"", p.reason, p.refusal, p.notice?.takeIf { it.isNotBlank() },
            p.seats?.projectName, p.seats?.projectDir, p.seats?.projectGoal, p.seats?.warmUpPrompt,
            p.seats?.mailRefusal, p.seats?.httpRefusal,
        ) + p.seats!!.seats
    }

    @Test fun `core files exist`() = assertTrue(core.size >= 5 && core.all { it.isFile }, core.toString())

    @Test fun `core and the SDK name no demo preset value`() {
        val hits = core.flatMap { f -> val text = f.readText(); values.filter { it in text }.map { "${f.name}: $it" } }
        assertTrue(hits.isEmpty(), "demo preset values in core/SDK (they belong in plugins/demo/Demo_Lock/policies/demo.json): $hits")
    }
}
