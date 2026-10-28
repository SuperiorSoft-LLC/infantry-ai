package com.superiorsoftllc.agenthq.prompts

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.PromptAsk
import com.superiorsoftllc.agenthq.protocol.PromptDecisions
import com.superiorsoftllc.agenthq.protocol.PromptKinds
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.protocol.PromptOutcomes
import com.superiorsoftllc.agenthq.protocol.PromptRequest
import com.superiorsoftllc.agenthq.protocol.PromptResolved
import com.superiorsoftllc.agenthq.protocol.PromptResolvedBy
import com.superiorsoftllc.agenthq.protocol.PromptResponse
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID

/** PROMPT-1: the owner's answer to a tool approval. [allows] is what the harness acts on. */
enum class Decision(val wire: String) {
    ALLOW_ONCE(PromptDecisions.ALLOW_ONCE),
    ALLOW_SESSION(PromptDecisions.ALLOW_SESSION),
    /** PROMPT-6: allow, and store an [com.superiorsoftllc.agenthq.protocol.ApprovalRule] for the session's project. */
    ALLOW_ALWAYS(PromptDecisions.ALLOW_ALWAYS),
    DENY(PromptDecisions.DENY);

    val allows: Boolean get() = this != DENY

    companion object {
        fun parse(text: String?): Decision? = entries.firstOrNull { it.wire == text?.trim() }
    }
}

/**
 * PROMPT-1: a select question a harness asks the owner. PROMPT-5: [timeoutMillis] replaces the daemon's
 * `prompts.timeoutSec` for this one question (null = the setting).
 */
data class QuestionSpec(
    val question: String,
    val options: List<PromptOption>,
    val multiSelect: Boolean = false,
    val allowOther: Boolean = false,
    val timeoutMillis: Long? = null,
    /** ASK-TIMER-2 (1.140.0): the label of the option the timer auto-selects at expiry; null = none (no answer). */
    val recommended: String? = null,
) {
    companion object {
        /** The longest question a `prompt_ask` may carry. */
        const val QUESTION_MAX = 2_000

        /**
         * PROMPT-5: the [QuestionSpec] a `prompt_ask` asks for. Refused ([IllegalArgumentException]): a blank or
         * over-long question, no options, a blank or repeated label, a `timeoutSec` outside 1..86400.
         */
        fun of(ask: PromptAsk): QuestionSpec {
            val question = ask.question.trim()
            require(question.isNotEmpty()) { "a question is required" }
            require(question.length <= QUESTION_MAX) { "the question is longer than $QUESTION_MAX characters" }
            require(ask.options.isNotEmpty()) { "at least one option is required" }
            val options = ask.options.map { PromptOption(it.label.trim(), it.description.trim()) }
            require(options.none { it.label.isEmpty() }) { "an option label cannot be blank" }
            val repeated = options.groupBy { it.label }.filterValues { it.size > 1 }.keys.firstOrNull()
            require(repeated == null) { "option '${repeated!!.take(80)}' is given twice" }
            val timeout = ask.timeoutSec?.also {
                require(it in PromptAsk.TIMEOUT_SEC_RANGE) {
                    "timeoutSec must be ${PromptAsk.TIMEOUT_SEC_RANGE.first}..${PromptAsk.TIMEOUT_SEC_RANGE.last}, got $it"
                }
            }
            val recommended = ask.recommended?.trim()?.takeIf { it.isNotEmpty() }?.also { rec ->
                require(options.any { it.label == rec }) { "recommended '${rec.take(80)}' is not one of the options" }
            }
            return QuestionSpec(question, options, ask.multiSelect, true, timeout?.let { it * 1_000L }, recommended)
        }

        /**
         * ASK-TIMER-2: the option a harness marked as recommended in its own label (Claude's AskUserQuestion convention,
         * "Use JWT (Recommended)"); null = none, or more than one.
         */
        fun recommendedIn(options: List<PromptOption>): String? =
            options.filter { RECOMMENDED_MARK.containsMatchIn(it.label) }.singleOrNull()?.label

        private val RECOMMENDED_MARK = Regex("""\(\s*recommended\s*\)""", RegexOption.IGNORE_CASE)
    }
}

