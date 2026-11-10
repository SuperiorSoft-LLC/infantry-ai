// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.RemoteAccess
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runners.RunnerAudit
import com.superiorsoftllc.infantry.runners.RunnerLinkService
import com.superiorsoftllc.infantry.runners.RunnerLinkStore
import com.superiorsoftllc.infantry.runners.RunnerPairLink
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire.CheckRow
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.InetAddress

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): `infantry runner pair|add|list|rotate|detach|audit`. Pairing state lives in the
 * link keystore under `<stateDir>/runner-link` ([RunnerLinkStore]).
 *
 * * `pair --name <n>` (on the RUNNER): prints a one-time code (single use, expires after `limits.runners.pairCodeMin`, at most
 *   `limits.runners.pairAttemptsPerHour` tries) and the link certificate's fingerprint, serves the link until paired or expired.
 * * `add <name> <wss-url> --code <c> --fingerprint <fp>` (on the ORCHESTRATOR): checks the pin, trades the code for the
 *   per-pair credential.
 * * `rotate <name>`: in-band rekey. `detach [<name>]`: with a name, on the orchestrator; without, on the runner.
 * * `check <name> [--project <id>] [--harness <id>]` (RUNNER-CHECK-1): the health check over the link (contract, harness sign-in,
 *   grants, git remote, disk, link), one line per row with the fix under a failing one; exit 1 when any row fails.
 * * `audit [--limit N]`: the runner's audit log of every remote operation.
 *
 * RUNNER-POOL-6a: the transport is the Infantry_RemoteRunner plugin's and only the daemon loads plugins, so `pair`, `add`,
 * `rotate` and `detach <name>` go through the running daemon as the owner-only `runner_admin` frame
 * ([RunnerLinkService.admin]; the CLI presents the owner token from `<stateDir>/owner.token`). `list`, `audit` and the
 * runner-side `detach` read and write the link folder here, as before. Command names, flags and printed text are unchanged.
 */
internal object RunnerCli {
    const val USAGE = "usage: infantry runner connect --name <n> --via \"<command>\" [--host <h>] [--port <p>] | up --name <n> [--host <h>]... [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] | pair --name <n> [--host <h>]... [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] [--grant-on-pair [--grant-harnesses-max <ids>]] | add <name> <wss-url> --code <code> [--fingerprint <fp>] | add '<infantry://runner?...>' [--name <n>] [--grant [--project <id>]] | add --link <link|-> [--name <n>] [--grant [--project <id>]] | list | check <name> [--project <id>] [--harness <id>] | test <name> [--harness <id>] [--model <id>] | rotate <name> | detach [<name>] | audit [--limit N]"

    /** RUNNER-ADD-LINK-1: what the usage error adds when a pasted link looks cut short (a PowerShell `&` eats the rest). */
    private const val QUOTE_HINT = "Quote the whole link, e.g. infantry runner add 'infantry://runner?v=1&...'. A shell without quotes cuts it at &."

    private val json = Json { ignoreUnknownKeys = true }

    /** The words after `runner` and the flags the CLI parser took for it. */
    data class Args(val words: List<String>, val name: String? = null, val code: String? = null, val fingerprint: String? = null, val limit: String? = null, val allowProjects: String? = null, val allowHarnesses: String? = null, val hosts: String? = null, val link: String? = null, val project: String? = null, val harness: String? = null, val model: String? = null, val grantOnPair: Boolean = false, val grantHarnessesMax: String? = null, val grant: Boolean = false, val runnerPort: String? = null, val via: String? = null) {
        val action: String? get() = words.firstOrNull()?.lowercase()
        val positional: List<String> get() = words.drop(1)
        val flags: Map<String, String>
            get() = listOfNotNull(name?.let { "name" to it }, code?.let { "code" to it }, fingerprint?.let { "fingerprint" to it }, limit?.let { "limit" to it }, allowProjects?.let { "allow-projects" to it }, allowHarnesses?.let { "allow-harnesses" to it }, hosts?.let { "host" to it }, runnerPort?.let { "port" to it }, link?.let { "link" to it }, project?.let { "project" to it }, harness?.let { "harness" to it }, model?.let { "model" to it }, "".takeIf { grantOnPair }?.let { "grant-on-pair" to it }, grantHarnessesMax?.let { "grant-harnesses-max" to it }, "".takeIf { grant }?.let { "grant" to it }, via?.let { "via" to it }).toMap()
    }

