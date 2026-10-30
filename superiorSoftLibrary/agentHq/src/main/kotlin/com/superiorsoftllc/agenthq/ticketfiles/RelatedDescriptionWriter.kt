package com.superiorsoftllc.agenthq.ticketfiles

import com.superiorsoftllc.agenthq.backlog.RelatedDescription
import com.superiorsoftllc.agenthq.backlog.RelatedDraft

/**
 * KNOWLEDGE-PTR-1 (C): the stored RELATED (auto) block, over the file index only (no git, so a save never waits on it).
 * `related.descriptionBlock` off = the description is stored exactly as given. A ticket opted out ([RelatedDescriptionBlock.OPT_OUT])
 * loses a block it had and gets none. Nothing resolves = no block (a stale one is removed). Idempotent: the same board gives
 * the same block, and the human text below it is never changed.
 */
class RelatedDescriptionWriter(
    files: TicketFiles,
    private val config: () -> RelatedConfig = { RelatedConfig.DEFAULT },
) : RelatedDescription {
    private val resolver = RelatedTickets(files)

    override fun describe(draft: RelatedDraft): String? = runCatching {
        val cfg = config()
        if (!cfg.descriptionBlock) return@runCatching null
        if (RelatedDescriptionBlock.optedOut(draft.description)) return@runCatching RelatedDescriptionBlock.strip(draft.description)
        RelatedDescriptionBlock.apply(draft.description, block(draft, cfg))
    }.getOrNull()

    /** The block's text for [draft] (no markers), null = none: what `ticket related --suggest` prints without storing it. */
    fun block(draft: RelatedDraft, cfg: RelatedConfig = config()): String? {
        val set = resolver.resolve(
            ticketId = draft.ticketId, title = draft.title, description = draft.description, projectId = draft.projectId,
            rows = draft.rows, links = draft.links, extraIds = emptyList(), cfg = cfg,
            maxTickets = cfg.blockTickets, linkedFirst = false,
        )
        return resolver.descriptionBlock(set, cfg)
    }
}
