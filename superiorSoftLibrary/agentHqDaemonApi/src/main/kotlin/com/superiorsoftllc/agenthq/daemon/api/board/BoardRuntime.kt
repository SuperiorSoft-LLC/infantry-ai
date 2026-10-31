package com.superiorsoftllc.agenthq.daemon.api.board

import com.superiorsoftllc.agenthq.daemon.api.Frame

/**
 * DP-5h-3a (DaemonApi 1.12.0, additive; docs/DAEMON-BOARD-API.md section 10): everything a BOARD PLUGIN needs from the daemon to build
 * its board, beyond the [BoardHost] ports: the stored settings the board reads at every start, the project tables (lane caps, routing
 * rules), the role catalog, the usage pacing check, the routing-refusal alert and the push to every client. Plain data only (JSON text,
 * strings, longs, [Frame]); the plugin parses with its own shaded classes, so no class crosses the plugin's class loader.
 *
 * A daemon without one answers [NoBoardRuntime]: [host] is the no-board host, every read answers its neutral default.
 */
interface BoardRuntime {
    /** The host the board runs on: storage, git, sessions, agents, run health, context sources, images, events. */
    val host: BoardHost

    /** A stored setting's JSON text (e.g. `tickets.exitBehavior`, `tickets.weightExecutors`); null = unset. */
    fun setting(key: String): String? = null

    /** The effective value of a limit setting (stored, else environment, else default); null = no such limit. */
    fun limit(key: String): Long? = null

    /** LANE-CAPS: the most agents of [role] that may run at once in [projectId]. */
    fun laneCap(projectId: String?, role: String): Int = 1

    /** ROUTING-1: the project's routing rules as a JSON array text (`[]` = none). */
    fun routingRules(projectId: String?): String = "[]"

    /** BUG-ASSIGN-1: the role ids a ticket may name; null = the canonical set. */
    fun roleIds(): List<String>? = null

    /** USAGE-PACE: whether one more ticket worker may start on the meter (provider, model) spends. */
    fun pacing(providerId: String?, model: String?): BoardPacing = BoardPacing()

    /** ROUTING-1: a rule-driven start was refused (the daemon raises an attention item). */
    fun routingRefused(ticketId: String, projectId: String?, detail: String) {}

    /**
     * True while the daemon itself still builds the board (DP-5h-3b moves that to the plugin): a board plugin then registers its
     * tools only and leaves the board, its frames and its consumers to core.
     */
    val coreBuiltBoard: Boolean get() = false

    /** Contract 1.5.0: push these frames to every connected client, in order. */
    fun push(frames: List<Frame>) {}
}

/** [over] non-null = the start is refused with that reason; [warning] is advice on an allowed start. */
data class BoardPacing(val over: String? = null, val warning: String? = null)

/** A daemon without a board runtime. */
object NoBoardRuntime : BoardRuntime {
    override val host: BoardHost = NoBoardHost
}