    /** RUNNER-LINK-HOST-1: `--port` is a port number or absent. */
    private fun portOk(p: String?): Boolean = p == null || p.toIntOrNull()?.let { it in 1..65535 } == true

    /** The usage error for [args], or null when they are valid. */
    fun usageError(args: Args): String? {
        val positional = args.positional
        val flags = args.flags
        val ok = when (args.action) {
            // RUNNER-GRANT-AT-PAIR-1: `runner pair --grant-on-pair [--grant-harnesses-max <ids>]` (the code carries a one-time grant).
            "pair" -> positional.isEmpty() && !args.name.isNullOrBlank() && flags.keys.all { it in setOf("name", "allow-projects", "allow-harnesses", "host", "port", "grant-on-pair", "grant-harnesses-max") } &&
                !(args.grantOnPair && args.allowProjects != null) && portOk(args.runnerPort)
            // RUNNER-PAIR-1: `runner connect --name <n> --via "<command>" [--host <h>] [--port <p>]`.
            "connect" -> positional.isEmpty() && !args.name.isNullOrBlank() && !args.via.isNullOrBlank() && flags.keys.all { it in setOf("name", "via", "host", "port") } && portOk(args.runnerPort)
            "up" -> positional.isEmpty() && !args.name.isNullOrBlank() && flags.keys.all { it in setOf("name", "allow-projects", "allow-harnesses", "host", "port") } && portOk(args.runnerPort)
            // RUNNER-GRANT-AT-PAIR-1: `--grant` is the owner's confirm for the one grant a link's code may carry.
            "add" -> addUsageOk(args) || (positional.size == 2 && !args.code.isNullOrBlank() && flags.keys.all { it in setOf("code", "fingerprint") })
            "list" -> positional.isEmpty() && flags.isEmpty()
            "rotate" -> positional.size == 1 && flags.isEmpty()
            "detach" -> positional.size <= 1 && flags.isEmpty()
            // RUNNER-CHECK-1: `runner check <name> [--project <id>] [--harness <id>]`.
            "check" -> positional.size == 1 && positional[0].isNotBlank() && flags.keys.all { it in setOf("project", "harness") }
            // RUNNER-TEST-1: `runner test <name> [--harness <id>] [--model <id>]`.
            "test" -> positional.size == 1 && positional[0].isNotBlank() && flags.keys.all { it in setOf("harness", "model") }
            "audit" -> positional.isEmpty() && flags.keys.all { it == "limit" } && (args.limit == null || (args.limit.toIntOrNull() ?: 0) > 0)
            else -> false
        }
        // A cut-short link (a shell ate the rest after `&`) gets the quote hint, not just the usage line.
        if (ok) return null
        return if (positional.any { isLink(it) }) "$USAGE\n$QUOTE_HINT" else USAGE
    }

    /** RUNNER-ADD-LINK-1: `add '<infantry://runner?...>' [--name <n>]` or `add --link <link|-> [--name <n>]`. */
    private fun addUsageOk(args: Args): Boolean {
        val positional = args.positional
        // RUNNER-GRANT-AT-PAIR-1: `--grant [--project <id>]` belongs to the link form (the code decides whether it is allowed).
        val linkFlags = setOf("link", "name", "grant", "project")
        if (args.link != null) {
            return positional.isEmpty() && args.code == null && args.fingerprint == null &&
                args.flags.keys.all { it in linkFlags } && args.link.isNotBlank()
        }
        return positional.size == 1 && isLink(positional[0]) && args.flags.keys.all { it in setOf("name", "grant", "project") }
    }

