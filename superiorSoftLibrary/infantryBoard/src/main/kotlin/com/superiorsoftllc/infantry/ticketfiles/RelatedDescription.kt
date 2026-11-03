package com.superiorsoftllc.infantry.ticketfiles


/** KNOWLEDGE-PTR-1 (C): a ticket as a save or a link is about to store it, with the board it is stored into. */
data class RelatedDraft(
    /** Null on a create (the id is not cut yet). */
    val ticketId: String?,
    val title: String,
    /** The description about to be stored, its old RELATED (auto) block included. */
    val description: String,
    val projectId: String?,
    val rows: List<TicketInfo>,
    /** The board's links, the one this operation adds included. */
    val links: List<TicketLinkInfo>,
)

/**
 * KNOWLEDGE-PTR-1 (C): rewrites the stored RELATED (auto) block at the top of a description on every ticket save that sets
 * a title or description, and on every `relates_to` link. Answers the full description to store, or null = keep it as
 * given (switched off, or a failure: the save never fails on it). The daemon binds it to the file index.
 */
fun interface RelatedDescription {
    fun describe(draft: RelatedDraft): String?

    companion object {
        val NONE = RelatedDescription { null }
    }
}
