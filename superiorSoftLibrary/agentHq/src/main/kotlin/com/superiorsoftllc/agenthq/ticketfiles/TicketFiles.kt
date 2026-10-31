package com.superiorsoftllc.agenthq.ticketfiles

import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.worktree.GitCli
import com.superiorsoftllc.agenthq.worktree.SystemGitCli
import java.io.File

/** One changed file of one ticket, as `ticket_files` stores it. */
data class TicketFile(
    val ticketId: String,
    val path: String,
    /** git's letter: A, M, D, R, C or T. */
    val status: String,
    val oldPath: String? = null,
    /** Null for a binary file. */
    val added: Long? = null,
    val removed: Long? = null,
    val tipSha: String? = null,
    val mergeSha: String? = null,
    val recordedAt: Long = 0,
)

/** A ticket that shares changed files with another: [shared] paths, [total] its own paths, [jaccard] the overlap. */
data class RelatedTicket(val ticketId: String, val shared: Int, val total: Int, val jaccard: Double)

/** Where one recording took its diff from. */
enum class FileSource { BRANCH, MERGE, COMMITS }

/** The answer of one [TicketFiles.record]: null source = nothing in git names this ticket. */
data class Recorded(val ticketId: String, val source: FileSource?, val files: Int)

/** What [TicketFiles.backfill] did: tickets recovered (by source), already recorded, and not recovered. */
data class BackfillReport(
    val considered: Int,
    val alreadyRecorded: Int,
    val recovered: Map<FileSource, Int>,
    val notRecovered: List<String>,
) {
    val recoveredTotal: Int get() = recovered.values.sum()
}

/**
 * TICKET-FILES (ticket 55476f3c): the files each ticket changed, and the tickets each file was changed by.
 *
 * WRITE SIDE (git, once per ticket). [record] runs ONE `git diff -z --raw --numstat -M` and replaces the ticket's rows.
 * The diff comes from, in order:
 *  1. MERGE: every `Merge ticket/<short-id> ... into ...` commit (feature and staging merges), `<merge>^1..<merge>`,
 *     unioned (a path changed by several merges keeps its largest counts);
 *  2. BRANCH: the ticket's own branch, `<base>..<tip>` (base = the commit its start cut from, the fork point), the
 *     usual answer at done time, before the merge; a later `ticket files --backfill --all` re-records it from the merge;
 *  3. COMMITS: every non-merge commit whose subject names the short id (`FIX 2a38cac2: ...`), `<c>^..<c>`, unioned.
 * It also writes `refs/agenthq/tickets/<id>` at the tip, so git never collects a deleted or unmerged branch and the diff
 * stays reproducible with `git show`.
 *
 * READ SIDE (no git). [forTicket], [forPaths], [related], [filesForRelated]: indexed SQL over `ticket_files`.
 *
 * WHY NO SECOND HISTORY STORE. ESTIMATION 1/3 (166b3a84, ticket_metrics + forkPoint) is not merged here; the fork point
 * is the ticket's own `base` column (BRANCH-PER-TASK), so this adds only the one table, nothing parallel to ticket_runs.
 */
