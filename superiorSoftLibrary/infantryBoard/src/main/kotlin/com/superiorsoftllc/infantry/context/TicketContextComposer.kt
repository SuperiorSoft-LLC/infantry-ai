package com.superiorsoftllc.infantry.context

import com.superiorsoftllc.infantry.agents.identityLine
import com.superiorsoftllc.infantry.agents.renderTraits
import com.superiorsoftllc.infantry.protocol.ContextSwitch
import com.superiorsoftllc.infantry.protocol.EffectiveContext
import com.superiorsoftllc.infantry.session.ContextSource
import com.superiorsoftllc.infantry.ticketfiles.RelatedWorkSource
import com.superiorsoftllc.infantry.settings.ProjectSettings

/** Composes the bounded profile, ticket and linked-ticket context for a ticket start. */
class TicketContextComposer(
    private val settings: ProjectSettings,
    /** P10-K9: the `knowledge` source, read for the ticket's project when the start composes. */
    private val knowledge: KnowledgeSection = KnowledgeSection.NONE,
    /** P10-K15: the `skills` source, rendered after `knowledge` from the started session's frozen skill ids. */
    private val skills: SkillsSection = SkillsSection.NONE,
    /** NOTES-1: the started role's own notes in the ticket's project, listed after `knowledge`. */
    private val roleNotes: RoleNotesSource = RoleNotesSource.NONE,
    /** DAEMON-CONTEXT-1: the related tickets and their files, assembled by the daemon into the first prompt. */
    private val related: RelatedWorkSource = RelatedWorkSource.NONE,
    /** STACK-NEUTRAL-5: the programs the watch shims, read at every start; empty = the watch is off (no watch line). */
    private val watchedPrograms: () -> List<String> = { emptyList() },
) : TicketContext {
    override fun compose(input: ContextInput): ComposedContext {
        val relatedBlock = input.ticketId?.let { runCatching { related.block(it, input.description) }.getOrNull() }
        // KNOWLEDGE-PTR-1: the live related block supersedes the description's stored RELATED (auto) block, so the prompt
        // does not carry both; with no live block (switched off, nothing found) the stored one stays as written.
        val description = if (relatedBlock != null) com.superiorsoftllc.infantry.ticketfiles.RelatedDescriptionBlock.strip(input.description) else input.description
        val core = input.title + "\n\n" + description
        val switches = effective(input.includeLinkedOverride, input.includeSkillsOverride)
        // RELATED-SHAPE-1: only the opt-in `tickets` mode names related tickets (RELATED_TICKETS_LINE, linked list, old files line).
        val ticketsMode = runCatching { related.mode() }.getOrDefault(com.superiorsoftllc.infantry.ticketfiles.RelatedConfig.MODE_TICKETS) ==
            com.superiorsoftllc.infantry.ticketfiles.RelatedConfig.MODE_TICKETS
        val includeLinked = switches.includeLinkedTasksContext.value && ticketsMode
        val sections = buildList {
            add("infantry" to "You are running inside Infantry AI. Ticket: ${input.ticketId}. Project: ${input.projectId ?: "none"}. Infantry AI tickets, agents, mail and project knowledge live in the Infantry AI daemon and are reached with the `infantry` CLI, never through files. See superiorSoftLibrary/infantry/docs/AGENT-QUICKREF.md.\n" + ticketMoveLine(input.ticketId, emptyList(), ticketsMode) + "\n" + MCP_LINE + (if (ticketsMode) "\n" + RELATED_TICKETS_LINE else ""))
            // ROLE-NOTE-1: the built-in report-by-mail text yields to the project (setting off = neither line; role notes govern).
            val finish = settings.includeFinishInstructions() ?: true
            add("team" to if (finish) ((input.reportTo?.let { TEAM_BLOCK + "\n" + reportToLine(it) } ?: TEAM_BLOCK) + "\n" + FINISH_LINE) else TEAM_BLOCK)
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
            relatedBlock?.let { add("related" to it) }
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
            "Use `infantry memory index|show|search|save --scope role|session`; the default scope is the shared project notes."
    }

    companion object {
        /** IMG-3: the heading of the attached-images section; each image follows as `[image: <absolute path>]`. */
        const val IMAGES_HEADING = "Attached images (read each file to see it):"

        /** IMG-3: the attached-images section, or null when the ticket has none. */
        fun imagesSection(paths: List<String>): String? =
            paths.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = IMAGES_HEADING + "\n") { "[image: $it]" }

        /**
         * BUG-E2E-2: the agent closes its own ticket. Blocked work is NOT moved to pending: the board sets pending
         * itself from an open blocked_by prerequisite and refuses that move by hand, so the agent reports instead.
         */
        fun ticketMoveLine(ticketId: String?, watchedPrograms: List<String> = emptyList(), relatedFilesLine: Boolean = true): String {
            val id = ticketId ?: "<ticket id>"
            return "When the work is done, run `infantry ticket move $id done`. If you are blocked, leave the ticket " +
                "in_progress and mail what blocks you (Team, below) " +
                "(pending is set by the daemon from a blocked_by link; a move there by hand is refused).\n" +
                DESC_FILE_LINE + BG_RUN_LINE + watchLine(watchedPrograms) + (if (relatedFilesLine) RELATED_FILES_LINE else "")
        }

        /** `infantry bg run`: a long gate the agent starts is flagged to the daemon, so the session reads Running, not idle. */
        const val BG_RUN_LINE = "\nRun long commands (builds, test gates) as `infantry bg run --notify --name <label> -- <cmd...>`: " +
            "it returns at once, your session shows Running, and the daemon mails you when the job ends (exit code + last lines), " +
            "matches a pattern or runs too long. Never sleep or poll: end your turn and continue when that mail arrives " +
            "(`infantry bg wait <id>` only if mail does not wake your harness). `infantry bg list|status|logs|stop <id>` manage them. " +
            "If `infantry bg --help` does not list `run --notify` (an older CLI), run the command as a plain background job, end your turn " +
            "and use `infantry bg wait <id>` or read its log later; still never sleep or poll."

        /**
         * STALL-1 (ticket 802c64d5) / STACK-NEUTRAL-5: the daemon-side watch, told once at ticket start. Empty [programs] = the watch
         * is off: no line. The tool list is the shimmed programs of the profiles in force, never a name written here.
         */
        fun watchLine(programs: List<String>): String {
            if (programs.isEmpty()) return ""
            val names = programs.map { "`$it`" }
            val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
            return "\nYour commands are WATCHED by the daemon, at no token cost: $list run through " +
                "its runner automatically (START, `still running` every minute, END with exit code), and any long process under your " +
                "session is seen when it ends. The result arrives as an `[AUTOMATIC]` mail that wakes you; a quiet job, a job still " +
                "running after 15 min and an idle or stuck ticket get one too. Opt out only for a real need: " +
                "`infantry unwatched --reason \"<why>\" -- <cmd...>` (logged and reported to the PM). Never sleep, poll or block a turn only to wait: end it, the mail wakes you."
        }

        /** TICKET-FILES (55476f3c): the related tickets' files are one indexed read, far cheaper than grepping for them. */
        const val RELATED_FILES_LINE = "\nBefore you grep or read around, call infantry_ticket_related_files with this ticket's id (CLI: " +
            "`infantry ticket files <id> --related`; a path's tickets: `infantry ticket files --path <glob>`): the files of the tickets that changed the same code."

        /**
         * MCP-1: every session the daemon spawns has Infantry AI's own MCP server, so the agent prefers its tools (no Tool
         * approval, no shell quoting); the CLI stays the fallback and covers what the tools do not (e.g. ticket move).
         */
        const val MCP_LINE = "When the Infantry AI MCP tools are available (mcp__infantry__* in Claude, infantry_* in opencode), " +
            "prefer them over the CLI: infantry_ticket_create / infantry_ticket_update / infantry_ticket_get for tickets " +
            "(the description goes in verbatim, multi-line included), infantry_ticket_start to put an agent to work on a " +
            "ticket (it runs the ticket's role, provider and model on the ticket's own branch; infantry_list_harnesses " +
            "lists the providers and models to pin), infantry_message for mail. Use the `infantry` CLI " +
            "for anything they do not cover, or when they are not available. Start ticket work with infantry_ticket_start " +
            "or `infantry ticket start <id>`, never infantry_spawn: a spawn runs outside the board, without the ticket's " +
            "provider, model or branch, and leaves the ticket in To Do."

        /**
         * APP-KNOWLEDGE (rule B): a worker reuses prior work before it reads from scratch. Checks the linkedTickets
         * section, the board, then the related tickets' files (RELATED_FILES_LINE above); cites their ids in the report.
         */
        const val RELATED_TICKETS_LINE = "\nBefore you start, check the related tickets: (1) the linkedTickets section above, if this " +
            "ticket has one, (2) the board (infantry_board, or infantry_ticket_get on an id) for tickets that touch the same work, " +
            "(3) their changed files (infantry ticket files <id> --related; MCP infantry_ticket_related_files), so you go straight " +
            "to the code. Read their findings, reuse their work, and cite their ids in your report. If none are related, say so " +
            "in one line and carry on."

        /**
         * Team block every agent's first message carries (MAIL-5). The CLI reads the agent's project
         * from its environment, so these commands are scoped to the agent's own project. Keep it short.
         */
        /**
         * BUG-CLI-1: a multi-line `--desc` loses every line after the first on Windows (cmd.exe ends the launcher's
         * arguments at the first newline), so agents are told to pass long text through a file or stdin.
         */
        const val DESC_FILE_LINE = "To file or edit a ticket with a multi-line description, write it to a file and pass " +
            "`--desc-file <path>` to `infantry ticket create` / `ticket update` (or pipe it with `--desc -`); " +
            "never a multi-line `--desc`, which keeps only its first line on Windows."

        /**
         * PROMPT-5: how ANY harness (Pi, Codex, OpenCode, Claude) asks the owner a select question. It goes through the
         * `infantry` CLI, which BUG-PERM-5 allows in every Tool approval mode, so it works whatever the mode.
         * ASK-1: the line teaches the NON-BLOCKING form (`--recommend`): the agent never waits on the owner.
         */
        const val ASK_LINE = "- The owner WANTS you to ask for real decisions: when a choice has 2-4 real options and their preference matters (design, priority, destructive/irreversible), run `infantry ask --question \"...\" --option \"label|consequence\" --recommend <label> --reason \"...\"` (MCP infantry_ask; --other, --default-after <min>). " +
            "Always recommend one. Don't ask trivia or what is already decided. It returns an id at once and does not wait: keep working on what doesn't depend on it; the pick arrives as mail (thread question:<id>)."

        /**
         * BUG-MAIL-2: the starter of a ticket started from a session. The worker may sit in another project than its
         * starter, where `send pm` finds nobody; `send starter` (or the starter's session id) reaches it anyway.
         */
        fun reportToLine(reportTo: ReportTo): String =
            "- Report to: ${reportTo.name} (${reportTo.sessionId}), who started this ticket. Mail it with " +
                "`infantry send $STARTER \"msg\"` (or `infantry send ${reportTo.sessionId} \"msg\"`); it works across projects."

        /**
         * When a worker finishes it mails the ticket's starter. Same words for every harness and model, so it never
         * depends on the ticket description. The daemon also sends an [AUTOMATIC] notice; this mail is the real report.
         */
        const val FINISH_LINE = "WHEN YOU ARE FINISHED: run `infantry send starter \"<message>\"` (the starter is who started this " +
            "ticket; if `starter` fails, use `infantry send pm`). The message must give: the commit sha, what changed, " +
            "what you tested and what you did not test, any problem, and a line 'Files read: <paths>' listing the files you read (a report without it is refused). Then stop. Do not keep working after you mail."

        /** BUG-MAIL-2: the recipient alias for the session that started the sender's ticket. */
        const val STARTER = "starter" // = mailbox.MailAddresses.STARTER in the daemon

        /**
         * MAIL-5 team block. BUG-MAIL-MCP-2: mail is read with the MCP tools infantry_inbox / infantry_read (MAIL-MCP-1);
         * the CLI stays the fallback. Quoted verbatim in docs/AGENT-QUICKREF.md section 6 (pinned by a test).
         */
        const val TEAM_BLOCK = "Team (scoped to your project):\n" +
            "- infantry agents --live: who is live in this project\n" +
            "- infantry send <role|agentId> \"msg\": message a colleague (queued if they are busy)\n" +
            "- infantry_inbox / infantry_read <id> (your Infantry AI MCP tools): your mail; " +
            "fallback when they are not available: infantry inbox / infantry read <id>\n" +
            "- infantry mail: every mail command (answer one with --reply-to <id>); infantry --help: every command, grouped. " +
            "Start here: superiorSoftLibrary/infantry/docs/AGENT-QUICKREF.md, section 0\n" +
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

/** DP-5e: what the composer asks of the host for the `knowledge` source (core renders it from its knowledge stores). */
fun interface KnowledgeSection {
    fun section(projectId: String?): String?

    companion object {
        /** No knowledge base wired: no section. */
        val NONE = KnowledgeSection { null }
    }
}

/** DP-5e: what the composer asks of the host for the `skills` source (core renders it from the skill library and the frozen ids). */
fun interface SkillsSection {
    /** The section for a session frozen with [skillIds] (null = no instance); [include] is the ticket's resolved switch, null = the project's. */
    fun section(skillIds: List<String>?, include: Boolean?): String?

    companion object {
        /** No library wired: no section. */
        val NONE = SkillsSection { _, _ -> null }
    }
}