    suspend fun run(stream: FrameStream, args: Args, out: Appendable, err: Appendable, stateDir: File): Int {
        val positional = args.positional
        val flags = args.flags
        val catalog = limits(stream)
        fun limit(def: LimitDef): Long = catalog[def.key] ?: def.default
        val dir = RunnerLinkStore.dir(stateDir)
        return try {
            when (args.action) {
                "pair" -> pair(
                    stream, flags.getValue("name"), ::limit, out, err, flags["allow-projects"], flags["allow-harnesses"], flags["host"],
                    port = flags["port"], grantOnPair = args.grantOnPair, grantHarnessesMax = args.grantHarnessesMax,
                )
                "up" -> up(stream, flags.getValue("name"), ::limit, out, err, flags["allow-projects"], flags["allow-harnesses"], flags["host"], flags["port"])
                "add" -> add(stream, args, ::limit, out, err)
                // RUNNER-PAIR-1: the daemon runs `runner pair` on the runner through --via and redeems the link; it is never printed.
                "connect" -> {
                    val r = admin(
                        stream, ::limit, "op" to "connect", "name" to flags.getValue("name"), "via" to flags.getValue("via"),
                        "host" to flags["host"], "port" to flags["port"], "orchestratorName" to hostName(),
                        extraSeconds = ADMIN_CALLS * limit(LimitRegistry.RUNNERS_CONNECT_SEC),
                    )
                    if (r.failed) { err.appendLine("error: ${r.error}"); return 1 }
                    if (r["already"] == "true") out.appendLine("runner '${r["name"]}' is already connected at ${r["url"]} (${r["runnerId"]}) and answers; nothing to pair")
                    else out.appendLine("runner '${r["name"]}' paired at ${r["url"]} (${r["runnerId"]}); every project and harness may run there")
                    r["skipped"]?.let { out.appendLine("skipped: $it") }
                    if (r["enabledTurnedOn"] == "true") out.appendLine("runners.enabled is now on")
                    out.appendLine("Next: infantry runner test ${r["name"]}")
                    0
                }
                "list" -> {
                    if (!RunnerLinkStore.exists(stateDir)) { out.appendLine("No runner link: this machine never paired."); return 0 }
                    val store = RunnerLinkStore.open(stateDir)
                    out.appendLine("this machine: ${store.selfId()}")
                    store.orchestrator()?.let { out.appendLine("paired as runner '${it.runnerName}' with orchestrator ${it.name} (${it.id}), credential issued ${iso(it.issuedAt)}") }
                    val runners = store.runners()
                    if (runners.isEmpty()) out.appendLine("paired runners: none")
                    runners.forEach { out.appendLine("runner ${it.name} | ${it.url} | ${it.runnerId} | credential issued ${iso(it.issuedAt)}") }
                    0
                }
                "rotate" -> {
                    val r = admin(stream, ::limit, "op" to "rotate", "name" to positional[0])
                    if (r.failed) { err.appendLine("error: ${r.error}"); return 1 }
                    out.appendLine("runner '${r["name"]}': credential rotated; the old one no longer works")
                    0
                }
                // RUNNER-CHECK-1: one line per health-check row, with the fix under a failing one; exit 1 when any row fails.
                "check" -> {
                    val r = admin(
                        stream, ::limit, "op" to "check", "name" to positional[0], "projectId" to flags["project"], "harness" to flags["harness"],
                        extraSeconds = limit(LimitRegistry.RUNNERS_CHECK_GIT_SEC),
                    )
                    if (r.failed) { err.appendLine("error: ${r.error}"); return 1 }
                    val rows = r.checks()
                    if (rows.isEmpty()) { err.appendLine("error: the daemon returned no check rows"); return 1 }
                    for (row in rows) {
                        out.appendLine("${row.id.padEnd(ID_COLUMN).take(ID_COLUMN)}  ${if (row.ok) "ok" else FAIL}    ${row.detail}")
                        row.fix?.takeIf { !row.ok }?.let { out.appendLine("${" ".repeat(ID_COLUMN + 8)}fix: $it") }
                    }
                    val bad = rows.count { !it.ok }
                    out.appendLine(if (bad == 0) "runner '${positional[0]}': every check passed" else "runner '${positional[0]}': $bad of ${rows.size} checks failed")
                    if (bad > 0) 1 else 0
                }
                // RUNNER-TEST-1: one throwaway turn on the runner, its reply printed; exit 0 only when a reply came back.
                "test" -> test(stream, positional[0], flags["harness"], flags["model"], ::limit, out, err)
                "detach" -> {
                    val name = positional.firstOrNull()
                    if (name != null) {
                        val r = admin(stream, ::limit, "op" to "detach", "name" to name)
                        if (r.failed) { err.appendLine("error: ${r.error}"); return 1 }
                        out.appendLine("runner '$name' detached here; ${r["remote"]}")
                    } else {
                        if (!RunnerLinkStore.exists(stateDir)) { err.appendLine("error: this machine never paired"); return 1 }
                        val store = RunnerLinkStore.open(stateDir)
                        val was = store.orchestrator()
                        if (!store.unpair()) { out.appendLine("this runner is not paired"); return 0 }
                        RunnerAudit(File(dir, RunnerAudit.FILE)).record(OWNER_LOCAL, "detach", "ok: orchestrator ${was?.name} revoked")
                        out.appendLine("orchestrator ${was?.name} (${was?.id}) revoked: its credential no longer works. The daemon stops serving the link on its next check.")
                    }
                    0
                }
                "audit" -> {
                    val shown = flags["limit"]?.toInt() ?: limit(LimitRegistry.RUNNERS_AUDIT_SHOWN).toInt()
                    val entries = RunnerAudit(File(dir, RunnerAudit.FILE)).tail(shown)
                    if (entries.isEmpty()) out.appendLine("The runner audit log is empty.")
                    entries.forEach { e ->
                        out.appendLine(listOfNotNull(iso(e.at), e.who, e.op, e.ticket?.let { "ticket $it" }, e.from?.let { "from $it" }, e.result).joinToString(" | "))
                    }
                    0
                }
                else -> { err.appendLine(USAGE); 2 }
            }
        } catch (e: IllegalArgumentException) {
            err.appendLine("error: ${e.message}")
            1
        } catch (e: IllegalStateException) {
            // The daemon refused the frame (e.g. not an owner connection) or did not answer.
            err.appendLine("error: ${e.message}")
            1
        }
    }

