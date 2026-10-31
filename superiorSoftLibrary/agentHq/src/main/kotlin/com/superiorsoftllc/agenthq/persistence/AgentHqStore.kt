package com.superiorsoftllc.agenthq.persistence

import com.superiorsoftllc.agenthq.protocol.TriggerAction
import com.superiorsoftllc.agenthq.protocol.TriggerEventView
import com.superiorsoftllc.agenthq.protocol.TriggerView
import com.superiorsoftllc.agenthq.usage.ProviderState
import com.superiorsoftllc.agenthq.usage.ProviderStatus
import com.superiorsoftllc.agenthq.usage.RunUsage
import com.superiorsoftllc.agenthq.usage.RunUsageRecord
import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.db.AgentHQDatabaseHandle
import com.superiorsoftllc.agenthq.db.Agents
import com.superiorsoftllc.agenthq.db.Attention
import com.superiorsoftllc.agenthq.db.Events
import com.superiorsoftllc.agenthq.db.Node_attempts
import com.superiorsoftllc.agenthq.db.Pod_nodes
import com.superiorsoftllc.agenthq.db.Pods
import com.superiorsoftllc.agenthq.db.Runs
import com.superiorsoftllc.agenthq.db.Settings
import com.superiorsoftllc.agenthq.db.Tickets
import com.superiorsoftllc.agenthq.db.Ticket_runs
import com.superiorsoftllc.agenthq.attention.AttentionItem
import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.role.RoleBinding
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.session.SessionState
import java.io.Closeable

/**
 * Phase 4.5 -- the repository seam over the generated SQLDelight queries.
 *
 * Phase 5 orchestration code depends on these interfaces only. It never imports a generated row
 * class, never writes SQL, and can be unit-tested against a fake store. The one implementation,
 * [SqlAgentHqStore], is the only code that touches `runsQueries` / `podsQueries` / `eventsQueries` / `nodesQueries`
 * outside the db package's own tests.
 */
interface RunRepository {
    /** Inserts [run], or overwrites the row with that id in place (its events survive). */
    fun upsert(run: RunRecord)
    fun get(id: String): RunRecord?
    fun bySession(sessionId: String): List<RunRecord>
    fun byPod(podId: String): List<RunRecord>
    fun unfinished(): List<RunRecord>
    fun delete(id: String)
}

interface PodRepository {
    /** Inserts a new pod; a duplicate id throws rather than silently replacing a live pod. */
    fun create(pod: PodRecord)
    fun get(id: String): PodRecord?
    fun all(): List<PodRecord>
    fun open(): List<PodRecord>
    fun updateState(id: String, state: String)
    fun finish(id: String, state: String, endedAt: Long)

    /** Deletes the pod, its runs and their events (ON DELETE CASCADE). */
    fun delete(id: String)
}

interface EventRepository {
    /** Appends [event] to [runId]'s log and returns its seq. The run must exist. */
    fun append(runId: String, event: RunEvent, at: Long): Long

    /** [runId]'s log in order, strictly after [afterSeq] (0 = from the start). */
    fun forRun(runId: String, afterSeq: Long = 0): List<StoredEvent>
    fun count(runId: String): Long
}

interface NodeRepository {
    /** Inserts a new node; a duplicate id or a duplicate (podId, name) throws. The pod must exist. */
    fun create(node: NodeRecord)
    fun get(id: String): NodeRecord?
    /** Every node of [podId], in spec order. */
    fun byPod(podId: String): List<NodeRecord>
    /** Overwrites ONLY state, attempts, reviewState, detail, endedAt of the row with [node]'s id. */
    fun update(node: NodeRecord)
    fun addDependency(dependency: NodeDependency)
    fun dependencies(podId: String): List<NodeDependency>
    fun recordAttempt(attempt: NodeAttempt)
    /** [nodeId]'s attempts, oldest (attempt 1) first. */
    fun attempts(nodeId: String): List<NodeAttempt>
    /** Nodes whose reviewState is "PENDING", oldest endedAt first, then spec order. */
    fun reviewQueue(): List<NodeRecord>
}

/**
 * Story 9.1: agent profiles. It speaks the wire type [AgentProfile] directly, with no second DTO, the
 * same rule as [RunRecord]. Validation is the caller's ([com.superiorsoftllc.agenthq.agents.AgentProfiles]);
 * this layer stores what it is given.
 */
interface AgentRepository {
    /** Creates the profile with [profile]'s id (which must be set), or overwrites that profile, at [at]. */
    fun save(profile: AgentProfile, at: Long)
    fun get(id: String): AgentProfile?
    /** Every profile in profile order: oldest first, then by id. */
    fun all(): List<AgentProfile>
    /** Deletes the profile; false when there was none with that id. */
    fun delete(id: String): Boolean
}

/** ROLE-1a: the provider / model / effort a role runs on, one row per role id. */
interface RoleBindingRepository {
    /** Creates the binding for [binding]'s role, or overwrites it in place, stamping [at]. */
    fun set(binding: RoleBinding, at: Long)
    fun get(roleId: String): RoleBinding?
    /** Every binding, ordered by role id. */
    fun all(): List<RoleBinding>
}

/** P10-A: the prompt library's rows, keyed by id and unique by name. Rules live in [PromptStore]. */
interface PromptRepository {
    /** Updates the prompt named [name] in place (id and createdAt kept), or creates it with an id from [newId]. */
    fun upsertByName(name: String, body: String, tags: List<String>, at: Long, newId: () -> String): com.superiorsoftllc.agenthq.protocol.PromptView
    fun get(id: String): com.superiorsoftllc.agenthq.protocol.PromptView?
    /** Every prompt ordered by name. */
    fun all(): List<com.superiorsoftllc.agenthq.protocol.PromptView>
    /** Deletes the prompt; false when there was none with that id. */
    fun delete(id: String): Boolean
}