/** PROMPT-1: the owner's answer to a [QuestionSpec]; [answered] false = no answer (timeout, session end, ...). */
data class Answer(val answers: List<String>, val otherText: String?, val answered: Boolean) {
    companion object {
        val NONE = Answer(emptyList(), null, answered = false)
    }
}

/**
 * PROMPT-1: the internal API a harness adapter calls when its CLI wants a human. Both calls SUSPEND until the owner
 * answers, the prompt times out, or the session ends; neither throws for those outcomes.
 */
interface PromptAsker {
    suspend fun requestApproval(session: String, tool: String, input: String): Decision

    /**
     * ASK-TIMER: [requestApproval], plus whether the prompt's timer decided it (so the worker is told why). ASK-TIMER-2:
     * [recommended] is the decision the asker recommends, which the timer auto-selects (null = `prompts.onTimeout`).
     */
    suspend fun requestApprovalOutcome(session: String, tool: String, input: String, recommended: Decision? = null): ApprovalOutcome =
        ApprovalOutcome(requestApproval(session, tool, input))
    suspend fun askQuestion(session: String, spec: QuestionSpec): Answer

    companion object {
        /** For a daemon (or a test) with no prompt relay: every approval is denied, every question unanswered. */
        val CANNOT_PROMPT: PromptAsker = object : PromptAsker {
            override suspend fun requestApproval(session: String, tool: String, input: String) = Decision.DENY
            override suspend fun askQuestion(session: String, spec: QuestionSpec) = Answer.NONE
        }
    }
}

/**
 * PROMPT-1 (contract 1.77.0): the daemon's open prompts, per session.
 *
 *  - [requestApproval] / [askQuestion] open a prompt, push `prompt_request` through [sink] and suspend the asker.
 *  - [respond] (a client's `prompt_response`) resolves it; an unknown, already resolved or malformed answer is an
 *    [IllegalArgumentException] and changes nothing.
 *  - Unanswered after [timeoutMillis] (read at every open; the daemon reads `prompts.timeoutSec`), a prompt resolves as
 *    deny / no answer. ASK-TIMER (1.139.0): [policyFor] gives the timer per session (0 = none) and the approval's
 *    action at expiry (`prompts.onTimeout`: deny | approve); `prompt_request.expiresAt` carries the deadline and the
 *    expiry is one [notice] line in the session's transcript. A session that ends ([onSessionEvent], [endSession]) resolves its open prompts the same way.
 *  - Every resolution, whoever caused it, pushes `prompt_resolved` through [sink], so every card closes.
 *  - `allow_session` remembers the tool for that session: a later [requestApproval] for it returns at once, with no
 *    prompt, until the session ends.
 *  - [snapshot] lists the prompts still open, for a client that (re)connects.
 *  - PROMPT-6 (1.78.0): `allow_always` also stores a rule in [rules] for the session's project ([projectOf]); a later
 *    [requestApproval] in ANY session of that project that a rule matches returns [Decision.ALLOW_ALWAYS] with no
 *    prompt, and [notice] gets "auto-allowed by rule <tool> [<prefix>]" for that session's transcript. Only `ask` mode
 *    reaches [requestApproval] (a `deny` run never relays a call here), so the rules are never consulted in `deny`.
 */
