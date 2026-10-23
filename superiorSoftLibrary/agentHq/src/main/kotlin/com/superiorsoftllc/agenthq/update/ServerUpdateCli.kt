package com.superiorsoftllc.agenthq.update

import com.superiorsoftllc.agenthq.daemon.DaemonPort
import com.superiorsoftllc.agenthq.daemon.RemoteAccess
import com.superiorsoftllc.agenthq.daemon.UpdateApplyReply
import com.superiorsoftllc.agenthq.daemon.UpdateApplyRequest
import com.superiorsoftllc.agenthq.session.agentHqSetting
import com.superiorsoftllc.agenthq.session.stateDir
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/** SERVER-UPDATE-2: where `infantry update` sends its check and apply: the running daemon, or this process alone. */
interface UpdateBackend {
    val label: String
    fun check(): ServerUpdateStatus
    fun apply(confirm: Boolean): UpdateApplyReply
}

/** No daemon is running: check and stage in this process. The next start of the daemon runs the staged version. */
class DirectBackend(private val service: ServerUpdateService) : UpdateBackend {
    override val label = "no daemon is running; updating the install on disk"
    override fun check() = service.check()
    override fun apply(confirm: Boolean): UpdateApplyReply = when (val r = service.apply(confirm)) {
        is ApplyResult.Staged -> UpdateApplyReply("staged", version = r.version, message = "start the daemon again to run ${r.version}")
        ApplyResult.NothingToApply -> UpdateApplyReply("nothing_to_apply", message = "no newer version is known")
        is ApplyResult.AgentsRunning -> UpdateApplyReply("agents_running", running = r.running)
        is ApplyResult.Unsupported -> UpdateApplyReply("unsupported", message = r.instructions)
        is ApplyResult.Failed -> UpdateApplyReply("failed", message = r.message)
    }
}

/** A daemon is running here: ask it, so the restart path is the one a client's Update button takes. */
class HttpBackend(private val base: String, private val token: String?) : UpdateBackend {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    override val label = "asking the running daemon at $base"

    private fun post(path: String, body: String, timeout: Duration): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    override fun check(): ServerUpdateStatus {
        val r = post("/update/check", "{}", Duration.ofSeconds(90))
        if (r.statusCode() == 401) error("the daemon refused the token; set AGENTHQ_TOKEN")
        if (r.statusCode() != 200) error("the daemon answered HTTP ${r.statusCode()}")
        return json.decodeFromString(ServerUpdateStatus.serializer(), r.body())
    }

    override fun apply(confirm: Boolean): UpdateApplyReply {
        val r = post("/update/apply", json.encodeToString(UpdateApplyRequest.serializer(), UpdateApplyRequest(confirm)), Duration.ofMinutes(15))
        return runCatching { json.decodeFromString(UpdateApplyReply.serializer(), r.body()) }
            .getOrElse { UpdateApplyReply("failed", message = "the daemon answered HTTP ${r.statusCode()}") }
    }
}

/**
 * SERVER-UPDATE-2: the commands behind `bin/infantry`:
 *
 *  - `update [--check-only] [--yes]`  check, show the notes, ask, stage; exit 0 done / up to date, 10 an update exists
 *    (`--check-only`), 3 this install cannot update itself (Docker, dev), 1 failed.
 *  - `rollback`  switch `current` back to the version before it.
 *  - `adopt <appHome>`  first run from a freshly unpacked archive: copy it to `<root>/versions/<v>` and make it current.
 *
 * The update token is read from `AGENTHQ_UPDATE_TOKEN` / `INFANTRY_UPDATE_TOKEN` by [ServerUpdateService]; it is never
 * printed, and nothing here writes it anywhere.
 */
