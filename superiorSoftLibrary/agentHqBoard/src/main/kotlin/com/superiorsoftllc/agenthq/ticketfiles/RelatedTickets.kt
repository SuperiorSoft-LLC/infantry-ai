package com.superiorsoftllc.agenthq.ticketfiles


/**
 * KNOWLEDGE-PTR-1 (ticket b8ca2bf7, absorbs RELATED-TICKETS c0732366): what the related set of a ticket shows, all of it
 * data. The switches and texts are daemon settings (`related.*`, [com.superiorsoftllc.agenthq.settings.SettingsService]);
 * the caps are `limits.ticketFiles.*` entries of `LimitRegistry`. Defaults: everything on.
 */
data class RelatedConfig(
    /** `related.brief`: the RELATED WORK block of a ticket's start message. */
    val brief: Boolean = true,
    /** `related.briefInstruction`: the one instruction line under that block ("" = none). */
    val instruction: String = DEFAULT_INSTRUCTION,
    /** `related.descriptionBlock`: the stored `RELATED (auto ...)` block at the top of a description. */
    val descriptionBlock: Boolean = true,
    /** `related.familyPattern`: group 1 = the series label, group 2 = its number ("" = no families). */
    val familyPattern: String = DEFAULT_FAMILY_PATTERN,
    /** `related.suggestNumber`: a title with a known label and no number gets the next number suggested. */
    val suggestNumber: Boolean = true,
    val briefTickets: Int = 5,
    val briefFiles: Int = 5,
    val briefChars: Int = 1500,
    val blockTickets: Int = 8,
    val blockFiles: Int = 5,
    val blockChars: Int = 2500,
) {
    val family: Regex? by lazy { familyPattern.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() } }

    companion object {
        const val DEFAULT_FAMILY_PATTERN = "^([A-Z][A-Z0-9]*(?:-[A-Z][A-Z0-9]*)*)-(\\d+)\\b"
        const val DEFAULT_INSTRUCTION = "Read these files first; call agenthq_ticket_related_files and agenthq_ticket_files " +
            "(CLI `agenthq ticket files <id> [--related]`) before you grep or read broadly."
        val DEFAULT = RelatedConfig()
    }
}

/** A title's series: `USAGE-UI-1` = label `USAGE-UI`, number 1. */
data class Family(val label: String, val number: Int?)

/** KNOWLEDGE-PTR-1 (B): ticket families by title label. Exact label match: `USAGE-UI` is not `USAGE`. */
object TicketFamily {
    /** The label and number [title] carries under [pattern], null when it carries none. */
    fun of(title: String, pattern: Regex?): Family? {
        val m = pattern?.find(title.trim()) ?: return null
        val label = m.groups[1]?.value?.takeIf { it.isNotEmpty() } ?: return null
        return Family(label, m.groups[2]?.value?.toIntOrNull())
    }

    /**
     * The family of [title], or, when it has no number, the longest label OTHER tickets use that the title starts with
     * as a whole word (`USAGE: x`, `USAGE x`; never `USAGEX` or `USAGE-UI x` for USAGE).
     */
    fun resolve(title: String, others: Collection<TicketInfo>, pattern: Regex?): Family? {
        of(title, pattern)?.let { return it }
        val trimmed = title.trim()
        return others.mapNotNull { of(it.title, pattern)?.label }.distinct()
            .filter { trimmed.startsWith(it) && trimmed.getOrNull(it.length)?.let { c -> !c.isLetterOrDigit() && c != '-' } != false }
            .maxByOrNull { it.length }?.let { Family(it, null) }
    }

    /** The other tickets of [label], in number order. */
    fun members(label: String, rows: Collection<TicketInfo>, pattern: Regex?, excludeId: String?): List<Pair<TicketInfo, Int>> =
        rows.filter { it.id != excludeId }.mapNotNull { r -> of(r.title, pattern)?.takeIf { it.label == label }?.let { r to (it.number ?: 0) } }
            .sortedWith(compareBy({ it.second }, { it.first.id }))

    /** `USAGE-3` when USAGE-1 and USAGE-2 exist. */
    fun nextNumber(label: String, rows: Collection<TicketInfo>, pattern: Regex?, excludeId: String?): String =
        "$label-${(members(label, rows, pattern, excludeId).maxOfOrNull { it.second } ?: 0) + 1}"
}

/**
 * KNOWLEDGE-PTR-1 (C): the marked, regenerable block at the TOP of a description. [apply] replaces the old block (or adds
 * one) and keeps the human text below it byte for byte; [strip] gives that text back. A marker anywhere but the top is
 * human text and is never touched.
 */
object RelatedDescriptionBlock {
    const val BEGIN = "<!-- RELATED (auto) -->"
    const val END = "<!-- /RELATED -->"

    /** A line `related: off` in the human text opts the ticket out of the stored block and the start block. */
    const val OPT_OUT = "related: off"

