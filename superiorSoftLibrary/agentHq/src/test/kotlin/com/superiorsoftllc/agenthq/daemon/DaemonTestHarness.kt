package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real `java.net.http.WebSocket` client on the real socket, recording every frame in arrival order.
 *
 * It owns its own [Json] rather than borrowing one from a test class: a nested class in Kotlin has no
 * access to the enclosing instance, and sharing a decoder with the daemon under test is exactly the
 * coupling that lets a wrong-encoder bug pass unnoticed.
 *
 * `WebSocket.Listener` is a Java interface, so it is a supertype LIST, not a constructor call -- the
 * same form `ClientMain.kt` uses.
 */
class WsTestClient : WebSocket.Listener {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var link: WebSocket? = null
    private val buffer = StringBuilder()
    val frames = CopyOnWriteArrayList<Envelope>()
    /** Original wire objects, retaining additive fields for raw protocol assertions. */
    val rawFrames = CopyOnWriteArrayList<JsonObject>()

    // The JDK listener contract is `void` here; request(1) is the flow-control continuation.
    override fun onOpen(webSocket: WebSocket) {
        link = webSocket
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        buffer.append(data)
        if (last) {
            val text = buffer.toString()
            buffer.setLength(0)
            runCatching { json.parseToJsonElement(text) as? JsonObject }
                .getOrNull()?.let { rawFrames += it }
            runCatching { json.decodeFromString(Envelope.serializer(), text) }
                .getOrNull()?.let { frames += it }
        }
        // Request the next part on EVERY onText, `last` or not: the JDK may deliver one message in
        // several parts, and pressure is per part. Requesting only on `last` stalls the socket on the
        // first fragmented message, silently starving every frame after it (story 9.3 grew a session
        // frame past the fragmentation threshold, which is how this latent harness bug surfaced).
        webSocket.request(1)
        return null
    }

    fun send(envelope: Envelope) {
        val socket = link ?: error("socket not open yet")
        runCatching { socket.sendText(json.encodeToString(Envelope.serializer(), envelope), true).join() }
    }

    fun send(type: String, data: String) = send(Envelope(type, data))

    /** SEC-FIX-1: a text frame exactly as given, not an envelope (the daemon reads a non-JSON one as a prompt). */
    fun sendRaw(text: String) {
        val socket = link ?: error("socket not open yet")
        runCatching { socket.sendText(text, true).join() }
    }

    fun close() {
        runCatching { link?.sendClose(WebSocket.NORMAL_CLOSURE, "bye")?.join() }
    }

    /** Frames of [type] seen so far, in arrival order. */
    fun of(type: String): List<Envelope> = frames.filter { it.type == type }

    /**
     * Wait for the [occurrence]-th frame of [type] (0-based). Fails loudly rather than silently
     * passing -- a test that times out and proceeds proves nothing.
     */
    fun await(type: String, occurrence: Int = 0, timeoutMs: Long = TestWait.defaultMs): Envelope {
        val found = awaitOrNull(type, occurrence, timeoutMs)
            ?: error("no '$type' frame #$occurrence within ${timeoutMs}ms; got ${frames.map { it.type }}")
        return found
    }

    fun awaitOrNull(type: String, occurrence: Int = 0, timeoutMs: Long = TestWait.defaultMs): Envelope? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            of(type).getOrNull(occurrence)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    /** Wait for the first frame satisfying [predicate]; the failure message names what was expected. */
    fun awaitWhere(description: String, timeoutMs: Long = TestWait.defaultMs, predicate: (Envelope) -> Boolean): Envelope {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            frames.firstOrNull(predicate)?.let { return it }
            Thread.sleep(20)
        }
        error("no frame matching $description within ${timeoutMs}ms; got ${frames.map { it.type to it.data.take(60) }}")
    }
}