class PendingPrompts(
    private val timeoutMillis: () -> Long = { DEFAULT_TIMEOUT_MS },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** PROMPT-6: the remembered "Always allow" rules; the daemon backs them with the settings table. */
    val rules: ApprovalRules = ApprovalRules.inMemory(),
    /** PROMPT-6: the project a session belongs to; null = none, so no rule is matched or stored for it. */
    private val projectOf: (String) -> String? = { null },
    /**
     * ASK-TIMER (1.139.0): the timer a prompt of a session gets (global `prompts.timeoutSec` / `prompts.onTimeout`
     * with the session's override over it). Default: [timeoutMillis] and deny. A timeout of 0 = no timer.
     */
    private val policyFor: (String) -> PromptTimeoutPolicy = { PromptTimeoutPolicy(timeoutMillis()) },
) : PromptAsker {
    /** Where `prompt_request` / `prompt_resolved` go: the daemon points it at every connected client. */
    @Volatile
    var sink: (Envelope) -> Unit = {}

    /** PROMPT-6: a one-line transcript notice for a session (the daemon emits it as an `[agenthq]` stderr line). */
    @Volatile
    var notice: (sessionId: String, text: String) -> Unit = { _, _ -> }

    private sealed interface Resolution {
        data class Approval(val decision: Decision) : Resolution
        data class Question(val answer: Answer) : Resolution
    }

    /** [fullInput] is the call's whole input (the card only carries a summary): an `allow_always` rule is made from it. */
    private class Open(
        val request: PromptRequest,
        val fullInput: String? = null,
        val result: CompletableDeferred<Resolution> = CompletableDeferred(),
    ) {
        /** Who resolved it; set before [result] completes. */
        @Volatile
        var resolvedBy: String? = null
    }

    private val lock = Any()
    private val open = LinkedHashMap<String, Open>()
    /** Recently resolved ids, so a late second answer is refused as a duplicate rather than as unknown. */
    private val resolved = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > RESOLVED_MEMORY
    }
    private val sessionAllowed = HashMap<String, MutableSet<String>>()

    override suspend fun requestApproval(session: String, tool: String, input: String): Decision =
        requestApprovalOutcome(session, tool, input).decision

    override suspend fun requestApprovalOutcome(session: String, tool: String, input: String, recommended: Decision?): ApprovalOutcome {
        if (isAllowedForSession(session, tool)) return ApprovalOutcome(Decision.ALLOW_SESSION)
        // PROMPT-6: a remembered rule of the session's project answers with no card (and one transcript line).
        val rule = runCatching { rules.match(projectOf(session), tool, input) }.getOrNull()
        if (rule != null) {
            runCatching { notice(session, ruleNotice(rule)) }
            return ApprovalOutcome(Decision.ALLOW_ALWAYS)
        }
        val policy = runCatching { policyFor(session) }.getOrElse { PromptTimeoutPolicy(DEFAULT_TIMEOUT_MS) }
        // ASK-TIMER-2: the timer never stores a rule or a session grant: a recommended allow is auto-selected as allow once.
        val autoPick = recommended?.let { if (it.allows) Decision.ALLOW_ONCE else Decision.DENY }
        val now = clock()
        val request = PromptRequest(
            promptId = newId(),
            sessionId = session,
            kind = PromptKinds.TOOL_APPROVAL,
            createdAt = now,
            tool = tool,
            input = summarize(input),
            expiresAt = expiry(now, policy.timeoutMillis),
            onTimeout = (autoPick ?: policy.onTimeout.decision).wire,
            recommended = autoPick?.wire,
        )
        val settled = await(request, fullInput = input, policy = policy)
        val decision = (settled.resolution as? Resolution.Approval)?.decision ?: Decision.DENY
        return ApprovalOutcome(decision, timedOut = settled.resolvedBy == PromptResolvedBy.TIMEOUT, timeoutSec = policy.timeoutMillis / 1_000)
    }

    override suspend fun askQuestion(session: String, spec: QuestionSpec): Answer {
        val global = runCatching { policyFor(session) }.getOrElse { PromptTimeoutPolicy(DEFAULT_TIMEOUT_MS) }
        // PROMPT-5: a question's own timeout replaces the session's / the global one.
        val policy = spec.timeoutMillis?.let { global.copy(timeoutMillis = it) } ?: global
        val now = clock()
        val request = PromptRequest(
            promptId = newId(),
            sessionId = session,
            kind = PromptKinds.QUESTION,
            createdAt = now,
            expiresAt = expiry(now, policy.timeoutMillis),
            question = spec.question,
            options = spec.options,
            multiSelect = spec.multiSelect,
            allowOther = spec.allowOther,
            recommended = spec.recommended?.takeIf { rec -> spec.options.any { it.label == rec } },
        )
        return (await(request, policy = policy).resolution as? Resolution.Question)?.answer ?: Answer.NONE
    }

    /** True when the owner chose `allow_session` for [tool] earlier in [session]. */
    fun isAllowedForSession(session: String, tool: String): Boolean =
        synchronized(lock) { sessionAllowed[session]?.contains(tool) == true }

    /** The prompts still open, oldest first. */
    fun snapshot(): List<PromptRequest> = synchronized(lock) { open.values.map { it.request } }

    /**
     * A client's `prompt_response`, answered by [resolvedBy] (`client:<n>`). Returns the `prompt_resolved` it caused.
     * Refused ([IllegalArgumentException], nothing changes): an unknown id, one already resolved, or an answer that
     * does not fit the prompt's kind and options.
     */
    fun respond(response: PromptResponse, resolvedBy: String): PromptResolved {
        val id = response.promptId.trim()
        require(id.isNotEmpty()) { "promptId is required" }
        val entry = synchronized(lock) {
            open[id] ?: throw IllegalArgumentException(
                if (resolved.containsKey(id)) "prompt '${id.take(80)}' is already resolved" else "unknown prompt '${id.take(80)}'",
            )
        }
        val resolution = validate(entry.request, response)
        return resolve(id, resolvedBy, resolution)
            ?: throw IllegalArgumentException("prompt '${id.take(80)}' is already resolved")
    }

    /** The daemon's session stream: a turn's end or a terminal session resolves that session's open prompts. */
    fun onSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Exit -> resolveSession(event.sessionId, PromptResolvedBy.SESSION_END)
            is SessionEvent.Status -> if (event.session.state in ENDED) endSession(event.session.id)
            else -> Unit
        }
    }

    /** [sessionId] ended: its open prompts resolve as deny / no answer and its `allow_session` tools are forgotten. */
    fun endSession(sessionId: String) {
        resolveSession(sessionId, PromptResolvedBy.SESSION_END)
        synchronized(lock) { sessionAllowed.remove(sessionId) }
    }

    /** The daemon is stopping: every open prompt resolves as deny / no answer. */
    fun closeAll(resolvedBy: String = PromptResolvedBy.DAEMON_STOP) {
        val entries = synchronized(lock) { open.values.toList() }
        entries.forEach { resolve(it.request.promptId, resolvedBy, noAnswer(it.request)) }
    }

    private fun resolveSession(sessionId: String, resolvedBy: String) {
        val entries = synchronized(lock) { open.values.filter { it.request.sessionId == sessionId } }
        entries.forEach { resolve(it.request.promptId, resolvedBy, noAnswer(it.request)) }
    }

    /** How a prompt was settled: its [resolution] and who caused it (`client:<n>`, [PromptResolvedBy.TIMEOUT], ...). */
    private data class Settled(val resolution: Resolution, val resolvedBy: String)

    /**
     * Open [request] and suspend until it resolves. ASK-TIMER: [policy] is its timer (0 = none); on expiry it resolves
     * with [PromptTimeoutPolicy.onTimeout] (a question: no answer) and one transcript line goes to [notice].
     */
    private suspend fun await(request: PromptRequest, fullInput: String? = null, policy: PromptTimeoutPolicy): Settled {
        val entry = Open(request, fullInput)
        synchronized(lock) { open[request.promptId] = entry }
        sink(Envelope(Envelope.PROMPT_REQUEST, json.encodeToString(PromptRequest.serializer(), request), request.sessionId))
        try {
            val answered = if (policy.timeoutMillis <= 0) entry.result.await()
            else withTimeoutOrNull(policy.timeoutMillis) { entry.result.await() }
            if (answered != null) return Settled(answered, entry.resolvedBy ?: "")
            if (resolve(request.promptId, PromptResolvedBy.TIMEOUT, onExpiry(request, policy)) != null) {
                runCatching { notice(request.sessionId, expiryNotice(request, policy)) }
            }
            // Whoever won the race (the timeout above, or an answer that landed just before it) completed it.
            return Settled(entry.result.await(), entry.resolvedBy ?: "")
        } catch (e: CancellationException) {
            // The asker went away (its run was stopped): close the card everywhere instead of leaving it dangling.
            resolve(request.promptId, PromptResolvedBy.CANCELLED, noAnswer(request))
            throw e
        }
    }

    /** Resolve [id] once; null when it was not open (already resolved). Pushes `prompt_resolved` on success. */
    private fun resolve(id: String, resolvedBy: String, resolution: Resolution): PromptResolved? {
        val entry = synchronized(lock) {
            val entry = open.remove(id) ?: return null
            resolved[id] = Unit
            if (resolution is Resolution.Approval && resolution.decision == Decision.ALLOW_SESSION) {
                entry.request.tool?.let { tool -> sessionAllowed.getOrPut(entry.request.sessionId) { HashSet() }.add(tool) }
            }
            entry
        }
        // PROMPT-6: remember the rule before the asker resumes, so its very next call already matches. A store failure
        // is logged, never turned into a refusal: the owner said allow, and this call is allowed either way.
        if (resolution is Resolution.Approval && resolution.decision == Decision.ALLOW_ALWAYS) {
            val tool = entry.request.tool
            if (tool != null) {
                runCatching { rules.add(projectOf(entry.request.sessionId), tool, entry.fullInput ?: entry.request.input.orEmpty()) }
                    .onFailure { System.err.println("[agentHq] allow_always rule not stored: ${it.message}") }
            }
        }
        entry.resolvedBy = resolvedBy
        entry.result.complete(resolution)
        val frame = PromptResolved(id, resolvedBy, outcomeOf(resolution))
        runCatching {
            sink(Envelope(Envelope.PROMPT_RESOLVED, json.encodeToString(PromptResolved.serializer(), frame), entry.request.sessionId))
        }
        return frame
    }

    private fun validate(request: PromptRequest, response: PromptResponse): Resolution = when (request.kind) {
        PromptKinds.TOOL_APPROVAL -> {
            require(response.answers.isEmpty() && response.otherText == null) { "a tool_approval answer takes only 'decision'" }
            val decision = Decision.parse(response.decision)
                ?: throw IllegalArgumentException("unknown decision '${response.decision?.take(40)}' (known: ${PromptDecisions.ALL.joinToString()})")
            Resolution.Approval(decision)
        }
        else -> {
            require(response.decision == null) { "a question answer takes 'answers' / 'otherText', not 'decision'" }
            val labels = request.options.map { it.label }.toSet()
            val unknown = response.answers.firstOrNull { it !in labels }
            require(unknown == null) { "unknown option '${unknown!!.take(80)}'" }
            require(response.answers.distinct().size == response.answers.size) { "an option is answered twice" }
            require(request.multiSelect || response.answers.size <= 1) { "this question takes one answer" }
            val other = response.otherText?.trim()?.takeIf { it.isNotEmpty() }
            require(response.answers.isNotEmpty() || other != null) { "an answer needs 'answers' or 'otherText'" }
            Resolution.Question(Answer(response.answers, other, answered = true))
        }
    }

    /**
     * ASK-TIMER-2: what an expired prompt resolves as: its RECOMMENDED option when it has one (a question's label, an
     * approval's decision); else `prompts.onTimeout` for an approval and no answer for a question.
     */
    private fun onExpiry(request: PromptRequest, policy: PromptTimeoutPolicy): Resolution = when {
        request.kind == PromptKinds.TOOL_APPROVAL ->
            Resolution.Approval(Decision.parse(request.recommended) ?: policy.onTimeout.decision)
        request.recommended != null -> Resolution.Question(Answer(listOf(request.recommended), null, answered = true))
        else -> Resolution.Question(Answer.NONE)
    }

    private fun noAnswer(request: PromptRequest): Resolution =
        if (request.kind == PromptKinds.TOOL_APPROVAL) Resolution.Approval(Decision.DENY) else Resolution.Question(Answer.NONE)

    private fun outcomeOf(resolution: Resolution): String = when (resolution) {
        is Resolution.Approval -> resolution.decision.wire
        is Resolution.Question -> if (resolution.answer.answered) PromptOutcomes.ANSWERED else PromptOutcomes.NO_ANSWER
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 600_000L
        /** The longest `input` summary a `prompt_request` carries. */
        const val INPUT_SUMMARY_MAX = 200
        private const val RESOLVED_MEMORY = 1024
        private val ENDED = setOf(SessionState.EXITED, SessionState.ORPHANED, SessionState.FAILED)
        private val json = Json { encodeDefaults = true }

        /** PROMPT-6: the transcript line for a call a rule answered: "auto-allowed by rule <tool> [<prefix>]". */
        fun ruleNotice(rule: com.superiorsoftllc.agenthq.protocol.ApprovalRule): String =
            "auto-allowed by rule ${rule.tool}" + (rule.prefix?.let { " $it" } ?: "")

        /** ASK-TIMER: the deadline of a prompt opened at [now] with [timeoutMillis]; null = no timer. */
        fun expiry(now: Long, timeoutMillis: Long): Long? = if (timeoutMillis <= 0) null else now + timeoutMillis

        /** ASK-TIMER: the transcript line an expired prompt leaves, e.g. "tool approval for Bash timed out after 600s: auto-denied". */
        fun expiryNotice(request: PromptRequest, policy: PromptTimeoutPolicy): String {
            val after = "timed out after ${policy.timeoutMillis / 1_000}s with no answer"
            // ASK-TIMER-2: a recommended option was auto-selected.
            request.recommended?.let { rec ->
                val what = if (request.kind == PromptKinds.TOOL_APPROVAL) "tool approval for ${request.tool ?: "a tool"}"
                else "question \"${request.question.orEmpty().take(80)}\""
                return "auto-selected recommended: ${optionLabel(request, rec)} ($what $after)"
            }
            return if (request.kind == PromptKinds.TOOL_APPROVAL) {
                val action = if (policy.onTimeout == TimeoutAction.APPROVE) "auto-approved (allow once)" else "auto-denied"
                "tool approval for ${request.tool ?: "a tool"} $after: $action (prompts.onTimeout = ${policy.onTimeout.wire})"
            } else {
                "question \"${request.question.orEmpty().take(80)}\" $after: resolved as no answer"
            }
        }

        /** ASK-TIMER-2: an option in words: an approval decision as Allow / Deny, a question's label as is. */
        fun optionLabel(request: PromptRequest, option: String): String =
            if (request.kind != PromptKinds.TOOL_APPROVAL) option
            else if (Decision.parse(option)?.allows == true) "Allow" else "Deny"

        /** One line, at most [INPUT_SUMMARY_MAX] characters: a card shows what the call is, never its whole payload. */
        fun summarize(input: String): String {
            val oneLine = input.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length <= INPUT_SUMMARY_MAX) oneLine else oneLine.take(INPUT_SUMMARY_MAX - 1) + "…"
        }
    }
}
