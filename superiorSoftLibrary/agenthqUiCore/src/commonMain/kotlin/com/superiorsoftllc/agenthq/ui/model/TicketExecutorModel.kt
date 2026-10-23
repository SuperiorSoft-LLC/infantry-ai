package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.EffectiveExecutor
import com.superiorsoftllc.agenthq.ui.protocol.ExecutorField
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Ticket

/**
 * TICKET-EXEC-UI: who a ticket goes to and what it runs on, as the board's forms hold them. Every field null
 * means "inherit / no pin": [agentId] null is unassigned on an edit (Auto on a create), [provider] null inherits the
 * profile's / role's / daemon's harness, [model] null inherits the model.
 */
data class TicketPins(val agentId: String?, val provider: String?, val model: String?) {
    companion object {
        /** What [t] pins today; blanks read as "no pin". */
        fun of(t: Ticket): TicketPins = TicketPins(t.agentId.nonBlank(), t.provider.nonBlank(), t.model.nonBlank())
    }
}

/**
 * TICKET-EXEC-UI: the `ticket_save` fields an edit sends. Null = unchanged (left out of the frame), "" = clear the
 * pin (the contract's own rule for `agentId`, `provider` and `model` on an edit), else the new value.
 */
data class TicketPinEdit(val agentId: String?, val provider: String?, val model: String?) {
    val isEmpty: Boolean get() = agentId == null && provider == null && model == null
}

/** TICKET-EXEC-UI: only what changed between [before] and [after]; a pin taken away is sent as "". */
fun ticketPinEdit(before: TicketPins, after: TicketPins): TicketPinEdit = TicketPinEdit(
    agentId = changed(before.agentId, after.agentId),
    provider = changed(before.provider, after.provider),
    model = changed(before.model, after.model),
)

private fun changed(old: String?, new: String?): String? {
    val o = old.nonBlank()
    val n = new.nonBlank()
    return if (o == n) null else n ?: ""
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * TICKET-EXEC-UI: a model belongs to its provider. When the form's provider becomes [provider], [model] survives only
 * when that provider's catalog lists it; otherwise it falls back to inherit (null). A provider of null (inherit) takes
 * the model with it, since a model pin with no provider pin would name an id of a harness nobody chose.
 */
fun modelForProvider(
    harnesses: List<ProviderInfo>,
    catalog: List<ProviderModels>,
    provider: String?,
    model: String?,
): String? {
    val m = model.nonBlank() ?: return null
    val p = provider.nonBlank() ?: return null
    val full = ExecutorPickerModel.catalogWithProviderLists(harnesses, catalog)
    return m.takeIf { full.any { row -> row.provider == p && m in row.models } }
}

/**
 * TICKET-EXEC-UI: the card's "runs on" line: each resolved value with where it came from, e.g.
 * `claude (ticket) / opus-5 (profile)`. A value the source leaves open reads `default`. Null when the daemon sent no
 * executor view at all.
 */
fun executorLine(e: EffectiveExecutor?): String? {
    if (e == null || (e.provider == null && e.model == null)) return null
    fun part(f: ExecutorField?): String = f?.let { "${it.value ?: "default"} (${it.source})" } ?: "default (default)"
    return "${part(e.provider)} / ${part(e.model)}"
}