/** TRAITS-T1: the trait library's rows, keyed by slug id. Rules (limits, versioning, check validation) live in [TraitStore]. */
interface TraitRepository {
    /**
     * Reads the trait [id] (null when absent) and stores what [next] returns, in ONE transaction, so two concurrent
     * saves cannot both compute the same version. A result equal to the stored row writes nothing.
     */
    fun saveWith(id: String, next: (com.superiorsoftllc.agenthq.protocol.TraitView?) -> com.superiorsoftllc.agenthq.protocol.TraitView): com.superiorsoftllc.agenthq.protocol.TraitView
    fun get(id: String): com.superiorsoftllc.agenthq.protocol.TraitView?
    /** Every trait ordered by id. */
    fun all(): List<com.superiorsoftllc.agenthq.protocol.TraitView>
    /**
     * Deletes the trait; false when there was none with that id. [beforeDelete] runs inside the same transaction once the
     * row is known to exist (TRAITS-T2/T3: it throws to refuse while a role or an agent still binds the trait).
     */
    fun delete(id: String, beforeDelete: () -> Unit = {}): Boolean
}

/** TRAITS-T2: the ordered trait ids a role binds, one row per role id. Rules live in RoleTraitHandler. */
interface RoleTraitRepository {
    /**
     * Stores [traitIds] for [roleId] (overwriting in place), stamping [at]. [beforeWrite] runs first inside the same
     * transaction (it throws to refuse, e.g. a trait that no longer exists), so a set and a trait delete cannot interleave.
     */
    fun set(roleId: String, traitIds: List<String>, at: Long, beforeWrite: () -> Unit = {}): com.superiorsoftllc.agenthq.protocol.RoleTraits
    fun get(roleId: String): com.superiorsoftllc.agenthq.protocol.RoleTraits?
    /** Every role with a row, ordered by role id. */
    fun all(): List<com.superiorsoftllc.agenthq.protocol.RoleTraits>
}

/** P10-B: the skills library's rows, keyed by id and unique by name. Rules live in [SkillStore]. */
interface SkillRepository {
    /**
     * Updates the skill named [name] in place (id and createdAt kept), or creates it with an id from [newId].
     * P10-K12: a null [attachedOnly] keeps the stored flag (a new skill gets false).
     */
    fun upsertByName(name: String, description: String, body: String, tags: List<String>, at: Long, attachedOnly: Boolean? = null, newId: () -> String): com.superiorsoftllc.agenthq.protocol.SkillView
    fun get(id: String): com.superiorsoftllc.agenthq.protocol.SkillView?
    /** P10-K12: the skill with exactly this name, or null. */
    fun getByName(name: String): com.superiorsoftllc.agenthq.protocol.SkillView?
    /** Every skill ordered by name. */
    fun all(): List<com.superiorsoftllc.agenthq.protocol.SkillView>
    /** Deletes the skill; false when there was none with that id. */
    fun delete(id: String): Boolean
}

/**
 * Story 9.2: the backlog's tickets and the links between them. Every rule (columns, positions, the
 * derived `pending` column, cycles) is the caller's ([com.superiorsoftllc.agenthq.backlog.TicketBoard]);
 * this layer stores what it is given, so a caller that changes several rows wraps them in
 * [AgentHqStore.transaction].
 */
interface TicketRepository {
    /** Inserts a new ticket; a duplicate id throws. */
    fun create(ticket: TicketRow)
    /** Overwrites everything but the id and createdAt of the ticket with [ticket]'s id. */
    fun update(ticket: TicketRow)
    fun get(id: String): TicketRow?
    /** Every ticket. Order is not meaningful here: the board orders by its own column order. */
    fun all(): List<TicketRow>
    /** Deletes the ticket and every link naming it; false when there was none with that id. */
    fun delete(id: String): Boolean
    /** Stores [link] (in its stored direction); false when that exact link was already there. */
    fun link(link: TicketLinkRow): Boolean
    /** Removes [link]'s (ticketId, relatedId, type); false when there was none. */
    fun unlink(link: TicketLinkRow): Boolean
    /** Every link, oldest first. */
    fun links(): List<TicketLinkRow>
}

interface SettingsRepository {
    fun get(key: String): SettingRow?
    fun all(): List<SettingRow>
    fun put(setting: SettingRow)
}

interface TicketRunRepository {
    fun insert(run: TicketRunRow)
    fun finish(ticketId: String, sessionId: String, endedAt: Long, outcome: String, detail: String?)
    fun byTicket(ticketId: String): List<TicketRunRow>
}

/** Story 9.4: durable attention records, ordered by their creation time. */
interface AttentionRepository {
    fun insert(item: AttentionItem)
    fun get(id: String): AttentionItem?
    fun all(): List<AttentionItem>
    /** Returns the row after acknowledgement, or null when [id] does not exist. */
    fun acknowledge(id: String, at: Long): AttentionItem?
    fun openCount(): Long
}

/** Story 9.7b: one row per ended run (`run_usage`); token columns are null when the run reported none. */
interface UsageRepository {
    fun insert(record: RunUsageRecord)
    /** The REPORTED usage of [sessionId]'s runs, oldest first: a run with no signal is not usage. */
    fun forSession(sessionId: String): List<RunUsage>
    /** Every ended run since [epochMillis], reported or not (the budget recount after a restart). */
    fun since(epochMillis: Long): List<RunUsageRecord>
}

/** Story 9.7b: the latest observed limit state per provider (`provider_state`). */
interface ProviderStateRepository {
    fun upsert(state: ProviderState)
    fun all(): List<ProviderState>
}

/** Story 9.5: triggers and the durable disposition row of every event they received. */
interface TriggerRepository {
    /** Stores [trigger] with its schedule period [intervalMs] (null = not a timed trigger, or never due). */
    fun upsert(trigger: TriggerView, intervalMs: Long?)
    fun get(id: String): TriggerView?
    fun all(): List<TriggerView>
    /** Every stored schedule period, keyed by trigger id. Read once at daemon start to restore due times. */
    fun intervals(): Map<String, Long>
    /** Removes the trigger and its event rows; false when there was no such trigger. */
    fun delete(id: String): Boolean
    /** Stores [event] (replacing a row with its id) and keeps only the newest [keep] rows of its trigger. */
    fun insertEvent(event: TriggerEventView, keep: Long = 500)
    fun events(triggerId: String): List<TriggerEventView>
    /** P9.6: stores (replacing) the per-hook HMAC secret of a webhook trigger. Never part of [TriggerView]. */
    fun setWebhookSecret(triggerId: String, secret: String)
    fun webhookSecret(triggerId: String): String?
    fun deleteWebhookSecret(triggerId: String)
    /** TRIGGER-NUDGE: the id of the trigger's prompt mail still waiting for its target, or null. Kept across [upsert]. */
    fun pendingMail(triggerId: String): String?
    fun setPendingMail(triggerId: String, mailId: String?)
}

