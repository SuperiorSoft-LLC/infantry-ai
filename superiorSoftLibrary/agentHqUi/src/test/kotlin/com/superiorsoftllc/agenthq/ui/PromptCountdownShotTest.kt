package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.PromptCards
import com.superiorsoftllc.agenthq.ui.protocol.PromptRequestView
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** ASK-TIMER (1.139.0): a tool-approval card with a timer shows its countdown; rendered to build/prompt-shots for review. */
@OptIn(ExperimentalTestApi::class)
class PromptCountdownShotTest {
    @Test
    fun `render an approval card with its countdown`() = runDesktopComposeUiTest(900, 220) {
        val prompt = PromptRequestView(
            "p1", "s1", PromptCards.KIND_TOOL_APPROVAL, createdAt = 1, tool = "Bash", input = "git push origin main",
            expiresAt = System.currentTimeMillis() + 582_000, onTimeout = PromptCards.DENY,
        )
        setContent { PromptCard(listOf(prompt), allowAlways = true, onAnswer = {}, onDismiss = {}) }
        onNodeWithTag(PROMPT_COUNTDOWN_TAG).assertExists()
        val f = File(File("build/prompt-shots").apply { mkdirs() }, "prompt-countdown.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", f)
        assertTrue(f.length() > 0)
    }
}
