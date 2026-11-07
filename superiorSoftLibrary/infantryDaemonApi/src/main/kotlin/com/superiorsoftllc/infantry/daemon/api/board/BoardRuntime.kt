// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.board

import com.superiorsoftllc.infantry.daemon.api.Frame

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

    /** RUNNER-POOL-6 (1.45.0, additive): the runners placement may pick (the pool's view); empty = only `local`. */
    fun runners(): List<BoardRunner> = emptyList()

    /**
     * True while the daemon itself still builds the board (DP-5h-3b moves that to the plugin): a board plugin then registers its
     * tools only and leaves the board, its frames and its consumers to core.
     */
    val coreBuiltBoard: Boolean get() = false

    /** DP-5h-3a2 (1.13.0, additive): the typed ticket event stream (triggers, reaper, ticket-files consume it; so may the plugin). */
    val ticketEvents: TicketEvents get() = NoTicketEvents

    /** DP-5h-3a2 (1.13.0): the story-shim inputs. */
    val stories: BoardStoryInputs get() = BoardStoryInputs.NONE

    /** DP-5h-3a2 (1.13.0): the epic store's persistence and checks. */
    val epics: BoardEpicInputs get() = BoardEpicInputs.NONE

    /** DP-5h-3a2 (1.13.0): the RELATED (auto) block to store atop a description; null = keep as given. */
    fun relatedDescription(draft: BoardRelatedDraft): String? = null

    /** DP-5h-3a2 (1.13.0): of [ids], the skill ids no stored skill carries (a save naming one is refused). */
    fun unknownSkills(ids: List<String>): List<String> = ids

    /** DP-5h-3e-b (1.14.0, additive): the first-turn context inputs the board composes with: the project's linked-tasks and skills switches (null = never stored), its baseline skill ids, a role's notes (name, description) and the RELATED block (null = none). */
    fun includeLinkedTasksContext(): Boolean? = null
    fun includeSkillsContext(): Boolean? = null
    fun skillIds(): List<String> = emptyList()
    fun includeFinishInstructions(): Boolean? = null
    fun roleNotes(projectId: String, role: String): List<Pair<String, String>> = emptyList()
    fun relatedBlock(ticketId: String, description: String): String? = null

    /** STACK-NEUTRAL-5 (additive): the programs the watch shims while the watch is on (`limits.watch.enabled` and `limits.watch.forced`), for the start brief's watch line; empty = the watch is off. */
    fun watchedPrograms(): List<String> = emptyList()

    /** Daemon tests only: a FIXED first turn (prompt, then (source, chars) pairs) that replaces the composed context; null = compose. */
    fun fixedFirstTurn(): Pair<String, List<Pair<String, Int>>>? = null

    /** DP-5h-3e-b (1.14.0, additive): the plugin that built the board hands core its [BoardCoreAccess]; core's consumers resolve it at call time. */
    fun attachCore(access: BoardCoreAccess) {}

    /** DP-5h-3e-b (1.14.0): the plugin is stopping or reloading; core's consumers answer "no board" until the next [attachCore]. */
    fun detachCore() {}

    /** Contract 1.5.0: push these frames to every connected client, in order. */
    fun push(frames: List<Frame>) {}
}

/** [over] non-null = the start is refused with that reason; [warning] is advice on an allowed start. */
data class BoardPacing(val over: String? = null, val warning: String? = null)

/** A daemon without a board runtime. */
object NoBoardRuntime : BoardRuntime {
    override val host: BoardHost = NoBoardHost
}

/**
 * RUNNER-POOL-6 (1.45.0): one runner as placement sees it: [state] `up` | `degraded` | `down` (only `up` takes starts), [freeSlots],
 * the [projects] it agrees to run and the harnesses signed in on it ([loggedInHarnesses]).
 */
data class BoardRunner(
    val name: String,
    val state: String,
    val freeSlots: Int = 1,
    val projects: List<String> = emptyList(),
    val loggedInHarnesses: List<String> = emptyList(),
)
