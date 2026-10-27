package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.UiFlags
import com.superiorsoftllc.agenthq.ui.protocol.SecretEntryRow
import com.superiorsoftllc.agenthq.ui.protocol.SecretFieldRow
import com.superiorsoftllc.agenthq.ui.protocol.SecretRow
import com.superiorsoftllc.agenthq.ui.protocol.SecretState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** SECRETS-2: renders Configuration > Secrets (open and demo-locked) to build/secrets-shots for review. */
@OptIn(ExperimentalTestApi::class)
class SecretsViewShotTest {
    private val state = SecretState(
        "os",
        listOf(SecretRow("update-token", "Update token", set = true, tail = "••••WXYZ")),
        listOf(
            SecretEntryRow(
                "android-signing",
                listOf(SecretFieldRow("keyAlias", "••••"), SecretFieldRow("keyPassword", "••••a1b2"), SecretFieldRow("storeFile", "••••.jks"), SecretFieldRow("storePassword", "••••6789")),
            ),
            SecretEntryRow("deploy", listOf(SecretFieldRow("DEPLOY_TOKEN", "••••mnop"))),
        ),
    )

    private fun shot(name: String, s: SecretState) = runDesktopComposeUiTest(1000, 1500) {
        setContent {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {}, secrets = s, initialTab = ConfigTab.SECRETS,
                secretActions = SecretActions(set = { _, _ -> }, clear = {}, import = {}, setField = { _, _, _ -> }, deleteField = { _, _ -> }, delete = {}),
            )
        }
        waitForIdle()
        val out = File("build/secrets-shots").apply { mkdirs() }
        val f = File(out, "$name.png")
        // The dialog is its own root; the last root is it.
        val roots = onAllNodes(isRoot())
        val dialog = roots[roots.fetchSemanticsNodes().size - 1]
        ImageIO.write(dialog.captureToImage().toAwtImage(), "png", f)
        assertTrue(f.length() > 0)
    }

    @Test fun `render the secrets view`() = shot("config-secrets", state)

    @Test fun `render the secrets view under the demo lock`() = shot("config-secrets-locked", state.copy(locked = true))
}