/** The repositories plus the cross-table operations that must be atomic. */
interface AgentHqStore : Closeable {
    val runs: RunRepository
    val pods: PodRepository
    val events: EventRepository
    val nodes: NodeRepository
    val agents: AgentRepository
    val roleBindings: RoleBindingRepository
    val prompts: PromptRepository
    val skills: SkillRepository
    val traits: TraitRepository
    val roleTraits: RoleTraitRepository
    /** TRAITS-T4: the frozen role instance of each agent/role session (29.sqm; its skill ids since P10-K13, 35.sqm). */
    val sessionInstances: com.superiorsoftllc.agenthq.session.SessionInstanceRepository
    val tickets: TicketRepository
    val settings: SettingsRepository
    val ticketRuns: TicketRunRepository
    val attention: AttentionRepository
    val usage: UsageRepository
    val providerStates: ProviderStateRepository
    val triggers: TriggerRepository

    /** Runs [block] in one transaction: all its writes commit together or not at all. */
    fun <T> transaction(block: () -> T): T

    /**
     * [transaction] for a body that READS AND THEN WRITES (BUG-BoardCommitImmediate): the write lock is taken
     * before [block] runs, the `BEGIN IMMEDIATE` shape.
     *
     * A plain [transaction] is DEFERRED: its first read only takes a WAL snapshot. If another connection commits
     * after that read, the later write can never upgrade the stale snapshot and fails at once with
     * SQLITE_BUSY_SNAPSHOT -- busy_timeout is bypassed by design (PROBE-A in
     * docs/2026-10-15-ticketstartdaemontest-sqlite-busy-flake.md). Taking the lock first turns that into an
     * ordinary wait: a competing writer queues behind this transaction (or this one behind it, up to
     * busy_timeout), and every read inside [block] sees the latest committed state.
     *
     * Costs the write lock for the whole body, so it is for read-then-write bodies only; a read-only body keeps
     * [transaction] and WAL's reader concurrency. Nested inside an open transaction it is a plain nested
     * transaction (the outer one decided the locking).
     */
    fun <T> writeTransaction(block: () -> T): T

    /**
     * Daemon-startup crash recovery. Every run still in flight belonged to a previous daemon
     * process that is gone, so in ONE transaction each is closed as [SessionState.ORPHANED] at
     * [now] (exit code left null -- inventing one would be a stored lie; line counts and provider
     * session id kept) and gets a terminal `finished` event with [FinishedPayload.Outcome.ORPHANED],
     * so a client replaying its log sees it end the same way a live client would.
     *
     * Idempotent: a second call finds nothing to recover. Returns the recovered run ids.
     */
    fun recoverAfterCrash(now: Long): List<String>
}

/**
 * The SQLite implementation. Owns [handle] and closes it.
 *
 * [handle] is public so the daemon -- and a test asserting the daemon's own database really is in WAL
 * mode -- can reach the driver behind this store. That is the point of
 * [com.superiorsoftllc.agenthq.db.AgentHQDatabaseHandle.driver] being exposed at all: a pragma claim
 * is only worth anything when it can be read back off the connection that will actually serve the
 * daemon.
 */
class SqlAgentHqStore(val handle: AgentHQDatabaseHandle) : AgentHqStore {

    val database: AgentHQDatabase
        get() = handle.database

    private val db = handle.database
    private val tx = com.superiorsoftllc.agenthq.db.TrackedTransacter(db, handle.writes)

    override val runs: RunRepository = object : RunRepository {
        private val q = db.runsQueries

        override fun upsert(run: RunRecord) {
            q.insertRun(
                id = run.id,
                session_id = run.sessionId,
                provider = run.provider,
                working_dir = run.workingDir,
                prompt = run.prompt,
                state = run.state.name,
                started_at = run.startedAt,
                ended_at = run.endedAt,
                exit_code = run.exitCode?.toLong(),
                stdout_lines = run.stdoutLines,
                stderr_lines = run.stderrLines,
                provider_session_id = run.providerSessionId,
                error = run.error,
                pod_id = run.podId,
            )
        }

        override fun get(id: String) = q.getRunById(id).executeAsOneOrNull()?.toRecord()
        override fun bySession(sessionId: String) = q.getRunsBySessionId(sessionId).executeAsList().map { it.toRecord() }
        override fun byPod(podId: String) = q.getRunsByPodId(podId).executeAsList().map { it.toRecord() }
        override fun unfinished() = q.getUnfinishedRuns().executeAsList().map { it.toRecord() }
        override fun delete(id: String) {
            q.deleteRun(id)
        }
    }

    override val pods: PodRepository = object : PodRepository {
        private val q = db.podsQueries

        override fun create(pod: PodRecord) {
            q.insertPod(pod.id, pod.goal, pod.state, pod.maxConcurrency, pod.createdAt, pod.endedAt)
        }

        override fun get(id: String) = q.getPodById(id).executeAsOneOrNull()?.toRecord()
        override fun all() = q.getAllPods().executeAsList().map { it.toRecord() }
        override fun open() = q.getOpenPods().executeAsList().map { it.toRecord() }
        override fun updateState(id: String, state: String) {
            q.updatePodState(state, id)
        }
        override fun finish(id: String, state: String, endedAt: Long) {
            q.finishPod(state, endedAt, id)
        }
        override fun delete(id: String) {
            q.deletePod(id)
        }
    }

