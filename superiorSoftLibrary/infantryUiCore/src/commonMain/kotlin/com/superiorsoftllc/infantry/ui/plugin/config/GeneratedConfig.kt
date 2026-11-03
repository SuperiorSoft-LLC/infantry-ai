package com.superiorsoftllc.infantry.ui.plugin.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldValidator
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import com.superiorsoftllc.infantry.ui.CONFIG_HIGHLIGHT_STATE
import com.superiorsoftllc.infantry.ui.CONFIG_SECTION_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.ConfigEntry
import com.superiorsoftllc.infantry.ui.ConfigTab
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.RadioButton
import com.superiorsoftllc.infantry.ui.handCursor
import com.superiorsoftllc.infantry.ui.Switch
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.shell.parseConfigColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A generated card that no [ConfigEntry] stands for; the suffix is `<node>/<group>`. */
const val GENERATED_CARD_TAG_PREFIX = "config-card-"
/** CONFIG-11: the daemon's refusal line on a daemon card, and the read-only note on an unsigned plugin's card. */
const val GENERATED_DAEMON_ERROR_TAG = "config-daemon-error"
const val GENERATED_READONLY_TAG_PREFIX = "config-readonly-"
/** One generated field; the suffix is `<node>/<key>`. A toggle is [NODE_FIELD_CHECK_TAG_PREFIX] + `<node>/<key>`, an option [NODE_FIELD_OPTION_TAG_PREFIX]. */
const val GENERATED_FIELD_TAG_PREFIX = "config-field-"

/**
 * CONFIG-3 (docs/CONFIG-MANIFEST-DESIGN.md section 4): the Settings dialog's tabs that are generated from the compiled
 * manifest's declarations. Phase 1 pilots Appearance only; every other tab stays hand-written until CONFIG-5.
 */
val GENERATED_TABS: Set<ConfigTab> = setOf(ConfigTab.APPEARANCE)

/** One card of a generated tab: a declaration that has a `tab`, with the module that declared it. */
class ConfigCard(val module: String, val declaration: ConfigDeclaration) {
    val node: String get() = declaration.node
    val title: String get() = declaration.title ?: declaration.group
    val applies: String? get() = declaration.applies
    val fields: List<ConfigFieldDeclaration> get() = declaration.fields.sortedBy { it.order }

    /** The hand-written section this card replaces (same tab, same title), so its test tag, search entry and highlight carry over. */
    fun replaces(): ConfigEntry? = ConfigEntry.entries.firstOrNull { it.tab.label == declaration.tab && it.title == title }
}

/**
 * The generated cards of [tab], from the snapshot (the daemon's compiled manifest), else the local tree: no jar, no card.
 * Sorted by `order`, ties in load order. Empty for a tab outside [GENERATED_TABS].
 */
fun generatedCards(factory: TabFactory?, tab: ConfigTab?): List<ConfigCard> {
    if (factory == null || tab == null || tab !in GENERATED_TABS) return emptyList()
    return factory.config.declarations
        .filter { it.declaration.tab == tab.label && it.declaration.fields.isNotEmpty() && it.declaration.block == null }
        .sortedBy { it.declaration.order }
        .map { ConfigCard(it.module, it.declaration) }
}

/** The hand-written sections that a generated card replaces on [tab]; the dialog draws the card instead. */
fun generatedEntries(factory: TabFactory?, tab: ConfigTab?): Set<ConfigEntry> = generatedCards(factory, tab).mapNotNull { it.replaces() }.toSet()

/** CONFIG-3: the cards of [tab] drawn at the top of its body; [highlight] outlines the card standing in for a searched section. */
@Composable
fun GeneratedCards(factory: TabFactory?, tab: ConfigTab?, highlight: ConfigEntry?) {
    if (factory == null) return
    for (card in generatedCards(factory, tab)) GeneratedCard(factory, card, highlight)
}

/**
 * CONFIG-5: one form card of the plan (a snapshot-driven dialog), lit when it is [highlight] or the card [highlightKey]
 * (a search hit that has no hand-written section). A card that replaces a section keeps its `config-section-*` tag.
 */
@Composable
fun PlanFormCard(factory: TabFactory, card: PlanCard, highlight: ConfigEntry?, highlightKey: String?) {
    GeneratedCard(factory, ConfigCard(card.module, card.declaration), highlight, highlightKey)
}