    fun strip(description: String): String {
        if (!description.startsWith(BEGIN)) return description
        val end = description.indexOf(END)
        if (end < 0) return description
        var rest = end + END.length
        // The separator [apply] writes: one newline and one blank line.
        if (description.startsWith("\n\n", rest)) rest += 2 else if (description.startsWith("\n", rest)) rest += 1
        return description.substring(rest)
    }

    /** [description] with its block replaced by [block] (null = removed). */
    fun apply(description: String, block: String?): String {
        val human = strip(description)
        if (block == null) return human
        val marked = "$BEGIN\n$block\n$END"
        return if (human.isEmpty()) marked else "$marked\n\n$human"
    }

    fun optedOut(description: String): Boolean =
        strip(description).lineSequence().any { it.trim().equals(OPT_OUT, ignoreCase = true) }
}

/** Why a ticket is in the related set. */
enum class RelatedReason(val label: String) { LINKED("linked"), NAMED("named"), FAMILY("series"), FILES("same files") }

data class RelatedEntry(val row: TicketInfo, val reason: RelatedReason, val files: List<TicketFile>) {
    /** The commit to `git show` for the diff: the merge, else the branch tip recorded when it went done. */
    val sha: String? get() = files.firstNotNullOfOrNull { it.mergeSha } ?: files.firstNotNullOfOrNull { it.tipSha }
}

/** The related set of one ticket (or of a draft, [ticketId] null). */
data class RelatedSet(
    val own: Set<String>,
    val entries: List<RelatedEntry>,
    val family: Family?,
    /** Only for a title with a known label and no number (and `related.suggestNumber` on). */
    val suggestedTitle: String?,
    val isHub: (String) -> Boolean,
)

/**
 * KNOWLEDGE-PTR-1: the one resolver behind the start block (A, [LiveRelatedWork]) and the stored description block
 * (C, [descriptionBlock]). Reads the `ticket_files` index only (no git) unless [filesOf] says otherwise, so a done ticket's
 * files stay listed after its worktree and branch are gone. No second store: links are the board's own `ticket_links`,
 * families come from titles, files from `ticket_files`.
 */
