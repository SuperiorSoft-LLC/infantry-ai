// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.owner

import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.COMMITTED_DAEMON_MAIN
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.COMMITTED_GITIGNORE
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.COMMITTED_README
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.DAEMON_MAIN
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.PIN_MODEL
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.ROLE_MODEL
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.ROUNDTRIP_MODEL
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.ROUNDTRIP_PROVIDER
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.STUB_A
import com.superiorsoftllc.infantry.ui.owner.OwnerRig.Companion.STUB_B
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * E2E-OWNER-1: what the owner does by hand, automated, so a green run means it works for HIM. Every scenario boots its
 * own [OwnerRig] (the owner's dirty main checkout + linked preview worktree, the real daemon JVM started like
 * `runRealDaemon`, the owner's window, a PM session) and acts only through the agents' real entrance: the INSTALLED
 * `infantry mcp serve` with the PM session's env ([OwnerRig.pm]) or the installed CLI ([OwnerRig.cli]). It asserts what
 * the owner would see: the answer an agent reads, the card in the window, what the worker was really asked to run
 * (the stub's recording, never the ticket row), and where it really runs (git, never the daemon's word).
 *
 * Run alone with `./gradlew :superiorSoftLibrary:infantryUi:ownerSmoke` (the task builds the install first). It is not
 * part of `test`.
 *
 * ADDING A SCENARIO (BASE-1a and later): one more @Test here, `OwnerRig.use("<name>") { rig -> ... }`. Create through
 * [createPinned] (or [OwnerRig.pm] directly), snapshot `rig.worktrees()` before a start, and check every start with
 * [assertWorkerStarted]. Never reach into the daemon directly: if the scenario needs a hook, it is a gap to report.
 */
class OwnerSmokeTest {

    @Test
    fun `S1 - MCP create pins role, provider and model, and an update answers the saved role`() = OwnerRig.use("s1") { rig ->
        val pm = rig.pm()
        val created = pm.call(CREATE, buildJsonObject {
            put("title", "Owner smoke S1")
            put("description", "Created by the owner-path smoke.")
            put("role", "backend")
            put("provider", STUB_A)
            put("model", PIN_MODEL)
        })
        assertFalse(created.isError, "infantry_ticket_create refused: ${created.text}")
        val id = created.string("ticketId") ?: fail("the create answer names no ticketId: ${created.text}")
        assertEquals("backend", created.string("role"), "the create answer shows the saved role: ${created.text}")
        assertEquals(STUB_A, created.string("provider"), "the create answer shows the saved provider: ${created.text}")
        assertEquals(PIN_MODEL, created.string("model"), "the create answer shows the saved model: ${created.text}")

        val updated = pm.call(UPDATE, buildJsonObject {
            put("ticketId", id)
            put("role", "fullstack")
        })
        assertFalse(updated.isError, "infantry_ticket_update refused: ${updated.text}")
        assertEquals("fullstack", updated.string("role"), "the update answer shows the saved role: ${updated.text}")
        assertTrue("role fullstack" in updated.string("message").orEmpty(), "the update says what it saved: ${updated.text}")

        val got = pm.call(GET, buildJsonObject { put("ticketId", id) })
        assertFalse(got.isError, "infantry_ticket_get refused: ${got.text}")
        assertEquals("fullstack", got.string("role"), "ticket_get shows the saved role: ${got.text}")
        assertEquals(STUB_A, got.string("provider"), "the provider pin survives a role change: ${got.text}")
        assertEquals(PIN_MODEL, got.string("model"), "the model pin survives a role change: ${got.text}")

        val card = rig.board(id, "as role:fullstack pinned to $STUB_A/$PIN_MODEL") {
            it.agentId == "role:fullstack" && it.provider == STUB_A && it.model == PIN_MODEL
        }
        assertEquals("todo", card.status, "nothing runs before a start")
    }

    @Test
    fun `S2 - MCP ticket_start runs the pinned harness on a new ticket worktree, started by the PM`() = OwnerRig.use("s2") { rig ->
        val id = createPinned(rig, "Owner smoke S2")
        val before = rig.worktrees()

        val started = rig.pm().call(START, buildJsonObject { put("ticketId", id) })
        assertFalse(started.isError, "infantry_ticket_start refused, the owner's failure: ${started.text}")
        assertEquals("started", started.string("outcome"), "the start answer: ${started.text}")
        assertEquals(rig.lead.sessionId, started.string("startedBy"), "startedBy is the PM session: ${started.text}")
        val note = started.string("note") ?: fail("the start answer names no 'cut from' note: ${started.text}")

        val worker = assertWorkerStarted(rig, id, note, before, STUB_A, PIN_MODEL, role = "fullstack")
        assertEquals(worker.card.sessionId, started.string("sessionId"), "the answer names the worker the board shows")
        assertEquals(worker.card.branch, started.string("branch"), "the answer names the branch the board shows")
    }

    @Test
    fun `S3 - a dirty main checkout still starts, and the owner's dirt is left alone`() = OwnerRig.use("s3") { rig ->
        // Dirtier than the owner: a STAGED edit, an untracked file, and a dirty preview worktree on top of the tracked edits.
        File(rig.repo, "README.md").appendText("a staged edit of the owner\n")
        rig.git(rig.repo, "add", "README.md")
        File(rig.repo, "scratch.txt").writeText("untracked notes of the owner\n")
        File(rig.preview, "FEATURE.md").appendText("an uncommitted preview edit\n")
        val dirtBefore = dirt(rig)
        assertTrue(dirtBefore.getValue("main status").lines().size >= 4, "the main checkout is dirty: $dirtBefore")

        val id = createPinned(rig, "Owner smoke S3")
        val before = rig.worktrees()
        val started = rig.pm().call(START, buildJsonObject { put("ticketId", id) })
        assertFalse(started.isError, "a dirty main checkout must not refuse a start (BUG-REGRESS-1 option A): ${started.text}")
        val note = started.string("note") ?: fail("the start answer names no 'cut from' note: ${started.text}")

        val worker = assertWorkerStarted(rig, id, note, before, STUB_A, PIN_MODEL, role = "fullstack")
        assertEquals(dirtBefore, dirt(rig), "the owner's uncommitted work is exactly as it was")
        assertEquals("", rig.git(worker.cwd, "status", "--porcelain", "--untracked-files=all"), "the worker's worktree is clean")
        assertEquals(COMMITTED_README, text(File(worker.cwd, "README.md")), "the worker has the COMMITTED README, not the staged one")
        assertEquals(COMMITTED_DAEMON_MAIN, text(File(worker.cwd, DAEMON_MAIN)), "the worker has the committed DaemonMain.kt")
        assertEquals(COMMITTED_GITIGNORE, text(File(worker.cwd, ".gitignore")), "the worker has the committed .gitignore")
        assertFalse(File(worker.cwd, "scratch.txt").exists(), "the owner's untracked file did not follow the worker")
    }

    @Test
    fun `S4 - the board path (CLI ticket move in_progress) starts it like S2`() = OwnerRig.use("s4") { rig ->
        val id = createPinned(rig, "Owner smoke S4")
        val before = rig.worktrees()

        val moved = rig.cli("ticket", "move", id, "in_progress")
        assertEquals(0, moved.exit, "the board path refused the start: $moved")
        assertEquals("moved $id -> in_progress", moved.out.lines().first().trim(), "the CLI answer: $moved")
        val note = moved.out.lines().map { it.trim() }.firstOrNull { it.startsWith("note:") }?.removePrefix("note:")?.trim()
            ?: fail("the move printed no 'cut from' note: $moved")

        assertWorkerStarted(rig, id, note, before, STUB_A, PIN_MODEL, role = "fullstack")
        assertEquals(rig.lead.sessionId, rig.startedByInDb(id), "the board path records the PM as the starter too")
    }

    @Test
    fun `S5 - the agent side runs the installed infantry, and a stale install is caught`() = OwnerRig.use("s5") { rig ->
        // The daemon gave its sessions the INSTALLED launcher (InfantryBin), first on PATH: what an agent's shell runs.
        assertEquals(Install.bin, File(rig.lead.env.getValue("INFANTRY_BIN")).canonicalFile, "the session's INFANTRY_BIN: ${rig.lead}")
        val firstOnPath = rig.lead.env.getValue("PATH").split(File.pathSeparator).first()
        assertEquals(Install.bin, File(firstOnPath).canonicalFile, "the installed bin is first on the session PATH")
        val which = rig.whichInfantry()
        assertTrue(which.isNotEmpty() && File(which.first()).canonicalFile.parentFile == Install.bin, "an agent's shell runs the installed infantry: $which")

        // It is today's build: the launcher's jar is the daemon's own, and its MCP server has the ticket tools.
        assertEquals(emptyList(), Install.staleness(Install.home), "the install the agents run is the daemon's build")
        val tools = rig.pm().toolNames()
        assertTrue(Install.REQUIRED_TOOLS.all { it in tools }, "the installed MCP serves ${Install.REQUIRED_TOOLS}: $tools")

        // The guard fires on a stale install: an older, different jar, and a server without infantry_ticket_start.
        val daemonJar = Install.daemonJar()
        val stale = File(rig.root, "stale-install")
        File(Install.home, "bin").listFiles().orEmpty().forEach { it.copyTo(File(stale, "bin/${it.name}"), overwrite = true) }
        File(stale, "lib/${daemonJar.name}").apply {
            parentFile.mkdirs()
            writeBytes(daemonJar.readBytes() + "an older build".toByteArray())
            setLastModified(daemonJar.lastModified() - 86_400_000L)
        }
        val reasons = Install.staleness(stale)
        assertTrue(reasons.size == 1 && "differs from the daemon's" in reasons[0] && "is older" in reasons[0], "a stale jar is named: $reasons")
        val refused = assertFailsWith<AssertionError> { Install.requireFresh(stale) }
        assertTrue(refused.message.orEmpty().startsWith("STALE INSTALL"), "the stale install fails loudly: ${refused.message}")
        val lacking = assertFailsWith<AssertionError> { Install.requireTools(tools - START, "an older infantry mcp serve") }
        assertTrue(START in lacking.message.orEmpty() && lacking.message.orEmpty().startsWith("STALE INSTALL"), "${lacking.message}")
    }

    @Test
    fun `pm-roundtrip - the PM pins and starts a ticket, the worker mails back where it runs`() = OwnerRig.use("pm-roundtrip") { rig ->
        // 1. A PM session is connected: its own MCP, the installed one, with its session env.
        assertEquals("pm", rig.lead.env["INFANTRY_ROLE"], "the lead session runs role pm: ${rig.lead}")
        val pm = rig.pm()
        val binding = rig.window.state.value.roleBindings.getValue("backend")
        assertEquals(STUB_B to ROLE_MODEL, binding.provider to binding.model, "the role's default differs from the pin")

        // 2. The PM creates the ticket and pins its worker: harness stub-a, provider acme, model acme/m2.
        val created = pm.call(CREATE, buildJsonObject {
            put("title", "Owner smoke pm-roundtrip")
            put("description", "Check where you run, then report.\n${RecordingHarness.REPLY_LINE}")
            put("role", "backend")
            put("provider", STUB_A)
            put("model", ROUNDTRIP_MODEL)
        })
        assertFalse(created.isError, "infantry_ticket_create refused: ${created.text}")
        val id = created.string("ticketId") ?: fail("the create answer names no ticketId: ${created.text}")
        val before = rig.worktrees()

        // 3. The PM starts it through MCP.
        val started = pm.call(START, buildJsonObject { put("ticketId", id) })
        assertFalse(started.isError, "infantry_ticket_start refused: ${started.text}")
        assertEquals("in_progress", started.string("status"), "the start answer: ${started.text}")
        assertEquals(rig.lead.sessionId, started.string("startedBy"), "startedBy is the PM session: ${started.text}")
        val note = started.string("note") ?: fail("the start answer names no 'cut from' note: ${started.text}")
        val worker = assertWorkerStarted(rig, id, note, before, STUB_A, ROUNDTRIP_MODEL, role = "backend")
        assertEquals(ROUNDTRIP_PROVIDER, worker.run.modelProvider, "the worker ran the pinned provider: ${worker.run}")

        // 4-5. The worker checked its location through its own MCP and mailed the starter; the PM reads it through its own MCP (infantry_inbox, infantry_read).
        val workerSession = worker.card.sessionId!!
        val realBranch = worker.card.branch!!
        val realBase = rig.commitOfNote(note)
        val mail = awaitReply(rig, workerSession, id, worker.run)
        assertEquals(workerSession, mail.from, "the reply comes from that worker")
        assertEquals(rig.lead.sessionId, mail.to, "the reply is the PM's")
        val reply = Json.parseToJsonElement(mail.body) as? JsonObject ?: fail("the reply body is not the worker's report: ${mail.body}")
        assertEquals(id, reply.text("ticketId"), "the reply is about that ticket: ${mail.body}")
        assertEquals(realBranch, reply.text("branch"), "the worker reports the real ticket branch: ${mail.body}")
        assertEquals(realBase, reply.text("baseSha"), "the worker reports the base the start note names: ${mail.body}")
        assertEquals(worker.cwd, File(reply.text("cwd") ?: "").canonicalFile, "the worker reports the real ticket worktree: ${mail.body}")
        val checks = reply["checks"] as? JsonObject ?: fail("the reply carries no checks: ${mail.body}")
        assertTrue(checks.values.all { (it as? JsonPrimitive)?.booleanOrNull == true }, "the worker's own location checks: $checks")

        // The PM's console got it as its next turn too: the mail woke the PM session with the reply.
        val turn = rig.awaitRecording("the PM's turn carrying the reply") {
            it.sessionId == rig.lead.sessionId && it.file != rig.lead.file && mail.id in it.prompt
        }
        assertTrue(realBranch in turn.prompt && workerSession in turn.prompt, "the PM's turn shows the reply: ${turn.prompt}")
    }

    /**
     * SPAWN-KILL-E2E-1: the owner's "spawn and destroy agents", on the agent's real path. The PM's installed MCP spawns a
     * helper by ROLE with a task and NO ticket; the daemon starts it with that task as its first prompt and the env it
     * gives every session; the PM reads the helper's reply, kills it, and the slot comes back. Kills the PM did not
     * spawn are refused, and the spawn was charged to the PM's pod (the helper's own spawn hits the daemon's depth rule).
     */
    @Test
    fun `S6 - an agent spawns a helper with no ticket, reads its reply, kills it and gets the slot back`() = OwnerRig.use("s6") { rig ->
        val pm = rig.pm()
        val task = "owner-smoke S6: you are a helper with no ticket. ${UUID.randomUUID()}"

        // 1. The PM spawns a helper: a role and a task, no ticketId.
        val spawned = pm.call(SPAWN, buildJsonObject {
            put("role", "backend")
            put("task", task)
        })
        assertFalse(spawned.isError, "infantry_spawn refused: ${spawned.text}")
        val helper = spawned.string("sessionId") ?: fail("the spawn answer names no sessionId: ${spawned.text}")
        assertEquals("true", spawned.string("taskSent"), "the task was sent: ${spawned.text}")
        assertEquals("1", spawned.string("openSpawns"), "the helper holds the PM's first slot: ${spawned.text}")

        // 2. The helper runs the role's harness with the task as its FIRST prompt, in the env the daemon gives a session.
        val run = rig.awaitRecording("the helper's first run") { it.sessionId == helper }
        assertEquals(STUB_B, run.harness, "the helper runs role backend's harness: $run")
        assertEquals("", run["resume"], "the task is the helper's first turn, not a resumed one: $run")
        assertTrue(task in run.prompt, "the helper's first prompt carries the task: ${run.prompt}")
        rig.agentEnv(run) // the helper's env points at THIS daemon and names the installed infantry
        assertEquals(helper, run.env["INFANTRY_SESSION_ID"], "the daemon names the helper's own session in its env: $run")

        // The spawn was charged to the PM's pod: the helper (depth 1) may not spawn again, by the DAEMON's rule.
        val helperMcp = rig.openMcp(run, "helper")
        val nested = helperMcp.call(SPAWN, buildJsonObject {
            put("role", "backend")
            put("task", "owner-smoke S6: a grandchild")
        })
        assertTrue(nested.isError && "spawn refused: depth limit" in nested.text, "the daemon refuses the helper's spawn: ${nested.text}")
        // Kill scope (contract 1.114.0): a non-pm kills only what it spawned, so the helper may not kill the PM; and no
        // session may kill itself, not even a pm whose project scope covers its own session.
        val byHelper = helperMcp.call(KILL, buildJsonObject { put("sessionId", rig.lead.sessionId) })
        assertTrue(byHelper.isError && "was not spawned by this session" in byHelper.text, "the helper's kill: ${byHelper.text}")
        val byPm = pm.call(KILL, buildJsonObject { put("sessionId", rig.lead.sessionId) })
        assertTrue(byPm.isError && "may not kill its own session" in byPm.text, "the PM's kill of itself: ${byPm.text}")
        assertNotEquals("EXITED", session(pm, rig.lead.sessionId).string("status"), "a refused kill leaves the PM running")

        // 3. The helper replies through its own MCP's mail, and the PM reads it from its inbox; then the helper's turn ends.
        val replyBody = "owner-smoke S6 reply from $helper"
        val sent = helperMcp.call(MESSAGE, buildJsonObject {
            put("to", rig.lead.sessionId)
            put("subject", "owner-smoke S6 reply")
            put("body", replyBody)
        })
        assertFalse(sent.isError, "the helper's infantry_message refused: ${sent.text}")
        val mail = awaitReply(rig, helper, "the S6 helper's reply", run)
        assertEquals(rig.lead.sessionId, mail.to, "the reply is the PM's: ${mail.from} -> ${mail.to}")
        assertEquals(replyBody, mail.body, "the PM reads what the helper wrote: ${mail.body}")
        File(rig.records, "$helper.release").writeText("done")
        awaitSession(pm, helper, "IDLE")

        // 4. The PM kills it, and its slot is free again.
        val killed = pm.call(KILL, buildJsonObject { put("sessionId", helper) })
        assertFalse(killed.isError, "infantry_kill refused: ${killed.text}")
        assertEquals(helper, killed.string("sessionId"), "the kill answers for the helper: ${killed.text}")
        assertEquals("EXITED", killed.string("status"), "killing an IDLE helper ends it (BUG-KILL-IDLE-1): ${killed.text}")

        val again = pm.call(SPAWN, buildJsonObject {
            put("role", "backend")
            put("task", "owner-smoke S6: the second helper")
        })
        assertFalse(again.isError, "infantry_spawn after a kill refused: ${again.text}")
        assertEquals("1", again.string("openSpawns"), "the killed helper's slot was released: ${again.text}")
    }

    // ---- shared steps and checks --------------------------------------------------------------------------------------

    private fun session(mcp: McpStdio, id: String): McpStdio.ToolAnswer =
        mcp.call(GET_SESSION, buildJsonObject { put("sessionId", id) }).also {
            assertFalse(it.isError, "infantry_get_session $id refused: ${it.text}")
        }

    /** Polls `infantry_get_session` until [id] is in [status]; the last answer is in the failure. */
    private fun awaitSession(mcp: McpStdio, id: String, status: String, timeoutMs: Long = SESSION_WAIT_MS): McpStdio.ToolAnswer {
        val end = System.currentTimeMillis() + timeoutMs
        var last = session(mcp, id)
        while (last.string("status") != status && System.currentTimeMillis() < end) {
            Thread.sleep(250)
            last = session(mcp, id)
        }
        if (last.string("status") != status) fail("session $id never reached $status within ${timeoutMs}ms: ${last.text}")
        return last
    }

    /** S1's steps for a scenario that starts a ticket: created as backend on stub-a/m1 through MCP, then made fullstack. */
    private fun createPinned(rig: OwnerRig, title: String): String {
        val pm = rig.pm()
        val created = pm.call(CREATE, buildJsonObject {
            put("title", title)
            put("description", "Created by the owner-path smoke.")
            put("role", "backend")
            put("provider", STUB_A)
            put("model", PIN_MODEL)
        })
        assertFalse(created.isError, "infantry_ticket_create refused: ${created.text}")
        val id = created.string("ticketId") ?: fail("the create answer names no ticketId: ${created.text}")
        val updated = pm.call(UPDATE, buildJsonObject {
            put("ticketId", id)
            put("role", "fullstack")
        })
        assertFalse(updated.isError, "infantry_ticket_update refused: ${updated.text}")
        assertEquals("fullstack", updated.string("role"), "the update answer shows the saved role: ${updated.text}")
        return id
    }

    class Started(val card: Ticket, val run: OwnerRig.Recording, val cwd: File)

    /**
     * Everything a started ticket must show the owner: In Progress in his window with a worker; the worker REALLY ran
     * [harness]/[model] for [role] (the stub's recording); it runs in a NEW worktree of the main repo on
     * `ticket/<id>-<slug>`, whose HEAD is the commit the start note names; and its brief names the PM as its starter.
     */
    private fun assertWorkerStarted(
        rig: OwnerRig,
        ticketId: String,
        note: String,
        worktreesBefore: List<OwnerRig.Worktree>,
        harness: String,
        model: String,
        role: String,
    ): Started {
        val card = rig.board(ticketId, "in_progress with a worker, a branch and a base") {
            it.status == "in_progress" && it.sessionId != null && it.branch != null && it.base != null
        }
        val session = card.sessionId!!
        val branch = card.branch!!

        val run = rig.awaitRecording("the worker run of ticket $ticketId (session $session)") { it.sessionId == session }
        assertEquals(harness, run.harness, "the worker ran the pinned harness: $run")
        assertEquals(model, run.model, "the worker ran the pinned model: $run")
        assertEquals(role, run.env["INFANTRY_ROLE"], "the worker runs the ticket's role: $run")
        assertEquals(ticketId, run["ticketId"], "the worker's brief names its ticket: $run")

        val cwd = File(run.cwd).canonicalFile
        val main = rig.repo.canonicalFile
        assertTrue(cwd.path.startsWith(File(main, ".worktrees").path + File.separator), "the worker runs under the main repo's .worktrees: $cwd")
        assertNotEquals(main, cwd, "the worker does not run in the owner's main checkout")
        assertNotEquals(rig.preview.canonicalFile, cwd, "the worker does not run in the daemon's own worktree")
        assertTrue(worktreesBefore.none { it.path == cwd }, "the worker's worktree is NEW: $cwd was already listed")
        val listed = rig.worktrees().firstOrNull { it.path == cwd } ?: fail("$cwd is not a worktree of the main repo: ${rig.worktrees()}")
        assertEquals("refs/heads/$branch", listed.branch, "the worktree is on the board's branch")
        assertTrue(BRANCH.matches(branch), "the branch is ticket/<id>-<slug>: $branch")
        assertTrue(
            ticketId.removePrefix("ticket-").lowercase().filter { it.isLetterOrDigit() }.startsWith(branch.removePrefix("ticket/").substringBefore('-')),
            "the branch carries the ticket's id: $branch for $ticketId",
        )
        assertEquals(branch, run["git.branch"], "the worker's checkout is on the ticket branch")

        val base = rig.commitOfNote(note)
        assertEquals(base, run["git.head"], "the worker's HEAD is the commit the start note names ($note)")
        assertEquals(base, card.base, "the board's base is that commit")
        // BUG-BASE-087: cut from the project's base branch (its current tip), labelled, never the main checkout's HEAD.
        assertEquals("cut from ${OwnerRig.FEATURE} ${base.take(8)} (project base)", note.trim(), "the note names the project base")
        assertEquals(rig.git(rig.repo, "rev-parse", OwnerRig.FEATURE).trim(), base, "the base is ${OwnerRig.FEATURE}'s tip")

        assertTrue("(${rig.lead.sessionId}), who started this ticket" in run.prompt, "the worker's brief names the PM as its starter")
        return Started(card, run, cwd)
    }

    class Mail(val id: String, val from: String, val to: String, val subject: String, val body: String)

    /**
     * The PM reads its inbox through its OWN MCP (MAIL-MCP-1): `infantry_inbox` until the worker's mail is listed, then
     * `infantry_read` for its body, on the installed `infantry mcp serve` with the PM's session env.
     */
    private fun awaitReply(rig: OwnerRig, worker: String, ticketId: String, run: OwnerRig.Recording): Mail {
        val pm = rig.pm()
        val end = System.currentTimeMillis() + REPLY_WAIT_MS
        var last = "nothing yet"
        while (System.currentTimeMillis() < end) {
            val inbox = pm.call(INBOX, buildJsonObject { put("unreadOnly", true) })
            last = "$INBOX -> ${inbox.text}"
            assertFalse(inbox.isError, "infantry_inbox refused: ${inbox.text}")
            val row = (inbox.json()["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { it.text("from") == worker }
            if (row != null) {
                val read = pm.call(READ, buildJsonObject { put("messageId", row.text("id")) })
                last = "$READ -> ${read.text}"
                if (!read.isError) {
                    val mail = read.json()
                    assertEquals("true", mail.text("read"), "infantry_read marks the reply read: ${read.text}")
                    val unread = pm.call(INBOX, buildJsonObject { put("unreadOnly", true) }).json()["messages"] as? JsonArray
                    assertTrue(unread.orEmpty().none { (it as? JsonObject)?.text("id") == mail.text("id") }, "a read reply leaves the unread inbox: $unread")
                    return Mail(
                        mail.text("id").orEmpty(),
                        mail.text("from").orEmpty(),
                        mail.text("to").orEmpty(),
                        mail.text("subject").orEmpty(),
                        mail.text("body").orEmpty(),
                    )
                }
            }
            Thread.sleep(1_000)
        }
        fail("the PM never read a reply from worker $worker about $ticketId: $last; the worker's own report: ${run.reply}")
    }

    /** Everything of the owner's that a start must leave alone: statuses, the index, and every dirty file's bytes. */
    private fun dirt(rig: OwnerRig): Map<String, String> = mapOf(
        "main status" to rig.git(rig.repo, "status", "--porcelain", "--untracked-files=all"),
        "main index" to rig.git(rig.repo, "diff", "--cached", "--name-only"),
        "preview status" to rig.git(rig.preview, "status", "--porcelain", "--untracked-files=all"),
        ".gitignore" to File(rig.repo, ".gitignore").readText(),
        "DaemonMain.kt" to File(rig.repo, DAEMON_MAIN).readText(),
        "README.md" to File(rig.repo, "README.md").readText(),
        "scratch.txt" to File(rig.repo, "scratch.txt").readText(),
        "FEATURE.md" to File(rig.preview, "FEATURE.md").readText(),
    )

    /** A checked-out file's text with Windows line endings folded (git's autocrlf may have written CRLF). */
    private fun text(file: File): String = file.readText().replace("\r\n", "\n")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val CREATE = "infantry_ticket_create"
        const val UPDATE = "infantry_ticket_update"
        const val GET = "infantry_ticket_get"
        const val START = "infantry_ticket_start"
        const val INBOX = "infantry_inbox"
        const val READ = "infantry_read"
        const val SPAWN = "infantry_spawn"
        const val MESSAGE = "infantry_message"
        const val KILL = "infantry_kill"
        const val GET_SESSION = "infantry_get_session"
        const val SESSION_WAIT_MS = 60_000L
        const val REPLY_WAIT_MS = 90_000L
        val BRANCH = Regex("^ticket/[a-z0-9]{1,8}-[a-z0-9-]+$")
    }
}
