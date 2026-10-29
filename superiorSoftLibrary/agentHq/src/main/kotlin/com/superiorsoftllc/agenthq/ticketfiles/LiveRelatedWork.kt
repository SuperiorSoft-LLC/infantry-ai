package com.superiorsoftllc.agenthq.ticketfiles

import com.superiorsoftllc.agenthq.context.RelatedBlock
import com.superiorsoftllc.agenthq.context.RelatedFile
import com.superiorsoftllc.agenthq.context.RelatedTicketView
import com.superiorsoftllc.agenthq.context.RelatedWorkSource
import com.superiorsoftllc.agenthq.persistence.TicketRow

/**
 * DAEMON-CONTEXT-1: builds the related block from the file index, for a ticket that may still be running. Its own files
 * are the recorded ones or, before done, its branch's live diff; files named in its text (paths, class names) seed the
 * search too. Related = tickets named by id in the text first, then by Jaccard overlap on the non-hub files.
 */
class LiveRelatedWork(
    private val files: TicketFiles,
    private val ticket: (String) -> TicketRow?,
    private val hubShare: Double = HUB_SHARE,
    private val minTicketsForHubs: Int = MIN_TICKETS_FOR_HUBS,
) : RelatedWorkSource {

    override fun block(ticketId: String, description: String): String? = runCatching {
        val row = ticket(ticketId) ?: return@runCatching null
        val text = row.title + "\n" + description
        val recorded = files.recordedTicketIds().size
        val hubCache = HashMap<String, Boolean>()
        fun isHub(path: String) = hubCache.getOrPut(path) {
            recorded >= minTicketsForHubs && files.pathFrequency(path).toDouble() / recorded >= hubShare
        }
        val own = files.liveFiles(row).map { it.path }.filterNot(::isHub).toMutableSet()
        if (own.isEmpty()) own += mentionedGlobs(text).flatMap { files.forPaths(listOf(it)) }.map { it.path }.filterNot(::isHub)

        val named = idsNamed(text).filter { it != ticketId && ticket(it) != null }
        val shared = HashMap<String, Int>()
        for (path in own) {
            for (f in files.forPaths(listOf(path)).filter { it.path == path && it.ticketId != ticketId }) shared.merge(f.ticketId, 1, Int::plus)
        }
        val ranked = shared.map { (id, n) ->
            val total = files.forTicket(id).count { !isHub(it.path) }
            id to n.toDouble() / (own.size + total - n).coerceAtLeast(1)
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).map { it.first }
        val chosen = (named + ranked).distinct().take(3)

        val views = chosen.mapNotNull { id ->
            val t = ticket(id) ?: return@mapNotNull null
            RelatedTicketView(
                id = id, title = t.title, status = t.status, branch = t.branch,
                outcome = t.description.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty(),
                files = files.liveFiles(t).map { RelatedFile(it.path, it.status) },
            )
        }
        RelatedBlock.render(own, views, ::isHub)
    }.getOrNull()

    companion object {
        /** A path that at least this share of the recorded tickets touch is a hub (CONTRACT.md style): not shown. */
        const val HUB_SHARE = 0.25
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