    override val events: EventRepository = object : EventRepository {
        private val q = db.eventsQueries

        // insert + last_insert_rowid() must run on the SAME connection with nothing in between,
        // which the transaction guarantees.
        override fun append(runId: String, event: RunEvent, at: Long): Long =
            tx.transactionWithResult {
                q.insertEvent(runId, event.type, RunEvent.encodePayload(event), EventVocabulary.VERSION, at)
                q.lastInsertedSeq().executeAsOne()
            }

        override fun forRun(runId: String, afterSeq: Long) =
            q.getEventsByRunIdAfter(runId, afterSeq).executeAsList().map { it.toStored() }

        override fun count(runId: String) = q.countEventsByRunId(runId).executeAsOne()
    }

    override val nodes: NodeRepository = object : NodeRepository {
        private val q = db.nodesQueries

        override fun create(node: NodeRecord) {
            q.insertNode(
                node.id, node.podId, node.name, node.specIndex.toLong(), node.prompt, node.state,
                node.maxAttempts.toLong(), node.attempts.toLong(), node.reviewState, node.detail,
                node.createdAt, node.endedAt,
            )
        }

        override fun get(id: String) = q.getNodeById(id).executeAsOneOrNull()?.toRecord()
        override fun byPod(podId: String) = q.getNodesByPodId(podId).executeAsList().map { it.toRecord() }
        override fun update(node: NodeRecord) {
            q.updateNode(node.state, node.attempts.toLong(), node.reviewState, node.detail, node.endedAt, node.id)
        }
        override fun addDependency(dependency: NodeDependency) {
            q.insertDependency(dependency.nodeId, dependency.dependsOn)
        }
        override fun dependencies(podId: String) =
            q.getDependenciesByPodId(podId).executeAsList().map { NodeDependency(it.node_id, it.depends_on) }
        override fun recordAttempt(attempt: NodeAttempt) {
            q.insertAttempt(attempt.runId, attempt.nodeId, attempt.attempt.toLong())
        }
        override fun attempts(nodeId: String) = q.getAttemptsByNodeId(nodeId).executeAsList().map { it.toRecord() }
        override fun reviewQueue() = q.getReviewQueue().executeAsList().map { it.toRecord() }
    }

    override val roleBindings: RoleBindingRepository = object : RoleBindingRepository {
        private val q = db.role_bindingsQueries

        private fun com.superiorsoftllc.agenthq.db.Role_bindings.toBinding() =
            RoleBinding(role_id, provider, model, effort, updated_at)

        override fun set(binding: RoleBinding, at: Long) {
            // ORIGIN-SCRUB-1a: a retired role id (an older client) reads and writes the row of the id that replaced it.
            q.setRoleBinding(RoleIds.normalize(binding.roleId), binding.provider, binding.model, binding.effort, at)
        }

        override fun get(roleId: String) = q.getRoleBinding(RoleIds.normalize(roleId)).executeAsOneOrNull()?.toBinding()
        override fun all() = q.getAllRoleBindings().executeAsList().map { it.toBinding() }
    }

    override val prompts: PromptRepository = object : PromptRepository {
        private val q = db.promptsQueries
        private val tagsJson = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())

        private fun com.superiorsoftllc.agenthq.db.Prompts.toView() = com.superiorsoftllc.agenthq.protocol.PromptView(
            id, name, body, kotlinx.serialization.json.Json.decodeFromString(tagsJson, tags), created_at, updated_at,
        )

        override fun upsertByName(name: String, body: String, tags: List<String>, at: Long, newId: () -> String): com.superiorsoftllc.agenthq.protocol.PromptView =
            transaction {
                val encoded = kotlinx.serialization.json.Json.encodeToString(tagsJson, tags)
                if (q.getPromptByName(name).executeAsOneOrNull() != null) q.updatePromptByName(body, encoded, at, name)
                else q.insertPrompt(newId(), name, body, encoded, at, at)
                q.getPromptByName(name).executeAsOne().toView()
            }

