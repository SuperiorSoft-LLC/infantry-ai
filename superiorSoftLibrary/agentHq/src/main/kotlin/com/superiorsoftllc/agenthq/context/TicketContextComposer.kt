package com.superiorsoftllc.agenthq.context

import com.superiorsoftllc.agenthq.agents.KnowledgeCounter
import com.superiorsoftllc.agenthq.agents.SkillsSource
import com.superiorsoftllc.agenthq.agents.identityLine
import com.superiorsoftllc.agenthq.agents.section
import com.superiorsoftllc.agenthq.agents.renderTraits
import com.superiorsoftllc.agenthq.protocol.ContextSwitch
import com.superiorsoftllc.agenthq.protocol.EffectiveContext
import com.superiorsoftllc.agenthq.session.ContextSource
import com.superiorsoftllc.agenthq.settings.ProjectSettings

/** Composes the bounded profile, ticket and linked-ticket context for a ticket start. */
class TicketContextComposer(
    private val settings: ProjectSettings,
    /** P10-K9: the `knowledge` source, read for the ticket's project when the start composes. */
    private val knowledge: KnowledgeCounter = KnowledgeCounter.NONE,
    /** P10-K15: the `skills` source, rendered after `knowledge` from the started session's frozen skill ids. */
    private val skills: SkillsSource = SkillsSource.NONE,
    /** NOTES-1: the started role's own notes in the ticket's project, listed after `knowledge`. */
    private val roleNotes: RoleNotesSource = RoleNotesSource.NONE,
) : TicketContext {
    override fun compose(input: ContextInput): ComposedContext {
        val core = input.title + "\n\n" + input.description
        val switches = effective(input.includeLinkedOverride, input.includeSkillsOverride)
        val includeLinked = switches.includeLinkedTasksContext.value
        val sections = buildList {
            add("agenthq" to "You are running inside Infantry AI. Ticket: ${input.ticketId}. Project: ${input.projectId ?: "none"}. Infantry AI tickets, agents, mail and project knowledge live in the Infantry AI daemon and are reached with the `agenthq` CLI, never through files. See superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md.\n" + ticketMoveLine(input.ticketId) + "\n" + MCP_LINE)
            add("team" to ((input.reportTo?.let { TEAM_BLOCK + "\n" + reportToLine(it) } ?: TEAM_BLOCK) + "\n" + FINISH_LINE))
            input.instructions?.takeIf(String::isNotBlank)?.let { add("instructions" to it) }
            // The same persona -> identity -> task order [firstTurn] gives every other first turn.
            identityLine(input.role, input.agentId)?.let { add("identity" to it) }
            // TRAITS-T5: persona -> identity -> traits -> task, the order [firstTurn] gives every other first turn.
            renderTraits(input.traits)?.let { add("traits" to it) }
            // P10-K9: persona -> identity -> traits -> knowledge -> task; no project, no section.
            knowledge.section(input.projectId)?.let { add("knowledge" to it) }
            // NOTES-1: role notes (persist with the role) and the pointer to this session's own temporary notes.
            roleNotesSection(input.projectId, input.role)?.let { add("roleNotes" to it) }
            // P10-K15: ... -> knowledge -> skills -> task; the ticket's resolved switch decides, false = no section.
            skills.section(input.skillIds, switches.includeSkillsContext?.value)?.let { add("skills" to it) }
            add("ticket" to core)
            // IMG-3: right after the ticket it illustrates, so the worker reads the images with the brief.
            imagesSection(input.images)?.let { add("images" to it) }
            if (includeLinked && input.links.isNotEmpty()) {
                val linkedTickets = input.links.joinToString("\n") { link ->
                    val singleLineTitle = link.title.replace('\r', ' ').replace('\n', ' ')
                    "${link.ticketId} | $singleLineTitle | ${link.status} | ${link.type}"
                }
                add("linkedTickets" to linkedTickets)
            }
        }

        val prompt = sections.joinToString("\n\n") { it.second }
        val sources = sections.map { (source, content) ->
            ContextSource(source = source, chars = content.length)
        }
        return ComposedContext(prompt, sources)
    }

    /** NOTES-1: null with no project, no role or no role notes (a start with none is byte-identical to before). */
    private fun roleNotesSection(projectId: String?, role: String?): String? {
        val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val roleId = role?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lines = roleNotes.notes(project, roleId)
        if (lines.isEmpty()) return null
        val list = lines.joinToString("\n") { "- ${it.first}: ${it.second}" }
        return "ROLE NOTES (scope role: notes for the '$roleId' role, they persist with the role and only sessions of that role see them):\n" +
            list + "\n" +
            "Session notes (scope session) are yours alone and are deleted when this session is removed. " +
            "Use `agenthq memory index|show|search|save --scope role|session`; the default scope is the shared project notes."
    }

    internal companion object {
        /** IMG-3: the heading of the attached-images section; each image follows as `[image: <absolute path>]`. */
        const val IMAGES_HEADING = "Attached images (read each file to see it):"

        /** IMG-3: the attached-images section, or null when the ticket has none. */
        fun imagesSection(paths: List<String>): String? =
            paths.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = IMAGES_HEADING + "\n") { "[image: $it]" }

        /**
         * BUG-E2E-2: the agent closes its own ticket. Blocked work is NOT moved to pending: the board sets pending
         * itself from an open blocked_by prerequisite and refuses that move by hand, so the agent reports instead.
         */
        fun ticketMoveLine(ticketId: String?): String {
            val id = ticketId ?: "<ticket id>"
            return "When the work is done, run `agenthq ticket move $id done`. If you are blocked, leave the ticket " +
                "in_progress and mail what blocks you (Team, below) " +
                "(pending is set by the daemon from a blocked_by link; a move there by hand is refused).\n" +
                DESC_FILE_LINE + BG_RUN_LINE
        }

        /** `agenthq bg run`: a long gate the agent starts is flagged to the daemon, so the session reads Running, not idle. */
        const val BG_RUN_LINE = "\nRun long commands (Gradle builds, test gates) as `agenthq bg run --notify --name <label> -- <cmd...>`: " +
            "it returns at once, your session shows Running, and the daemon mails you when the job ends (exit code + last lines), " +
            "matches a pattern or runs too long. Never sleep or poll: end your turn and continue when that mail arrives " +
            "(`agenthq bg wait <id>` only if mail does not wake your harness). `agenthq bg list|status|logs|stop <id>` manage them."

        /**
         * MCP-1: every session the daemon spawns has Infantry AI's own MCP server, so the agent prefers its tools (no Tool
         * approval, no shell quoting); the CLI stays the fallback and covers what the tools do not (e.g. ticket move).
         */
        const val MCP_LINE = "When the Infantry AI MCP tools are available (mcp__agenthq__* in Claude, agenthq_* in opencode), " +
            "prefer them over the CLI: agenthq_ticket_create / agenthq_ticket_update / agenthq_ticket_get for tickets " +
            "(the description goes in verbatim, multi-line included), agenthq_ticket_start to put an agent to work on a " +
            "ticket (it runs the ticket's role, provider and model on the ticket's own branch; agenthq_list_harnesses " +
            "lists the providers and models to pin), agenthq_message for mail. Use the `agenthq` CLI " +
            "for anything they do not cover, or when they are not available. Start ticket work with agenthq_ticket_start " +
            "or `agenthq ticket start <id>`, never agenthq_spawn: a spawn runs outside the board, without the ticket's " +
            "provider, model or branch, and leaves the ticket in To Do."

        /**
         * Team block every agent's first message carries (MAIL-5). The CLI reads the agent's project
         * from its environment, so these commands are scoped to the agent's own project. Keep it short.
         */
        /**
         * BUG-CLI-1: a multi-line `--desc` loses every line after the first on Windows (cmd.exe ends the launcher's
         * arguments at the first newline), so agents are told to pass long text through a file or stdin.
         */
        const val DESC_FILE_LINE = "To file or edit a ticket with a multi-line description, write it to a file and pass " +
            "`--desc-file <path>` to `agenthq ticket create` / `ticket update` (or pipe it with `--desc -`); " +
            "never a multi-line `--desc`, which keeps only its first line on Windows."

        /**
         * PROMPT-5: how ANY harness (Pi, Codex, OpenCode, Claude) asks the owner a select question. It goes through the
         * `agenthq` CLI, which BUG-PERM-5 allows in every Tool approval mode, so it works whatever the mode.
         * ASK-1: the line teaches the NON-BLOCKING form (`--recommend`): the agent never waits on the owner.
         */
        const val ASK_LINE = "- The owner WANTS you to ask for real decisions: when a choice has 2-4 real options and their preference matters (design, priority, destructive/irreversible), run `agenthq ask --question \"...\" --option \"label|consequence\" --recommend <label> --reason \"...\"` (MCP agenthq_ask; --other, --default-after <min>). " +
            "Always recommend one. Don't ask trivia or what is already decided. It returns an id at once and does not wait: keep working on what doesn't depend on it; the pick arrives as mail (thread question:<id>)."

        /**
         * BUG-MAIL-2: the starter of a ticket started from a session. The worker may sit in another project than its
         * starter, where `send pm` finds nobody; `send starter` (or the starter's session id) reaches it anyway.
         */
        fun reportToLine(reportTo: ReportTo): String =
            "- Report to: ${reportTo.name} (${reportTo.sessionId}), who started this ticket. Mail it with " +
                "`agenthq send $STARTER \"msg\"` (or `agenthq send ${reportTo.sessionId} \"msg\"`); it works across projects."

        /**
         * When a worker finishes it mails the ticket's starter. Same words for every harness and model, so it never
         * depends on the ticket description. The daemon also sends an [AUTOMATIC] notice; this mail is the real report.
         */
        const val FINISH_LINE = "WHEN YOU ARE FINISHED: run `agenthq send starter \"<message>\"` (the starter is who started this " +
            "ticket; if `starter` fails, use `agenthq send pm`). The message must give: the commit sha, what changed, " +
            "what you tested and what you did not test, and any problem. Then stop. Do not keep working after you mail."

        /** BUG-MAIL-2: the recipient alias for the session that started the sender's ticket. */
        const val STARTER = "starter"

        /**
         * MAIL-5 team block. BUG-MAIL-MCP-2: mail is read with the MCP tools agenthq_inbox / agenthq_read (MAIL-MCP-1);
         * the CLI stays the fallback. Quoted verbatim in docs/AGENT-QUICKREF.md section 6 (pinned by a test).
         */
        const val TEAM_BLOCK = "Team (scoped to your project):\n" +
            "- agenthq agents --live: who is live in this project\n" +
            "- agenthq send <role|agentId> \"msg\": message a colleague (queued if they are busy)\n" +
            "- agenthq_inbox / agenthq_read <id> (your Infantry AI MCP tools): your mail; " +
            "fallback when they are not available: agenthq inbox / agenthq read <id>\n" +
            "- agenthq mail: every mail command (answer one with --reply-to <id>); agenthq --help: every command, grouped. " +
            "Start here: superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md, section 0\n" +
            ASK_LINE
    }

    override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext = EffectiveContext(
        includeLinkedTasksContext = resolve(override, settings.includeLinkedTasksContext()),
        // P10-K11: the skills switch resolves exactly like the linked-tasks one: ticket, then project, then true.
        includeSkillsContext = resolve(skillsOverride, settings.includeSkillsContext()),
    )

    private fun resolve(ticket: Boolean?, project: Boolean?): ContextSwitch = when {
        ticket != null -> ContextSwitch(value = ticket, source = "ticket")
        project != null -> ContextSwitch(value = project, source = "project")
        else -> ContextSwitch(value = true, source = "default")
    }
}

/** NOTES-1: the (name, description) pairs of a role's notes in a project, read when a start composes. */
fun interface RoleNotesSource {
    fun notes(projectId: String, role: String): List<Pair<String, String>>

    companion object {
        val NONE = RoleNotesSource { _, _ -> emptyList() }
    }
}
