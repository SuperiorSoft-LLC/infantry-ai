// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.RemoteAccess
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runners.RunnerAudit
import com.superiorsoftllc.infantry.runners.RunnerLinkService
import com.superiorsoftllc.infantry.runners.RunnerLinkStore
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
 * * `audit [--limit N]`: the runner's audit log of every remote operation.
 *
 * RUNNER-POOL-6a: the transport is the Infantry_RemoteRunner plugin's and only the daemon loads plugins, so `pair`, `add`,
 * `rotate` and `detach <name>` go through the running daemon as the owner-only `runner_admin` frame
 * ([RunnerLinkService.admin]; the CLI presents the owner token from `<stateDir>/owner.token`). `list`, `audit` and the
 * runner-side `detach` read and write the link folder here, as before. Command names, flags and printed text are unchanged.
 */
internal object RunnerCli {
    const val USAGE = "usage: infantry runner up --name <n> [--host <h>]... [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] | pair --name <n> [--host <h>]... [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] | add <name> <wss-url> --code <code> [--fingerprint <fp>] | list | rotate <name> | detach [<name>] | audit [--limit N]"

    private val json = Json { ignoreUnknownKeys = true }

    /** The words after `runner` and the flags the CLI parser took for it. */
    data class Args(val words: List<String>, val name: String? = null, val code: String? = null, val fingerprint: String? = null, val limit: String? = null, val allowProjects: String? = null, val allowHarnesses: String? = null, val hosts: String? = null) {
        val action: String? get() = words.firstOrNull()?.lowercase()
        val positional: List<String> get() = words.drop(1)
        val flags: Map<String, String>
            get() = listOfNotNull(name?.let { "name" to it }, code?.let { "code" to it }, fingerprint?.let { "fingerprint" to it }, limit?.let { "limit" to it }, allowProjects?.let { "allow-projects" to it }, allowHarnesses?.let { "allow-harnesses" to it }, hosts?.let { "host" to it }).toMap()
    }

    /** The usage error for [args], or null when they are valid. */
    fun usageError(args: Args): String? {
        val positional = args.positional
        val flags = args.flags
        val ok = when (args.action) {
            "pair", "up" -> positional.isEmpty() && !args.name.isNullOrBlank() && flags.keys.all { it in setOf("name", "allow-projects", "allow-harnesses", "host") }
            "add" -> positional.size == 2 && !args.code.isNullOrBlank() && flags.keys.all { it in setOf("code", "fingerprint") }
            "list" -> positional.isEmpty() && flags.isEmpty()
            "rotate" -> positional.size == 1 && flags.isEmpty()
            "detach" -> positional.size <= 1 && flags.isEmpty()
            "audit" -> positional.isEmpty() && flags.keys.all { it == "limit" } && (args.limit == null || (args.limit.toIntOrNull() ?: 0) > 0)
            else -> false
        }
        return if (ok) null else USAGE
    }