        override fun get(id: String) = q.getPromptById(id).executeAsOneOrNull()?.toView()
        override fun all() = q.getAllPrompts().executeAsList().map { it.toView() }
        override fun delete(id: String): Boolean = transaction {
            val existed = q.getPromptById(id).executeAsOneOrNull() != null
            if (existed) q.deletePrompt(id)
            existed
        }
    }

    override val skills: SkillRepository = object : SkillRepository {
        private val q = db.skillsQueries
        private val tagsJson = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())

        private fun com.superiorsoftllc.agenthq.db.Skills.toView() = com.superiorsoftllc.agenthq.protocol.SkillView(
            id, name, description, body, kotlinx.serialization.json.Json.decodeFromString(tagsJson, tags), created_at, updated_at,
            attachedOnly = attached_only != 0L,
        )

        override fun upsertByName(name: String, description: String, body: String, tags: List<String>, at: Long, attachedOnly: Boolean?, newId: () -> String): com.superiorsoftllc.agenthq.protocol.SkillView =
            transaction {
                val encoded = kotlinx.serialization.json.Json.encodeToString(tagsJson, tags)
                val current = q.getSkillByName(name).executeAsOneOrNull()
                if (current != null) {
                    val flag = attachedOnly?.let { if (it) 1L else 0L } ?: current.attached_only
                    q.updateSkillByName(description, body, encoded, flag, at, name)
                } else {
                    q.insertSkill(newId(), name, description, body, encoded, at, at, if (attachedOnly == true) 1L else 0L)
                }
                q.getSkillByName(name).executeAsOne().toView()
            }

        override fun get(id: String) = q.getSkillById(id).executeAsOneOrNull()?.toView()
        override fun getByName(name: String) = q.getSkillByName(name).executeAsOneOrNull()?.toView()
        override fun all() = q.getAllSkills().executeAsList().map { it.toView() }
        override fun delete(id: String): Boolean = transaction {
            val existed = q.getSkillById(id).executeAsOneOrNull() != null
            if (existed) q.deleteSkill(id)
            existed
        }
    }

    override val traits: TraitRepository = object : TraitRepository {
        private val q = db.traitsQueries
        private val checkJson = kotlinx.serialization.json.Json { encodeDefaults = true }

        private fun com.superiorsoftllc.agenthq.db.Traits.toView() = com.superiorsoftllc.agenthq.protocol.TraitView(
            id = id, name = name, version = version.toInt(), body = body,
            check = check_spec?.let { checkJson.decodeFromString(com.superiorsoftllc.agenthq.protocol.TraitCheck.serializer(), it) },
            createdAt = created_at, updatedAt = updated_at,
        )

        override fun saveWith(
            id: String,
            next: (com.superiorsoftllc.agenthq.protocol.TraitView?) -> com.superiorsoftllc.agenthq.protocol.TraitView,
        ): com.superiorsoftllc.agenthq.protocol.TraitView = transaction {
            val current = q.getTraitById(id).executeAsOneOrNull()?.toView()
            val wanted = next(current)
            require(wanted.id == id) { "a trait cannot change its id" }
            val spec = wanted.check?.let { checkJson.encodeToString(com.superiorsoftllc.agenthq.protocol.TraitCheck.serializer(), it) }
            when {
                current == null -> q.insertTrait(id, wanted.name, wanted.version.toLong(), wanted.body, spec, wanted.createdAt, wanted.updatedAt)
                current != wanted -> q.updateTrait(wanted.name, wanted.version.toLong(), wanted.body, spec, wanted.updatedAt, id)
            }
            q.getTraitById(id).executeAsOne().toView()
        }

        override fun get(id: String) = q.getTraitById(id).executeAsOneOrNull()?.toView()
        override fun all() = q.getAllTraits().executeAsList().map { it.toView() }
        override fun delete(id: String, beforeDelete: () -> Unit): Boolean = transaction {
            val existed = q.getTraitById(id).executeAsOneOrNull() != null
            if (existed) {
                beforeDelete()
                q.deleteTrait(id)
            }
            existed
        }
    }

    override val roleTraits: RoleTraitRepository = object : RoleTraitRepository {
        private val q = db.role_traitsQueries
        private val idsJson = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())

        private fun com.superiorsoftllc.agenthq.db.Role_traits.toView() = com.superiorsoftllc.agenthq.protocol.RoleTraits(
            role_id, kotlinx.serialization.json.Json.decodeFromString(idsJson, trait_ids), updated_at,
        )

        override fun set(roleId: String, traitIds: List<String>, at: Long, beforeWrite: () -> Unit) = transaction {
            beforeWrite()
            val id = RoleIds.normalize(roleId) // ORIGIN-SCRUB-1a, as roleBindings above
            q.setRoleTraits(id, kotlinx.serialization.json.Json.encodeToString(idsJson, traitIds), at)
            q.getRoleTraits(id).executeAsOne().toView()
        }

        override fun get(roleId: String) = q.getRoleTraits(RoleIds.normalize(roleId)).executeAsOneOrNull()?.toView()
        override fun all() = q.getAllRoleTraits().executeAsList().map { it.toView() }
    }

    override val sessionInstances: com.superiorsoftllc.agenthq.session.SessionInstanceRepository =
        object : com.superiorsoftllc.agenthq.session.SessionInstanceRepository {
            private val q = db.session_instancesQueries
            private val snapshotsJson = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
            private val snapshots = kotlinx.serialization.builtins.ListSerializer(com.superiorsoftllc.agenthq.session.TraitSnapshot.serializer())
            private val skillIdsSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())

            override fun insert(sessionId: String, instance: com.superiorsoftllc.agenthq.session.RoleInstance, at: Long) {
                q.insertSessionInstance(
                    sessionId, instance.agentId, instance.role, instance.instructions,
                    snapshotsJson.encodeToString(snapshots, instance.traits), at,
                    snapshotsJson.encodeToString(skillIdsSerializer, instance.skillIds), // P10-K13 (35.sqm)
                )
            }

            override fun get(sessionId: String) = q.getSessionInstance(sessionId).executeAsOneOrNull()?.let {
                com.superiorsoftllc.agenthq.session.RoleInstance(
                    it.agent_id, it.role, it.instructions, snapshotsJson.decodeFromString(snapshots, it.traits),
                    snapshotsJson.decodeFromString(skillIdsSerializer, it.skill_ids),
                )
            }

            override fun delete(sessionId: String) {
                q.deleteSessionInstance(sessionId)
            }
        }

    override val agents: AgentRepository = object : AgentRepository {
        private val q = db.agentsQueries

        override fun save(profile: AgentProfile, at: Long) {
            val id = requireNotNull(profile.id) { "a profile is stored under an id; assign one first" }
            q.saveAgent(
                id = id,
                name = profile.name,
                role = profile.role,
                provider = profile.provider,
                model = profile.model,
                instructions = profile.instructions,
                at = at,
                projectId = profile.projectId,
                effort = profile.effort,
                traitIds = profile.traitIds?.let { kotlinx.serialization.json.Json.encodeToString(TRAIT_IDS_JSON, it) },
                skillIds = profile.skillIds?.let { kotlinx.serialization.json.Json.encodeToString(TRAIT_IDS_JSON, it) },
            )
        }

        override fun get(id: String) = q.getAgentById(id).executeAsOneOrNull()?.toProfile()
        override fun all() = q.getAllAgents().executeAsList().map { it.toProfile() }

        // Read-then-delete in one transaction, so "was there one" and the delete see the same row.
        override fun delete(id: String): Boolean = writeTransaction {
            val existed = q.getAgentById(id).executeAsOneOrNull() != null
            if (existed) q.deleteAgent(id)
            existed
        }
    }

    override val tickets: TicketRepository = object : TicketRepository {
        private val q = db.ticketsQueries

        override fun create(ticket: TicketRow) {
            q.insertTicket(
                id = ticket.id,
                title = ticket.title,
                description = ticket.description,
                ticket_type = ticket.ticketType,
                status = ticket.status,
                position = ticket.position.toLong(),
                wont_fix = if (ticket.wontFix) 1L else 0L,
                agent_id = ticket.agentId,
                provider = ticket.provider,
                model = ticket.model,
                use_worktree = ticket.useWorktree?.let { if (it) 1L else 0L },
                worktree_branch = ticket.worktreeBranch,
                include_linked_tasks_context = ticket.includeLinkedTasksContext?.let { if (it) 1L else 0L },
                queued_for = ticket.queuedFor,
                queued_at = ticket.queuedAt,
                session_id = ticket.sessionId,
                project_id = ticket.projectId,
                epic_id = ticket.epicId,
                branch_name = ticket.branch,
                base_commit = ticket.base,
                skill_ids = kotlinx.serialization.json.Json.encodeToString(TRAIT_IDS_JSON, ticket.skillIds),
                skills_mode = ticket.skillsMode,
                include_skills_context = ticket.includeSkillsContext?.let { if (it) 1L else 0L },
                started_by = ticket.startedBy,
                images = kotlinx.serialization.json.Json.encodeToString(TRAIT_IDS_JSON, ticket.images),
                weight = ticket.weight?.toLong(),
                context_limit = ticket.contextLimit?.toLong(),
                effort = ticket.effort,
                created_at = ticket.createdAt,
                updated_at = ticket.updatedAt,
            )
        }

        override fun update(ticket: TicketRow) {
            q.updateTicket(
                title = ticket.title,
                description = ticket.description,
                ticket_type = ticket.ticketType,
                status = ticket.status,
                position = ticket.position.toLong(),
                wont_fix = if (ticket.wontFix) 1L else 0L,
                agent_id = ticket.agentId,
                provider = ticket.provider,
                model = ticket.model,
                use_worktree = ticket.useWorktree?.let { if (it) 1L else 0L },
                worktree_branch = ticket.worktreeBranch,
                include_linked_tasks_context = ticket.includeLinkedTasksContext?.let { if (it) 1L else 0L },
                queued_for = ticket.queuedFor,
                queued_at = ticket.queuedAt,
                session_id = ticket.sessionId,
                project_id = ticket.projectId,
                epic_id = ticket.epicId,
                branch_name = ticket.branch,
                base_commit = ticket.base,
                skill_ids = kotlinx.serialization.json.Json.encodeToString(TRAIT_IDS_JSON, ticket.skillIds),
                skills_mode = ticket.skillsMode,
                include_skills_context = ticket.includeSkillsContext?.let { if (it) 1L else 0L },
                started_by = ticket.startedBy,
                images = kotlinx.serialization.json.Json.encodeToString(TRAIT_IDS_JSON, ticket.images),
                weight = ticket.weight?.toLong(),
                context_limit = ticket.contextLimit?.toLong(),
                effort = ticket.effort,
                updated_at = ticket.updatedAt,
                id = ticket.id,
            )
        }

        override fun get(id: String) = q.getTicketById(id).executeAsOneOrNull()?.toRow()
        override fun all() = q.getAllTickets().executeAsList().map { it.toRow() }

        // Read-then-write in one transaction, so "was there one" and the write see the same row.
        override fun delete(id: String): Boolean = writeTransaction {
            val existed = q.getTicketById(id).executeAsOneOrNull() != null
            if (existed) q.deleteTicket(id)
            existed
        }

        override fun link(link: TicketLinkRow): Boolean = tx.transactionWithResult {
            val existed = q.getLink(link.ticketId, link.relatedId, link.type).executeAsOneOrNull() != null
            if (!existed) q.insertLink(link.ticketId, link.relatedId, link.type, link.createdAt)
            !existed
        }

        override fun unlink(link: TicketLinkRow): Boolean = tx.transactionWithResult {
            val existed = q.getLink(link.ticketId, link.relatedId, link.type).executeAsOneOrNull() != null
            if (existed) q.deleteLink(link.ticketId, link.relatedId, link.type)
            existed
        }

        override fun links() = q.getAllLinks().executeAsList().map {
            TicketLinkRow(ticketId = it.ticket_id, relatedId = it.related_id, type = it.type, createdAt = it.created_at)
        }
    }

    override val settings: SettingsRepository = object : SettingsRepository {
        private val q = db.settingsQueries

        override fun get(key: String) = q.getSettingByKey(key).executeAsOneOrNull()?.toRecord()
        override fun all() = q.getAllSettings().executeAsList().map { it.toRecord() }
        override fun put(setting: SettingRow) {
            q.putSetting(setting.key, setting.value, setting.updatedAt)
            q.updateSetting(setting.value, setting.updatedAt, setting.key)
        }
    }

    override val ticketRuns: TicketRunRepository = object : TicketRunRepository {
        private val q = db.ticket_runsQueries

        override fun insert(run: TicketRunRow) {
            q.insertTicketRun(
                ticket_id = run.ticketId,
                session_id = run.sessionId,
                started_at = run.startedAt,
                ended_at = run.endedAt,
                outcome = run.outcome,
                detail = run.detail,
            )
        }

        override fun finish(ticketId: String, sessionId: String, endedAt: Long, outcome: String, detail: String?) {
            q.finishTicketRun(
                ended_at = endedAt,
                outcome = outcome,
                detail = detail,
                ticket_id = ticketId,
                session_id = sessionId,
            )
        }

        override fun byTicket(ticketId: String) =
            q.getTicketRunsByTicketId(ticketId).executeAsList().map { it.toRecord() }
    }

    override val attention: AttentionRepository = object : AttentionRepository {
        private val q = db.attentionQueries

        override fun insert(item: AttentionItem) {
            q.insertAttention(
                id = item.id,
                created_at = item.at,
                kind = item.kind.wireName,
                trigger_id = item.triggerId,
                ticket_id = item.ticketId,
                pod_id = item.podId,
                session_id = item.sessionId,
                outcome = item.outcome,
                detail = item.detail,
                acked_at = item.ackedAt,
            )
        }

        override fun get(id: String) = q.getAttentionById(id).executeAsOneOrNull()?.toAttentionItem()
        override fun all() = q.getAllAttention().executeAsList().map { it.toAttentionItem() }
        override fun acknowledge(id: String, at: Long): AttentionItem? = tx.transactionWithResult {
            val existing = q.getAttentionById(id).executeAsOneOrNull() ?: return@transactionWithResult null
            if (existing.acked_at == null) q.acknowledgeAttention(at, id)
            q.getAttentionById(id).executeAsOneOrNull()?.toAttentionItem()
        }
        override fun openCount() = q.countOpenAttention().executeAsOne()
    }

    override val usage: UsageRepository = object : UsageRepository {
        private val q = db.usageQueries

        override fun insert(record: RunUsageRecord) {
            val u = record.usage
            q.insertRunUsage(
                run_id = record.runId,
                session_id = record.sessionId,
                provider = record.provider,
                model = record.model,
                ended_at = record.endedAt,
                input = u?.input,
                output = u?.output,
                reasoning = u?.reasoning,
                cache_read = u?.cacheRead,
                cache_write = u?.cacheWrite,
                cost_usd = u?.costUsd,
                context_limit = record.contextLimit?.toLong(),
                effort = record.effort,
            )
        }

        override fun forSession(sessionId: String): List<RunUsage> =
            q.getRunUsageBySession(sessionId).executeAsList().mapNotNull { it.toRecord().usage }

        override fun since(epochMillis: Long): List<RunUsageRecord> =
            q.getRunUsageSince(epochMillis).executeAsList().map { it.toRecord() }

        private fun com.superiorsoftllc.agenthq.db.Run_usage.toRecord() = RunUsageRecord(
            runId = run_id,
            sessionId = session_id,
            provider = provider,
            model = model,
            endedAt = ended_at,
            contextLimit = context_limit?.toInt(),
            effort = effort,
            usage = input?.let {
                RunUsage(run_id, provider, model, it, output ?: 0, reasoning ?: 0, cache_read ?: 0, cache_write ?: 0, cost_usd)
            },
        )
    }

    override val providerStates: ProviderStateRepository = object : ProviderStateRepository {
        private val q = db.usageQueries

        override fun upsert(state: ProviderState) {
            q.upsertProviderState(
                provider = state.provider,
                status = state.status.wireName,
                limit_window = state.window,
                utilization = state.utilization,
                resets_at = state.resetsAt,
                observed_at = state.observedAt,
                source = state.source,
            )
        }

        override fun all(): List<ProviderState> = q.getAllProviderStates().executeAsList().map {
            ProviderState(
                provider = it.provider,
                status = ProviderStatus.fromWireName(it.status),
                window = it.limit_window,
                utilization = it.utilization,
                resetsAt = it.resets_at,
                observedAt = it.observed_at,
                source = it.source,
            )
        }
    }

    override val triggers: TriggerRepository = object : TriggerRepository {
        private val q = db.triggersQueries

        // Ticket a8a6fd98: read-then-write (it keeps the row's pending mail id), so the write lock is taken first (see
        // [writeTransaction]). As a deferred transaction it failed at once with SQLITE_BUSY while the board committed
        // the same run's end on its own thread: the session's exit lost the trigger's `running -> armed`, and the
        // exception killed the session thread before the later event arms ran.
        override fun upsert(trigger: TriggerView, intervalMs: Long?): Unit = writeTransaction {
            val pending = q.getTrigger(trigger.id).executeAsOneOrNull()?.pending_mail_id
            q.upsertTrigger(
                id = trigger.id,
                name = trigger.name,
                kind = trigger.kind,
                action_kind = trigger.action.kind,
                action_agent_id = trigger.action.agentId,
                enabled = if (trigger.enabled) 1L else 0L,
                state = trigger.state,
                coalesce_ms = trigger.coalesceMs,
                ceiling_runs = trigger.ceilingRuns.toLong(),
                ceiling_window_ms = trigger.ceilingWindowMs,
                created_at = trigger.createdAt,
                tripped_at = trigger.trippedAt,
                tripped_reason = trigger.trippedReason,
                interval_ms = intervalMs,
                action_prompt = trigger.action.prompt,
                project_id = trigger.projectId,
                last_fired_at = trigger.lastFiredAt,
                next_fire_at = trigger.nextFireAt,
                pending_mail_id = pending,
            )
            Unit
        }

        override fun pendingMail(triggerId: String): String? = q.getTrigger(triggerId).executeAsOneOrNull()?.pending_mail_id
        override fun setPendingMail(triggerId: String, mailId: String?) { q.setTriggerPendingMail(mailId, triggerId) }

        override fun get(id: String): TriggerView? = q.getTrigger(id).executeAsOneOrNull()?.toView()
        override fun all(): List<TriggerView> = q.getAllTriggers().executeAsList().map { it.toView() }
        override fun intervals(): Map<String, Long> = q.getAllTriggers().executeAsList()
            .mapNotNull { row -> row.interval_ms?.let { row.id to it } }
            .toMap()

        // BUG-FLAKE-1: read-then-write, so the write lock is taken first (see [writeTransaction]). As a deferred
        // transaction this failed at once with SQLITE_BUSY whenever another connection was writing -- the board
        // committing a finished run -- and the trigger_delete was lost.
        override fun delete(id: String): Boolean = writeTransaction {
            val existed = q.getTrigger(id).executeAsOneOrNull() != null
            q.deleteTriggerEvents(id)
            q.deleteWebhookSecret(id)
            q.deleteTrigger(id)
            existed
        }

        override fun insertEvent(event: TriggerEventView, keep: Long) {
            tx.transaction {
                q.insertTriggerEvent(event.eventId, event.triggerId, event.receivedAt, event.source, event.disposition, event.deliveryId, event.digest)
                q.pruneTriggerEvents(event.triggerId, event.triggerId, keep)
            }
        }

        override fun events(triggerId: String): List<TriggerEventView> = q.getTriggerEvents(triggerId).executeAsList().map {
            TriggerEventView(it.event_id, it.trigger_id, it.received_at, it.source, it.disposition, it.delivery_id, it.digest)
        }

        override fun setWebhookSecret(triggerId: String, secret: String) { q.setWebhookSecret(triggerId, secret) }
        override fun webhookSecret(triggerId: String): String? = q.getWebhookSecret(triggerId).executeAsOneOrNull()
        override fun deleteWebhookSecret(triggerId: String) { q.deleteWebhookSecret(triggerId) }

        private fun com.superiorsoftllc.agenthq.db.Triggers.toView() = TriggerView(
            id = id,
            name = name,
            kind = kind,
            action = TriggerAction(action_kind, action_agent_id, action_prompt),
            enabled = enabled != 0L,
            state = state,
            coalesceMs = coalesce_ms,
            ceilingRuns = ceiling_runs.toInt(),
            ceilingWindowMs = ceiling_window_ms,
            createdAt = created_at,
            trippedAt = tripped_at,
            trippedReason = tripped_reason,
            intervalMs = interval_ms,
            projectId = project_id,
            lastFiredAt = last_fired_at,
            nextFireAt = next_fire_at,
        )
    }

    override fun <T> transaction(block: () -> T): T = tx.transactionWithResult { block() }

    // SQLDelight issues the deferred BEGIN itself and has no hook for `BEGIN IMMEDIATE`, so the write lock is
    // claimed by making the transaction's FIRST statement a write. That is exactly what BEGIN IMMEDIATE does: the
    // write-flagged OP_Transaction starts the read snapshot and the write lock in one step, with no snapshot taken
    // before it to go stale, and while it waits the busy handler (busy_timeout) runs. The claim matches no row.
    override fun <T> writeTransaction(block: () -> T): T {
        val outermost = handle.driver.currentTransaction() == null
        return tx.transactionWithResult {
            if (outermost) handle.driver.execute(null, com.superiorsoftllc.agenthq.db.WriteTracker.WRITE_CLAIM, 0)
            block()
        }
    }

    override fun recoverAfterCrash(now: Long): List<String> = tx.transactionWithResult {
        val orphans = db.runsQueries.getUnfinishedRuns().executeAsList()
        for (run in orphans) {
            db.runsQueries.orphanRun(
                state = SessionState.ORPHANED.name,
                ended_at = now,
                error = run.error ?: ORPHAN_DETAIL,
                id = run.id,
            )
            events.append(
                run.id,
                RunEvent.Finished(FinishedPayload(exitCode = null, outcome = FinishedPayload.Outcome.ORPHANED, detail = ORPHAN_DETAIL)),
                now,
            )
        }
        orphans.map { it.id }
    }

    override fun close() = handle.close()

    companion object {
        const val ORPHAN_DETAIL = "daemon restarted while the run was in flight"

        /** Opens (creating or migrating) the database at [path] behind the repository seam. */
        fun open(path: String, holderLabel: String? = null, busyTimeoutMs: Int = 5_000): SqlAgentHqStore =
            SqlAgentHqStore(AgentHQDatabaseFactory.open(path, holderLabel, busyTimeoutMs))
    }
}

