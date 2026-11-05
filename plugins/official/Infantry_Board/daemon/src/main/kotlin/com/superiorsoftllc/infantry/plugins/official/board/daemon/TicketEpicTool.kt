// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.EpicInfo
import com.superiorsoftllc.infantry.daemon.api.board.Epics
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.board.TicketPatch
import com.superiorsoftllc.infantry.daemon.api.board.Tickets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * EPIC-MOVE-1: file one or many tickets under an epic, or under none.
 *
 * The target is an epic id or an epic name of the tickets' project (case-insensitive); a name no epic has creates that epic
 * (only when at least one ticket will move). `none` (or blank) clears the epic. A move is a `ticket_save` of `epicId` alone,
 * so status, order, comments, branch and worker are kept.
 *
 * BASE GUARD: an in_progress ticket already cut its branch from the base its epic chose. When the old and the new epic
 * choose a different base ([EpicInfo.baseBranch]; blank = the project's base, which both share), the ticket is NOT moved
 * and the answer says why, unless [force] is set: then it moves, its branch keeps its old base, and the answer warns.
 * Every id is resolved before anything is written: one unknown id refuses the whole call.
 */
internal object EpicMove {
    const val NONE = "none"
    private const val IN_PROGRESS = "in_progress"
    private val EPIC_ID = Regex("""^epic-[0-9a-f]{8}$""")

    data class Skipped(val ticketId: String, val reason: String)

    data class Outcome(
        /** The target, null = no epic. */
        val epic: EpicInfo?,
        val created: Boolean,
        val moved: List<TicketInfo>,
        val unchanged: List<String>,
        val refused: List<Skipped>,
        val warnings: List<String>,
    )

    class Refused(val text: String) : RuntimeException(text, null, false, false)

    private fun <T> ok(r: BoardResult<T>): T = when (r) {
        is BoardResult.Ok -> r.value
        is BoardResult.Refused -> throw Refused(r.text)
        is BoardResult.Unavailable -> throw Refused(r.reason)
    }

    /** Moves [ids] to [target] (id, name or [NONE]); a refusal of the whole call throws [Refused]. */
    fun run(tickets: Tickets, epics: Epics, by: Caller, ids: List<String>, target: String, force: Boolean): Outcome {
        val wanted = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) throw Refused("name at least one ticket")
        val found = wanted.map { id -> ok(tickets.get(id, by)) }.distinctBy { it.id }
        val clear = target.isBlank() || target.trim().equals(NONE, ignoreCase = true)
        val project = found.map { it.projectId }.distinct()
        if (!clear && project.size != 1) throw Refused("the tickets belong to ${project.size} projects; an epic belongs to one, so move them per project")
        val projectId = project.single()
        val known = if (clear) emptyList() else ok(epics.list(projectId, by))
        val name = target.trim()
        val existing = if (clear) null else known.firstOrNull { it.id == name } ?: known.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (!clear && existing == null && EPIC_ID.matches(name)) throw Refused("no epic '$name' in project ${projectId ?: "(none)"}")
        if (!clear && existing == null && projectId.isNullOrBlank()) throw Refused("the tickets have no project, so a new epic has nowhere to live")
        // Old epics' bases, read once each (an epic that is gone counts as no base).
        val oldEpics = found.mapNotNull { it.epicId }.distinct().associateWith { id -> (epics.get(id, by) as? BoardResult.Ok)?.value }
        val newBase = existing?.baseBranch.orEmpty()
        val unchanged = mutableListOf<String>()
        val refused = mutableListOf<Skipped>()
        val warnings = mutableListOf<String>()
        val toMove = mutableListOf<TicketInfo>()
        for (t in found) {
            val same = if (clear) t.epicId == null else existing != null && t.epicId == existing.id
            if (same) { unchanged += t.id; continue }
            val oldBase = t.epicId?.let { oldEpics[it]?.baseBranch }.orEmpty()
            if (t.status == IN_PROGRESS && oldBase != newBase) {
                val why = "in progress on a branch cut from ${label(oldBase)}; the ${if (clear) "project" else "new epic"} cuts from ${label(newBase)}"
                if (!force) { refused += Skipped(t.id, "$why: finish or unassign it first, or pass force (its branch keeps its base)"); continue }
                warnings += "${WireTickets.shortId(t.id)}: $why; moved anyway (force), its branch keeps its old base"
            }
            toMove += t
        }
        var created = false
        val epic = when {
            clear -> null
            existing != null -> existing
            toMove.isEmpty() -> null
            else -> ok(epics.create(projectId!!, name, "", by)).also { created = true }
        }
        val moved = toMove.map { t -> ok(tickets.update(t.id, TicketPatch(epicId = epic?.id ?: ""), by)) }
        return Outcome(epic ?: existing, created, moved, unchanged, refused, warnings)
    }

    private fun label(base: String) = if (base.isEmpty()) "the project's base" else "'$base'"

    /** The answer line(s) a tool and the CLI print. */
    fun text(o: Outcome, target: String): String = buildString {
        val where = o.epic?.let { "epic ${it.name} (${it.id})" } ?: if (target.isBlank() || target.trim().equals(NONE, true)) "no epic" else "epic '${target.trim()}'"
        append("Moved ${o.moved.size} ticket(s) to $where")
        if (o.created) append(", created it")
        if (o.moved.isNotEmpty()) append(": ").append(o.moved.joinToString(", ") { WireTickets.shortId(it.id) })
        append('.')
        if (o.unchanged.isNotEmpty()) append(" Already there: ").append(o.unchanged.joinToString(", ") { WireTickets.shortId(it) }).append('.')
        o.refused.forEach { append("\nNot moved ${WireTickets.shortId(it.ticketId)}: ${it.reason}") }
        o.warnings.forEach { append("\nWarning ").append(it) }
    }

    fun json(o: Outcome, target: String): JsonObject = buildJsonObject {
        put("epicId", o.epic?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("epicName", o.epic?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("created", o.created)
        put("moved", buildJsonArray { o.moved.forEach { add(JsonPrimitive(it.id)) } })
        put("unchanged", buildJsonArray { o.unchanged.forEach { add(JsonPrimitive(it)) } })
        put("refused", buildJsonArray { o.refused.forEach { add(buildJsonObject { put("ticketId", it.ticketId); put("reason", it.reason) }) } })
        put("warnings", buildJsonArray { o.warnings.forEach { add(JsonPrimitive(it)) } })
        put("message", text(o, target))
    }

    /** The `tickets` argument (array of ids) plus a single `ticketId`. */
    fun ids(args: JsonObject): List<String> {
        val list = (args["tickets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
        return listOfNotNull(ToolArgs.string(args, "ticketId")) + list
    }
}

/** EPIC-MOVE-1: `infantry_ticket_epic`, the bulk move ([EpicMove]); `infantry ticket epic <id...> <epic|none>` calls it. */
class TicketEpicTool(private val api: DaemonApi) : DaemonTool {
    override val name = NAME
    override val description =
        "Move one or many tickets to an epic (id or name; a new name creates the epic in the tickets' project), or out of " +
            "any epic with epic \"none\". Status, order, comments, branch and worker are kept. An in_progress ticket whose " +
            "branch was cut from another base than the new epic's is NOT moved (the answer says why) unless force is true."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("tickets", TicketSave.pathsProp("Ticket ids (full or 8-char) to move."))
            put("ticketId", ToolArgs.prop("One ticket id (full or 8-char); with tickets, added to the list."))
            put("epic", ToolArgs.prop("Target epic: its id or name (case-insensitive; an unknown name creates it), or none to clear."))
            put("force", ToolArgs.boolProp("true moves in_progress tickets even when the new epic cuts from another base (their branch keeps its base)."))
        })
        put("required", buildJsonArray { add(JsonPrimitive("epic")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult {
        val args = call.arguments
        val target = (args["epic"] as? JsonPrimitive)?.content ?: return ToolResult("$NAME needs an epic (id, name or none)", isError = true)
        val outcome = try {
            EpicMove.run(api.tickets, api.epics, WireTickets.by(call), EpicMove.ids(args), target, ToolArgs.flag(args, "force"))
        } catch (e: EpicMove.Refused) {
            return ToolResult("$NAME refused: ${e.text}", isError = true)
        }
        val nothing = outcome.moved.isEmpty() && outcome.unchanged.isEmpty()
        return ToolResult(EpicMove.json(outcome, target).toString(), isError = nothing && outcome.refused.isNotEmpty())
    }

    companion object { const val NAME = "infantry_ticket_epic" }
}