class TicketFiles(
    private val db: AgentHQDatabase,
    private val tickets: () -> List<TicketInfo>,
    /** The git checkout a ticket of a project lives in (the board's cutRepo), null = none. */
    private val repoFor: (projectId: String?) -> File?,
    private val git: GitCli = SystemGitCli(timeoutMs = 120_000),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val q get() = db.ticket_filesQueries

    // ---- read side -------------------------------------------------------------------------------------------------

    fun forTicket(ticketId: String): List<TicketFile> = q.filesForTicket(ticketId).executeAsList().map {
        TicketFile(it.ticket_id, it.path, it.status, it.old_path, it.added, it.removed, it.tip_sha, it.merge_sha, it.recorded_at)
    }

    /** Rows whose path matches any of [globs] (see [sqlGlob]), newest recording first, one row per (ticket, path). */
    fun forPaths(globs: List<String>): List<TicketFile> = globs.map(::sqlGlob).distinct().flatMap { glob ->
        q.filesForPathGlob(glob).executeAsList().map {
            TicketFile(it.ticket_id, it.path, it.status, it.old_path, it.added, it.removed, it.tip_sha, it.merge_sha, it.recorded_at)
        }
    }.distinctBy { it.ticketId to it.path }

    /** Tickets that share changed files with [ticketId], best Jaccard overlap first (ties: more shared paths). */
    fun related(ticketId: String): List<RelatedTicket> {
        val own = forTicket(ticketId).size
        if (own == 0) return emptyList()
        return q.relatedTickets(ticketId).executeAsList().map {
            val shared = it.shared.toInt()
            val total = it.total.toInt()
            RelatedTicket(it.ticket_id, shared, total, shared.toDouble() / (own + total - shared).coerceAtLeast(1))
        }.sortedWith(compareByDescending<RelatedTicket> { it.jaccard }.thenByDescending { it.shared }.thenBy { it.ticketId })
    }

    /** The files of the top [top] tickets related to [ticketId], each with the related tickets that changed it. */
    fun filesForRelated(ticketId: String, top: Int): Pair<List<RelatedTicket>, Map<String, List<TicketFile>>> {
        val chosen = related(ticketId).take(top)
        val byPath = chosen.flatMap { forTicket(it.ticketId) }.groupBy { it.path }
        return chosen to byPath
    }

    fun recordedTicketIds(): Set<String> = q.recordedTickets().executeAsList().toSet()

    /** DAEMON-CONTEXT-1: how many recorded tickets changed [path]. */
    fun pathFrequency(path: String): Int = q.pathFrequency(path).executeAsOne().toInt()

    /**
     * DAEMON-CONTEXT-1: the files of a ticket that may still be running: its recorded rows, else (no recording before
     * done) the diff of its branch against its base, live from git. Empty when it has neither.
     */
    fun liveFiles(ticket: TicketInfo): List<TicketFile> {
        forTicket(ticket.id).takeIf { it.isNotEmpty() }?.let { return it }
        val repo = repoFor(ticket.projectId)?.takeIf { it.isDirectory } ?: return emptyList()
        val tip = ticket.branch?.let { revParse(repo, it) } ?: return emptyList()
        val base = ticket.base?.let { revParse(repo, it) } ?: return emptyList()
        return diff(repo, base, tip).map { it.copy(ticketId = ticket.id) }
    }

    /** DAEMON-CONTEXT-1: stores the 'Files read' line of [ticketId]'s done report. */
    fun saveFilesRead(ticketId: String, line: String) = q.saveFilesRead(ticketId, line, clock())

    fun filesRead(ticketId: String): String? = q.filesReadFor(ticketId).executeAsOneOrNull()

    // ---- write side ------------------------------------------------------------------------------------------------

    /** Records [ticket]'s files from git (see the class note), replacing its rows. Never throws on a git failure. */
    fun record(ticket: TicketInfo, history: History? = null): Recorded {
        val repo = repoFor(ticket.projectId)?.takeIf { it.isDirectory } ?: return Recorded(ticket.id, null, 0)
        val log = history ?: History.read(git, repo)
        val short = shortId(ticket.id)
        val now = clock()

        val branchTip = ticket.branch?.let { revParse(repo, it) } ?: revParse(repo, refName(ticket.id))
        val base = ticket.base?.let { revParse(repo, it) }
        // A merge is exactly what the ticket brought in; a branch diff also carries any base merged into the branch.
        log.merges[short]?.takeIf { it.isNotEmpty() }?.let { merges ->
            val rows = union(merges.map { m -> diff(repo, "$m^1", m).map { it.copy(mergeSha = m) } })
                .map { it.copy(ticketId = ticket.id, tipSha = branchTip, recordedAt = now) }
            return store(ticket.id, repo, branchTip ?: merges.last(), rows, FileSource.MERGE)
        }
        if (branchTip != null && base != null) {
            val rows = diff(repo, base, branchTip).map { it.copy(ticketId = ticket.id, tipSha = branchTip, recordedAt = now) }
            return store(ticket.id, repo, branchTip, rows, FileSource.BRANCH)
        }
        log.commits[short]?.takeIf { it.isNotEmpty() }?.let { commits ->
            val rows = union(commits.map { c -> diff(repo, "$c^", c) })
                .map { it.copy(ticketId = ticket.id, tipSha = branchTip ?: commits.last(), recordedAt = now) }
            return store(ticket.id, repo, branchTip ?: commits.last(), rows, FileSource.COMMITS)
        }
        return Recorded(ticket.id, null, 0)
    }

    /**
     * The one-time rebuild: every done ticket with no rows yet is [record]ed, the git history read once per repository.
     * [all] re-records the ones that already have rows too.
     */
    fun backfill(all: Boolean = false): BackfillReport {
        val done = tickets().filter { it.status == TicketStatus.DONE }
        val have = if (all) emptySet() else recordedTicketIds()
        val todo = done.filter { it.id !in have }
        val histories = HashMap<String, History>()
        val recovered = HashMap<FileSource, Int>()
        val missed = ArrayList<String>()
        for (ticket in todo) {
            val repo = repoFor(ticket.projectId)?.takeIf { it.isDirectory }
            if (repo == null) { missed += ticket.id; continue }
            val history = histories.getOrPut(repo.absolutePath) { History.read(git, repo) }
            val result = runCatching { record(ticket, history) }.getOrNull()
            val source = result?.source
            if (source == null) missed += ticket.id else recovered.merge(source, 1, Int::plus)
        }
        return BackfillReport(done.size, done.size - todo.size, recovered, missed)
    }

    private fun store(ticketId: String, repo: File, keepAlive: String, rows: List<TicketFile>, source: FileSource): Recorded {
        // Keeps the tip reachable after its branch is deleted; a failure only costs reproducibility, never the rows.
        git.run(repo, listOf("update-ref", refName(ticketId), keepAlive))
        q.transaction {
            q.deleteForTicket(ticketId)
            for (r in rows) q.insertFile(r.ticketId, r.path, r.status, r.oldPath, r.added, r.removed, r.tipSha, r.mergeSha, r.recordedAt)
        }
        return Recorded(ticketId, source, rows.size)
    }

    private fun revParse(repo: File, rev: String): String? =
        git.run(repo, listOf("rev-parse", "--verify", "--quiet", "$rev^{commit}")).takeIf { it.ok }?.out()?.takeIf { it.isNotBlank() }

    private fun diff(repo: File, from: String, to: String): List<TicketFile> {
        val result = git.run(repo, listOf("-c", "core.quotepath=off", "diff", "-z", "--raw", "--numstat", "-M", from, to))
        return if (result.ok) parseDiff(result.stdout) else emptyList()
    }

    /** The git history [record] matches ticket ids in: merges and plain commits by short id, oldest first. */
    class History(val merges: Map<String, List<String>>, val commits: Map<String, List<String>>) {
        companion object {
            private val SHORT = Regex("ticket/([0-9a-f]{8})")
            private val NAMED = Regex("\\b([0-9a-f]{8})\\b")

            fun read(git: GitCli, repo: File): History {
                val merges = HashMap<String, MutableList<String>>()
                val commits = HashMap<String, MutableList<String>>()
                val out = git.run(repo, listOf("log", "--all", "--reverse", "--format=%H%x1f%P%x1f%s")).takeIf { it.ok }?.stdout.orEmpty()
                for (line in out.lineSequence()) {
                    val parts = line.split('\u001f')
                    if (parts.size < 3) continue
                    val (sha, parents, subject) = parts
                    if (parents.trim().contains(' ')) {
                        // `Merge branch 'feature' into ticket/<id>` merges the other way: only what precedes " into " counts.
                        if (!subject.startsWith("Merge", ignoreCase = true)) continue
                        val head = subject.substringBefore(" into ")
                        SHORT.findAll(head).map { it.groupValues[1] }.toSet().forEach { merges.getOrPut(it, ::ArrayList) += sha }
                    } else {
                        NAMED.findAll(subject).map { it.groupValues[1] }.filter { it.any(Char::isLetter) && it.any(Char::isDigit) }
                            .toSet().forEach { commits.getOrPut(it, ::ArrayList) += sha }
                    }
                }
                return History(merges, commits)
            }
        }
    }

    companion object {
        const val REF_PREFIX = "refs/agenthq/tickets/"

        fun refName(ticketId: String) = REF_PREFIX + ticketId

        /** `ticket-55476f3c` -> `55476f3c` (the id branches and merge subjects carry). */
        fun shortId(ticketId: String) = ticketId.removePrefix("ticket-")

        /**
         * A user glob as an SQLite GLOB: `**` is `*` (GLOB's `*` already crosses `/`); a pattern with no wildcard
         * matches any path that contains it (`TicketBoard.kt` finds `src/.../TicketBoard.kt`).
         */
        fun sqlGlob(glob: String): String {
            val g = glob.trim().replace('\\', '/').replace("**", "*")
            return if (g.none { it == '*' || it == '?' || it == '[' }) "*$g*" else g
        }

        /** Parses `git diff -z --raw --numstat -M`: every raw record first, then every numstat record. */
        fun parseDiff(out: String): List<TicketFile> {
            val tokens = out.split('\u0000')
            val raw = LinkedHashMap<String, TicketFile>()
            val counts = HashMap<String, Pair<Long?, Long?>>()
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i].trimStart('\n')
                if (t.isEmpty()) { i++; continue }
                if (t.startsWith(":")) {
                    val letter = t.substringAfterLast(' ').take(1)
                    if (letter == "R" || letter == "C") {
                        val old = tokens.getOrNull(i + 1).orEmpty()
                        val new = tokens.getOrNull(i + 2).orEmpty()
                        raw[new] = TicketFile("", new, letter, oldPath = old)
                        i += 3
                    } else {
                        val path = tokens.getOrNull(i + 1).orEmpty()
                        raw[path] = TicketFile("", path, letter)
                        i += 2
                    }
                } else {
                    val fields = t.split('\t')
                    if (fields.size < 3) { i++; continue }
                    val added = fields[0].toLongOrNull()
                    val removed = fields[1].toLongOrNull()
                    if (fields[2].isEmpty()) {
                        counts[tokens.getOrNull(i + 2).orEmpty()] = added to removed
                        i += 3
                    } else {
                        counts[fields[2]] = added to removed
                        i += 1
                    }
                }
            }
            return raw.values.filter { it.path.isNotEmpty() }.map { f ->
                val (a, r) = counts[f.path] ?: (null to null)
                f.copy(added = a, removed = r)
            }
        }

        /** Several diffs of one ticket as one: a path keeps its latest status and its largest counts. */
        fun union(diffs: List<List<TicketFile>>): List<TicketFile> {
            val byPath = LinkedHashMap<String, TicketFile>()
            for (f in diffs.flatten()) {
                val prior = byPath[f.path]
                byPath[f.path] = if (prior == null) f else f.copy(
                    added = maxOf(prior.added ?: 0, f.added ?: 0).takeIf { prior.added != null || f.added != null },
                    removed = maxOf(prior.removed ?: 0, f.removed ?: 0).takeIf { prior.removed != null || f.removed != null },
                )
            }
            return byPath.values.sortedBy { it.path }
        }
    }
}
