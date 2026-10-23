package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.worktree.newGitRepo
import com.superiorsoftllc.agenthq.worktree.testGit
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-REGRESS-1: the boot line says where ticket starts cut their worktrees. With no AGENTHQ_REPO it used to say
 * "sessions share the checkout", which has been false for ticket starts since BUG-WT-1 (a start cuts its own
 * `ticket/` branch in the main checkout of the git repository the daemon runs in) and sent the owner looking in the
 * wrong place.
 */
class WorktreeBootLineTest {

    private val cleanups = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = cleanups.forEach { it.deleteRecursively() }

    @Test
    fun aConfiguredRepo_isNamed() {
        val repo = newGitRepo("agenthq-bootline").also { cleanups += it }

        val line = worktreeBootLine(repo, repo, "HEAD")

        assertTrue(line.contains("repo=${repo.absolutePath}"), line)
        assertTrue(line.contains("own worktree"), line)
    }

    @Test
    fun noRepo_insideAGitCheckout_namesTheCheckoutTicketStartsCutFromAndTheBaseRef() {
        val repo = newGitRepo("agenthq-bootline").also { cleanups += it }
        val nested = File(repo, "superiorSoftLibrary/agentHq").apply { mkdirs() }

        val line = worktreeBootLine(null, nested, "release")

        assertTrue(line.contains("repo=<unset>"), line)
        assertTrue(line.contains("ticket starts cut their own ticket/ branch from release of the main checkout"), line)
        assertTrue(line.contains("checkout ${repo.absoluteFile.absolutePath},"), "names the checkout's root, not the nested dir: $line")
        assertFalse(line.contains("share the checkout"), "ticket starts do not share the checkout: $line")
    }

    /**
     * The owner's layout: the daemon runs in a LINKED worktree (mvp). A ticket start resolves the base and puts its
     * worktree in the repository's MAIN checkout ([com.superiorsoftllc.agenthq.worktree.TicketBranches]), so the
     * line names that checkout, never the linked one.
     */
    @Test
    fun noRepo_insideALinkedWorktree_namesTheMainCheckoutTicketStartsCutIn() {
        val repo = newGitRepo("agenthq-bootline").also { cleanups += it }
        val linked = File(repo.parentFile, repo.name + "-mvp").also { cleanups += it }
        assertTrue(testGit().run(repo, listOf("worktree", "add", "-q", "-b", "mvp", linked.absolutePath)).ok, "a linked worktree")
        val module = File(linked, "module").apply { mkdirs() }

        val line = worktreeBootLine(null, module, "HEAD")

        assertTrue(line.contains("from HEAD of the main checkout ${repo.absolutePath}, in its .worktrees/"), line)
        assertFalse(line.contains("checkout ${linked.absolutePath}"), "not the linked worktree: $line")
        assertTrue(line.contains("other sessions run in ${module.absolutePath}"), line)
    }

    @Test
    fun noRepo_outsideAnyGitCheckout_saysSo() {
        val plain = Files.createTempDirectory("agenthq-bootline-plain").toFile().also { cleanups += it }

        val line = worktreeBootLine(null, plain, "HEAD")

        assertTrue(line.contains("not a git checkout"), line)
        assertTrue(line.contains(plain.absolutePath), line)
    }
}
