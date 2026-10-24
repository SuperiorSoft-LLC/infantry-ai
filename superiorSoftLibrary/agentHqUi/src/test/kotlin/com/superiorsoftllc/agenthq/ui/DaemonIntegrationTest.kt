package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import com.superiorsoftllc.agenthq.ui.protocol.NodeSpec
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.queuedFor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The UI's brain against the REAL daemon (real Ktor server, real WebSocket, real sessions, SQLite)
 * running the STUB provider. Nothing here knows or needs a provider CLI, which is the constraint.
 */
class DaemonIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()
    /** The daemon the last [boot] started, for tests that drive it in-process (attention). */
    private var daemon: AgentHqDaemon? = null

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun boot(
        token: String? = null,
        host: String = "127.0.0.1",
        script: StubScript = StubScript.default(),
        delayMs: Long = 0,
        /** The stub's own scripted transport unless a test needs another, e.g. [HeldTurnRunner]. */
        runner: ProcessRunner? = null,
    ): Int {
        val dir = Files.createTempDirectory("agenthq-ui-test").toFile()
        val provider = StubProvider(script, delayMs = delayMs)
        val daemon = AgentHqDaemon.build(
            port = 0, host = host, provider = provider, runner = runner ?: provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = token,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
            // BUG-GATE19-1: a plain temp dir, not build()'s default user.dir (this git worktree): BUG-WT-1's handOut cuts a
            // worktree from a git project folder and refuses useWorktree=false over one, so the ticket never started.
            workingDir = File(dir, "workdir").apply { mkdirs() },
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
        )
        this.daemon = daemon
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    private fun until(what: String, c: Controller, timeoutMs: Long = 20_000, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        val s = c.state.value
        error("not met: $what; conn=${s.connection} agents=${s.agents.values.map { it.session.state to it.output.map { l -> l.kind } }} notices=${s.notices}")
    }

    @Test fun `spawn streams the scripted session, then resume continues THAT session`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.state == ConnState.CONNECTED }
        assertEquals("1", c.state.value.connection.contract!!.substringBefore('.'))

        assertIs<CommandResult.Sent>(runBlocking { c.spawn("say hello") })
        val s1 = until("first run finished", c) { s -> s.agents.values.any { a -> a.output.any { it.kind == LineKind.FINISHED } } }
        val agent = s1.agents.values.single()
        assertTrue(agent.output.any { it.kind == LineKind.TEXT && it.text.contains("[stub]") }, "text deltas rendered")
        assertEquals("stub", agent.session.provider)
        // the session record may lag the finished frame by a beat; wait until it is resumable
        until("resumable", c) { it.agents[agent.id]?.session?.resumable == true }

        assertIs<CommandResult.Sent>(runBlocking { c.resume(agent.id, "again") })
        val s2 = until("second run finished", c) { s -> s.agents[agent.id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }
        assertEquals(1, s2.agents.size, "resume must not fork a new agent")
        assertEquals(2, s2.agents.getValue(agent.id).session.runCount)
        assertTrue(s2.agents.getValue(agent.id).output.any { it.kind == LineKind.USER && it.text == "again" })
    }

    @Test fun `a pod submitted from the UI shows up with node states and converges`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking {
            c.submitPod("two steps", listOf(NodeSpec("a", "do a"), NodeSpec("b", "do b", setOf("a"))))
        })
        // submit answers once; later state needs a query, as the Pods tab's Load button does
        val id = until("pod known", c) { it.pods.isNotEmpty() }.pods.keys.single()
        until("pod converged", c) {
            runBlocking { c.queryPod(id) }
            it.pods.values.any { p -> p.pod.state == "CONVERGED" }
        }
        assertEquals(listOf("a", "b"), c.state.value.pods.values.single().nodes.sortedBy { it.specIndex }.map { it.name })
    }

    @Test fun `a wrong token is shown as refused and is not retried`() {
        val port = boot(token = "s3cret-token-value-0123456789-abcdefghij", host = "0.0.0.0")
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", "wrong")
        val s = until("refused", c) { it.connection.state == ConnState.REFUSED }
        assertTrue(s.connection.detail.contains("401") || s.connection.detail.contains("403"), s.connection.detail)
        Thread.sleep(1500)
        assertEquals(ConnState.REFUSED, c.state.value.connection.state, "no retry loop on a refused token")

        c.connect("ws://127.0.0.1:$port/ws", "s3cret-token-value-0123456789-abcdefghij")
        until("connected with the right token", c) { it.connection.state == ConnState.CONNECTED }
    }

    @Test fun `commands on a dead link are refused loudly and never reported as sent`() {
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:1/ws", null)
        val s = until("reconnecting", c) { it.connection.state == ConnState.RECONNECTING }
        assertTrue(s.connection.detail.isNotBlank(), "a failure always says why")
        assertIs<CommandResult.Refused>(runBlocking { c.spawn("hi") })
        assertTrue(c.state.value.notices.any { it.text.contains("nothing was sent") })
    }

    @Test fun `the daemon dying flips a connected client to reconnecting instead of staying green`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        closers.removeAt(closers.lastIndex).invoke() // stop the daemon
        until("reconnecting after loss", c) { it.connection.state == ConnState.RECONNECTING }
    }

    // --- talking to an agent (contract 1.11.0) ---------------------------------------------------

    private fun transcript(s: AppState, id: String) = s.agents.getValue(id).output.map { it.kind to it.text }

    @Test fun `talking to an agent - the first message, the streamed reply and a follow-up stay in one session`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertTrue(c.state.value.connection.echoesTurns, "a 1.11.0+ daemon echoes turns: ${c.state.value.connection.contract}")

        assertIs<CommandResult.Sent>(runBlocking { c.spawn("hello agent") })
        val first = until("the spawned agent is on screen with its finished reply", c) { s ->
            s.selected?.output?.any { it.kind == LineKind.FINISHED } == true
        }
        val id = first.selectedId!!
        assertEquals(LineKind.USER to "hello agent", transcript(first, id).first(), "the first message is the daemon's own turn")
        assertTrue(transcript(first, id).any { it.first == LineKind.TEXT && it.second.contains("[stub]") }, "the reply streamed")

        until("ready for a follow-up", c) { it.agents[id]?.session?.resumable == true }
        assertIs<CommandResult.Sent>(runBlocking { c.prompt(id, "and a follow-up?") })
        val second = until("the follow-up answered", c) { s -> s.agents[id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }

        val lines = transcript(second, id)
        assertEquals(listOf("hello agent", "and a follow-up?"), lines.filter { it.first == LineKind.USER }.map { it.second }, "each turn once")
        // Contract 1.64.0 (BUG-M2-11b-1) supersedes UI-BOOT-1 / UI-LOG-1 / RESUME-1's per-turn lines: the session's ONE
        // launch shows ONE boot LOG (`* session started`) ahead of the first reply, a turn that reuses it shows none, and
        // no turn shows a process-exit notice (a one-shot turn's end is `finished`). Each run keeps its own stderr LOG.
        assertEquals(
            listOf(
                LineKind.USER, LineKind.LOG, LineKind.TEXT, LineKind.LOG, LineKind.FINISHED,
                LineKind.USER, LineKind.TEXT, LineKind.LOG, LineKind.FINISHED,
            ),
            lines.map { it.first }.filter { it != LineKind.STATUS },
        )
        assertTrue(lines.first { it.first == LineKind.LOG }.second.contains("* session started (stub"), "the one boot line: $lines")
        assertEquals(1, second.agents.size, "a follow-up never forks a new agent")
        val settled = until("the session at rest after two runs", c) { s ->
            s.agents.getValue(id).session.let { it.runCount == 2 && it.state == SessionState.IDLE }
        }
        assertEquals(1, settled.agents.getValue(id).session.resumeCount)
    }

    @Test fun `a follow-up while the agent is still answering is refused with the reason and nothing is sent`() {
        val port = boot(delayMs = 400)
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { c.spawn("take your time") })
        val busy = until("the agent answering", c) { s -> s.selected?.session?.state in setOf(SessionState.STARTING, SessionState.BUSY) }
        val id = busy.selectedId!!

        val r = runBlocking { c.prompt(id, "too early") }
        assertIs<CommandResult.Refused>(r)
        assertTrue(r.reason.contains("still answering"), r.reason)

        val done = until("the first turn finished", c) { s -> s.agents[id]!!.output.any { it.kind == LineKind.FINISHED } }
        assertTrue(transcript(done, id).none { it.second == "too early" }, "a refused message never reaches the transcript")
        assertEquals(1, until("at rest", c) { it.agents.getValue(id).session.state == SessionState.IDLE }.agents.getValue(id).session.runCount)
    }

    @Test fun `interrupt stops the busy turn and the session rests IDLE with the same id, conversation and an interrupted line`() {
        // Held (BUG-FLAKE-3): the turn cannot finish on its own, so only the interrupt can bring the session to IDLE.
        val port = boot(runner = HeldTurnRunner())
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { c.spawn("scan everything") })
        val busy = until("the agent answering", c) { s -> s.selected?.session?.state in setOf(SessionState.STARTING, SessionState.BUSY) }
        val id = busy.selectedId!!
        // The provider reports its session id a moment after the turn starts: wait for it, or the snapshot is null.
        val providerSession = until("the provider session id", c) { it.agents[id]?.session?.providerSessionId != null }.agents.getValue(id).session.providerSessionId

        assertIs<CommandResult.Sent>(runBlocking { c.interrupt(id) })
        val rest = until("at rest after the interrupt", c) { it.agents.getValue(id).session.state == SessionState.IDLE }

        assertEquals(id, rest.selectedId)
        assertEquals(providerSession, rest.agents.getValue(id).session.providerSessionId, "the conversation survives an interrupt")
        assertTrue(
            transcript(rest, id).any { (kind, text) -> kind == LineKind.LOG && text.contains("turn interrupted by the owner") },
            "the interrupted turn keeps its marker in the transcript: ${transcript(rest, id)}",
        )
    }

    /**
     * The button is WIRED: a real click on the tagged Interrupt control reaches the daemon and the busy turn stops.
     * The assertions are on the daemon's effect (the session settles IDLE with the same conversation, and the
     * interrupt-only marker line is in the transcript), not on the click. A no-op click leaves the agent busy and
     * fails the wait; a click on a control that was not enabled (the turn already ended) fails the enabled guard.
     *
     * BUG-FLAKE-3: the turn is HELD ([HeldTurnRunner]) until the click's interrupt ends it. The stub's scripted turn
     * ended on its own about 1.2 s after its session id, and under CPU load the Compose harness alone took 1.7-4.2 s
     * to compose the button, so the click came after the turn was over (10 of 10 runs failed under load).
     */
    @OptIn(ExperimentalTestApi::class)
    @Test fun `clicking the Interrupt button on a busy agent stops its turn on the real daemon`() {
        val port = boot(runner = HeldTurnRunner())
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { c.spawn("scan everything") })
        val busy = until("the agent answering", c) { s -> s.selected?.session?.state in setOf(SessionState.STARTING, SessionState.BUSY) }
        val id = busy.selectedId!!
        // The provider reports its session id a moment after the turn starts: wait for it, or the snapshot is null.
        val providerSession = until("the provider session id", c) { it.agents[id]?.session?.providerSessionId != null }.agents.getValue(id).session.providerSessionId

        runDesktopComposeUiTest(900, 300) {
            setContent {
                val state by c.state.collectAsState()
                val agent = state.agents.getValue(id)
                InterruptButton(agent, live = state.connection.isLive, controller = c)
            }
            onNodeWithTag(INTERRUPT_BUTTON_TAG).assertIsEnabled()
            onNodeWithTag(INTERRUPT_BUTTON_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { c.state.value.agents[id]?.session?.state == SessionState.IDLE }
        }

        val rest = c.state.value
        assertEquals(id, rest.selectedId)
        assertEquals(providerSession, rest.agents.getValue(id).session.providerSessionId, "the conversation survives the click")
        assertTrue(
            transcript(rest, id).any { (kind, text) -> kind == LineKind.LOG && text.contains("turn interrupted by the owner") },
            "the click reached the daemon: its interrupt marker is in the transcript: ${transcript(rest, id)}",
        )
    }

    @Test fun `a message queued while the agent is busy is not sent or echoed, and goes out as the next turn when the first ends`() {
        val port = boot(delayMs = 400)
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { c.spawn("take your time") })
        val busy = until("the agent answering", c) { s -> s.selected?.session?.state in setOf(SessionState.STARTING, SessionState.BUSY) }
        val id = busy.selectedId!!

        val queued = runBlocking { c.queueMessage(id, "and then this") }
        assertEquals(CommandResult.Queued(1), queued)
        val waiting = c.state.value
        assertEquals(listOf("and then this"), waiting.queuedFor(id).map { it.text })
        assertTrue(transcript(waiting, id).none { it.second == "and then this" }, "a queued message must not read as sent")

        val drained = until("queue sent as the next turn", c) { s ->
            s.queuedFor(id).isEmpty() && s.agents.getValue(id).session.runCount == 2 && s.agents.getValue(id).session.state == SessionState.IDLE
        }
        assertTrue(transcript(drained, id).any { it.second == "and then this" }, "the queued message is sent and then visible")
    }

    /**
     * ticket-b5aa01bc: mail held for the busy agent wakes it the moment its turn ends (MailRouter), so the queue's drain
     * reaches a daemon that already has the mail's run in flight and refuses it. The queued message must survive that
     * refusal: back at the front, still queued, and sent as the turn after the mail's.
     */
    @Test fun `a queued message the daemon refuses because mail woke the agent first is kept and sent on the next turn end`() {
        val port = boot(delayMs = 400)
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { c.spawn("take your time") })
        val busy = until("the agent answering", c) { s -> s.selected?.session?.state in setOf(SessionState.STARTING, SessionState.BUSY) }
        val id = busy.selectedId!!

        assertEquals(CommandResult.Queued(1), runBlocking { c.queueMessage(id, "and then this") })
        assertEquals(CommandResult.Queued(2), runBlocking { c.queueMessage(id, "and after that") })
        // Mail to the busy agent stays queued in the daemon's mailbox and is delivered the instant the turn ends.
        val mail = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/mail/send"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("""{"from":"owner","to":"$id","body":"mail while busy"}"""))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, mail.statusCode(), mail.body())

        val drained = until("the mail's turn, then both queued messages, in order", c, timeoutMs = 30_000) { s ->
            val a = s.agents.getValue(id)
            s.queuedFor(id).isEmpty() && a.session.runCount == 4 && a.session.state == SessionState.IDLE
        }
        // The refused message went back to the FRONT: it is still sent before the one queued after it.
        val userLines = transcript(drained, id).filter { it.first == LineKind.USER }.map { it.second }
        assertEquals(listOf("and then this", "and after that"), userLines.filter { it.startsWith("and ") }, "$userLines")
        assertTrue(drained.notices.none { it.text.contains("already has a run in flight") }, "${drained.notices}")

        // A normal send to the agent at rest still goes out and is echoed as sent.
        assertIs<CommandResult.Sent>(runBlocking { c.prompt(id, "a plain send") })
        until("the plain send echoed and run", c) { s ->
            s.agents.getValue(id).session.runCount == 5 &&
                transcript(s, id).any { it.first == LineKind.USER && it.second == "a plain send" }
        }
    }

    @Test fun `resume produces visible feedback in the transcript`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { c.spawn("first turn") })
        val s1 = until("first run finished", c) { s -> s.agents.values.any { a -> a.output.any { it.kind == LineKind.FINISHED } } }
        val id = s1.agents.values.single().id
        until("resumable", c) { it.agents[id]?.session?.resumable == true }

        assertIs<CommandResult.Sent>(runBlocking { c.resume(id, "resume turn") })
        val s2 = until("resume finished", c) { s -> s.agents[id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }
        val resumeRun = transcript(s2, id).drop(transcript(s2, id).indexOfLast { it.first == LineKind.USER && it.second == "resume turn" })

        // Contract 1.64.0: the resume feedback is in the Activity box, not a chat line, and no boot line repeats.
        assertTrue(s2.agents.getValue(id).activityLog.any { it.detail.orEmpty().contains("Resuming session") },
            "resume activity should contain a 'Resuming session' entry: ${s2.agents.getValue(id).activityLog}")
        assertTrue(resumeRun.none { it.first == LineKind.LOG && it.second.contains("Resuming session") }, "$resumeRun")
    }

    @Test fun `a second window that opens the agent sees the whole conversation once and in order`() {
        val port = boot()
        val a = Controller(scope)
        a.connect("ws://127.0.0.1:$port/ws", null)
        until("a connected", a) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { a.spawn("first question") })
        val id = until("first answered", a) { s -> s.selected?.output?.any { it.kind == LineKind.FINISHED } == true }.selectedId!!
        until("resumable", a) { it.agents[id]?.session?.resumable == true }
        assertIs<CommandResult.Sent>(runBlocking { a.prompt(id, "second question") })
        val conversation = transcript(until("second answered", a) { s -> s.agents[id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }, id)

        val b = Controller(scope)
        b.connect("ws://127.0.0.1:$port/ws", null)
        until("b knows the agent", b) { it.connection.isLive && id in it.agents }
        b.select(id)
        val reopened = until("b replayed the conversation", b) { s -> s.agents[id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }
        assertEquals(conversation, transcript(reopened, id), "a reopened conversation reads exactly like the live one")
    }

    @Test fun `a reconnect to the same daemon fills in a turn that happened while the window was away`() {
        val port = boot()
        val url = "ws://127.0.0.1:$port/ws"
        val a = Controller(scope)
        a.connect(url, null)
        until("a connected", a) { it.connection.isLive }
        assertIs<CommandResult.Sent>(runBlocking { a.spawn("before the drop") })
        val id = until("first answered", a) { s -> s.selected?.output?.any { it.kind == LineKind.FINISHED } == true }.selectedId!!
        until("resumable", a) { it.agents[id]?.session?.resumable == true }
        val instance = a.state.value.connection.instance!!

        a.disconnect()
        val other = Controller(scope)
        other.connect(url, null)
        until("other knows the agent", other) { it.connection.isLive && it.agents[id]?.session?.resumable == true }
        assertIs<CommandResult.Sent>(runBlocking { other.prompt(id, "while you were away") })
        until("other saw it answered", other) { s -> s.agents[id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }

        a.connect(url, null)
        val back = until("a caught up", a) { s -> s.connection.isLive && s.agents[id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }
        assertEquals(instance, back.connection.instance, "same daemon process")
        assertEquals(listOf("before the drop", "while you were away"), transcript(back, id).filter { it.first == LineKind.USER }.map { it.second })
    }

    // --- Role picker / Role detail (contract 1.12.0) and attention (contract 1.9.0) -----------------

    @Test fun `the Role picker - a role and a profile are saved, and a session starts from the profile`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        val loaded = until("catalog and providers loaded on connect", c) { it.connection.isLive && it.roles.isNotEmpty() && it.providers.isNotEmpty() }
        assertEquals("architect", loaded.roles.first().id, "canonical roles first")
        assertEquals("stub", loaded.providers.first { it.default }.id)

        assertIs<CommandResult.Sent>(runBlocking { c.saveRole("data-wrangler", "Data Wrangler", "#22c55e", "You clean data.") })
        until("the saved role pushed back", c) { it.role("data-wrangler")?.source == "CUSTOM" }

        assertIs<CommandResult.Sent>(runBlocking {
            c.saveProfile(AgentProfile(name = "Wrangler", role = "data-wrangler", instructions = "You clean data."))
        })
        val profile = until("the profile listed under its role", c) { it.profilesFor("data-wrangler").isNotEmpty() }
            .profilesFor("data-wrangler").single()

        assertIs<CommandResult.Sent>(runBlocking { c.spawnAs(profile.id!!, "tidy this table") })
        val started = until("the profile's session answered", c) { s ->
            s.selected?.role == "data-wrangler" && s.selected!!.output.any { it.kind == LineKind.FINISHED }
        }
        assertEquals(
            LineKind.USER to "You clean data.\n\n" +
                "You are running inside Infantry AI as role 'data-wrangler', agent id '${profile.id}'. " +
                "Your environment carries the same identity (AGENTHQ_ROLE, AGENTHQ_AGENT_ID, AGENTHQ_SESSION_ID).\n\n" +
                "tidy this table",
            started.selected!!.output.first().let { it.kind to it.text },
            "the profile's instructions lead the first turn, then who the agent runs as (BUG-808ce596), as the daemon delivered it",
        )
        assertEquals(profile.id, started.selected!!.session.agentId)
    }

    @Test fun `attention raised by the daemon shows up live, is acknowledged from the UI, and is history for a later window`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.isLive }
        val raised = daemon!!.attention.raise(kind = AttentionKind.STALLED, sessionId = "s-1", outcome = "stalled", detail = "no output for 15 minutes")

        until("pushed live", c) { s -> s.attention.any { it.id == raised.id } }
        assertEquals(1, c.state.value.attentionOpenCount)
        assertIs<CommandResult.Sent>(runBlocking { c.ackAttention(raised.id) })
        until("acknowledged", c) { s -> s.attention.single { it.id == raised.id }.ackedAt != null }
        assertEquals(0, c.state.value.attentionOpenCount)

        val late = Controller(scope)
        late.connect("ws://127.0.0.1:$port/ws", null)
        val history = until("history on connect", late) { s -> s.attention.any { it.id == raised.id } }
        assertEquals("stalled", history.attention.single().kind)
        assertTrue(history.attention.single().ackedAt != null)
    }

    // --- usage and limit state (story 9.8c, contract 1.13.0) -----------------------------------

    @Test fun `a run's reported usage and every provider's limit state reach the window`() {
        val tokens = """{"type":"step_finish","part":{"tokens":{"input":120,"output":30,"reasoning":0,"cache":{"read":0,"write":0}}}}"""
        val port = boot(script = StubScript(stdoutLines = listOf("""{"type":"step_start","sessionID":"u-ui"}""", """{"type":"text","text":"ok"}""", tokens)))
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        val connected = until("provider limits loaded on connect", c) { it.connection.isLive && it.providerStates.isNotEmpty() }
        assertEquals("unknown", connected.providerStates.getValue("stub").status, "never observed is unknown")
        assertEquals(null, connected.providerStates.getValue("stub").utilization, "and has no bar: not 0%")

        assertIs<CommandResult.Sent>(runBlocking { c.spawn("count my tokens") })
        val counted = until("the run's usage on its agent", c) { s -> s.selected?.usageTotals != null }
        val totals = counted.selected!!.usageTotals!!
        assertEquals(120L, totals.input)
        assertEquals(30L, totals.output)
        assertEquals(1, totals.runs)
    }

    // --- scrum board, profiles and triggers (story 9.8a/b, contracts 1.4.0 / 1.6.0 / 1.14.0) ---------

    /** Poll a trigger's event rows (the window asks, the daemon answers) until [cond] holds. */
    private fun untilEvents(what: String, c: Controller, triggerId: String, cond: (List<String>) -> Boolean): List<String> {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            runBlocking { c.loadTriggerEvents(triggerId) }
            val rows = c.state.value.triggerEvents[triggerId].orEmpty().map { it.disposition }
            if (cond(rows)) return rows
            Thread.sleep(100)
        }
        error("events not met: $what; rows=${c.state.value.triggerEvents[triggerId]?.map { it.disposition }} notices=${c.state.value.notices}")
    }

    private fun connected(port: Int): Controller {
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected and profiles/roles loaded", c) { it.connection.isLive && it.roles.isNotEmpty() && it.providers.isNotEmpty() }
        return c
    }

    @Test fun `profiles are listed flat, edited in place by id, and deleted`() {
        val c = connected(boot())
        assertIs<CommandResult.Sent>(runBlocking { c.saveProfile(AgentProfile(name = "Builder", role = "backend", instructions = "Build.")) })
        val saved = until("profile listed", c) { it.profiles.size == 1 }.profiles.single()
        assertEquals("backend", saved.role)

        // an edit is a save carrying the stored id: it overwrites, it does not add a second profile
        assertIs<CommandResult.Sent>(runBlocking { c.saveProfile(saved.copy(name = "Builder 2", model = "m-1")) })
        val edited = until("profile renamed", c) { it.profiles.singleOrNull()?.name == "Builder 2" }.profiles.single()
        assertEquals(saved.id, edited.id)
        assertEquals("m-1", edited.model)

        assertIs<CommandResult.Sent>(runBlocking { c.deleteProfile(saved.id!!) })
        until("profile gone", c) { it.profiles.isEmpty() }
    }

    @Test fun `the board - a ticket is created, started into a real session, finished, and a start that cannot run says why`() {
        val c = connected(boot())
        assertIs<CommandResult.Sent>(runBlocking { c.saveProfile(AgentProfile(id = "backend", name = "Backend", role = "backend")) })
        until("profile listed", c) { it.profiles.isNotEmpty() }

        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Ship it", "write the thing", null, "backend") })
        val created = until("ticket on the board", c) { it.tickets.isNotEmpty() }.tickets.single()
        assertEquals(TicketColumns.TODO, created.status)
        assertEquals("backend", created.agentId)

        assertIs<CommandResult.Sent>(runBlocking { c.moveTicket(created.id, TicketColumns.IN_PROGRESS) })
        val started = until("the ticket has its session", c) { s -> s.ticket(created.id)?.let { it.status == TicketColumns.IN_PROGRESS && it.sessionId != null } == true }
        val sessionId = started.ticket(created.id)!!.sessionId!!
        until("the ticket's session is an agent on screen", c) { it.agents[sessionId]?.session?.ticketId == created.id }
        until("its run finished", c) { it.ticket(created.id)?.lastRun?.outcome != null }

        assertIs<CommandResult.Sent>(runBlocking { c.moveTicket(created.id, TicketColumns.DONE) })
        until("done column", c) { s -> s.column(TicketColumns.DONE).map { it.id } == listOf(created.id) && s.column(TicketColumns.IN_PROGRESS).isEmpty() }

        // an unassigned ticket cannot be handed to anyone: the card stays put and the window says why
        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Nobody", "", null, "") })
        val orphan = until("second ticket", c) { it.tickets.size == 2 }.tickets.first { it.title == "Nobody" }
        assertIs<CommandResult.Sent>(runBlocking { c.moveTicket(orphan.id, TicketColumns.IN_PROGRESS) })
        val refused = until("refusal said", c) { s -> s.notices.any { it.text.contains("start_refused") } }
        assertEquals(TicketColumns.TODO, refused.ticket(orphan.id)!!.status)

        assertIs<CommandResult.Sent>(runBlocking { c.deleteTicket(orphan.id) })
        until("deleted", c) { s -> s.tickets.map { it.id } == listOf(created.id) }
    }

    @Test fun `start next hands out the top ready ticket`() {
        val c = connected(boot())
        assertIs<CommandResult.Sent>(runBlocking { c.saveProfile(AgentProfile(id = "backend", name = "Backend", role = "backend")) })
        until("profile listed", c) { it.profiles.isNotEmpty() }
        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("First", "", null, "backend") })
        until("first on the board", c) { it.tickets.size == 1 }
        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Second", "", null, "backend") })
        until("second on the board", c) { it.tickets.size == 2 }

        assertIs<CommandResult.Sent>(runBlocking { c.startNextTicket() })
        val s = until("the top ticket started", c) { st -> st.tickets.any { it.title == "First" && it.status == TicketColumns.IN_PROGRESS && it.sessionId != null } }
        assertEquals(TicketColumns.TODO, s.tickets.single { it.title == "Second" }.status)
    }

    @Test fun `a trigger is created, fired on an empty board, fires a ticket, and is deleted`() {
        val c = connected(boot())
        assertIs<CommandResult.Sent>(runBlocking { c.saveTrigger(null, "Pull next", null, 0, 6) })
        val trigger = until("trigger listed", c) { it.triggers.size == 1 }.triggers.single()
        assertEquals("armed", trigger.state)
        assertEquals("start_next", trigger.action.kind)

        assertIs<CommandResult.Sent>(runBlocking { c.fireTrigger(trigger.id) })
        untilEvents("nothing to start", c, trigger.id) { it == listOf("nothing_to_start") }
        assertEquals("armed", c.state.value.trigger(trigger.id)!!.state, "an empty board is never a trip")

        assertIs<CommandResult.Sent>(runBlocking { c.saveProfile(AgentProfile(id = "backend", name = "Backend", role = "backend")) })
        until("profile listed", c) { it.profiles.isNotEmpty() }
        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Triggered", "", null, "backend") })
        until("ticket on the board", c) { it.tickets.size == 1 }
        assertIs<CommandResult.Sent>(runBlocking { c.fireTrigger(trigger.id) })
        untilEvents("started", c, trigger.id) { it == listOf("nothing_to_start", "started") }
        until("the trigger started the ticket", c) { s -> s.tickets.single().let { it.status == TicketColumns.IN_PROGRESS && it.sessionId != null } }
        until("the trigger is armed again after a clean run", c) { it.trigger(trigger.id)?.state == "armed" }

        assertIs<CommandResult.Sent>(runBlocking { c.deleteTrigger(trigger.id) })
        until("trigger gone", c) { it.triggers.isEmpty() }
    }

    @Test fun `a failed run trips the trigger, it is shown tripped with attention, suppresses later fires, and only arm clears it`() {
        val c = connected(boot(script = StubScript.failing()))
        assertIs<CommandResult.Sent>(runBlocking { c.saveProfile(AgentProfile(id = "backend", name = "Backend", role = "backend")) })
        until("profile listed", c) { it.profiles.isNotEmpty() }
        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Will fail", "", null, "backend") })
        until("ticket on the board", c) { it.tickets.size == 1 }
        assertIs<CommandResult.Sent>(runBlocking { c.saveTrigger(null, "Fragile", "backend", 0, 6) })
        val trigger = until("trigger listed", c) { it.triggers.size == 1 }.triggers.single()
        assertEquals("backend", trigger.action.agentId)

        assertIs<CommandResult.Sent>(runBlocking { c.fireTrigger(trigger.id) })
        val tripped = until("tripped", c) { it.trigger(trigger.id)?.state == "tripped" }
        assertTrue(tripped.trigger(trigger.id)!!.trippedReason!!.isNotBlank(), "a trip always says why")
        until("trip raised attention", c) { s -> s.attention.any { it.kind == "trigger_tripped" && it.triggerId == trigger.id } }
        assertEquals(1, c.state.value.trippedTriggerCount)

        assertIs<CommandResult.Sent>(runBlocking { c.fireTrigger(trigger.id) })
        untilEvents("suppressed", c, trigger.id) { it == listOf("started", "suppressed_tripped") }

        assertIs<CommandResult.Sent>(runBlocking { c.armTrigger(trigger.id) })
        until("re-armed", c) { it.trigger(trigger.id)?.state == "armed" }
        assertEquals(0, c.state.value.trippedTriggerCount)
    }
}

/**
 * BUG-FLAKE-3: a stub turn that stays in flight until it is stopped, so a test acting on a BUSY agent does not race
 * the clock. The stub's own scripted turn ends by itself a fixed time after it starts; this one streams what a turn
 * streams first (the provider session id, then a text line), on its own thread after stdin EOF like the stub replay,
 * and then HOLDS. Only the daemon's interrupt or stop ([RunningProcess.killTree]) ends it, and, like a killed stub
 * replay, it reports no exit. BUSY therefore lasts until the action under test lands, however slow the machine is,
 * and an interrupt that never arrives fails the test's IDLE wait instead of being covered by a natural finish.
 */
private class HeldTurnRunner : ProcessRunner {
    override val id: String = "held-turn"

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = HeldTurn(listener)

    private class HeldTurn(private val listener: ProcessListener) : RunningProcess {
        private val stopped = CountDownLatch(1)
        private val streaming = AtomicBoolean(false)

        override val isAlive: Boolean get() = stopped.count > 0

        override fun writeInput(text: String) = Unit

        /** Stdin EOF starts the turn, once; a later close (a graceful stop's signal) does not end a busy turn. */
        override fun closeInput() {
            if (!streaming.compareAndSet(false, true)) return
            Thread({ OPENING.forEach { if (isAlive) listener.onStdout(it) } }, "held-turn").apply { isDaemon = true }.start()
        }

        override fun killTree() = stopped.countDown()

        override fun awaitExit(): Int {
            stopped.await()
            return KILLED
        }
    }

    private companion object {
        const val KILLED = 137
        val OPENING = listOf(
            """{"type":"step_start","sessionID":"stub-held-1"}""",
            """{"type":"text","text":"[stub] scanning, held until interrupted"}""",
        )
    }
}