object ServerUpdateCli {
    fun update(args: List<String>, backend: UpdateBackend, out: PrintStream, ask: (String) -> Boolean): Int {
        val checkOnly = "--check-only" in args
        val yes = "--yes" in args || "-y" in args
        out.println("Infantry AI server update: ${backend.label}")
        val status = try { backend.check() } catch (e: Exception) { out.println("Could not check: ${e.message}"); return 1 }
        out.println("Running ${status.version} (${status.distribution}, contract ${status.contract}).")
        status.error?.let { out.println("Check failed: $it"); return 1 }
        val offer = status.available ?: run {
            out.println(status.disabledReason?.let { "Updates are off: $it." } ?: "Up to date.")
            return if (status.enabled) 0 else 3
        }
        out.println("Update available: ${offer.version}")
        offer.notes.lineSequence().take(12).forEach { out.println("  $it") }
        if (!status.canSelfUpdate) {
            status.instructions?.let { out.println(it) }
            return 3
        }
        if (checkOnly) return 10
        if (!yes && !ask("Update to ${offer.version}?")) { out.println("Not updated."); return 0 }
        var reply = try { backend.apply(false) } catch (e: Exception) { out.println("Update failed: ${e.message}"); return 1 }
        if (reply.result == "agents_running") {
            val n = reply.running ?: status.runningAgents
            if (!yes && !ask("$n agent${if (n == 1) " is" else "s are"} working. Update anyway?")) { out.println("Not updated."); return 0 }
            reply = try { backend.apply(true) } catch (e: Exception) { out.println("Update failed: ${e.message}"); return 1 }
        }
        return when (reply.result) {
            "staged" -> { out.println("Updated to ${reply.version}: ${reply.message ?: "restart the daemon to run it"}."); 0 }
            "unsupported" -> { out.println(reply.message ?: "This install cannot update itself."); 3 }
            "nothing_to_apply" -> { out.println("Nothing to apply."); 0 }
            else -> { out.println("Update failed: ${reply.message ?: reply.result}"); 1 }
        }
    }

    fun rollback(install: ServerInstall, out: PrintStream): Int {
        val before = install.current()
        val now = install.rollback() ?: run { out.println("Nothing to roll back to."); return 1 }
        out.println("Rolled back from ${before ?: "?"} to $now. Restart the daemon to run it.")
        return 0
    }

    /** The backend for this machine: the daemon on its recorded port when it answers `/health`, else this process alone. */
    fun backendFor(service: ServerUpdateService, state: File = stateDir()): UpdateBackend {
        val port = File(state, DaemonPort.FILE_NAME).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?: agentHqSetting(DaemonPort.PORT_ENV)?.toIntOrNull()
        if (port != null) {
            val base = "http://127.0.0.1:$port"
            val alive = runCatching {
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                    HttpRequest.newBuilder(URI.create("$base/health")).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode() == 200
            }.getOrDefault(false)
            if (alive) return HttpBackend(base, RemoteAccess.clientToken())
        }
        return DirectBackend(service)
    }
}

fun main(args: Array<String>) {
    val out = System.out
    val rest = args.drop(1)
    val code = when (args.firstOrNull()) {
        "update" -> {
            val service = ServerUpdateService.fromEnvironment(runningAgents = { 0 })
            ServerUpdateCli.update(rest, ServerUpdateCli.backendFor(service), out) { question ->
                out.print("$question [y/N] ")
                out.flush()
                readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
            }
        }
        "rollback" -> installRoot()?.let { ServerUpdateCli.rollback(ServerInstall(it), out) } ?: run { out.println("No managed install folder (AGENTHQ_INSTALL_ROOT)."); 1 }
        "adopt" -> {
            val home = rest.firstOrNull()?.let(::File)?.takeIf { it.isDirectory }
            val version = home?.let { runCatching { File(it, "VERSION").readText().trim() }.getOrNull() }?.takeIf { SemVer.parse(it) != null }
            if (home == null || version == null) { out.println("adopt needs the unpacked server folder (with a VERSION file)."); 1 }
            else {
                val root = home.canonicalFile.parentFile
                ServerInstall(root).adopt(home, version)
                out.println("Adopted $version into ${File(root, "versions")}; start it with ${File(root, "infantry")}")
                0
            }
        }
        else -> { out.println("usage: infantry update [--check-only] [--yes] | rollback"); 2 }
    }
    exitProcess(code)
}

private fun installRoot(): File? = agentHqSetting("AGENTHQ_INSTALL_ROOT")?.let(::File)
