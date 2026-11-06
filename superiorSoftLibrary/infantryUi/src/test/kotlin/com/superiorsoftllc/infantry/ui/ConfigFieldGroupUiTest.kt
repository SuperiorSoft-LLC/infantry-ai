// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigSchemaFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.ConfigManifest
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.config.GENERATED_FIELD_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_INPUT_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.uiPrefsOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * FORMAT-1 (ticket f759bfa2): on a generated settings card each field is one visual group (label, description, then its
 * editor), and the next field's label starts clearly below the previous editor, so a value is never typed into the
 * wrong box. The owner typed a GitHub repo into "Source" because the editor sat as close to the next label as to its own.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigFieldGroupUiTest {
    private var saved: MutableMap<String, String>? = null
    @BeforeTest fun pin() { saved = uiPrefsOverride; uiPrefsOverride = mutableMapOf() }
    @AfterTest fun restore() { uiPrefsOverride = saved }

    private val node = "daemon.Acme_Updates"
    private val keys = listOf("acme.source", "acme.repo", "acme.channel")
    private val schema = ConfigSchemaFrame(
        7, listOf(ConfigDeclaration(node, "Updates", listOf(
            ConfigFieldDeclaration("acme.source", "text", label = "Source (public or private)", order = 1, scope = "daemon",
                description = "public never sends a token. private reads a read-only token from env, else the keystore, else the OS credential store."),
            ConfigFieldDeclaration("acme.repo", "text", label = "GitHub repo (owner/repo)", order = 2, scope = "daemon"),
            ConfigFieldDeclaration("acme.channel", "text", label = "Channel (production or unstable)", order = 3, scope = "daemon",
                description = "production = GitHub releases; unstable = the local releases folder below."),
        ), title = "Updater", tab = "General")),
    )
    private val wire = Json { encodeDefaults = true }
    private fun frames() = listOf(
        Envelope(Envelope.CONFIG_SCHEMA, wire.encodeToString(ConfigSchemaFrame.serializer(), schema)),
        Envelope(Envelope.CONFIG_VALUES, wire.encodeToString(ConfigValuesFrame.serializer(), ConfigValuesFrame(7, listOf(NodeConfigEntry(node, "acme.source", JsonPrimitive("public"), "user"))))),
    )

    private var manifest: ConfigManifest? = null
    private val api = object : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> = Result.success(emptyList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> = Result.success(emptyList())
        override suspend fun manifest(host: String) = Result.success(manifest)
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> { this@ConfigFieldGroupUiTest.manifest = manifest; return Result.success(manifest) }
    }

    private fun factory(): TabFactory {
        val all = CoreModules.all
        return TabFactory(all, statuses = all.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) }, configApi = api)
    }

    @Test fun eachFieldIsLabelDescriptionEditorAndTheNextLabelStartsClearlyBelowTheEditor() {
        val tabs = factory()
        withConfigDialogLink(tabs, frames = frames()) { controller, _ ->
            waitUntil(timeoutMillis = 10_000) { controller.state.value.daemonConfigValues != null }
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            onNodeWithTag(GENERATED_FIELD_TAG_PREFIX + "$node/acme.source").performScrollTo()
            waitForIdle()
            val dir = File("build/format1-shots").apply { mkdirs() }
            runCatching { onAllNodes(isRoot())[1].captureToImage().toAwtImage() }.onSuccess { ImageIO.write(it, "png", File(dir, "settings-group.png")) }
            val groups = keys.map { onNodeWithTag(GENERATED_FIELD_TAG_PREFIX + "$node/$it").getBoundsInRoot() }
            val inputs = keys.map { onNodeWithTag(NODE_FIELD_INPUT_TAG_PREFIX + "$node/$it", useUnmergedTree = true).getBoundsInRoot() }
            val labels = listOf("Source (public or private)", "GitHub repo (owner/repo)", "Channel (production or unstable)")
                .map { onNodeWithText(it, useUnmergedTree = true).getBoundsInRoot() }
            for (i in keys.indices) {
                // Order inside one group: label, then (description), then the editor; all inside the group's box.
                assertTrue(labels[i].bottom <= inputs[i].top, "field $i: the editor starts below its own label: ${labels[i]} / ${inputs[i]}")
                assertTrue(inputs[i].top >= groups[i].top && inputs[i].bottom <= groups[i].bottom, "field $i: the editor is inside its group: ${groups[i]} / ${inputs[i]}")
                assertTrue(labels[i].top >= groups[i].top && labels[i].bottom <= groups[i].bottom, "field $i: the label is inside its group")
            }
            val descriptions = schema.declarations[0].fields.map { f -> f.description?.let { onNodeWithText(it, useUnmergedTree = true).getBoundsInRoot() } }
            for (i in 0 until keys.size - 1) {
                val ownGap = inputs[i].top - (descriptions[i] ?: labels[i]).bottom
                val nextGap = labels[i + 1].top - inputs[i].bottom
                // The groups never overlap, and the editor sits nearer its own label than the next one's.
                assertTrue(groups[i].bottom <= groups[i + 1].top, "groups $i and ${i + 1} overlap: ${groups[i]} / ${groups[i + 1]}")
                assertTrue(nextGap >= 12.dp, "field $i: at least 12dp before the next label, was $nextGap")
                assertTrue(nextGap > ownGap, "field $i: own gap $ownGap, next gap $nextGap")
            }
        }
    }
}
