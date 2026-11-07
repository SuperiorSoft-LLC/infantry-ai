// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.RemoteAccess
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runners.PairCodes
import com.superiorsoftllc.infantry.runners.RunnerAudit
import com.superiorsoftllc.infantry.runners.RunnerLinkListener
import com.superiorsoftllc.infantry.runners.RunnerLinkServer
import com.superiorsoftllc.infantry.runners.RunnerLinkService
import com.superiorsoftllc.infantry.runners.RunnerLinkStore
import com.superiorsoftllc.infantry.runners.RunnerLinkTls
import com.superiorsoftllc.infantry.runners.RunnerPeers
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.InetAddress

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): `infantry runner pair|add|list|rotate|detach|audit`. Pairing state lives in the
 * link keystore under `<stateDir>/runner-link` ([RunnerLinkStore]); the daemon is asked only for the settings
 * (`runners.allowPlainTailnet`, `runner.projects`, `runner.harnesses`) and the `limits.runners.*` values.
 *
 * * `pair --name <n>` (on the RUNNER): prints a one-time code (single use, expires after `limits.runners.pairCodeMin`, at most
 *   `limits.runners.pairAttemptsPerHour` tries) and the link certificate's fingerprint, serves the link until paired or expired.
 * * `add <name> <wss-url> --code <c> --fingerprint <fp>` (on the ORCHESTRATOR): checks the pin, trades the code for the
 *   per-pair credential.
 * * `rotate <name>`: in-band rekey. `detach [<name>]`: with a name, on the orchestrator; without, on the runner.
 * * `audit [--limit N]`: the runner's audit log of every remote operation.
 */
internal object RunnerCli {
    const val USAGE = "usage: infantry runner pair --name <n> | add <name> <wss-url> --code <code> [--fingerprint <fp>] | list | rotate <name> | detach [<name>] | audit [--limit N]"

    private val json = Json { ignoreUnknownKeys = true }

    /** The words after `runner` and the flags the CLI parser took for it. */
    data class Args(val words: List<String>, val name: String? = null, val code: String? = null, val fingerprint: String? = null, val limit: String? = null) {
        val action: String? get() = words.firstOrNull()?.lowercase()
        val positional: List<String> get() = words.drop(1)
        val flags: Map<String, String>
            get() = listOfNotNull(name?.let { "name" to it }, code?.let { "code" to it }, fingerprint?.let { "fingerprint" to it }, limit?.let { "limit" to it }).toMap()
    }