    suspend fun run(stream: FrameStream, args: Args, out: Appendable, err: Appendable, stateDir: File): Int {
        val positional = args.positional
        val flags = args.flags
        val catalog = limits(stream)
        fun limit(def: LimitDef): Long = catalog[def.key] ?: def.default
        val dir = RunnerLinkStore.dir(stateDir)
        return try {
            when (args.action) {
                "pair" -> pair(stream, flags.getValue("name"), ::limit, out, err, flags["allow-projects"], flags["allow-harnesses"], flags["host"])
                "up" -> up(stream, flags.getValue("name"), ::limit, out, err, flags["allow-projects"], flags["allow-harnesses"], flags["host"])
                "add" -> {
                    val r = admin(
                        stream, ::limit, "op" to "add", "name" to positional[0], "url" to positional[1], "code" to flags.getValue("code"),
                        "fingerprint" to flags["fingerprint"], "orchestratorName" to hostName(),
                    )
                    if (r.failed) {
                        err.appendLine("error: ${r.error}")
                        r["presentedFingerprint"]?.takeIf { flags["fingerprint"] == null }?.let {
                            err.appendLine("If it matches, run: infantry runner add ${positional[0]} ${positional[1]} --code ${flags.getValue("code")} --fingerprint $it")
                        }
                        return 1
                    }
                    out.appendLine("runner '${r["name"]}' paired (${r["url"]}); runner id ${r["runnerId"]}")
                    out.appendLine("The credential is stored in this machine's link keystore only; it rotates every ${limit(LimitRegistry.RUNNERS_CREDENTIAL_ROTATE_DAYS)} days.")
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
    }

    /** Sends one `runner_admin` request to the daemon; a link call may take `limits.runners.linkCallSeconds`, add makes two. */
    private suspend fun admin(stream: FrameStream, limit: (LimitDef) -> Long, vararg fields: Pair<String, String?>): AdminAnswer {
        val body = JsonObject(fields.filter { it.second != null }.associate { (k, v) -> k to JsonPrimitive(v) })
        val wait = maxOf(stream.answerTimeoutMs, (limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) * ADMIN_CALLS + ADMIN_SLACK_SECONDS) * MS_PER_SECOND)
        val answer = stream.request(Envelope(RunnerLinkService.ADMIN, body.toString()), RunnerLinkService.ADMIN, RunnerLinkService.ADMIN, idempotent = false, timeoutMs = wait)
        return AdminAnswer(json.parseToJsonElement(answer.data).jsonObject)
    }

    /**
     * RUNNER-UP-1: `runner up` on a daemon that is already answering. Paired: says so and stops. A pairing link still live: says
     * "waiting" and issues no new code (each issue is a fresh window for guessing). Otherwise issues the link and returns at once.
     */
    private suspend fun up(stream: FrameStream, name: String, limit: (LimitDef) -> Long, out: Appendable, err: Appendable, allowProjects: String?, allowHarnesses: String?, hosts: String?): Int {
        val status = admin(stream, limit, "op" to "pair_status")
        if (status.failed) { err.appendLine("error: ${status.error}"); return 1 }
        when (status["state"]) {
            "paired" -> { out.appendLine("already paired with ${status["orchestratorName"]}; `infantry runner detach` to pair again"); return 0 }
            "waiting" -> { out.appendLine("waiting: a pairing link was already issued and is not used yet; it expires on its own. For a new one: infantry runner pair --name $name"); return 0 }
        }
        return pair(stream, name, limit, out, err, allowProjects, allowHarnesses, hosts, wait = false)
    }

    private suspend fun pair(stream: FrameStream, name: String, limit: (LimitDef) -> Long, out: Appendable, err: Appendable, allowProjects: String? = null, allowHarnesses: String? = null, hosts: String? = null, wait: Boolean = true): Int {
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        // RUNNER-DEFAULTS-1: the grant is part of pairing. Flags state it outright; otherwise the owner is asked at the console, with
        // the harnesses installed here pre-selected (Enter confirms). With neither flags nor a console nothing is granted; the text says how.
        val preview = admin(stream, limit, "op" to "pair_preview")
        val installed = if (preview.failed) emptyList() else preview.list("installedHarnesses")
        var projects = allowProjects
        var harnesses = allowHarnesses
        val console = System.console()
        if (console != null) {
            if (harnesses == null && installed.isNotEmpty()) {
                val a = console.readLine("Harnesses the orchestrator may run here [${installed.joinToString(",")}] (Enter = these, 'none', or ids / *): ")?.trim().orEmpty()
                harnesses = when { a.isEmpty() -> installed.joinToString(","); a.equals("none", true) -> ""; else -> a }
            }
            if (projects == null) projects = console.readLine("Project ids the orchestrator may run here (comma separated, * = every project, Enter = none yet): ")?.trim().orEmpty()
        }
        val r = admin(stream, limit, "op" to "pair", "name" to name, "allowProjects" to projects, "allowHarnesses" to harnesses, "hosts" to hosts)
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
        out.appendLine("Remote starts are limited to ${RunnerLinkSettings.PROJECTS}=${r.list("projects")} and ${RunnerLinkSettings.HARNESSES}=${r.list("harnesses")} (empty = none).")
        if (r.list("projects").isEmpty() || r.list("harnesses").isEmpty()) {
            out.appendLine("An empty list grants nothing. To grant: infantry runner pair --name $name --allow-projects <ids|*> --allow-harnesses <ids|*>")
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
}