    /** One `runner_admin` answer object; [failed] with [error] when the daemon said `ok: false`. */
    class AdminAnswer(val fields: JsonObject) {
        val failed: Boolean get() = (fields["ok"] as? JsonPrimitive)?.content != "true"
        val error: String get() = this["error"] ?: "the daemon gave no reason"
        operator fun get(key: String): String? = RunnerLinkService.field(fields, key)
        fun list(key: String): List<String> = (fields[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        /** RUNNER-CHECK-1: the check's rows (`runner_admin` op `check`), in the runner's order. */
        fun checks(): List<CheckRow> = (fields[ROWS] as? JsonArray).orEmpty().mapNotNull { e ->
            runCatching { RunnerLinkWire.json.decodeFromString(CheckRow.serializer(), e.toString()) }.getOrNull()
        }
    }

    /** Sends one `runner_admin` request to the daemon; a link call may take `limits.runners.linkCallSeconds`, add makes two. */
    private suspend fun admin(stream: FrameStream, limit: (LimitDef) -> Long, vararg fields: Pair<String, String?>, linkHosts: Int = 0, extraSeconds: Long = 0): AdminAnswer {
        val body = JsonObject(fields.filter { it.second != null }.associate { (k, v) -> k to JsonPrimitive(v) })
        // An `add` from a link may try every host of the link, each bounded by limits.runners.addHostTimeoutSec; a `check` may spend
        // limits.runners.checkGitSec on the runner's git row.
        val wait = maxOf(stream.answerTimeoutMs, (limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) * ADMIN_CALLS + ADMIN_SLACK_SECONDS + extraSeconds + linkHosts * limit(LimitRegistry.RUNNERS_ADD_HOST_TIMEOUT_SEC)) * MS_PER_SECOND)
        val answer = stream.request(Envelope(RunnerLinkService.ADMIN, body.toString()), RunnerLinkService.ADMIN, RunnerLinkService.ADMIN, idempotent = false, timeoutMs = wait)
        return AdminAnswer(json.parseToJsonElement(answer.data).jsonObject)
    }