class RelatedTickets(
    private val files: TicketFiles,
    private val hubShare: Double = LiveRelatedWork.HUB_SHARE,
    private val minTicketsForHubs: Int = LiveRelatedWork.MIN_TICKETS_FOR_HUBS,
) {
    /**
     * [extraIds]: ids the caller names (`relatedTo`). [linkedFirst]: the start block puts the tickets a human linked or
     * named first; the stored block puts the family first (then linked, then same files).
     */
    fun resolve(
        ticketId: String?,
        title: String,
        description: String,
        projectId: String?,
        rows: Collection<TicketInfo>,
        links: Collection<TicketLinkInfo>,
        extraIds: Collection<String>,
        cfg: RelatedConfig,
        maxTickets: Int,
        linkedFirst: Boolean,
        filesOf: (TicketInfo) -> List<TicketFile> = { files.forTicket(it.id) },
        self: TicketInfo? = null,
        /** A ticket the index or the text names that [rows] does not hold (null = not shown). */
        lookup: (String) -> TicketInfo? = { null },
    ): RelatedSet {
        val human = RelatedDescriptionBlock.strip(description)
        val text = title + "\n" + human
        val byId = rows.associateBy { it.id }
        val recorded = files.recordedTicketIds().size
        val hubCache = HashMap<String, Boolean>()
        val isHub: (String) -> Boolean = { path ->
            hubCache.getOrPut(path) { recorded >= minTicketsForHubs && files.pathFrequency(path).toDouble() / recorded >= hubShare }
        }

        val own = (self?.let(filesOf) ?: ticketId?.let { files.forTicket(it) }.orEmpty()).map { it.path }.filterNot(isHub).toMutableSet()
        if (own.isEmpty()) own += LiveRelatedWork.mentionedGlobs(text).flatMap { files.forPaths(listOf(it)) }.map { it.path }.filterNot(isHub)

        val linked = (extraIds.map(String::trim) + links.filter { ticketId != null && (it.ticketId == ticketId || it.relatedId == ticketId) }
            .map { if (it.ticketId == ticketId) it.relatedId else it.ticketId }).distinct()
        val named = LiveRelatedWork.idsNamed(text)

        val sameProject = rows.filter { projectId == null || it.projectId == null || it.projectId == projectId }
        val family = TicketFamily.resolve(title, sameProject.filter { it.id != ticketId }, cfg.family)
        val familyIds = family?.let { f ->
            val members = TicketFamily.members(f.label, sameProject, cfg.family, ticketId)
            // Nearest numbers first when the cap cuts the series; shown in number order.
            val chosen = f.number?.let { n -> members.sortedBy { kotlin.math.abs(it.second - n) } } ?: members.reversed()
            chosen.take(maxTickets).sortedBy { it.second }.map { it.first.id }
        }.orEmpty()

        val shared = HashMap<String, Int>()
        for (path in own) for (f in files.forPaths(listOf(path))) if (f.path == path && f.ticketId != ticketId) shared.merge(f.ticketId, 1, Int::plus)
        val overlap = shared.map { (id, n) ->
            val total = files.forTicket(id).count { !isHub(it.path) }
            id to n.toDouble() / (own.size + total - n).coerceAtLeast(1)
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).map { it.first }

        val ordered = buildList {
            val explicit = linked.map { it to RelatedReason.LINKED } + named.map { it to RelatedReason.NAMED }
            val fam = familyIds.map { it to RelatedReason.FAMILY }
            if (linkedFirst) { addAll(explicit); addAll(fam) } else { addAll(fam); addAll(explicit) }
            addAll(overlap.map { it to RelatedReason.FILES })
        }.filter { it.first != ticketId }.distinctBy { it.first }
            .mapNotNull { (id, reason) -> (byId[id] ?: lookup(id))?.let { it to reason } }.take(maxTickets)

        val entries = ordered.map { (row, reason) -> RelatedEntry(row, reason, filesOf(row)) }
        val suggested = family?.takeIf { cfg.suggestNumber && it.number == null && TicketFamily.of(title, cfg.family) == null }
            ?.let { TicketFamily.nextNumber(it.label, sameProject, cfg.family, ticketId) }
        return RelatedSet(own, entries, family, suggested, isHub)
    }

    /** The stored block's text (without its markers), or null when nothing resolves and there is nothing to suggest. */
    fun descriptionBlock(set: RelatedSet, cfg: RelatedConfig): String? {
        if (set.entries.isEmpty() && set.suggestedTitle == null) return null
        val shownFiles = set.entries.associate { e -> e.row.id to pickFiles(e.files, set.own, set.isHub, cfg.blockFiles) }
        val fileCount = shownFiles.values.flatten().map { it.path }.distinct().size
        val lines = ArrayList<String>()
        lines += "RELATED (auto, ${set.entries.size} tickets, $fileCount files; suggestions, regenerated on every edit; " +
            "a line '${RelatedDescriptionBlock.OPT_OUT}' below turns it off):"
        set.suggestedTitle?.let { lines += "Series ${set.family!!.label}: the next number is $it (suggestion; the title is not changed)." }
        val tail = ArrayList<String>()
        val top = set.entries.flatMap { e -> shownFiles.getValue(e.row.id).map { it.path to e.row.id } }
            .groupBy({ it.first }, { it.second }).entries.sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenBy { it.key })
            .take(cfg.blockFiles)
        if (top.isNotEmpty()) tail += "Top files: " + top.joinToString("; ") { (p, ids) -> "${RelatedBlock.shortPath(p)} (${ids.joinToString(", ")})" }
        var used = (lines + tail).sumOf { it.length + 1 }
        for (e in set.entries) {
            val line = entryLine(e, shownFiles.getValue(e.row.id), e.files.size)
            if (used + line.length + 1 > cfg.blockChars) break
            lines += line
            used += line.length + 1
        }
        if (tail.isNotEmpty() && lines.sumOf { it.length + 1 } + tail.first().length <= cfg.blockChars) lines += tail
        return lines.joinToString("\n")
    }

    companion object {
        /** A related ticket's files to show: non-hub; the ones the ticket shares (or its module's) first, else its biggest changes. */
        fun pickFiles(all: List<TicketFile>, own: Set<String>, isHub: (String) -> Boolean, max: Int): List<TicketFile> {
            val ownModules = own.map(RelatedBlock::module).toSet()
            val candidates = all.filterNot { isHub(it.path) }
            val near = candidates.filter { it.path in own || RelatedBlock.module(it.path) in ownModules }
                .sortedWith(compareByDescending<TicketFile> { it.path in own }.thenBy { it.path })
            val pool = near.ifEmpty { candidates.sortedWith(compareByDescending<TicketFile> { (it.added ?: 0) + (it.removed ?: 0) }.thenBy { it.path }) }
            return pool.take(max.coerceAtLeast(0))
        }

        fun entryLine(e: RelatedEntry, shown: List<TicketFile>, total: Int): String {
            val more = total - shown.size
            val files = if (shown.isEmpty()) (if (total > 0) " -> $total files" else "")
            else " -> files: " + shown.joinToString(", ") { RelatedBlock.shortPath(it.path) } + (if (more > 0) " +$more more" else "")
            val sha = e.sha?.let { " (git show ${it.take(8)})" }.orEmpty()
            return "- ${e.reason.label}: ${e.row.id} ${clip(e.row.title, 70)} [${e.row.status}]$files$sha"
        }

        private fun clip(text: String, max: Int): String {
            val one = text.replace('\r', ' ').replace('\n', ' ').trim()
            return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
        }
    }
}
