// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.board.BoardLines
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.board.TicketQuery
import com.superiorsoftllc.infantry.daemon.api.sessions.ReadScope
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.TimeUnit

/**
 * RUNTIME-LOAD-5 (F5 remainder): `infantry_board`, moved out of the CLI's MCP server (`McpTools.board`). Same name, descriptor
 * (golden-checked) and lines: [BoardLines] renders them, the text `infantry board` prints too. The scope and the tickets come from
 * the host (the sessions read model's scope, the Tickets API); each ticket's worker is the host's view of its session, read in the
 * caller's scope; the branch tip is `git rev-parse --short` in the worker's checkout, bounded by [GIT_BUDGET_MS] in all.
 */
class BoardTool(private val api: DaemonApi) : DaemonTool {
    override val name = BOARD
    override val description =
        "The board in one call: tickets grouped by column (backlog, todo, in_progress, pending, done), one line each with its " +
            "worker (role, session id), the worker's live state (busy / idle / running background process), last action, " +
            "last output age, branch and last commit. Compact by default (done: last 5; backlog and todo: first 10 lines and a count); " +
            "pass columns / limit for more. Use this instead of tickets + sessions + transcripts."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Project whose tickets to summarise. Default: the caller's own project; \"all\" (pm / owner callers only) covers every project, each line prefixed [projectId].")
            }
            putJsonObject("columns") {
                put("type", "string")
                put("description", "Comma-separated columns to show, e.g. in_progress,pending (backlog, todo, in_progress, pending, done). Default: all, compact.")
            }
            putJsonObject("waiting") {
                put("type", "boolean")
                put("description", "true = only the tickets whose worker flagged itself 'active but waiting' (infantry waiting on). Their lines always show WAITING <age>: <reason>.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("description", "At most this many ticket lines per column (done: the newest). Default: compact (done 5, backlog/todo 10 and a count).")
            }
        }
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject { put("readOnlyHint", true) }
    override val timeoutMs = TIMEOUT_MS

    override fun call(call: ToolCall): ToolResult {
        val by = WireTickets.by(call)
        val scope = when (val r = api.sessionReads.scope(by, ToolArgs.string(call.arguments, "projectId"), call.clientProjectId)) {
            is SessionsResult.Ok -> r.value
            is SessionsResult.Refused -> return ToolResult("$BOARD refused: ${r.reason}", isError = true)
            is SessionsResult.Missing -> return ToolResult(r.text, isError = true)
            is SessionsResult.Unavailable -> return ToolResult("$BOARD is unavailable: ${r.reason}", isError = true)
        }
        scope.refusal?.let { return ToolResult("$BOARD refused: $it", isError = true) }
        val tickets = tickets(by, scope).getOrElse { return ToolResult("$BOARD refused: ${it.message}", isError = true) }
        // Each worker as the host shows it to this caller; a worker the caller may not see (or that is gone) reads as gone.
        val workers = tickets.mapNotNull { it.sessionId }.distinct().mapNotNull { id ->
            (api.sessionReads.find(id, by) as? SessionsResult.Ok)?.value?.takeIf { it.id == id }
        }
        val columns = ToolArgs.string(call.arguments, "columns")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
        val limit = (call.arguments["limit"] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val options = BoardLines.Options(
            columns, limit, compact = true,
            queueShown = api.settings.limit("limits.tools.boardQueueShown")?.toInt() ?: BoardLines.QUEUE_SHOWN,
            doneShown = api.settings.limit("limits.tools.boardDoneShown")?.toInt() ?: BoardLines.DONE_SHOWN,
            waitingOnly = (call.arguments["waiting"] as? JsonPrimitive)?.contentOrNull?.trim() == "true",
        )
        val lines = BoardLines.render(tickets.map(BoardLines::row), workers, System.currentTimeMillis(), commits(workers), options)
            .map { line -> if (scope.projectId == null) tickets.firstOrNull { line.startsWith("  " + BoardLines.shortId(it.id) + " ") }?.let { "  [${it.projectId}] ${line.trimStart()}" } ?: line else line }
        return ToolResult(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            scope.projectId?.let { put("projectId", it) }
            put("scope", scope.label)
            put("board", buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
        }))
    }

    /** Every ticket of [scope], page by page; `all` when the scope covers every project (the API's null is the caller's own). */
    private fun tickets(by: Caller, scope: ReadScope): Result<List<TicketInfo>> {
        val out = mutableListOf<TicketInfo>()
        var cursor: String? = null
        do {
            when (val r = api.tickets.list(by, TicketQuery(projectId = scope.projectId ?: ReadScope.ALL, limit = TicketQuery.MAX_LIMIT, cursor = cursor))) {
                is BoardResult.Ok -> { out += r.value.items; cursor = r.value.next }
                is BoardResult.Refused -> return Result.failure(IllegalStateException(r.text))
                is BoardResult.Unavailable -> return Result.failure(IllegalStateException(r.reason))
            }
        } while (cursor != null)
        return Result.success(out)
    }

    /** The branch tip of a row, via git in its worker's checkout or repo; null when it cannot be read or the budget is spent. */
    private fun commits(workers: List<SessionView>): (BoardLines.Row) -> String? {
        val byId = workers.associateBy { it.id }
        val deadline = System.currentTimeMillis() + GIT_BUDGET_MS
        return { t ->
            val branch = t.branch
            val dir = t.sessionId?.let { byId[it] }?.let { it.repoRoot ?: it.worktreePath }
            if (branch == null || dir == null || System.currentTimeMillis() > deadline) null
            else gitTip(listOf("git", "-C", dir, "rev-parse", "--short", branch), GIT_CALL_MS)
        }
    }

    companion object {
        /** BOARD-HANG-1: runs [command], waits at most [callMs] (then kills it), then reads: first output line, or null on failure/timeout. */
        internal fun gitTip(command: List<String>, callMs: Long): String? = runCatching {
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            p.outputStream.close()
            if (!p.waitFor(callMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); null }
            else if (p.exitValue() != 0) null
            else p.inputStream.bufferedReader().readText().trim().lines().firstOrNull()?.takeIf { it.isNotBlank() }
        }.getOrNull()

        const val BOARD = "infantry_board"
        /** The whole call, git lookups included (a timed-out tool call disables the plugin, so the git work is budgeted below it). */
        const val TIMEOUT_MS = 120_000L
        const val GIT_BUDGET_MS = 20_000L
        /** One git lookup; a stuck git is killed after this. */
        const val GIT_CALL_MS = 3_000L
    }
}