    /**
     * RUNNER-UP-1: `runner up` on a daemon that is already answering. Paired: says so and stops. A pairing link still live: says
     * "waiting" and issues no new code (each issue is a fresh window for guessing). Otherwise issues the link and returns at once.
     */
    private suspend fun up(stream: FrameStream, name: String, limit: (LimitDef) -> Long, out: Appendable, err: Appendable, allowProjects: String?, allowHarnesses: String?, hosts: String?, port: String? = null): Int {
        // A board-only daemon can pair, but no session would ever run on it: refuse with the mode instead of handing out a link.
        val preview = admin(stream, limit, "op" to "pair_preview")
        if (preview.failed) { err.appendLine("error: ${preview.error}"); return 1 }
        preview["mode"]?.takeIf { it != "runner" && it != "both" }?.let {
            err.appendLine("error: this daemon runs in $it mode; `runner up` needs a runner (or both) daemon: infantry daemon --mode runner")
            return 1
        }
        val status = admin(stream, limit, "op" to "pair_status")
        if (status.failed) { err.appendLine("error: ${status.error}"); return 1 }
        when (status["state"]) {
            "paired" -> { out.appendLine("already paired with ${status["orchestratorName"]}; `infantry runner detach` to pair again"); return 0 }
            "waiting" -> { out.appendLine("waiting: a pairing link was already issued and is not used yet; it expires on its own. For a new one: infantry runner pair --name $name"); return 0 }
        }
        return pair(stream, name, limit, out, err, allowProjects, allowHarnesses, hosts, port = port, wait = false)
    }

    /**
     * RUNNER-TEST-1 (docs/wiki/RunnerPairing-design.md 4, step 6): `infantry runner test <name> [--harness h] [--model m]`. The daemon
     * sends the runner one throwaway turn (`runner_admin` op `test`) and stops and removes it afterwards, so the owner sees the answer
     * that machine's agent CLI actually gave, not just a green check row. A test with a real model harness spends one small turn of
     * that account; `echo` spends nothing (docs/RUNNER-POOL.md section 5).
     */
    private suspend fun test(stream: FrameStream, name: String, harness: String?, model: String?, limit: (LimitDef) -> Long, out: Appendable, err: Appendable): Int {
        val r = admin(
            stream, limit, "op" to "test", "name" to name, "harness" to harness, "model" to model,
            extraSeconds = limit(LimitRegistry.RUNNERS_TEST_TURN_SEC),
        )
        if (r.failed) { err.appendLine("error: ${r.error}"); r.lines(LAST_LINES).takeIf { it.isNotEmpty() }?.let { printLastLines(it, out) }; return 1 }
        val reply = r["reply"]
        val ms = r["ms"]?.toLongOrNull()
        val took = ms?.let(::tookText) ?: "${r["seconds"].orEmpty()} s"
        val which = listOfNotNull(r["harness"]?.takeIf { it.isNotBlank() }, r["model"]?.takeIf { it.isNotBlank() }).joinToString(" ")
        if (reply.isNullOrBlank()) {
            err.appendLine("error: runner '$name'${if (which.isBlank()) "" else " ($which)"} gave no answer in $took")
            r.lines(LAST_LINES).takeIf { it.isNotEmpty() }?.let { printLastLines(it, out) }
            return 1
        }
        out.appendLine("runner '$name'${if (which.isBlank()) "" else " ($which)"} answered in $took:")
        out.appendLine(reply)
        r["cleanup"]?.let { out.appendLine("note: $it") }
        return 0
    }

    /** The transcript tail of a test that got no answer, indented under its note. */
    private fun printLastLines(lines: List<String>, out: Appendable) {
        if (lines.isEmpty()) { out.appendLine("the test session's transcript was empty"); return }
        out.appendLine("last lines of the test session:")
        lines.forEach { out.appendLine("  $it") }
    }

    /** A test turn's duration as the owner reads it: `412 ms` under a second, `3.2 s` above it. */
    private fun tookText(ms: Long): String =
        if (ms < MS_PER_SECOND) "$ms ms" else String.format(java.util.Locale.ROOT, "%.1f s", ms / MS_PER_SECOND.toDouble())