/**
 * A real [AgentHqDaemon] on an ephemeral port, driven over a real WebSocket.
 *
 * NOTHING here is faked except the script (or, where a test needs to hold a run open, the
 * [com.superiorsoftllc.agenthq.orchestration.GatedRunner]): the real Ktor/CIO server, the real
 * WebSocket route, the real SessionManager, mailbox, orchestrator and SQLite store are all assembled
 * by `AgentHqDaemon.build` exactly as `main` does. The provider is the stub, which is a real registry
 * entry with an in-memory transport -- that is the entire point of `AGENTHQ_PROVIDER=stub`.
 *
 * Each fixture gets its own temp state directory, so no test can reach the operator's `~/.agenthq`.
 */
class DaemonFixture private constructor(
    val stateDir: File,
    val daemon: AgentHqDaemon,
    val port: Int,
    /** The address clients dial; [health] and the WebSocket use it. */
    val connectHost: String = "127.0.0.1",
    /** The bearer token every client must send, or null when the daemon requires none. */
    val token: String? = null,
    /** SEC-FIX-1: the owner credential (`<stateDir>/owner.token`) an owner client presents. */
    val ownerToken: String? = null,
) {
    val client: WsTestClient = WsTestClient()
    private var socket: WebSocket? = null
    private val http: HttpClient = HttpClient.newHttpClient()
    /** Extra clients opened by [connectClient], closed by [stop] before the daemon goes down. */
    private val extraClients = CopyOnWriteArrayList<WsTestClient>()
    private var stopped = false

    /** The store the DAEMON opened, as the concrete type, so its driver can be interrogated. */
    val sqlStore: SqlAgentHqStore
        get() = daemon.store as? SqlAgentHqStore
            ?: error("the daemon was built with ${daemon.store::class.simpleName}, not a SqlAgentHqStore")

    fun send(type: String, data: String) = client.send(type, data)

    fun send(envelope: Envelope) = client.send(envelope)

    /**
     * Open a SECOND real WebSocket client on the same daemon, exactly the way [launch] connected
     * [client] (same URL, same Authorization header when a token is set), and wait for its `ready`
     * frame before returning it. The fixture keeps it so [stop] can close it; a test that needs more
     * than two clients just calls this again.
     */
    fun connectClient(owner: Boolean = true): WsTestClient {
        val extra = WsTestClient()
        openSocket(extra, owner)
        extra.await(Envelope.READY)
        extraClients += extra
        return extra
    }

    /**
     * Build the `ws://…/ws` socket for [wsClient], carrying the bearer token when one is required, and (SEC-FIX-1)
     * the owner credential when [owner]; without it the client is what an agent is.
     */
    private fun openSocket(wsClient: WsTestClient, owner: Boolean = true): WebSocket {
        val builder = HttpClient.newHttpClient().newWebSocketBuilder()
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (owner) ownerToken?.let { builder.header(OwnerCredential.HEADER, it) }
        return builder.buildAsync(URI.create("ws://$connectHost:$port/ws"), wsClient).join()
    }

    /** `GET /health` decoded, so a test can assert on fields rather than on raw JSON text. */
    fun health(): JsonObject {
        val request = HttpRequest.newBuilder(URI.create("http://$connectHost:$port/health")).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "/health returned ${response.statusCode()}: ${response.body()}" }
        return json.parseToJsonElement(response.body()) as JsonObject
    }

    /** Poll [condition] until it holds; fails with [what] rather than returning on timeout. */
    fun await(timeoutMs: Long = TestWait.defaultMs, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("not met within ${timeoutMs}ms: $what")
    }

    /** Stop the server, then release the orchestrator, the sessions and the SQLite handle. */
    fun stop() {
        if (stopped) return
        stopped = true
        client.close()
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "done")?.join() }
        extraClients.forEach { it.close() }
        runCatching { daemon.server.stop(1, 500) }
        daemon.close()
    }

    /** Remove the temp state directory. Safe to call after [stop]. */
    fun deleteState() {
        runCatching { stateDir.deleteRecursively() }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        /**
         * Boot the daemon and connect.
         *
         * @param runner overrides the provider's own transport -- pass `GatedRunner` when a test needs
         *   a run held open (`HANG`) or failing on cue instead of a fixed script.
         * @param stateDir / storePath are parameters so the crash-recovery test can boot a SECOND
         *   daemon over the exact database file the first one left behind.
         */
        fun launch(
            script: StubScript = StubScript.default(),
            runner: ProcessRunner? = null,
            stateDir: File = Files.createTempDirectory("agenthq-daemon-test").toFile(),
            storePath: String = File(stateDir, "agenthq.db").absolutePath,
            /**
             * The store the daemon runs on, or null to open [storePath] the way every other fixture
             * does. A test that must hold ONE write in flight -- [DaemonShutdownTest] builds a store
             * whose driver parks a statement -- hands in its own store; the daemon's own wiring is
             * otherwise untouched, so what such a test observes is still the real daemon.
             */
            store: AgentHqStore? = null,
            /** The address the daemon BINDS (what `AGENTHQ_HOST` would be). */
            host: String = "127.0.0.1",
            /** The address a CLIENT dials. Differs from [host] for a wildcard or LAN bind. */
            connectHost: String = "127.0.0.1",
            /** The bearer token to require, or null for loopback-only with no credential. */
            token: String? = null,
            /**
             * Providers a spawn may name besides the stub (contract 1.3.0). Explicitly empty by default,
             * so a test never inherits the operator's `AGENTHQ_PROVIDERS` from the environment.
             */
            additionalProviders: List<AgentProvider> = emptyList(),
            /**
             * Contract 1.6.0: the repository a ticket start cuts a worktree from, or null so starts
             * share the checkout. Passed EXPLICITLY to `build` so the operator's `AGENTHQ_REPO` never
             * leaks into a test (`build`'s own default reads that variable).
             */
            repo: File? = null,
            context: TicketContext? = null,
            /** Story 9.7b: the operator budget; unlimited unless a test sets one, so AGENTHQ_BUDGET_* never leaks in. */
            budget: com.superiorsoftllc.agenthq.usage.OperatorBudget? = null,
            /** Lock attribution: the label this daemon's store publishes as the holder of its writes. */
            holderLabel: String? = null,
            /** Lock attribution tests shorten the 5s wait so a lock timeout does not cost 5s a test. */
            busyTimeoutMs: Int = TestWait.storeBusyMs,
            /** IN-APP BUILD T2: the saved commands file; in the fixture state dir so the operator's never leaks in. */
            commandsFile: File = File(stateDir, "commands.json"),
            /** WIRE-1: empty by default so existing exact `providers` assertions stay isolated; pass [loadHarnesses] for the real thing. */
            harnesses: com.superiorsoftllc.agenthq.harness.HarnessRegistry = com.superiorsoftllc.agenthq.harness.HarnessRegistry(),
            /** ROLE-FIRST S4: empty by default, so no test ever spawns the operator's real `pi --list-models`. */
            modelCatalogs: com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
            /** BUG-CHANGES-BRANCH-2: the git `project_git_*` reads through; the real one unless a test fakes a slow git. */
            projectGitCli: com.superiorsoftllc.agenthq.worktree.GitCli = com.superiorsoftllc.agenthq.worktree.SystemGitCli(),
            /** PERF-3: the machine the executable resolver reads; the real one unless a test fakes a slow PATH scan. */
            executableEnvironment: com.superiorsoftllc.agenthq.executables.ExecutableEnvironment =
                com.superiorsoftllc.agenthq.executables.SystemExecutableEnvironment,
            /** BUG-G2: the agenthq CLI bin dir handed to sessions; null by default so the operator's install never leaks in. */
            agentHqBin: File? = null,
            /** BUG-G2c: the port to bind; 0 (the default) is ephemeral. A test that needs the port known up front passes a free one. */
            port: Int = 0,
            /** BUG-G2d: the pod spawn cap and depth, pinned to the defaults so an operator's env never changes a test. */
            podSpawnCap: Int = SpawnGuard.DEFAULT_MAX_PER_POD,
            podSpawnDepth: Int = SpawnGuard.DEFAULT_MAX_DEPTH,
            /** U1: no limit sources by default, so no test ever reads the operator's credentials or calls a provider. */
            limits: com.superiorsoftllc.agenthq.usage.LimitService = com.superiorsoftllc.agenthq.usage.LimitService.NONE,
            /** E1-c: a `stories.json` folded into the board at startup (E1-a); null (the default) migrates nothing. */
            storyMigrationFile: File? = null,
            /** DP-5h-3d: false = a daemon with no board. */
            withBoard: Boolean = true,
            /** PORT-1: refuse a taken [port] instead of falling back; pinned off so an operator's env never changes a test. */
            strictPort: Boolean = false,
            /** PORT-1: where the daemon records the port it bound; null (the default) records it nowhere. */
            portFile: File? = null,
            /**
             * BUG-WT-1: where a no-worktree session runs; a plain folder in the fixture's temp state dir, never the
             * test JVM's own directory (a git checkout, which a ticket start refuses).
             */
            workingDir: File = File(stateDir, "workdir").apply { mkdirs() },
            /** INSTALL-CWD-1: the installed app's project folders; off so the operator's AGENTHQ_PROJECT_DIRS never leaks in. */
            projectDirs: Boolean = false,
            /** ASSISTANT-1: the global Assistant's folder, inside the fixture's temp state dir. */
            assistantDir: File = File(stateDir, "assistant"),
            /** BUG-REGRESS-1: the ref a ticket start cuts from; `HEAD`, so the operator's `AGENTHQ_BASE_BRANCH` never leaks in. */
            baseRef: com.superiorsoftllc.agenthq.worktree.TicketBaseRef =
                com.superiorsoftllc.agenthq.worktree.TicketBaseRef.fixed(com.superiorsoftllc.agenthq.worktree.TicketBaseRef.HEAD),
            /** BUG-FLAKE-1: runs between build() and start(), while the port is chosen but the server has not bound it. */
            beforeStart: (AgentHqDaemon) -> Unit = {},
            /** BUG-PORT-0: the mail base override; null = the daemon's own default (`http://<host>:<port>`). */
            mailBaseUrl: String? = "http://127.0.0.1:1",
            /**
             * BUG-PORT-0b: starts the server and returns the bound port. The production [AgentHqDaemon.start] by default;
             * a test passes a direct `daemon.server.start(wait = false)` to boot the way the agentHqUi tests do.
             */
            startServer: (AgentHqDaemon) -> Int = AgentHqDaemon::start,
            /** IMG-5: the daemon's "now" (upload mtimes, the chat image retention sweep). */
            clock: () -> Long = System::currentTimeMillis,
            /** TICKET-HANDOVER: how long a switched ticket worker's handoff turn may run. */
            handoffTimeoutMs: Long = SessionHandover.DEFAULT_TIMEOUT_MS,
            /** WEB-1: the browser bundle served at `/`; none by default, so the operator's AGENTHQ_WEB_DIR never leaks in. */
            webBundle: WebBundle = WebBundle.EMPTY,
            /** SEC-FIX-1: the main [client] presents the owner credential; false connects it as an agent would. */
            owner: Boolean = true,
            /** DAEMON-PLUGINS-1: the plugins root the daemon loads its daemon plugins from ([TestDaemonPlugins.root]); null = none. */
            pluginsRoot: File? = null,
            pluginTrust: com.superiorsoftllc.agenthq.daemon.plugins.DaemonPluginHost.Trust? = null,
            pluginEnv: (String) -> String? = { null },
            orchestratorBindings: List<Pair<String, com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorBinding>> = emptyList(),
            /**
             * POLICY-1: the policy presets and the switch. Default: production only, over the fixture's OWN state dir (never
             * the process's, gap 9), so the operator's AGENTHQ_POLICY / policy.preset never leaks in.
             */
            policies: ((File) -> com.superiorsoftllc.agenthq.policy.PolicyRuntime)? = null,
            /** H2: the default provider; the stub unless a test boots the zero-harness daemon ([com.superiorsoftllc.agenthq.harness.NoHarnessProvider]). */
            defaultProvider: AgentProvider? = null,
        ): DaemonFixture {
            val opened: AgentHqStore = store ?: SqlAgentHqStore.open(storePath, holderLabel, busyTimeoutMs)
            val provider = defaultProvider ?: StubProvider(script = script, delayMs = 0)
            val daemon = AgentHqDaemon.build(
                port = port,
                provider = provider,
                runner = runner ?: provider.transport() ?: com.superiorsoftllc.agenthq.runner.ScriptedRunner(script, 0),
                sessionStore = JsonSessionStore(File(stateDir, "sessions.json")),
                mailStore = JsonMailStore(File(stateDir, "mailbox.json")),
                store = opened,
                // Nothing in these tests asserts on a delivered prompt's URL, and pointing it at a
                // dead port keeps a stray delivery from looking like a live one.
                mailBaseUrl = mailBaseUrl,
                host = host,
                token = token,
                additionalProviders = additionalProviders,
                repo = repo,
                fixedFirstTurn = context?.let { c -> { c.compose(com.superiorsoftllc.agenthq.context.ContextInput(null, "", "", emptyList(), null)).let { r -> r.prompt to r.sources.map { it.source to it.chars } } } },
                budget = budget ?: com.superiorsoftllc.agenthq.usage.OperatorBudget(maxRunsPerDay = null, maxTokensPerDay = null),
                commandsFile = commandsFile,
                harnesses = harnesses,
                modelCatalogs = modelCatalogs,
                projectGitCli = projectGitCli,
                executableEnvironment = executableEnvironment,
                agentHqBin = agentHqBin,
                podSpawnCap = podSpawnCap,
                podSpawnDepth = podSpawnDepth,
                limits = limits,
                storyMigrationFile = storyMigrationFile,
                withBoard = withBoard,
                strictPort = strictPort,
                portFile = portFile,
                workingDir = workingDir,
                projectDirs = projectDirs,
                assistantDir = assistantDir,
                baseRef = baseRef,
                clock = clock,
                handoffTimeoutMs = handoffTimeoutMs,
                webBundle = webBundle,
                ownerToken = OwnerCredential.ensure(stateDir),
                pluginsRoot = pluginsRoot ?: if (withBoard) TestDaemonPlugins.boardRoot("official/Infantry_Attention", "official/Infantry_Roles") else TestDaemonPlugins.rolesRoot(),
                pluginTrust = pluginTrust ?: TestDaemonPlugins.trustAll,
                // AR-4b-ii-b: custom roles live in the fixture's OWN state dir, never in ~/.agenthq/roles.
                pluginEnv = { name -> if (name == "AGENTHQ_ROLES_DIR") File(stateDir, "roles").path else pluginEnv(name) },
                orchestratorBindings = orchestratorBindings,
                policies = policies?.invoke(stateDir) ?: com.superiorsoftllc.agenthq.policy.PolicyRuntime(emptyList(), { null }, null, stateDir),
            )
            beforeStart(daemon)
            // PORT-1: the production start path: bind, then record the bound port (resolves the ephemeral 0).
            val boundPort = startServer(daemon)
            val fixture = DaemonFixture(stateDir, daemon, boundPort, connectHost, token, OwnerCredential.ensure(stateDir))
            fixture.socket = fixture.openSocket(fixture.client, owner)
            return fixture
        }
    }
}
