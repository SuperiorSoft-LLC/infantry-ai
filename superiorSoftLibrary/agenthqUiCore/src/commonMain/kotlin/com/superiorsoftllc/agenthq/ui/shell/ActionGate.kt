package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.plugin.api.ActionKind
import com.superiorsoftllc.agenthq.plugin.api.PolicyWire

/**
 * POLICY-1 (docs/POLICY.md): whether ONE named action may be performed here, and why not. A gate is enabled when the link
 * is live, the daemon's policy ([LinkContext.policy]) allows the action, and (for anything but a read) the nearest
 * [WritePolicy] allows writes. The daemon runs the same matcher on the same id, so the dimming and the refusal agree.
 */
data class ActionGate(
    val actionId: String,
    /** Live and allowed: the control may be used now. */
    val enabled: Boolean,
    /** Why the policy (or a write policy) refuses it; null when only the link is down, or when allowed. */
    val reason: String?,
    /** Allowed by the policy and the write policies, whatever the link (a control that also works offline asks this). */
    val allowed: Boolean = enabled,
) {
    /** A control's label: [normal], or the reason while the action is off. */
    fun label(normal: String): String = if (!enabled && reason != null) reason else normal

    companion object {
        /**
         * The pure decision ([rememberGate] supplies [writes] from the nearest [WritePolicy]). [actionId] "" = the generic
         * undeclared write: refused by any rule that matches an undeclared action of its [kind] (`*`, `kind:write`).
         */
        fun of(actionId: String, kind: String, live: Boolean, policy: PolicyWire, writes: WritePolicy): ActionGate {
            val allowed = policy.allows(actionId, kind)
            val written = kind == ActionKind.READ || writes.canWrite
            val reason = when {
                !allowed -> policy.reason ?: "Disabled by the '${policy.label.ifBlank { policy.preset }}' policy"
                !written -> writes.reason
                else -> null
            }
            return ActionGate(actionId, live && allowed && written, reason, allowed && written)
        }
    }
}

/** The gate of [actionId] (a [kind] action) on this link. */
@Composable
fun LinkContext.gate(actionId: String, kind: String = ActionKind.WRITE): ActionGate =
    ActionGate.of(actionId, kind, live, policy, LocalWritePolicy.current)

/** The gate of [actionId] on the window's link (the shell's), for a control drawn without a [LinkContext] in hand. */
@Composable
fun rememberGate(actionId: String, kind: String = ActionKind.WRITE): ActionGate {
    val link = LocalShellContext.current?.link
    return ActionGate.of(actionId, kind, link?.live ?: false, link?.policy ?: PolicyWire.Production, LocalWritePolicy.current)
}

/**
 * The ONE helper a gated control is drawn through: [content] gets the gate (enabled, reason), and a
 * [com.superiorsoftllc.agenthq.ui.WritePolicyTooltip] inside it says this gate's reason on hover.
 */
@Composable
fun Gated(actionId: String, kind: String = ActionKind.WRITE, link: LinkContext? = null, content: @Composable (ActionGate) -> Unit) {
    val gate = link?.gate(actionId, kind) ?: rememberGate(actionId, kind)
    androidx.compose.runtime.CompositionLocalProvider(LocalActionGate provides gate) { content(gate) }
}

/** The gate of the nearest [Gated] (what its [com.superiorsoftllc.agenthq.ui.WritePolicyTooltip] says); null outside one. */
val LocalActionGate = androidx.compose.runtime.compositionLocalOf<ActionGate?> { null }

/** A text button for a named action: dimmed with the policy's reason on hover while off, and its click is blocked. */
@Composable
fun ActionButton(
    actionId: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: String = ActionKind.WRITE,
    link: LinkContext? = null,
    enabled: Boolean = true,
) {
    Gated(actionId, kind, link) { gate ->
        com.superiorsoftllc.agenthq.ui.WritePolicyTooltip { tip ->
            TextButton(
                onClick = { if (gate.enabled && enabled) onClick() },
                enabled = gate.enabled && enabled,
                modifier = modifier.then(tip).testTag("$ACTION_BUTTON_TAG:$actionId"),
            ) { Text(label) }
        }
    }
}

/** Test tag prefix of an [ActionButton] (`action:<id>`). */
const val ACTION_BUTTON_TAG = "action"

/** The gate of [actionId] on [this] link state, for a pane that holds the app state rather than a [LinkContext]. */
@Composable
fun com.superiorsoftllc.agenthq.ui.model.ConnectionInfo.gate(actionId: String, kind: String = ActionKind.WRITE): ActionGate =
    ActionGate.of(actionId, kind, isLive, policy, LocalWritePolicy.current)