    /** The usage error for [args], or null when they are valid. */
    fun usageError(args: Args): String? {
        val positional = args.positional
        val flags = args.flags
        val ok = when (args.action) {
            "pair" -> positional.isEmpty() && !args.name.isNullOrBlank() && flags.keys == setOf("name")
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
        val values = runnerSettings(stream)
        val dir = RunnerLinkStore.dir(stateDir)
        return try {
            when (args.action) {
                "pair" -> pair(flags.getValue("name"), dir, stateDir, values, ::limit, out, err)
                "add" -> {
                    val store = RunnerLinkStore.open(stateDir)
                    val peers = RunnerPeers(store, RunnerLinkService.clientCall(::limit) { values })
                    val r = try {
                        peers.add(positional[0], positional[1], flags.getValue("code"), flags["fingerprint"], hostName())
                    } catch (e: com.superiorsoftllc.infantry.runners.RunnerLinkClient.Failure) {
                        err.appendLine("error: ${e.message}")
                        e.presentedFingerprint?.takeIf { flags["fingerprint"] == null }?.let {
                            err.appendLine("If it matches, run: infantry runner add ${positional[0]} ${positional[1]} --code ${flags.getValue("code")} --fingerprint $it")
                        }
                        return 1
                    }
                    out.appendLine("runner '${r.name}' paired (${r.url}); runner id ${r.runnerId}")
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
                    val store = RunnerLinkStore.open(stateDir)
                    val r = RunnerPeers(store, RunnerLinkService.clientCall(::limit) { values }).rotate(positional[0])
                    out.appendLine("runner '${r.name}': credential rotated; the old one no longer works")
                    0
                }
                "detach" -> {
                    if (!RunnerLinkStore.exists(stateDir)) { err.appendLine("error: this machine never paired"); return 1 }
                    val store = RunnerLinkStore.open(stateDir)
                    val name = positional.firstOrNull()
                    if (name != null) {
                        val remote = RunnerPeers(store, RunnerLinkService.clientCall(::limit) { values }).detach(name)
                        out.appendLine("runner '$name' detached here; $remote")
                    } else {
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
        } catch (e: com.superiorsoftllc.infantry.runners.RunnerLinkClient.Failure) {
            err.appendLine("error: ${e.message}")
            1
        }
    }

    private fun pair(
        name: String,
        dir: File,
        stateDir: File,
        values: RunnerLinkSettings.Values,
        limit: (LimitDef) -> Long,
        out: Appendable,
        err: Appendable,
    ): Int {
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        val store = RunnerLinkStore.open(stateDir)
        store.orchestrator()?.let {
            err.appendLine("error: this runner is already paired with orchestrator ${it.name}; one orchestrator only. `infantry runner detach` first.")
            return 1
        }
        val plain = values.allowPlainTailnet
        val keyStore = RunnerLinkTls.ensure(dir, store.tlsPassword())
        val fingerprint = RunnerLinkTls.fingerprint(RunnerLinkTls.certificate(keyStore))
        val codes = PairCodes(
            File(dir, PairCodes.ATTEMPTS_FILE),
            expiryMs = { limit(LimitRegistry.RUNNERS_PAIR_CODE_MIN) * MS_PER_MINUTE },
            attemptsPerHour = { limit(LimitRegistry.RUNNERS_PAIR_ATTEMPTS_PER_HOUR) },
        )
        val server = RunnerLinkServer(store, RunnerAudit(File(dir, RunnerAudit.FILE)), codes, settings = { values }, runner = { null })
        val tls = if (plain) null else RunnerLinkTls.serverContext(keyStore, store.tlsPassword())
        val listener = RunnerLinkListener(
            server, tls, RunnerLinkService.ANY_HOST, limit(LimitRegistry.RUNNERS_LINK_PORT).toInt(),
            (limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) * MS_PER_SECOND).toInt(),
        )
        val port = try {
            listener.start()
        } catch (e: Exception) {
            listener.close()
            err.appendLine("error: cannot serve the runner link on port ${limit(LimitRegistry.RUNNERS_LINK_PORT)} (${e.message}); is the daemon already serving it, or set limits.runners.linkPort")
            return 1
        }
        try {
            val code = codes.issue(name, System.currentTimeMillis())
            val address = RemoteAccess.tailscaleAddress() ?: "<this-machine-address>"
            val scheme = if (plain) "ws" else "wss"
            out.appendLine("pair code:   $code   (one use; expires in ${limit(LimitRegistry.RUNNERS_PAIR_CODE_MIN)} min)")
            if (plain) out.appendLine("WARNING: ${RunnerLinkSettings.ALLOW_PLAIN_TAILNET_TEXT}")
            else out.appendLine("fingerprint: $fingerprint")
            out.appendLine("On the orchestrator run:")
            out.appendLine("  infantry runner add $name $scheme://$address:$port --code $code" + if (plain) "" else " --fingerprint $fingerprint")
            out.appendLine("Remote starts are limited to ${RunnerLinkSettings.PROJECTS}=${values.projects} and ${RunnerLinkSettings.HARNESSES}=${values.harnesses} (empty = none).")
            (out as? java.io.Flushable)?.flush()
            while (store.orchestrator() == null && codes.liveName(System.currentTimeMillis()) != null) Thread.sleep(PAIR_POLL_STEP)
            val paired = store.orchestrator() ?: run {
                err.appendLine("error: the pair code expired unused; run `infantry runner pair --name $name` again")
                return 1
            }
            out.appendLine("paired with orchestrator ${paired.name} (${paired.id}). The daemon serves the link from its next check (limits.runners.linkCheckSeconds).")
            return 0
        } finally {
            listener.close()
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

    /** The runner-link settings from the daemon; when they cannot be read, the safe defaults (no plain ws, empty allow-lists). */
    private suspend fun runnerSettings(stream: FrameStream): RunnerLinkSettings.Values = runCatching {
        val answer = stream.request(Envelope(Envelope.SETTINGS_GET, ""), Envelope.SETTINGS, "settings_get", idempotent = true)
        val all = json.parseToJsonElement(answer.data).jsonObject
        RunnerLinkSettings.Values(
            allowPlainTailnet = all[RunnerLinkSettings.ALLOW_PLAIN_TAILNET]?.jsonPrimitive?.booleanOrNull ?: false,
            projects = (all[RunnerLinkSettings.PROJECTS] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            harnesses = (all[RunnerLinkSettings.HARNESSES] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
    }.getOrDefault(RunnerLinkSettings.Values(false, emptyList(), emptyList()))

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("orchestrator")

    private fun iso(epochMillis: Long): String = com.superiorsoftllc.infantry.DisplayZone.stamp(epochMillis)

    private const val OWNER_LOCAL = "owner (local console)"
    private const val MS_PER_SECOND = 1000L
    private const val MS_PER_MINUTE = 60_000L
    private const val PAIR_POLL_STEP = 500L
}
