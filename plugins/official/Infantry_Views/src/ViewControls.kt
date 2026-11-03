// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.BlockType
import com.superiorsoftllc.infantry.ui.block.layout.LocalBlockSize
import com.superiorsoftllc.infantry.ui.model.PanelLayout
import com.superiorsoftllc.infantry.ui.block.layout.Place
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the widgets of one open view need to act: the runner and the tool kinds. Null runner = no actions (drawn disabled). */
class ViewActionHost(val runner: ActionRunner?, val kinds: ToolKinds?)

val LocalViewActions = compositionLocalOf { ViewActionHost(null, null) }

private val NONE = JsonObject(emptyMap())

@Composable
private fun BlockScope.json(): JsonObject = LocalViewProps.current[blockId] ?: NONE

/** The click handler for [action] (asks first when [confirm] or a destructive tool says so) and the dialog to compose. */
@Composable
private fun actionClick(action: ViewAction?, confirm: String?): Pair<(() -> Unit)?, @Composable () -> Unit> {
    val host = LocalViewActions.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val runner = host.runner
    if (action == null || action is ViewAction.Invalid || runner == null) return null to {}
    val go: () -> Unit = { scope.launch { runner.run(action) } }
    val click: () -> Unit = { if (needsConfirm(action, confirm, host.kinds)) asking = true else go() }
    val dialog: @Composable () -> Unit = {
        if (asking) AlertDialog(
            onDismissRequest = { asking = false }, modifier = Modifier.testTag("view-confirm"),
            text = { Text(confirmText(action, confirm)) },
            confirmButton = { TextButton({ asking = false; go() }, Modifier.testTag("view-confirm-yes")) { Text("Confirm") } },
            dismissButton = { TextButton({ asking = false }, Modifier.testTag("view-confirm-no")) { Text("Cancel") } },
        )
    }
    return click to dialog
}

private class ListBlock(id: String, place: Place = Place()) : Block("ui.list", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        val json = scope.json()
        val items = ViewData.items(json)
        Column(scope.modifier.fillMaxWidth().testTag("view-list")) {
            if (items.isEmpty()) Text(Props(json).string("empty", "Nothing here")!!, Modifier.padding(8.dp).testTag("view-list-empty"), color = Tones.color("muted"), style = MaterialTheme.typography.bodyMedium)
            items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider()
                val (click, dialog) = actionClick(item.action, null)
                Row(
                    Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable(onClick = click) else Modifier).padding(horizontal = 8.dp, vertical = 8.dp).testTag("view-list-item-${item.id}"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tones.color("muted"), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    item.badgeText?.let { ToneBadge(it, item.badgeTone) }
                }
                dialog()
            }
        }
    }
}

@Composable
private fun ToneBadge(text: String, tone: String) {
    val c = Tones.color(tone)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.15f), border = BorderStroke(1.dp, c.copy(alpha = 0.5f))) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = c, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private class TableBlock(id: String, place: Place = Place()) : Block("ui.table", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        val json = scope.json()
        val width = LocalBlockSize.current.width
        val compact = width != androidx.compose.ui.unit.Dp.Unspecified && width.value < PanelLayout.COMPACT_BELOW_DP
        val cols = ViewData.visibleColumns(ViewData.columns(json), compact)
        val rows = ViewData.rows(json)
        Column(scope.modifier.fillMaxWidth().testTag("view-table")) {
            if (rows.isEmpty()) {
                Text(Props(json).string("empty", "Nothing here")!!, Modifier.padding(8.dp).testTag("view-table-empty"), color = Tones.color("muted"), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                for (c in cols) Text(c.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Tones.color("muted"), textAlign = if (c.alignEnd) TextAlign.End else TextAlign.Start, maxLines = 1)
            }
            HorizontalDivider()
            for (row in rows) {
                val (click, dialog) = actionClick(row.action, null)
                Row(Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable(onClick = click) else Modifier).padding(horizontal = 8.dp, vertical = 8.dp).testTag("view-table-row-${row.id}")) {
                    for (c in cols) Text(row.cells[c.key].orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = if (c.alignEnd) TextAlign.End else TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                dialog()
                HorizontalDivider()
            }
        }
    }
}

private class ButtonBlock(id: String, place: Place = Place()) : Block("ui.button", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        val json = scope.json()
        val p = Props(json)
        val action = ViewAction.parse(json["action"])
        val (click, dialog) = actionClick(action, p.string("confirm"))
        val enabled = click != null && p.bool("enabled", true)
        val label = p.string("label", "")!!
        val m = scope.modifier.testTag("view-button")
        val onClick = click ?: {}
        Column {
            when (p.oneOf("style", listOf("primary", "secondary", "danger"), "secondary")) {
                "primary" -> Button(onClick, m, enabled = enabled) { Text(label) }
                "danger" -> Button(onClick, m, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(label) }
                else -> OutlinedButton(onClick, m, enabled = enabled) { Text(label) }
            }
            (action as? ViewAction.Invalid)?.let { Text(it.line, Modifier.testTag("view-button-problem"), color = Tones.color("bad"), style = MaterialTheme.typography.bodySmall) }
        }
        dialog()
    }
}

/** DV-4 block types; merged into [ViewWidgets]. Their `items`, `columns`, `rows` and `action` are read from the node config. */
object ViewControls {
    private fun empty() = ConfigFieldDeclaration("empty", ConfigFieldTypes.TEXT, "Empty text")

    val declarations: List<BlockTypeDeclaration> = listOf(
        BlockTypeDeclaration("ui.list", "List", listOf(empty())),
        BlockTypeDeclaration("ui.table", "Table", listOf(empty())),
        BlockTypeDeclaration(
            "ui.button", "Button", listOf(
                ConfigFieldDeclaration("label", ConfigFieldTypes.TEXT, "Label"),
                ConfigFieldDeclaration("style", ConfigFieldTypes.SELECT, "Style", JsonPrimitive("secondary"), listOf("primary", "secondary", "danger")),
                ConfigFieldDeclaration("confirm", ConfigFieldTypes.TEXT, "Confirm text"),
                ConfigFieldDeclaration("enabled", ConfigFieldTypes.CHECKBOX, "Enabled", JsonPrimitive(true)),
            ),
        ),
    )

    val registered: Map<String, BlockType> = linkedMapOf(
        "ui.list" to BlockType { id, _ -> ListBlock(id) },
        "ui.table" to BlockType { id, _ -> TableBlock(id) },
        "ui.button" to BlockType { id, _ -> ButtonBlock(id) },
    )
}
