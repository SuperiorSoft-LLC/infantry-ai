package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.context.ComposedContext
import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.session.ContextSource

/** Fixed context output for A-owned start tests; assertions do not depend on B's composer. */
class FakeTicketContext(
    val prompt: String = DEFAULT_PROMPT,
    val sources: List<ContextSource> = listOf(ContextSource(source = "ticket", chars = prompt.length)),
) : TicketContext {
    companion object {
        const val DEFAULT_PROMPT = "fixed fake ticket prompt"
    }

    override fun compose(input: ContextInput) = ComposedContext(prompt, sources)

    override fun effective(override: Boolean?, skillsOverride: Boolean?) = null
}
