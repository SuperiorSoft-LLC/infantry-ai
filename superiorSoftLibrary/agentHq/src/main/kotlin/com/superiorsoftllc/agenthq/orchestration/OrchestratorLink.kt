package com.superiorsoftllc.agenthq.orchestration

/**
 * DAEMON-CORE-1 (docs/DAEMON-CORE.md, "Orchestrator vs runner"): the ONE seam between the runner side of the daemon (sessions,
 * harnesses, working copies, the watch runner) and the orchestration side (board and ticket rules, mail rules, policy, usage,
 * accounts). Callers hold an [OrchestratorLink] and nothing else: they never know whether it is answered in this process
 * ([LocalOrchestratorLink]) or by a remote service. Local vs remote is a BINDING chosen once at startup by configuration
 * (`orchestrator.provider = local | <plugin id>`), never an if/else in a caller.
 *
 * A binding owns transport, auth, retries and encoding. This file and every caller therefore name no transport: no http or
 * websocket client type, host or URL (OrchestratorLinkTransportGuardTest fails when they do).
 *
 * Contract: every operation is idempotent on its natural key (the caller may repeat it after [OrchestratorReply.Unavailable])
 * and returns an [OrchestratorReply]; it never throws for an expected failure. [CONTRACT_VERSION] is bumped when an operation
 * is added or its meaning changes; a binding refuses a peer it does not understand with [OrchestratorReply.Unavailable].
 */
interface OrchestratorLink {
    /** The contract this binding speaks. A caller may log it; it never branches on it. */
    val contractVersion: String get() = CONTRACT_VERSION

    /**
     * Review a report a worker session mails to its starter. Refused = the text to send back to the worker; accepted = null.
     * Natural key: (sender session, body). Reads (and may record) ticket state, so it lives on the orchestration side.
     */
    fun reviewReport(from: String, body: String): OrchestratorReply<String?>

    companion object {
        const val CONTRACT_VERSION = "1.0.0"
    }
}

/** The error model: an answer, or "the orchestrator could not be reached / did not understand", never an exception. */
sealed interface OrchestratorReply<out T> {
    data class Ok<T>(val value: T) : OrchestratorReply<T>
    /** The binding could not get an answer (remote down, timeout, version mismatch). The caller decides its fail mode. */
    data class Unavailable(val reason: String) : OrchestratorReply<Nothing>
}

/** The caller's fail-open reading of a refusal-shaped reply: unavailable means "no refusal", the local behaviour stays reachable. */
fun OrchestratorReply<String?>.refusalOrNull(): String? = (this as? OrchestratorReply.Ok)?.value

/** The binding for `orchestrator.provider = local`: answers in this process from the functions the daemon hands it. */
class LocalOrchestratorLink(
    private val reviewReport: (from: String, body: String) -> String?,
) : OrchestratorLink {
    override fun reviewReport(from: String, body: String): OrchestratorReply<String?> = OrchestratorReply.Ok(reviewReport.invoke(from, body))
}
