package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RunUsage
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-3b: the session header shows three separate chips -- harness, provider, model -- next to the runs and
 * tokens chips, with the values the session really runs with (the same pair the "starting <harness> model
 * <id>" boot line prints: the session's own model, else the harness default from the `providers` frame).
 * An unknown value hides its chip; a long model id is shortened with the full id in a tooltip.
 */
@OptIn(ExperimentalTestApi::class)
class AgentExecutorChipsUiTest {
    private fun agent(id: String, harness: String, model: String?, usage: List<RunUsage> = emptyList()) = AgentView(
        session = SessionRecord(
            id = id, provider = harness, workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1,
            state = SessionState.IDLE, providerSessionId = "p", runCount = 8, model = model,
        ),
        role = "pm",
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, "Sprint plan drafted.", 1)),
        usage = usage,
    )

    private val roles = listOf(Role("pm", "Project Manager", "#8B5CF6"))
    private val usage = listOf(RunUsage("r:1", "opencode", "opencode/space-bunny-free", 108_000, 3_500, 0, 0, 0))

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun SemanticsNodeInteraction.description(): String =
        fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString("")

    @Test
    fun aSessionRunningOpencodeSpaceBunnyShowsHarnessProviderAndModelChips() {
        val a = agent("19451abf0c2e", "opencode", "opencode/space-bunny-free", usage)
        val state = AppState(agents = mapOf(a.id to a), roles = roles)
        runDesktopComposeUiTest(1100, 200) {
            setContent { CenterHeader(a, spawnMode = false, state = state) }
            assertEquals("opencode", onNodeWithTag("agent-header-harness", useUnmergedTree = true).text(), "harness chip")
            assertEquals("opencode", onNodeWithTag("agent-header-provider", useUnmergedTree = true).text(), "provider chip")
            assertEquals("space-bunny-free", onNodeWithTag("agent-header-model", useUnmergedTree = true).text(), "model chip")
            // The runs and tokens chips stay.
            onAllNodesWithText("runs 8").fetchSemanticsNodes().single()
            onAllNodesWithText("108.0k in / 3.5k out").fetchSemanticsNodes().single()
        }
    }

    @Test
    fun aSessionOnTheHarnessDefaultShowsTheDefaultTheDaemonResolved() {
        // model = null: the boot line prints the harness default, which the `providers` frame carries.
        val a = agent("19451abf0c2e", "opencode", null)
        val state = AppState(
            agents = mapOf(a.id to a), roles = roles,
            providers = listOf(ProviderInfo("opencode", default = true, defaultModel = "opencode-go/space-bunny-free")),
        )
        runDesktopComposeUiTest(1100, 200) {
            setContent { CenterHeader(a, spawnMode = false, state = state) }
            assertEquals("opencode", onNodeWithTag("agent-header-harness", useUnmergedTree = true).text())
            assertEquals("opencode-go", onNodeWithTag("agent-header-provider", useUnmergedTree = true).text())
            assertEquals("space-bunny-free", onNodeWithTag("agent-header-model", useUnmergedTree = true).text())
        }
    }

    @Test
    fun aSessionWithAnUnknownModelShowsNoModelChipAndNoProviderChip() {
        // No session model, no harness default, no run usage: nothing to show, so nothing is guessed.
        val a = agent("7c0ffee12345", "opencode", null)
        val state = AppState(agents = mapOf(a.id to a), roles = roles, providers = listOf(ProviderInfo("opencode")))
        runDesktopComposeUiTest(1100, 200) {
            setContent { CenterHeader(a, spawnMode = false, state = state) }
            assertEquals("opencode", onNodeWithTag("agent-header-harness", useUnmergedTree = true).text())
            onNodeWithTag("agent-header-model", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("agent-header-provider", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun aLongModelIdIsShortenedWithTheFullIdInATooltip() {
        val full = "openrouter/anthropic-claude-sonnet-4-5-thinking-preview-20260930"
        val a = agent("19451abf0c2e", "opencode", full)
        val state = AppState(agents = mapOf(a.id to a), roles = roles)
        runDesktopComposeUiTest(1100, 300) {
            setContent { CenterHeader(a, spawnMode = false, state = state) }
            val chip = onNodeWithTag("agent-header-model", useUnmergedTree = true)
            assertEquals("anthropic-claude-sonn…", chip.text(), "shortened")
            assertEquals(full, onNodeWithTag("agent-header-model-chip", useUnmergedTree = true).description(), "full id for screen readers")
            onNodeWithTag("agent-header-model-chip", useUnmergedTree = true).performMouseInput { enter(center) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(full).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** Evidence for the DONE report, written to build/reports/m2-3b. */
    @Test
    fun captureHeaderChips() {
        val a = agent("19451abf0c2e", "opencode", "opencode/space-bunny-free", usage)
        val bare = agent("7c0ffee12345", "opencode", null)
        val state = AppState(agents = listOf(a, bare).associateBy { it.id }, roles = roles)
        runDesktopComposeUiTest(1100, 140) {
            setContent {
                Column(Modifier.fillMaxSize().background(Palette.Bg).testTag("shot")) {
                    CenterHeader(a, spawnMode = false, state = state)
                    CenterHeader(bare, spawnMode = false, state = state)
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PM").fetchSemanticsNodes().isEmpty() }
            val out = File("build/reports/m2-3b/header-executor-chips.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
            println("M2-3b screenshot: ${out.absolutePath}")
        }
    }
}
