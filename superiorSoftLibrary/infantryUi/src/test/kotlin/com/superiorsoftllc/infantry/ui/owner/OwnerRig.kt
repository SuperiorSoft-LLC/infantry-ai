package com.superiorsoftllc.infantry.ui.owner

import com.superiorsoftllc.infantry.daemon.InfantryMcp
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * E2E-OWNER-1: the owner's machine, rebuilt in a temp dir for ONE scenario, then torn down.
 *
 * THE LAYOUT (what the owner has): a git repo whose MAIN checkout ([repo], branch `main`) holds uncommitted TRACKED
 * edits (`.gitignore`, `DaemonMain.kt`), and a LINKED worktree ([preview], `.worktrees/ui-gate`) on
 * `feature-agenthq-mvp` one commit ahead. The daemon runs from the linked worktree, as the owner's preview does.
 *
 * THE DAEMON starts the way `infantryUi:runRealDaemon` starts it: `RealDaemonMainKt` on infantryUi's main runtime
 * classpath, `INFANTRY_PORT` and `INFANTRY_PROVIDER` in its env, as a SEPARATE JVM, headless (`INFANTRY_HEADLESS`), with a
 * temp state dir and a free port. Never `~/.infantry`, never 8787/8788: every `GIT_*` and `INFANTRY_*` of this JVM is
 * stripped from every child first, and the daemon gets `-Duser.home=<temp>` as a last safety net.
 *
 * THE OWNER'S WINDOW is infantryUiCore's [Controller] (the window's own brain) connected to that daemon: it saves the
 * team (profiles, role bindings), starts the PM session, and is where the board is read.
 *
 * THE AGENT SIDE uses the INSTALLED launcher the daemon resolved (`InfantryBin`: `infantry/build/install/infantry/bin`),
 * never test classes: [pm] is `infantry mcp serve` launched with [InfantryMcp.launch] on the PM session's env, exactly the
 * entry a harness gets; [cli] is `infantry ...` resolved through that session's PATH.
 *
 * THE HARNESSES are [RecordingHarness] descriptors: `stub-a` (the pins under test), `stub-b` (the roles' default, so a
 * lost pin shows), `stub-lead` (the PM). Each run records what it was really asked to run.
 */
class OwnerRig private constructor(val name: String, val root: File) : AutoCloseable {
    /** The MAIN checkout: branch `main`, dirty with tracked edits, where ticket worktrees are cut. */
    val repo = File(root, "repo")

    /** The LINKED worktree on [FEATURE] the daemon runs from (the owner's `.worktrees/ui-gate-84ecfe2`). */
    val preview = File(repo, ".worktrees/ui-gate")

    val state = File(root, "state")
    val records = File(root, "records")
    val logs = File(root, "logs")
    private val home = File(root, "home")

    private var daemon: Process? = null
    private var daemonDescendants: List<ProcessHandle> = emptyList()

    /** The daemon's WebSocket, built from the port it ACTUALLY bound (its own boot line). */
    lateinit var wsUrl: String
        private set
    var port: Int = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The owner's window, connected to this rig's daemon. */
    val window = Controller(scope)

    /** The owner's project: [repo] is its directory, [FEATURE] the base branch its tickets are cut from. */
    lateinit var projectId: String
        private set

    /** The PM session's first run, as the stub recorded it: its id, cwd and env are the PM's. */
    lateinit var lead: Recording
        private set

    private var pmClient: McpStdio? = null
    private val closers = mutableListOf<() -> Unit>()
    private var cliRuns = 0

    // ---- boot -------------------------------------------------------------------------------------------------------

    private fun boot() {
        listOf(repo, state, records, logs, home).forEach { it.mkdirs() }
        Install.requireFresh()
        layOut()
        writeHarnesses()
        startDaemon()
        connectWindow()
        setUpProject()
        setUpTeam()
        startLead()
    }

    private fun layOut() {
        git(root, "init", "-b", "main", repo.path)
        File(repo, ".gitignore").writeText(COMMITTED_GITIGNORE)
        File(repo, "README.md").writeText(COMMITTED_README)
        File(repo, DAEMON_MAIN).apply { parentFile.mkdirs() }.writeText(COMMITTED_DAEMON_MAIN)
        git(repo, "add", ".gitignore", "README.md", DAEMON_MAIN)
        git(repo, "commit", "-m", "owner base")
        git(repo, "branch", FEATURE)
        git(repo, "worktree", "add", preview.path, FEATURE)
        File(preview, "FEATURE.md").writeText("work on $FEATURE\n")
        git(preview, "add", "FEATURE.md")
        git(preview, "commit", "-m", "feature work")
        // The owner's uncommitted TRACKED edits, in the main checkout.
        File(repo, ".gitignore").appendText("local.properties\n")
        File(repo, DAEMON_MAIN).appendText("// the owner's local edit, never committed\n")
        val dirt = git(repo, "status", "--porcelain").lines()
        check(" M .gitignore" in dirt && " M $DAEMON_MAIN" in dirt) { "the main checkout is not dirty the way the owner's is: $dirt" }
        check(git(repo, "rev-parse", "HEAD") != git(preview, "rev-parse", "HEAD")) { "main and $FEATURE must differ" }
    }

    private fun writeHarnesses() {
        val argfile = File(root, "recorder.args")
        argfile.writeText("-cp\n${argQuote(Smoke.testClasspath)}\n${RecordingHarness::class.java.name}\n")
        val dir = File(state, "harnesses").apply { mkdirs() }
        fun descriptor(id: String, mode: String, models: List<Pair<String, String>>) = buildJsonObject {
            put("id", id)
            put("name", "Owner smoke $id")
            put("command", Smoke.java.path)
            put("args", buildJsonArray {
                add("@${argfile.path}")
                listOf("--harness", id, "--records", records.path, "--mode", mode).forEach { add(it) }
                add(buildJsonArray { add("--resume"); add("{sessionId}") })
                add(buildJsonArray { add("--model"); add("{model}") })
            })
            // SESSION_RESUME + a `session` record: a session must be resumable for mail to reach it (MailRouter).
            put("capabilities", buildJsonArray { add("MODEL_SELECTION"); add("SESSION_RESUME") })
            put("output", buildJsonObject {
                put("format", "jsonl")
                put("map", buildJsonArray {
                    add(rule("session", "session", "id" to "id"))
                    add(rule("text", "text", "text" to "text"))
                })
            })
            put("models", buildJsonObject {
                put("list", buildJsonArray {
                    models.forEach { (provider, model) -> add(buildJsonObject { put("provider", provider); put("id", model) }) }
                })
            })
        }
        File(dir, "$STUB_A.json").writeText(
            descriptor(STUB_A, RecordingHarness.MODE_WORKER, listOf("stub" to PIN_MODEL, ROUNDTRIP_PROVIDER to ROUNDTRIP_MODEL)).toString(),
        )
        File(dir, "$STUB_B.json").writeText(descriptor(STUB_B, RecordingHarness.MODE_WORKER, listOf("zeta" to ROLE_MODEL)).toString())
        File(dir, "$STUB_LEAD.json").writeText(descriptor(STUB_LEAD, RecordingHarness.MODE_LEAD, listOf("stub" to LEAD_MODEL)).toString())
    }

    private fun startDaemon() {
        val requested = freePort()
        val argfile = File(root, "daemon.args")
        argfile.writeText(
            listOf(
                "-Djava.awt.headless=true",
                "\"-Duser.home=${home.path.replace('\\', '/')}\"",
                "-cp",
                argQuote(Smoke.daemonClasspath),
                DAEMON_MAIN_CLASS,
            ).joinToString("\n"),
        )
        val log = File(logs, "daemon.log")
        val builder = ProcessBuilder(Smoke.java.path, "@${argfile.path}")
            .directory(preview)
            .redirectErrorStream(true)
            .redirectOutput(log)
        builder.environment().apply {
            scrub(this)
            put("INFANTRY_PORT", requested.toString())
            put("INFANTRY_PROVIDER", "stub")
            put("INFANTRY_STATE_DIR", state.path)
            put("INFANTRY_HOME", state.path)
            put("INFANTRY_LIMITS_LIVE", "false")
            put("INFANTRY_HEADLESS", "true")
            put("INFANTRY_MCP_LINEAGE_DIR", File(root, "mcp-lineage").path)
        }
        val process = builder.start()
        daemon = process
        val boot = awaitLog(log, process, BOOT_LINE, "the daemon's boot line")
        port = boot.groupValues[3].toInt()
        wsUrl = "ws://${boot.groupValues[2]}:$port/ws"
        check(boot.groupValues[1] == "stub") { "the daemon runs provider ${boot.groupValues[1]}, not the stub" }
        check(port !in OWNER_PORTS) { "the daemon bound $port, one of the owner's ports $OWNER_PORTS" }
        awaitLog(log, process, HEADLESS_LINE, "the headless line (no window)")
    }

    private fun connectWindow() {
        window.connect(wsUrl, null)
        until("the owner's window connected, roles loaded") { it.connection.isLive && it.roles.any { r -> r.id == PM_ROLE } }
    }

    /**
     * BUG-BASE-087: a start with no base is refused, so the rig does what the owner does in the window: a project on the
     * repo, and its Git tab's base branch set to [FEATURE]. The team below joins it, so every ticket the PM files
     * (MCP or CLI, through its INFANTRY_PROJECT_ID) is in it and cut from [FEATURE]'s tip, not from main's HEAD.
     */
    private fun setUpProject() {
        sent("save the project", runBlocking { window.saveProject(PROJECT_NAME) })
        projectId = until("the project is listed") { s -> s.projects.any { it.name == PROJECT_NAME } }.projects.single { it.name == PROJECT_NAME }.id
        sent("select the project", runBlocking { window.selectProject(projectId) })
        sent("set its directory", runBlocking { window.saveProjectDirectory(projectId, repo.path) })
        until("the project's directory is stored") { s -> s.projects.single { it.id == projectId }.directory.isNotEmpty() }
        sent("set its base branch", runBlocking { window.setProjectBaseBranch(projectId, FEATURE) })
        until("the project's base branch is $FEATURE") { s -> s.projects.single { it.id == projectId }.baseBranch == FEATURE }
    }

    private fun setUpTeam() {
        sent("bind backend", runBlocking { window.saveRoleBinding("backend", STUB_B, ROLE_MODEL, null) })
        sent("bind fullstack", runBlocking { window.saveRoleBinding("fullstack", STUB_B, ROLE_MODEL, null) })
        sent("save the PM", runBlocking { window.saveProfile(AgentProfile(id = LEAD_PROFILE, name = "Owner PM", role = PM_ROLE, provider = STUB_LEAD, projectId = projectId)) })
        sent("save backend", runBlocking { window.saveProfile(AgentProfile(id = "owner-backend", name = "Backend", role = "backend", projectId = projectId)) })
        sent("save fullstack", runBlocking { window.saveProfile(AgentProfile(id = "owner-fullstack", name = "Fullstack", role = "fullstack", projectId = projectId)) })
        until("the team is stored (3 profiles, 2 role bindings)") { s ->
            s.profiles.mapNotNull { it.id }.containsAll(listOf(LEAD_PROFILE, "owner-backend", "owner-fullstack")) &&
                s.roleBindings["backend"]?.provider == STUB_B && s.roleBindings["fullstack"]?.provider == STUB_B
        }
    }

    private fun startLead() {
        sent("start the PM session", runBlocking { window.spawnAs(LEAD_PROFILE, "Owner smoke: you are the PM of this board.") })
        lead = awaitRecording("the PM session's first run (harness $STUB_LEAD)") {
            it.mode == RecordingHarness.MODE_LEAD && it.harness == STUB_LEAD
        }
        check(lead.env["INFANTRY_ROLE"] == PM_ROLE) { "the PM session runs role ${lead.env["INFANTRY_ROLE"]}: $lead" }
        agentEnv(lead) // refuses a PM env that does not point at THIS daemon
        lead.env["INFANTRY_PROJECT_ID"]?.let { project -> runBlocking { window.selectProject(project) } }
    }

    // ---- the agent side ---------------------------------------------------------------------------------------------

    /**
     * The env an agent's tools inherit from [session] (its harness's env as the stub recorded it). Refused unless it
     * points at THIS rig's daemon: a CLI without INFANTRY_URL would talk to the owner's daemon on 8787.
     */
    fun agentEnv(session: Recording): Map<String, String> {
        val env = session.env
        check(env["INFANTRY_URL"] == wsUrl) {
            "refusing an agent env that does not point at this rig's daemon (INFANTRY_URL=${env["INFANTRY_URL"]}, rig=$wsUrl)"
        }
        check(!env["INFANTRY_BIN"].isNullOrBlank()) { "the session env names no INFANTRY_BIN: $session" }
        return env
    }

    /** The PM's MCP: the installed `infantry mcp serve`, launched as a harness launches it, with the PM's env. */
    fun pm(): McpStdio = pmClient ?: openMcp(lead, "pm").also { pmClient = it }

    /** `infantry mcp serve` for [session], from [InfantryMcp.launch] on its env; refused when it is not the installed one. */
    fun openMcp(session: Recording, name: String): McpStdio {
        val env = agentEnv(session)
        val launch = InfantryMcp.launch(env)
        check(launch.argv.any { File(it).parentFile?.canonicalFile == Install.bin }) {
            "the MCP entry ${launch.argv} does not run the installed launcher in ${Install.bin}"
        }
        val client = McpStdio.start(launch.argv, File(session.cwd), name, File(logs, "mcp-$name.err")) { e ->
            scrub(e)
            e.putAll(env)
            e.putAll(launch.env)
        }
        closers += { client.close() }
        Install.requireTools(client.toolNames(), launch.argv.joinToString(" "))
        return client
    }

    data class CliRun(val args: List<String>, val exit: Int, val out: String, val err: String) {
        override fun toString() = "infantry ${args.joinToString(" ")} -> exit $exit; out: ${out.trim()}; err: ${err.trim()}"
    }

    /** `infantry <args>` as the PM types it: resolved through its session PATH (the installed bin is first). */
    fun cli(vararg args: String, timeoutMs: Long = CLI_TIMEOUT_MS): CliRun {
        val env = agentEnv(lead)
        val n = ++cliRuns
        val out = File(logs, "cli-$n.out")
        val err = File(logs, "cli-$n.err")
        val argv = if (Smoke.windows) listOf("cmd", "/c", "infantry") + args else listOf("sh", "-c", "exec infantry \"\$@\"", "infantry") + args
        val builder = ProcessBuilder(argv).directory(File(lead.cwd)).redirectOutput(out).redirectError(err)
        builder.environment().apply {
            scrub(this)
            putAll(env)
        }
        val process = builder.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw AssertionError("infantry ${args.joinToString(" ")} did not finish within ${timeoutMs}ms")
        }
        return CliRun(args.toList(), process.exitValue(), out.readText(), err.readText())
    }

    /** `where infantry` (Windows) / `command -v infantry` with the PM's env: which launcher an agent's shell runs. */
    fun whichInfantry(): List<String> {
        val env = agentEnv(lead)
        val argv = if (Smoke.windows) listOf("cmd", "/c", "where", "infantry") else listOf("sh", "-c", "command -v infantry")
        val builder = ProcessBuilder(argv).directory(File(lead.cwd)).redirectErrorStream(true)
        builder.environment().apply {
            scrub(this)
            putAll(env)
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---- what the owner and the stubs see ---------------------------------------------------------------------------

    /** Waits until the owner's window state meets [cond]. */
    fun until(what: String, timeoutMs: Long = WAIT_MS, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            window.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        val s = window.state.value
        throw AssertionError("the owner's window never showed: $what; connection=${s.connection} notices=${s.notices.takeLast(5)}")
    }

    /** The card the owner's window shows for [ticketId], once it meets [cond]. The board is reloaded now and then. */
    fun board(ticketId: String, what: String, timeoutMs: Long = WAIT_MS, cond: (Ticket) -> Boolean): Ticket {
        val project = lead.env["INFANTRY_PROJECT_ID"]
        val end = System.currentTimeMillis() + timeoutMs
        var reloadAt = System.currentTimeMillis() + RELOAD_MS
        while (System.currentTimeMillis() < end) {
            window.state.value.tickets.firstOrNull { it.id == ticketId }?.takeIf(cond)?.let { return it }
            if (project != null && System.currentTimeMillis() >= reloadAt) {
                runBlocking { window.selectProject(project) }
                reloadAt = System.currentTimeMillis() + RELOAD_MS
            }
            Thread.sleep(25)
        }
        val s = window.state.value
        val card = s.tickets.firstOrNull { it.id == ticketId }
        throw AssertionError(
            "the owner's window never showed ticket $ticketId $what; it shows: " +
                (card?.let { "status=${it.status} session=${it.sessionId} branch=${it.branch} start=${it.start}" } ?: "no such card") +
                "; notices=${s.notices.takeLast(5)}",
        )
    }

    class Recording(val file: File, private val props: Map<String, String>) {
        operator fun get(key: String): String? = props[key]
        val harness: String get() = props["harness"].orEmpty()
        val mode: String get() = props["mode"].orEmpty()
        val model: String get() = props["model"].orEmpty()
        val modelProvider: String get() = props["modelProvider"].orEmpty()
        val sessionId: String get() = props["sessionId"].orEmpty()
        val cwd: String get() = props["cwd"].orEmpty()
        val startedAt: Long get() = props["startedAt"]?.toLongOrNull() ?: 0
        val env: Map<String, String> get() = props.filterKeys { it.startsWith("env.") }.mapKeys { it.key.removePrefix("env.") }
        val prompt: String get() = File(file.parentFile, file.name.removeSuffix(".properties") + ".prompt.txt").readText()

        /** What the worker wrote after its report to the starter (pm-roundtrip), if it got that far. */
        val reply: Map<String, String>?
            get() = File(file.parentFile, file.name.removeSuffix(".properties") + ".reply.properties").takeIf { it.isFile }?.let(::load)

        override fun toString() =
            "${file.name}: harness=$harness model=$model modelProvider=$modelProvider mode=$mode session=$sessionId cwd=$cwd"
    }

    /** Every stub run so far, oldest first. */
    fun recordings(): List<Recording> =
        records.listFiles { f -> f.isFile && f.name.endsWith(".properties") && !f.name.endsWith(".reply.properties") }
            .orEmpty().map { Recording(it, load(it)) }.sortedBy { it.startedAt }

    fun awaitRecording(what: String, timeoutMs: Long = WAIT_MS, cond: (Recording) -> Boolean): Recording {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            recordings().firstOrNull(cond)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("no stub recording for $what; recorded: ${recordings()}; daemon log: ${daemonLogTail()}")
    }

    data class Worktree(val path: File, val head: String, val branch: String?)

    /** `git worktree list --porcelain` of the main repo, paths canonical. */
    fun worktrees(): List<Worktree> = git(repo, "worktree", "list", "--porcelain").split(Regex("\\r?\\n\\r?\\n"))
        .mapNotNull { block ->
            val lines = block.lines().map { it.trim() }
            val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ") ?: return@mapNotNull null
            Worktree(
                File(path).canonicalFile,
                lines.firstOrNull { it.startsWith("HEAD ") }?.removePrefix("HEAD ").orEmpty(),
                lines.firstOrNull { it.startsWith("branch ") }?.removePrefix("branch "),
            )
        }

    /** The commit a start note (`cut from <ref> <sha8>`) names, resolved in the main checkout, where it was cut. */
    fun commitOfNote(note: String): String {
        val match = NOTE.matchEntire(note.trim()) ?: throw AssertionError("not a start note: '$note'")
        val (ref, short) = match.destructured
        val commit = git(repo, "rev-parse", "$ref^{commit}").trim()
        if (!commit.startsWith(short)) throw AssertionError("the note says $ref is $short, the main checkout says $commit")
        return commit
    }

    /** `tickets.started_by` as the daemon stored it (what `to: "starter"` mail is routed by). */
    fun startedByInDb(ticketId: String): String? =
        DriverManager.getConnection("jdbc:sqlite:${File(state, "infantry.db").path}").use { db ->
            db.prepareStatement("SELECT started_by FROM tickets WHERE id = ?").use { st ->
                st.setString(1, ticketId)
                st.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }

    fun daemonLogTail(lines: Int = 20): String =
        runCatching { File(logs, "daemon.log").readLines().takeLast(lines).joinToString(" | ") }.getOrDefault("(no log)")

    /** `git <args>` in [dir], this rig's identity, no inherited GIT_* env. Fails loudly; output kept as printed. */
    fun git(dir: File, vararg args: String): String {
        val err = Files.createTempFile(logs.toPath(), "git", ".err").toFile()
        // The user's own git config otherwise applies (as it does to the daemon's git), minus signing and hooks.
        val argv = listOf(
            "git", "-c", "user.name=Owner Smoke", "-c", "user.email=owner-smoke@example.invalid",
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=${File(root, "no-hooks").path.replace('\\', '/')}",
        ) + args
        val builder = ProcessBuilder(argv).directory(dir).redirectError(err)
        scrub(builder.environment())
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(GIT_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("git ${args.joinToString(" ")} in $dir timed out")
        }
        val code = process.exitValue()
        val stderr = err.readText()
        err.delete()
        if (code != 0) throw AssertionError("git ${args.joinToString(" ")} in $dir failed ($code): $stderr")
        return out.trimEnd()
    }

    // ---- teardown ---------------------------------------------------------------------------------------------------

    override fun close() {
        runCatching { File(records, RecordingHarness.RELEASE_ALL).writeText("done") }
        closers.asReversed().forEach { runCatching { it() } }
        runCatching { window.disconnect() }
        scope.cancel()
        daemon?.let { process ->
            daemonDescendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
            runCatching { process.outputStream.close() } // the headless daemon's stop signal
            if (!process.waitFor(DAEMON_STOP_S, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(DAEMON_STOP_S, TimeUnit.SECONDS)
            }
            daemonDescendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
        keepEvidence()
        repeat(DELETE_TRIES) {
            if (!root.exists() || root.deleteRecursively()) return
            Thread.sleep(500)
        }
        System.err.println("[owner-smoke] could not delete $root (a process may still hold a file)")
    }

    /** Copies the logs and stub recordings to `<reportDir>/<scenario>/` (QA's evidence), replacing an older copy. */
    private fun keepEvidence() {
        val target = File(Smoke.reportDir ?: return, name)
        runCatching {
            target.deleteRecursively()
            logs.copyRecursively(File(target, "logs"), overwrite = true)
            records.copyRecursively(File(target, "records"), overwrite = true)
            listOf("daemon.args", "recorder.args").forEach { f -> File(root, f).takeIf { it.isFile }?.copyTo(File(target, f), overwrite = true) }
            File(state, "harnesses").takeIf { it.isDirectory }?.copyRecursively(File(target, "harnesses"), overwrite = true)
        }
    }

    companion object {
        const val FEATURE = "feature-agenthq-mvp"
        const val PROJECT_NAME = "Owner smoke"
        const val PM_ROLE = "pm"
        const val LEAD_PROFILE = "owner-pm"

        /** The pins under test. */
        const val STUB_A = "stub-a"
        const val PIN_MODEL = "m1"

        /** pm-roundtrip's pin: a model of an upstream provider, so harness, provider and model are three values. */
        const val ROUNDTRIP_PROVIDER = "acme"
        const val ROUNDTRIP_MODEL = "acme/m2"

        /** What the backend and fullstack roles run by default: never the pin, so a fallback shows. */
        const val STUB_B = "stub-b"
        const val ROLE_MODEL = "zeta/m9"

        const val STUB_LEAD = "stub-lead"
        const val LEAD_MODEL = "lead-1"

        val OWNER_PORTS = setOf(8787, 8788)

        const val DAEMON_MAIN = "superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/DaemonMain.kt"
        const val COMMITTED_GITIGNORE = ".worktrees/\nbuild/\n"
        const val COMMITTED_README = "# owner smoke\n"
        const val COMMITTED_DAEMON_MAIN = "package com.superiorsoftllc.infantry.daemon\n\nfun main() {}\n"

        internal const val DAEMON_MAIN_CLASS = "com.superiorsoftllc.infantry.ui.RealDaemonMainKt"
        internal val BOOT_LINE = Regex("""\[infantry-ui] daemon provider=(\S+) at ws://([^:/\s]+):(\d+)/ws""")
        internal val HEADLESS_LINE = Regex("""\[infantry-ui] headless""")
        val NOTE = Regex("""cut from (\S+) ([0-9a-f]{7,40})(?: \([^)]+\))?""")

        private const val WAIT_MS = 90_000L
        private const val RELOAD_MS = 3_000L
        private const val CLI_TIMEOUT_MS = 120_000L
        private const val GIT_TIMEOUT_S = 60L
        private const val DAEMON_STOP_S = 20L
        private const val DELETE_TRIES = 6

        /** Opens a rig for scenario [name], runs [body] on it, and always tears it down (evidence kept). */
        fun <T> use(name: String, body: (OwnerRig) -> T): T {
            val root = Files.createTempDirectory("infantry-owner-$name-").toFile().canonicalFile
            val rig = OwnerRig(name, root)
            try {
                rig.boot()
                return body(rig)
            } catch (failure: Throwable) {
                System.err.println("[owner-smoke] $name failed; daemon log tail: ${rig.daemonLogTail()}")
                throw failure
            } finally {
                rig.close()
            }
        }

        /** Removes every GIT_* and INFANTRY_* key: nothing of this JVM's own git or Infantry setup reaches a child. */
        fun scrub(env: MutableMap<String, String>) {
            env.keys.filter { key -> key.uppercase().let { it.startsWith("GIT_") || it.startsWith("INFANTRY_") } }.forEach { env.remove(it) }
        }

        fun load(file: File): Map<String, String> =
            Properties().apply { file.inputStream().use { load(it) } }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }

        private fun sent(what: String, result: CommandResult) {
            if (result !is CommandResult.Sent) throw AssertionError("the owner's window could not $what: $result")
        }

        /** One descriptor `jsonl` rule: a stdout record whose `kind` is [kind] becomes [event], [fields] = event <- record. */
        private fun rule(kind: String, event: String, vararg fields: Pair<String, String>) = buildJsonObject {
            put("match", buildJsonObject { put("kind", kind) })
            put("event", event)
            put("fields", buildJsonObject { fields.forEach { (to, from) -> put(to, from) } })
        }

        internal fun freePort(): Int {
            repeat(20) {
                val port = ServerSocket(0).use { it.localPort }
                if (port !in OWNER_PORTS) return port
            }
            throw AssertionError("no free port outside $OWNER_PORTS")
        }

        /** One classpath argument for a java @argfile: quoted, forward slashes (a backslash escapes inside quotes). */
        internal fun argQuote(classpath: List<File>): String =
            "\"" + classpath.joinToString(File.pathSeparator) { it.absolutePath.replace('\\', '/') } + "\""

        internal fun awaitLog(log: File, process: Process, line: Regex, what: String, timeoutMs: Long = 120_000L): MatchResult {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val text = runCatching { log.readText() }.getOrDefault("")
                line.find(text)?.let { return it }
                if (!process.isAlive) {
                    throw AssertionError("the daemon exited (${process.exitValue()}) before $what: ${text.lines().takeLast(25).joinToString(" | ")}")
                }
                Thread.sleep(100)
            }
            throw AssertionError("no $what within ${timeoutMs}ms: ${runCatching { log.readLines().takeLast(25) }.getOrNull()}")
        }
    }
}

/** The Gradle task's inputs (`ownerSmoke` sets them), and the JVM the children run on. */
internal object Smoke {
    val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val java: File = File(System.getProperty("java.home"), if (windows) "bin/java.exe" else "bin/java")

    /** infantryUi's MAIN runtime classpath: what `runRealDaemon` runs RealDaemonMainKt on. */
    val daemonClasspath: List<File> by lazy { classpathFrom("ownersmoke.daemonClasspathFile") }

    /** infantryUi's TEST runtime classpath: what [RecordingHarness] runs on. */
    val testClasspath: List<File> by lazy { classpathFrom("ownersmoke.testClasspathFile") }

    /** `:superiorSoftLibrary:infantry`'s installDist output. */
    val install: File by lazy { File(required("ownersmoke.install")).canonicalFile }

    /** Where each scenario's logs and recordings are kept after the run. */
    val reportDir: File? by lazy { System.getProperty("ownersmoke.reportDir")?.takeIf { it.isNotBlank() }?.let(::File) }

    private fun classpathFrom(property: String): List<File> =
        File(required(property)).readLines().map { it.trim() }.filter { it.isNotEmpty() }.map(::File)

    private fun required(property: String): String = System.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: throw AssertionError("system property $property is not set: run ./gradlew :superiorSoftLibrary:infantryUi:ownerSmoke")
}

/**
 * S5 / BUG-BUILD-1: the install agents run must be the one the daemon was built with. A launcher whose jar differs from
 * the daemon's (older, or just different), or an MCP server that lacks the ticket tools, is a STALE INSTALL: the rig
 * refuses to run on one, so a green smoke can never come from an outdated `infantry`.
 */
internal object Install {
    val home: File get() = Smoke.install
    val bin: File get() = File(home, "bin").canonicalFile

    val REQUIRED_TOOLS = listOf(
        "infantry_ticket_create",
        "infantry_ticket_update",
        "infantry_ticket_get",
        "infantry_ticket_start",
        "infantry_message",
        "infantry_inbox", // MAIL-MCP-1: the PM reads its reply through its own MCP
        "infantry_read",
    )

    /** The infantry jar on the daemon's classpath (`infantry/build/libs/infantry*.jar`). */
    fun daemonJar(): File = Smoke.daemonClasspath.firstOrNull { f ->
        f.isFile && f.extension == "jar" && f.name.startsWith("infantry") && f.parentFile?.name == "libs" &&
            f.parentFile?.parentFile?.parentFile?.name == "infantry"
    } ?: throw AssertionError("no infantry jar on the daemon classpath: ${Smoke.daemonClasspath.map { it.name }}")

    /** Every reason [install] is not the launcher the daemon's agents must run; empty = fresh. */
    fun staleness(install: File, daemonJar: File = daemonJar()): List<String> = buildList {
        val launchers = listOf("infantry.bat", "infantry")
        if (launchers.none { File(install, "bin/$it").isFile }) add("no launcher in ${File(install, "bin")}")
        val jar = File(install, "lib/${daemonJar.name}")
        when {
            !jar.isFile -> add("no ${daemonJar.name} in ${jar.parentFile}")
            !jar.readBytes().contentEquals(daemonJar.readBytes()) -> add(
                "its ${jar.name} (${jar.length()} bytes, modified ${jar.lastModified()}) differs from the daemon's " +
                    "${daemonJar.path} (${daemonJar.length()} bytes, modified ${daemonJar.lastModified()})" +
                    if (jar.lastModified() < daemonJar.lastModified()) " and is older" else "",
            )
        }
    }

    fun requireFresh(install: File = home) {
        val reasons = staleness(install)
        if (reasons.isNotEmpty()) {
            throw AssertionError(
                "STALE INSTALL at $install: ${reasons.joinToString("; ")}. Agents would run an older infantry than the " +
                    "daemon (BUG-BUILD-1); rebuild it with :superiorSoftLibrary:infantry:installDist.",
            )
        }
    }

    fun requireTools(tools: List<String>, launcher: String) {
        val missing = REQUIRED_TOOLS.filterNot { it in tools }
        if (missing.isNotEmpty()) {
            throw AssertionError("STALE INSTALL: `$launcher` serves no $missing (it lists $tools). Agents cannot work tickets or read their mail with it.")
        }
    }
}
