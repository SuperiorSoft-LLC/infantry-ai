package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.model.PromptTimerSetting
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** ASK-TIMER-2: Settings > Tool approval > Prompt timer; rendered to build/prompt-shots for review. */
@OptIn(ExperimentalTestApi::class)
class PromptTimerShotTest {
    @Test
    fun `render the prompt timer control`() = runDesktopComposeUiTest(900, 320) {
        setContent {
            AgentHqTheme {
                Box(Modifier.background(Palette.Panel).padding(12.dp)) {
                    PromptTimerControl(PromptTimerSetting.Value(120, "deny")) { _, _ -> }
                }
            }
        }
        onNodeWithTag(PROMPT_TIMER_STATUS_TAG).assertTextContains("Now: 2 min")
        val f = File(File("build/prompt-shots").apply { mkdirs() }, "prompt-timer-setting.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", f)
        assertTrue(f.length() > 0)
    }
}
