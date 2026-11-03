package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.PolicyWire

const val POLICY_TITLE = "Policy"
const val POLICY_APPLIES_TO =
    "Which policy preset the daemon enforces: what it turns off, for every client. Only the owner switches it; a deployment can pin it."

/** Test tags: a preset choice is POLICY_CHOICE_TAG_PREFIX + its id; the disabled list is POLICY_DISABLED_TAG. */
const val POLICY_CHOICE_TAG_PREFIX = "policy-choice:"
const val POLICY_DISABLED_TAG = "policy-disabled"
const val POLICY_PINNED_TAG = "policy-pinned"
/** GAP-AUDIT-1 (f): the line saying the active preset could not be seated. */
const val POLICY_SEATING_ERROR_TAG = "policy-seating-error"

/**
 * POLICY-1 (docs/POLICY.md section 6): Configuration > General > Policy, a locked trust section. The active preset, the
 * selector (enabled only when the daemon says this connection may switch: the owner, not pinned), why it is pinned, and
 * the read-only list of what the policy turns off and why.
 */
@Composable
fun ConfigPolicySection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.POLICY)) return
    val link = com.superiorsoftllc.infantry.ui.shell.LocalShellContext.current?.link ?: return
    val policy = link.policy
    FlagSection(ConfigEntry.POLICY, config.highlight, modifier) { PolicyBody(policy, link.live, link::selectPolicy) }
}

@Composable
internal fun PolicyBody(policy: PolicyWire, live: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Active: ${policy.label.ifBlank { policy.preset }}", fontSize = 12.sp)
        // GAP-AUDIT-1 (f): a seated preset the daemon could not seat (its project or sessions) says so here, not only in the log.
        policy.seatingError?.let {
            Text(
                "Not fully applied: $it", color = Palette.Danger, fontSize = 11.sp,
                modifier = Modifier.testTag(POLICY_SEATING_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (policy.pinned) {
            Text(
                "Pinned by ${policy.pinnedBy ?: "the deployment"}: change it there and restart the daemon.",
                color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(POLICY_PINNED_TAG),
            )
        } else if (!policy.canSwitch) {
            Text("Only the owner's connection switches the policy.", color = Palette.Muted, fontSize = 11.sp)
        }
        val enabled = live && policy.canSwitch
        Column(Modifier.selectableGroup()) {
            for (choice in policy.presets) {
                val selected = choice.id == policy.preset
                Row(
                    Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { if (!selected) onSelect(choice.id) }
                        .handCursor(enabled)
                        .testTag(POLICY_CHOICE_TAG_PREFIX + choice.id)
                        .semantics { contentDescription = "Policy ${choice.label.ifBlank { choice.id }}" }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(choice.label.ifBlank { choice.id }, fontSize = 12.sp)
                        if (choice.description.isNotBlank()) Text(choice.description, color = Palette.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        Column(Modifier.testTag(POLICY_DISABLED_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (policy.disable.isEmpty()) Text("Nothing is turned off.", color = Palette.Muted, fontSize = 11.sp)
            else {
                val why = policy.reason ?: "turned off by this policy"
                Text("Turned off ($why):", fontSize = 11.sp)
                for (rule in policy.disable) Text("  ${describeRule(rule)}", color = Palette.Muted, fontSize = 11.sp)
                if (policy.allow.isNotEmpty()) {
                    Text("Except:", fontSize = 11.sp)
                    for (rule in policy.allow) Text("  ${describeRule(rule)}", color = Palette.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

/** A policy rule in words: `*`, `kind:<k>`, `area.*`, or an action id. */
internal fun describeRule(rule: String): String = when {
    rule == "*" -> "every action"
    rule.startsWith("kind:") -> "every ${rule.removePrefix("kind:")} action"
    rule.endsWith(".*") -> "every ${rule.removeSuffix(".*")} action"
    else -> rule
}