private fun Attention.toAttentionItem() = AttentionItem(
    id = id,
    at = created_at,
    kind = AttentionKind.fromWireName(kind),
    triggerId = trigger_id,
    ticketId = ticket_id,
    podId = pod_id,
    sessionId = session_id,
    outcome = outcome,
    detail = detail,
    ackedAt = acked_at,
)

private fun Runs.toRecord() = RunRecord(
    id = id,
    sessionId = session_id,
    provider = provider,
    workingDir = working_dir,
    prompt = prompt,
    state = SessionState.valueOf(state),
    startedAt = started_at,
    endedAt = ended_at,
    exitCode = exit_code?.toInt(),
    stdoutLines = stdout_lines,
    stderrLines = stderr_lines,
    providerSessionId = provider_session_id,
    error = error,
    podId = pod_id,
)

private fun Pods.toRecord() = PodRecord(
    id = id,
    goal = goal,
    state = state,
    maxConcurrency = max_concurrency,
    createdAt = created_at,
    endedAt = ended_at,
)

private fun Events.toStored() = StoredEvent(
    seq = seq,
    runId = run_id,
    event = RunEvent.decode(type, payload),
    vocabularyVersion = vocabulary_version,
    createdAt = created_at,
)

private fun Pod_nodes.toRecord() = NodeRecord(
    id = id,
    podId = pod_id,
    name = name,
    specIndex = spec_index.toInt(),
    prompt = prompt,
    state = state,
    maxAttempts = max_attempts.toInt(),
    attempts = attempts.toInt(),
    reviewState = review_state,
    detail = detail,
    createdAt = created_at,
    endedAt = ended_at,
)

