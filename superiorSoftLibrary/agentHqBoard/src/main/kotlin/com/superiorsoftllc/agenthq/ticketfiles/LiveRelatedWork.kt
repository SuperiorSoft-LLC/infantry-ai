package com.superiorsoftllc.agenthq.ticketfiles


/**
 * DAEMON-CONTEXT-1: builds the related block from the file index, for a ticket that may still be running. Its own files
 * are the recorded ones or, before done, its branch's live diff; files named in its text (paths, class names) seed the
 * search too. KNOWLEDGE-PTR-1: related = the tickets linked to it or named by id first, then the other tickets of its
 * series (title label, [TicketFamily]), then by Jaccard overlap on the non-hub files ([RelatedTickets]). The caps, the
 * instruction line and the off switch are [config] (`related.*`, `limits.ticketFiles.brief*`); a ticket whose text carries
 * the [RelatedDescriptionBlock.OPT_OUT] line gets no block. A done ticket's files come from the stored index, so the block
 * still works after its worktree and branch are gone.
 */
class LiveRelatedWork(
    private val files: TicketFiles,
    private val ticket: (String) -> TicketInfo?,
    private val rows: () -> Collection<TicketInfo> = { emptyList() },
    private val links: () -> Collection<TicketLinkInfo> = { emptyList() },
    private val config: () -> RelatedConfig = { RelatedConfig.DEFAULT },
    hubShare: Double = HUB_SHARE,
    minTicketsForHubs: Int = MIN_TICKETS_FOR_HUBS,
) : RelatedWorkSource {
    private val resolver = RelatedTickets(files, hubShare, minTicketsForHubs)

    override fun mode(): String = runCatching { config().mode }.getOrDefault(RelatedConfig.MODE_FILES_COMMAND)

    override fun block(ticketId: String, description: String): String? = runCatching {
        val cfg = config()
        if (!cfg.brief || RelatedDescriptionBlock.optedOut(description)) return@runCatching null
        if (cfg.mode == RelatedConfig.MODE_FILES_COMMAND) return@runCatching FILES_COMMAND_LINE
        if (cfg.mode == RelatedConfig.MODE_FILES_INLINE) {
            val paths = files.relatedPaths(ticketId)
            return@runCatching if (paths.isEmpty()) FILES_COMMAND_LINE else "Files changed by related work (${paths.size}):\n" + paths.joinToString("\n")
        }
        val row = ticket(ticketId) ?: return@runCatching null
        val all = rows().let { list -> if (list.any { it.id == ticketId }) list else list + row }
        // A ticket named in the text or found in the index that the board listing missed is still resolved by id.
        val set = resolver.resolve(
            ticketId = ticketId, title = row.title, description = description, projectId = row.projectId,
            rows = all, links = links().toList(), extraIds = emptyList(), cfg = cfg,
            maxTickets = cfg.briefTickets, linkedFirst = true, filesOf = files::liveFiles, self = row, lookup = ticket,
        )
        val views = set.entries.map { e ->
            val t = e.row
            RelatedTicketView(
                id = t.id, title = t.title, status = t.status, branch = t.branch,
                outcome = RelatedDescriptionBlock.strip(t.description).lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty(),
                files = e.files.map { RelatedFile(it.path, it.status, (it.added ?: 0) + (it.removed ?: 0)) },
                reason = e.reason.label, sha = e.sha,
            )
        }
        RelatedBlock.render(set.own, views, set.isHub, cfg.briefTickets, cfg.briefFiles, cfg.briefChars, cfg.instruction)
    }.getOrNull()

    companion object {
        /** A path that at least this share of the recorded tickets touch is a hub (CONTRACT.md style): not shown. */
        const val HUB_SHARE = 0.25

        /** RELATED-SHAPE-1: the whole related section of a brief in the default mode. */
        const val FILES_COMMAND_LINE = "Files changed by related work: run `agenthq ticket files <id> --related` (MCP: agenthq_ticket_related_files)"
        const val MIN_TICKETS_FOR_HUBS = 8

        private val ID = Regex("\\bticket-[0-9a-f]{8}\\b")
        private val PATH = Regex("[\\w.-]+(?:/[\\w.-]+)+\\.(?:kt|kts|sq|md|json|yml|yaml|java)")
        private val CLASS = Regex("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b")

        /** Tickets named in [text] as `ticket-xxxxxxxx`, in order. */
        fun idsNamed(text: String): List<String> = ID.findAll(text).map { it.value }.distinct().toList()

        /** SQL globs for the paths and CamelCase class names [text] mentions (at most 8). */
        fun mentionedGlobs(text: String): List<String> =
            (PATH.findAll(text).map { it.value } + CLASS.findAll(text).map { "*/${it.value}.*" }).distinct().take(8).toList()
    }
}
