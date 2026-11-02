package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionBinding
import com.superiorsoftllc.agenthq.settings.LimitDef

/**
 * AR-3b: what a layer above the core decides about a session it has bound to work (see [SessionBinding]). The core asks
 * these questions and never names what the binding is: the board's policy answers them for tickets (deadline,
 * one-binding-one-agent rule, gate lock, commit counts). [None] binds nothing and refuses nothing.
 */
interface SessionBindingPolicy {
    /** The binding for the opaque bound id carried by a start / a record, or null for none. */
    fun bindingFor(boundId: String?): SessionBinding?

    /** The OTHER busy session that a start or resume of [agentId] under [binding] would run next to, or null. */
    fun twin(exceptId: String?, agentId: String?, binding: SessionBinding?, sessions: Collection<SessionRecord>): SessionRecord?

    /** The refusal text for [twin] ([what] = "start" or "resume of <id>"). */
    fun twinRefusal(what: String, agentId: String?, binding: SessionBinding?, twin: SessionRecord): String

    /** The run deadline of an unattended run bound by [binding] (null = the generic unattended deadline). [live] reads a live limit. */
    fun deadlineMs(binding: SessionBinding?, live: (LimitDef, Long) -> Long): Long?

    /** The setting that names the deadline above, for the kill text; null = the generic one. */
    fun deadlineSetting(binding: SessionBinding?): String?

    /** Work for [sessionId] under [binding] holds a machine-wide lock right now (it is working, not stalled). */
    fun lockHeld(sessionId: String, binding: SessionBinding?): Boolean

    /** Progress counter of [session] (e.g. commits on its branch); null = none. */
    fun progressCount(session: SessionRecord): Int?

    object None : SessionBindingPolicy {
        override fun bindingFor(boundId: String?): SessionBinding? = null
        override fun twin(exceptId: String?, agentId: String?, binding: SessionBinding?, sessions: Collection<SessionRecord>): SessionRecord? = null
        override fun twinRefusal(what: String, agentId: String?, binding: SessionBinding?, twin: SessionRecord) = "$what refused"
        override fun deadlineMs(binding: SessionBinding?, live: (LimitDef, Long) -> Long): Long? = null
        override fun deadlineSetting(binding: SessionBinding?): String? = null
        override fun lockHeld(sessionId: String, binding: SessionBinding?) = false
        override fun progressCount(session: SessionRecord): Int? = null
    }
}