private fun Node_attempts.toRecord() = NodeAttempt(runId = run_id, nodeId = node_id, attempt = attempt.toInt())

private fun Agents.toProfile() = AgentProfile(
    id = id,
    name = name,
    role = role,
    provider = provider,
    model = model,
    instructions = instructions,
    projectId = project_id,
    effort = effort,
    traitIds = kotlinx.serialization.json.Json.decodeFromString(TRAIT_IDS_JSON, trait_ids),
    skillIds = kotlinx.serialization.json.Json.decodeFromString(TRAIT_IDS_JSON, skill_ids),
)

private val TRAIT_IDS_JSON = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())

private fun Tickets.toRow() = TicketRow(
    id = id,
    title = title,
    description = description,
    ticketType = ticket_type,
    status = status,
    position = position.toInt(),
    wontFix = wont_fix != 0L,
    createdAt = created_at,
    updatedAt = updated_at,
    agentId = agent_id,
    provider = provider,
    model = model,
    useWorktree = use_worktree?.let { it != 0L },
    worktreeBranch = worktree_branch,
    includeLinkedTasksContext = include_linked_tasks_context?.let { it != 0L },
    queuedFor = queued_for,
    queuedAt = queued_at,
    sessionId = session_id,
    projectId = project_id,
    epicId = epic_id,
    branch = branch_name,
    base = base_commit,
    skillIds = kotlinx.serialization.json.Json.decodeFromString(TRAIT_IDS_JSON, skill_ids),
    skillsMode = skills_mode,
    includeSkillsContext = include_skills_context?.let { it != 0L },
    startedBy = started_by,
    images = kotlinx.serialization.json.Json.decodeFromString(TRAIT_IDS_JSON, images),
    weight = weight?.toInt(),
    contextLimit = context_limit?.toInt(),
    effort = effort,
)

private fun Settings.toRecord() = SettingRow(key = key, value = value_, updatedAt = updated_at)

private fun Ticket_runs.toRecord() = TicketRunRow(
    ticketId = ticket_id,
    sessionId = session_id,
    startedAt = started_at,
    endedAt = ended_at,
    outcome = outcome,
    detail = detail,
)
