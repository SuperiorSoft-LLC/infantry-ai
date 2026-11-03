// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.SessionExecutor
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import com.superiorsoftllc.infantry.ui.protocol.RunUsage
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** M2-3b: which harness / provider / model the session header names, and what it hides as unknown. */
class SessionExecutorModelTest {
    private fun agent(harness: String, model: String?, usageModel: String? = null) = AgentView(
        session = SessionRecord(id = "s1", provider = harness, workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, model = model),
        usage = usageModel?.let { listOf(RunUsage("r:1", harness, it, 1, 1, 0, 0, 0)) }.orEmpty(),
    )

    @Test
    fun theSessionModelIsSplitIntoProviderAndModel() {
        assertEquals(
            SessionExecutor("opencode", "opencode", "space-bunny-free", "opencode/space-bunny-free"),
            AppState().sessionExecutor(agent("opencode", "opencode/space-bunny-free")),
        )
    }

    @Test
    fun noSessionModelFallsBackToTheHarnessDefaultLikeTheBootLine() {
        val s = AppState(providers = listOf(ProviderInfo("opencode", defaultModel = "opencode-go/space-bunny-free")))
        assertEquals(SessionExecutor("opencode", "opencode-go", "space-bunny-free", "opencode-go/space-bunny-free"), s.sessionExecutor(agent("opencode", null)))
    }

    @Test
    fun theSessionModelWinsOverTheDefaultAndTheDefaultOverRunUsage() {
        val s = AppState(providers = listOf(ProviderInfo("opencode", defaultModel = "opencode/a")))
        assertEquals("b", s.sessionExecutor(agent("opencode", "opencode/b", usageModel = "opencode/c")).model)
        assertEquals("a", s.sessionExecutor(agent("opencode", null, usageModel = "opencode/c")).model)
        assertEquals("c", AppState().sessionExecutor(agent("opencode", null, usageModel = "opencode/c")).model)
    }

    @Test
    fun anUnknownModelLeavesProviderAndModelNull() {
        assertEquals(SessionExecutor("opencode", null, null, null), AppState(providers = listOf(ProviderInfo("opencode"))).sessionExecutor(agent("opencode", " ")))
    }

    @Test
    fun aBareModelIdTakesItsProviderOnlyFromTheCatalogueGroup() {
        val bare = agent("claude", "claude-opus-4")
        assertEquals(SessionExecutor("claude", null, "claude-opus-4", "claude-opus-4"), AppState().sessionExecutor(bare))
        val s = AppState(modelCatalog = listOf(ProviderModels("claude", "anthropic", "Claude", listOf("claude-opus-4"))))
        assertEquals("anthropic", s.sessionExecutor(bare).provider)
    }

    @Test
    fun aLongModelIsShortenedAndAShortOneIsNot() {
        assertEquals("space-bunny-free", SessionExecutor("h", "p", "space-bunny-free", "p/space-bunny-free").shortModel)
        val long = "anthropic-claude-sonnet-4-5-thinking"
        assertEquals("anthropic-claude-sonn…", SessionExecutor("h", "p", long, "p/$long").shortModel)
        assertEquals(24, SessionExecutor("h", "p", "x".repeat(24), null).shortModel!!.length)
    }
}
