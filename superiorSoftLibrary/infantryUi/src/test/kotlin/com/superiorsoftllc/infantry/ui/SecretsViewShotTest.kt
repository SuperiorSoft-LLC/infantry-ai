// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.protocol.SecretEntryRow
import com.superiorsoftllc.infantry.ui.protocol.SecretFieldRow
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import com.superiorsoftllc.infantry.ui.protocol.SecretState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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

    private fun shot(name: String, s: SecretState, project: SecretProjectRef? = null) = runDesktopComposeUiTest(1000, 1500) {
        setContent {
            InfantryTheme {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {}, secrets = s, initialTab = ConfigTab.SECRETS,
                secretActions = SecretActions(set = { _, _ -> }, clear = {}, import = {}, setField = { _, _, _ -> }, deleteField = { _, _ -> }, delete = {}, move = { _, _, _ -> }, currentProject = project),
            )
            }
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

    @Test fun `render the secrets view with a project on screen`() = shot(
        "config-secrets-scoped",
        state.copy(entries = state.entries + SecretEntryRow("deploy", listOf(SecretFieldRow("DEPLOY_TOKEN", "••••zz99")), project = "p1")),
        SecretProjectRef("p1", "Site"),
    )

    @Test fun `render the empty secrets view`() = shot("config-secrets-empty", state.copy(entries = emptyList()))

    @Test fun `render a project's secrets editor`() = runDesktopComposeUiTest(700, 700) {
        setContent {
            InfantryTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(Palette.Panel).padding(16.dp)) {
                    SecretsScopeEditor(
                        entries = listOf(SecretEntryRow("deploy", listOf(SecretFieldRow("DEPLOY_TOKEN", "••••zz99")), project = "p1")),
                        locked = false, title = "Site secrets", hint = SecretForm.PROJECT_APPLIES_TO, scopeBadge = SecretForm.SCOPE_PROJECT,
                        actions = SecretScopeActions(setField = { _, _, _ -> }, deleteField = { _, _ -> }, delete = {}, move = {}, moveLabel = "Move to app"),
                        note = { "Overrides the app secret of the same name" },
                    )
                }
            }
        }
        waitForIdle()
        val f = File(File("build/secrets-shots").apply { mkdirs() }, "project-secrets.png")
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", f)
        assertTrue(f.length() > 0)
    }
}
