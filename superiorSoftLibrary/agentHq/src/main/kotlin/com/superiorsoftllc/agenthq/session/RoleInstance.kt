package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.protocol.TraitCheck
import com.superiorsoftllc.agenthq.protocol.TraitView
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * TRAITS-T4 (docs/AGENTHQ-ROLE-TRAITS.md section 3): a frozen copy of one trait as it was when a session spawned. A copy,
 * not a reference: a later edit of the trait (its version moves) never changes what a started session runs under, and
 * [version] says which rule it ran.
 */
@Serializable
data class TraitSnapshot(
    val id: String,
    val version: Int,
    val name: String,
    val body: String,
    val check: TraitCheck? = null,
) {
    companion object {
        fun of(trait: TraitView): TraitSnapshot = TraitSnapshot(trait.id, trait.version, trait.name, trait.body, trait.check)
    }
}

/**
 * TRAITS-T4: the clone a session is of its agent or role, written ONCE at spawn ([SessionManager.startNew]) and never
 * updated (D4). [instructions] is the persona frozen at spawn and [traits] the effective trait set
 * ([com.superiorsoftllc.agenthq.agents.TraitBindings.effective]) as snapshots, in order. The executor and the link to the
 * parent agent are NOT here: they already live on [SessionRecord] (`provider/model/effort`, `agentId`, `role`). No
 * transcript, ever (D6). Daemon-side only: it is not on the wire.
 *
 * P10-K13 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D11): [skillIds] is the effective skill set
 * ([com.superiorsoftllc.agenthq.agents.SkillAttachment.effective]) resolved at spawn, ids only, in effective order. Like
 * the rest of the row it is never updated: a ticket, agent or project edited after spawn does not reach it. Bodies are
 * read when the turn renders (a skill deleted since renders as `(deleted)`).
 */
data class RoleInstance(
    val agentId: String?,
    val role: String?,
    val instructions: String?,
    val traits: List<TraitSnapshot> = emptyList(),
    val skillIds: List<String> = emptyList(),
)

/** TRAITS-T4: where [RoleInstance]s are kept, keyed by session id. Insert-only: the first instance for a session wins. */
interface SessionInstanceRepository {
    /** Stores [instance] for [sessionId] unless the session already has one (then the stored row is kept, unchanged). */
    fun insert(sessionId: String, instance: RoleInstance, at: Long)
    fun get(sessionId: String): RoleInstance?
    fun delete(sessionId: String)
}

/** The default for a [SessionManager] built without the daemon's database (tests, embedded use). */
class InMemorySessionInstances : SessionInstanceRepository {
    private val rows = ConcurrentHashMap<String, RoleInstance>()
    override fun insert(sessionId: String, instance: RoleInstance, at: Long) {
        rows.putIfAbsent(sessionId, instance)
    }
    override fun get(sessionId: String): RoleInstance? = rows[sessionId]
    override fun delete(sessionId: String) {
        rows.remove(sessionId)
    }
}
