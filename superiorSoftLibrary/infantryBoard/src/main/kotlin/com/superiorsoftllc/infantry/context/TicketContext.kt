package com.superiorsoftllc.infantry.context

import com.superiorsoftllc.infantry.protocol.EffectiveContext
import com.superiorsoftllc.infantry.session.ContextSource

/** A linked ticket's prompt-safe display line. Bodies deliberately are not part of this type. */
data class LinkLine(
    val type: String,
    val ticketId: String,
    val title: String,
    val status: String,
)

/** Only the ticket core, profile instructions, link lines and context switch reach a composer. */
data class ContextInput(
    val instructions: String?,
    val title: String,
    val description: String,
    val links: List<LinkLine>,
    val includeLinkedOverride: Boolean?,
    val ticketId: String? = null,
    val projectId: String? = null,
    /** BUG-808ce596 follow-up: who the started session runs as, named after the persona ([identityLine]). */
    val role: String? = null,
    val agentId: String? = null,
    /** TRAITS-T5: the started session's standing rules, from its frozen instance; rendered as the `traits` source. */
    val traits: List<com.superiorsoftllc.infantry.session.TraitSnapshot> = emptyList(),
    /** P10-K15: the started session's skill ids, from its instance; null = no instance (anonymous), so no section. */
    val skillIds: List<String>? = null,
    /** P10-K15: the ticket's own `includeSkillsContext`; null inherits the project's (K-D9). */
    val includeSkillsOverride: Boolean? = null,
    /** BUG-MAIL-2: the session that started this ticket, named in the Team block; null = a UI start, no line. */
    val reportTo: ReportTo? = null,
    /** IMG-3: the ticket's attached images, as absolute paths; each is listed as `[image: <path>]` in the brief. */
    val images: List<String> = emptyList(),
)

/** BUG-MAIL-2: the session a ticket's worker reports to ([name] is its agent's name, else its role). */
data class ReportTo(val name: String, val sessionId: String)

data class ComposedContext(
    val prompt: String,
    val sources: List<ContextSource>,
)

interface TicketContext {
    fun compose(input: ContextInput): ComposedContext
    /** [override] is the ticket's linked-tasks switch; [skillsOverride] its skills switch (P10-K11). Null inherits. */
    fun effective(override: Boolean?, skillsOverride: Boolean? = null): EffectiveContext?

    companion object {
        /** Safe default for direct board tests that do not start sessions or render context views. */
        val NONE: TicketContext = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
    }
}