@Composable
private fun GeneratedCard(factory: TabFactory, card: ConfigCard, highlight: ConfigEntry?, highlightKey: String? = null) {
    val entry = card.replaces()
    val lit = (entry != null && entry == highlight) || highlightKey == "${card.node}/${card.declaration.group}"
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(lit) { if (lit) requester.bringIntoView() }
    val tag = entry?.let { CONFIG_SECTION_TAG_PREFIX + it.name } ?: "$GENERATED_CARD_TAG_PREFIX${card.node}/${card.declaration.group}"
    Column(
        Modifier.testTag(tag).bringIntoViewRequester(requester)
            .then(if (lit) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(6.dp)).padding(8.dp) else Modifier)
            .semantics { if (lit) stateDescription = CONFIG_HIGHLIGHT_STATE },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(card.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        card.applies?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
        // CONFIG-11: a daemon card is editable while the daemon answers, unless it is read-only (an unsigned plugin's).
        val daemonCard = factory.config.isDaemonNode(card.node)
        val editable = (daemonCard || factory.config.load == NodeConfigLoad.LOADED) && !card.declaration.locked && !card.declaration.readOnly
        if (card.declaration.readOnly) Text("Read-only: this plugin is not signed.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(GENERATED_READONLY_TAG_PREFIX + card.node))
        for (f in card.fields) if (ConfigFieldValidator.isVisible(f) { factory.resolvedConfig.json(card.node, it) }) GeneratedField(factory, card, f, editable)
        if (daemonCard) factory.config.daemonError?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(GENERATED_DAEMON_ERROR_TAG)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneratedField(factory: TabFactory, card: ConfigCard, f: ConfigFieldDeclaration, editable: Boolean) {
    val node = card.node
    val tag = "$node/${f.key}"
    val scope = rememberCoroutineScope()
    val value: JsonElement? = factory.resolvedConfig.json(node, f.key)
    val own = factory.config.own(node, f.key)
    // CONFIG-5: a value its type's rule refuses is not written; the reason shows under the editor.
    var problem by remember(tag, value) { mutableStateOf<String?>(null) }
    fun write(v: JsonElement?) {
        val why = if (v == null) null else factory.fieldTypes.validate(f, v)
        problem = why
        if (why == null) scope.launch { factory.config.set(node, f.key, v) }
    }
    val declared = DeclaredField(node, card.module, card.declaration.group, f)
    Column(Modifier.fillMaxWidth().testTag(GENERATED_FIELD_TAG_PREFIX + tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(f.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        f.description?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            when (f.type) {
                ConfigFieldTypes.CHECKBOX, ConfigFieldTypes.TOGGLE -> {
                    val on = (value as? JsonPrimitive)?.booleanOrNull ?: false
                    Switch(
                        checked = on, onCheckedChange = if (editable) { v -> write(JsonPrimitive(v)) } else null, enabled = editable,
                        modifier = Modifier.testTag(NODE_FIELD_CHECK_TAG_PREFIX + tag).semantics { contentDescription = f.label },
                    )
                    Text(if (on) "On" else "Off", fontSize = 12.sp)
                }
                ConfigFieldTypes.SELECT -> if (f.display == "dropdown") SelectDropdown(f, tag, value, editable, ::write) else for (o in f.options) Option(tag, o, f.optionLabels[o] ?: o, (value as? JsonPrimitive)?.content == o, editable) { write(JsonPrimitive(o)) }
                ConfigFieldTypes.RADIO -> Column(Modifier.selectableGroup()) {
                    for (o in f.options) {
                        val picked = (value as? JsonPrimitive)?.content == o
                        Row(
                            Modifier.selectable(picked, enabled = editable, role = Role.RadioButton) { write(JsonPrimitive(o)) }.handCursor(editable).testTag("$NODE_FIELD_OPTION_TAG_PREFIX$tag/$o"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(picked, null, enabled = editable)
                            Text(f.optionLabels[o] ?: o, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
                ConfigFieldTypes.COLOR -> {
                    val argb = parseConfigColor((value as? JsonPrimitive)?.content)
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(argb?.let { Color(it.toInt()) } ?: Color.Transparent)
                            .border(1.dp, Palette.Muted, RoundedCornerShape(4.dp)).testTag("config-swatch-$tag"),
                    )
                    TextEditor(declared, tag, value, editable, ::write)
                }
                ConfigFieldTypes.TEXT, ConfigFieldTypes.NUMBER -> TextEditor(declared, tag, value, editable, ::write)
                ConfigFieldTypes.MULTI_SELECT -> {
                    val picked = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                    for (o in f.options) Option(tag, o, f.optionLabels[o] ?: o, o in picked, editable) {
                        write(JsonArray((if (o in picked) picked - o else f.options.filter { it in picked || it == o }).map(::JsonPrimitive)))
                    }
                }
                // EXAMPLE-1: CONFIG-9's editors, as the Plugins view draws them (the card showed these read-only).
                ConfigFieldTypes.CLASSES -> ClassesEditor(declared, tag, value, editable, ::write)
                ConfigFieldTypes.LIST -> f.objectItemFields()?.let { ObjectListEditor(f, it, tag, value, editable, ::write) } ?: ListEditor(declared, tag, value, editable, ::write)
                ConfigFieldTypes.GROUP -> GroupEditor(f.fields, tag, value, editable, ::write)
                else -> factory.fieldTypes[f.type]?.let { RegisteredEditor(factory, f, value, editable, ::write) } ?: Chip(value?.toString() ?: "unset")
            }
            problem?.let { Chip(it, Palette.Danger) }
            if (editable && own?.setBy == ConfigSetBy.USER) {
                TextButton({ write(null) }, Modifier.testTag(NODE_FIELD_RESET_TAG_PREFIX + tag).semantics { contentDescription = "Reset ${f.label}" }) { Text("Reset") }
            }
        }
    }
}