    /** The strings of a JSON array field of an admin answer (the test's `lastLines`). */
    private fun AdminAnswer.lines(key: String): List<String> = (fields[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    /**
     * RUNNER-ADD-LINK-1: `add '<infantry://runner?...>' [--name <n>]` / `add --link <link|->` (one paste), and the older
     * `add <name> <wss-url> --code <c> [--fingerprint <fp>]`. Only the link form can try several hosts and turn `runners.enabled` on.
     */
    private suspend fun add(stream: FrameStream, args: Args, limit: (LimitDef) -> Long, out: Appendable, err: Appendable): Int {
        val positional = args.positional
        val pasted = args.link?.let { flag -> if (flag == "-") readStdin() else flag }
            ?: positional.firstOrNull()?.takeIf { isLink(it) }
        if (pasted == null) {
            // The older form: one name, one URL, the code and the fingerprint by hand.
            val r = admin(
                stream, limit, "op" to "add", "name" to positional[0], "url" to positional[1], "code" to args.code,
                "fingerprint" to args.fingerprint, "orchestratorName" to hostName(),
            )
            if (r.failed) {
                err.appendLine("error: ${r.error}")
                r["presentedFingerprint"]?.takeIf { args.fingerprint == null }?.let {
                    err.appendLine("If it matches, run: infantry runner add ${positional[0]} ${positional[1]} --code ${args.code.orEmpty()} --fingerprint $it")
                }
                return 1
            }
            return reportAdd(r, limit, out)
        }
        if (pasted.isBlank()) {
            err.appendLine("error: --link - read nothing; pipe the link in, e.g. infantry runner add --link - < link.txt")
            return 1
        }
        val parsed = when (val p = RunnerPairLink.parse(pasted)) {
            is RunnerPairLink.Parsed.Ok -> p.link
            is RunnerPairLink.Parsed.Bad -> {
                err.appendLine("error: ${p.reason}")
                if (args.link == null) err.appendLine(QUOTE_HINT)
                return 1
            }
        }
        // RUNNER-GRANT-AT-PAIR-1 (owner D24): the one confirm. The link says its code may grant (grant=1); the owner says yes here,
        // with `--grant` answering for them, and only then does the daemon ask the runner for the project and harnesses.
        val project = args.project?.takeIf { it.isNotBlank() }
        if (args.grant && !parsed.grant) {
            err.appendLine(
                "error: that link's code carries no grant authority (it was not issued with `--grant-on-pair` on the runner), so this " +
                    "pairing may not grant the project; issue the code there with --grant-on-pair and pair again",
            )
            return 1
        }
        var grant = false
        if (parsed.grant) {
            if (project == null) {
                if (args.grant) {
                    err.appendLine("error: --grant needs the project to grant: pass --project <id> (or set ${AccessPolicy.PROJECT_ID_ENV})")
                    return 1
                }
                out.appendLine("This link may grant the project it is added from, but none is known here; pass --project <id> to grant it at pairing.")
            } else {
                val harnesses = localHarnesses(stream, limit)
                val who = projectNameOf(stream, project)?.let { "$it ($project)" } ?: project
                val list = harnesses.ifEmpty { listOf("none installed on this machine") }.joinToString(", ")
                grant = if (args.grant) true else {
                    val console = System.console()
                    console != null && console.readLine("Grant runner ${args.name ?: parsed.name} access to project $who and harnesses $list? [y/N]: ")?.trim().equals("y", true)
                }
                if (!grant) out.appendLine("Pairing without a grant. To grant it, pair a new code with --grant, or grant on the runner: infantry settings set ${RunnerLinkSettings.PROJECTS} '[\"$project\"]'")
            }
        }
        val r = admin(
            stream, limit, "op" to "add", "link" to pasted,
            "name" to args.name?.takeIf { it.isNotBlank() }, "orchestratorName" to hostName(),
            "grant" to grant.takeIf { it }?.toString(), "projectId" to project.takeIf { grant }, linkHosts = parsed.hosts.size,
        )
        if (r.failed) {
            err.appendLine("error: ${r.error}")
            return 1
        }
        r["skipped"]?.let { out.appendLine("skipped: $it") }
        if (r["enabledTurnedOn"] == "true") out.appendLine("runners.enabled was off; turned on")
        return reportAdd(r, limit, out)
    }

    /** The one answer line both add forms print once the daemon paired, and what a pairing grant did. */
    private fun reportAdd(r: AdminAnswer, limit: (LimitDef) -> Long, out: Appendable): Int {
        out.appendLine("runner '${r["name"]}' paired (${r["url"]}); runner id ${r["runnerId"]}")
        out.appendLine("The credential is stored in this machine's link keystore only; it rotates every ${limit(LimitRegistry.RUNNERS_CREDENTIAL_ROTATE_DAYS)} days.")
        // RUNNER-GRANT-AT-PAIR-1: what the runner granted, or why it would not.
        val projects = r.list("grantedProjects")
        val harnesses = r.list("grantedHarnesses")
        if (projects.isNotEmpty() || harnesses.isNotEmpty()) {
            out.appendLine(
                "granted on the runner: " + listOfNotNull(
                    projects.takeIf { it.isNotEmpty() }?.let { "projects ${it.joinToString(", ")}" },
                    harnesses.takeIf { it.isNotEmpty() }?.let { "harnesses ${it.joinToString(", ")}" },
                ).joinToString(" and ") + " (its runner.projects / runner.harnesses, audited as runner_link_pair_grant)",
            )
        }
        r["grantRefusal"]?.takeIf { it.isNotBlank() }?.let { out.appendLine("not granted: $it") }
        return 0
    }

    /** RUNNER-GRANT-AT-PAIR-1: the harness ids this machine runs (the daemon's own list), so the confirm names what is asked for. */
    private suspend fun localHarnesses(stream: FrameStream, limit: (LimitDef) -> Long): List<String> {
        val answer = admin(stream, limit, "op" to "pair_preview")
        return if (answer.failed) emptyList() else answer.list("installedHarnesses")
    }

    /** The project's display name, for the grant confirm; null when it cannot be read (the id is shown instead). */
    private suspend fun projectNameOf(stream: FrameStream, projectId: String): String? = runCatching {
        json.decodeFromString(
            com.superiorsoftllc.infantry.protocol.Project.serializer(),
            stream.request(Envelope(Envelope.PROJECT_GET, projectId), Envelope.PROJECT, "project_get", idempotent = true).data,
        ).name
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** `--link -`: the one line(s) piped in, so the link need not land in shell history. */
    private fun readStdin(): String = runCatching { System.`in`.bufferedReader().readText().trim() }.getOrDefault("")

    /** Whether [text] is meant as a pairing link at all (RUNNER-LINK-1's codec owns the format). */
    private fun isLink(text: String): Boolean = text.trim().startsWith(RunnerPairLink.PREFIX, ignoreCase = true)

    private suspend fun pair(stream: FrameStream, name: String, limit: (LimitDef) -> Long, out: Appendable, err: Appendable, allowProjects: String? = null, allowHarnesses: String? = null, hosts: String? = null, port: String? = null, grantOnPair: Boolean = false, grantHarnessesMax: String? = null, wait: Boolean = true): Int {
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        require(!(grantOnPair && allowProjects != null)) {
            "--grant-on-pair replaces --allow-projects: the project is granted once, from the orchestrator, at pairing"
        }
        // RUNNER-DEFAULTS-1: the grant is part of pairing. Flags state it outright; otherwise the owner is asked at the console, with
        // the harnesses installed here pre-selected (Enter confirms). With neither flags nor a console nothing is granted; the text says how.
        val preview = admin(stream, limit, "op" to "pair_preview")
        val installed = if (preview.failed) emptyList() else preview.list("installedHarnesses")
        var projects = allowProjects
        var harnesses = allowHarnesses
        val console = System.console()
        // RUNNER-GRANT-AT-PAIR-1: with the code carrying a one-time grant there is nothing to type here: the project id lives on the
        // orchestrator, so the owner is not asked for it (and its harness ceiling is --grant-harnesses-max or what is installed).
        if (console != null && !grantOnPair) {
            if (harnesses == null && installed.isNotEmpty()) {
                val a = console.readLine("Harnesses the orchestrator may run here [${installed.joinToString(",")}] (Enter = these, 'none', or ids / *): ")?.trim().orEmpty()
                harnesses = when { a.isEmpty() -> installed.joinToString(","); a.equals("none", true) -> ""; else -> a }
            }
            if (projects == null) projects = console.readLine("Project ids the orchestrator may run here (comma separated, * = every project, Enter = none yet): ")?.trim().orEmpty()
        }
        val r = admin(
            stream, limit, "op" to "pair", "name" to name, "allowProjects" to projects, "allowHarnesses" to harnesses, "hosts" to hosts, "port" to port,
            "grantOnPair" to grantOnPair.takeIf { it }?.toString(), "harnessCeiling" to grantHarnessesMax?.takeIf { it.isNotBlank() },
        )
        if (r.failed) { err.appendLine("error: ${r.error}"); return 1 }
        val code = r["code"].orEmpty()
        val plain = r["plain"] == "true"
        val fingerprint = r["fingerprint"]
        val address = RemoteAccess.tailscaleAddress() ?: "<this-machine-address>"
        val scheme = if (plain) "ws" else "wss"
        r["link"]?.let {
            out.appendLine("Paste this into Settings > Runners > Add, or run on your PC: infantry runner add '$it'")
            out.appendLine("or by hand:")
        }
        out.appendLine("pair code:   $code   (one use; expires in ${r["expiryMin"]} min)")
        if (plain) out.appendLine("WARNING: ${RunnerLinkSettings.ALLOW_PLAIN_TAILNET_TEXT}")
        else out.appendLine("fingerprint: $fingerprint")
        out.appendLine("On the orchestrator run:")
        out.appendLine("  infantry runner add $name $scheme://$address:${r["port"]} --code $code" + if (plain) "" else " --fingerprint $fingerprint")
        if (grantOnPair) {
            val ceiling = r.list("harnessCeiling").ifEmpty { r.list("installedHarnesses") }
            out.appendLine(
                "This code carries a ONE-TIME grant: the first pairing may add 1 project (`infantry runner add '<link>' --grant`) and these " +
                    "harnesses: ${ceiling.ifEmpty { listOf("none installed") }.joinToString(", ")}. Nothing is granted unless the owner confirms there.",
            )
        }
        out.appendLine("Remote starts are limited to ${RunnerLinkSettings.PROJECTS}=${r.list("projects")} and ${RunnerLinkSettings.HARNESSES}=${r.list("harnesses")} (empty = none).")
        if (r.list("projects").isEmpty() || r.list("harnesses").isEmpty()) {
            out.appendLine("An empty list grants nothing. To grant: infantry runner pair --name $name --allow-projects <ids|*> --allow-harnesses <ids|*>")
            out.appendLine("  or grant the project once, from the orchestrator: infantry runner pair --name $name --grant-on-pair")
            out.appendLine("  or: infantry settings set ${RunnerLinkSettings.PROJECTS} '[\"<project-id>\"]'  /  infantry settings set ${RunnerLinkSettings.HARNESSES} '[\"<harness>\"]'")
        }
        (out as? java.io.Flushable)?.flush()
        if (!wait) {
            out.appendLine("The link is a secret for ${r["expiryMin"]} minutes and useless once used or expired. Check later with: infantry runner list")
            return 0
        }
        try {
            while (true) {
                val status = admin(stream, limit, "op" to "pair_status")
                if (status.failed) { err.appendLine("error: ${status.error}"); return 1 }
                when (status["state"]) {
                    "paired" -> {
                        out.appendLine("paired with orchestrator ${status["orchestratorName"]} (${status["orchestratorId"]}). The daemon serves the link from its next check (limits.runners.linkCheckSeconds).")
                        return 0
                    }
                    "waiting" -> kotlinx.coroutines.delay(PAIR_POLL_STEP)
                    else -> {
                        err.appendLine("error: the pair code expired unused; run `infantry runner pair --name $name` again")
                        return 1
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { admin(stream, limit, "op" to "pair_cancel") } }
            throw e
        }
    }

    /** The `limits.*` values from the daemon's catalog (key -> value); empty when it cannot be read (registry defaults then). */
    private suspend fun limits(stream: FrameStream): Map<String, Long> = runCatching {
        val answer = stream.request(Envelope(Envelope.LIMIT_CATALOG, ""), Envelope.LIMIT_CATALOG, "limit_catalog", idempotent = true)
        (json.parseToJsonElement(answer.data).jsonObject["limits"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val o = e.jsonObject
            val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val value = o["value"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            key to value
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("orchestrator")

    private fun iso(epochMillis: Long): String = com.superiorsoftllc.infantry.DisplayZone.stamp(epochMillis)

    private const val OWNER_LOCAL = "owner (local console)"
    private const val MS_PER_SECOND = 1000L
    private const val ADMIN_CALLS = 2L
    private const val ADMIN_SLACK_SECONDS = 10L
    private const val PAIR_POLL_STEP = 500L
    /** RUNNER-CHECK-1: the column a check row's id is padded to, so `ok` / `FAIL` line up. */
    private const val ID_COLUMN = 15
    private const val FAIL = "FAIL"
    private const val ROWS = "rows"
    /** RUNNER-TEST-1: the JSON field and count of the transcript lines a test with no answer reports. */
    private const val LAST_LINES = "lastLines"
}
