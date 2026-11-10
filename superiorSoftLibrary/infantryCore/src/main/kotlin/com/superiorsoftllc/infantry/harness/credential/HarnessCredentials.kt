// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.credential

import com.superiorsoftllc.infantry.keystore.Keystore

/**
 * FIRST-RUN-KEYS-1: what ONE harness says it needs to run: an API key, delivered to its process in the environment variable
 * [env]. Declared by the harness itself (module.json `harness.credential`, or a JSON descriptor's `credential`), never
 * hard-coded in core. The key lives in the keystore under [secretName] (`harness.<id>.key`), never in config or logs.
 */
data class HarnessCredential(
    val harnessId: String,
    /** The environment variable the harness process reads the key from. */
    val env: String,
    /** How the owner's UI names the key (default: the harness id). */
    val label: String = harnessId,
    /** Plain words on where to get the key (a URL or a sentence). */
    val help: String = "",
    /** CLAUDE-AUTH-CHOICE-1: the ways the owner may sign this harness in ([MODE_API_KEY], [MODE_SUBSCRIPTION]); empty = key only, no choice. */
    val authModes: List<String> = emptyList(),
    /** CLAUDE-AUTH-CHOICE-2: argv that reports the subscription login as JSON on stdout (no tokens); empty = no probe. */
    val probeArgv: List<String> = emptyList(),
    /** The JSON boolean in the probe's output that says "signed in". */
    val probeField: String = "loggedIn",
    /** What the owner runs in a terminal to sign in; shown with a Copy button. */
    val loginCommand: String = "",
) {
    /** The setting that stores the owner's choice of [authModes], per harness. */
    val modeSetting: String get() = modeSettingOf(harnessId)
    val secretName: String get() = secretNameOf(harnessId)

    companion object {
        const val FIELD = "value"
        fun secretNameOf(harnessId: String): String = "harness.$harnessId.key"
        const val MODE_API_KEY = "api-key"
        const val MODE_SUBSCRIPTION = "subscription-login"
        val KNOWN_MODES = listOf(MODE_API_KEY, MODE_SUBSCRIPTION)
        fun modeSettingOf(harnessId: String): String = "harness.$harnessId.authMode"
        /** [modes] with unknown names dropped (a manifest may not invent a mode the shell cannot run). */
        fun cleanModes(modes: List<String>): List<String> = modes.filter { it in KNOWN_MODES }.distinct()
        private val ENV_NAME = Regex("[A-Z_][A-Z0-9_]{0,63}")

        /** Null when [env] is a usable variable name; else why not (a harness may not name `PATH`-like or daemon variables). */
        fun envRefusal(env: String): String? = when {
            !ENV_NAME.matches(env) -> "credential env '${env.take(40)}' is not an upper-case variable name"
            env.startsWith("INFANTRY_") || env.startsWith("AGENTHQ_") -> "credential env may not be an INFANTRY_/AGENTHQ_ variable"
            env in RESERVED -> "credential env '$env' is a reserved system variable"
            else -> null
        }
        private val RESERVED = setOf("PATH", "PATHEXT", "HOME", "USERPROFILE", "SYSTEMROOT", "TEMP", "TMP", "COMSPEC", "NODE_OPTIONS", "LD_PRELOAD")
    }
}

/** One row a client may see: whether the key is set and a masked tail. Never the value. */
data class HarnessCredentialState(val credential: HarnessCredential, val set: Boolean, val tail: String)

/**
 * The harness credentials the loaded harnesses declare, over the keystore. [declared] is read per call (modules load and
 * unload at run time). [env] is what the launch merges into a harness process's environment; daemon side only.
 */
class HarnessCredentials(
    private val keystore: Keystore,
    /** The stored choice for a harness id (its `harness.<id>.authMode` setting), or null when none was made. */
    private val modeSource: (String) -> String? = { null },
    /** Runs a probe argv and returns its stdout whatever the exit code (a CLI may exit 1 when signed out); null when it cannot run. */
    private val probeRunner: (List<String>, String) -> String? = ::runProbe,
    private val declaredSource: () -> List<HarnessCredential>,
) {
    /** CLAUDE-AUTH-CHOICE-1: the chosen auth mode of [harnessId]: the stored one when the harness offers it, else null (no choice yet). */
    fun mode(harnessId: String): String? {
        val c = declaration(harnessId) ?: return null
        return modeSource(harnessId)?.takeIf { it in c.authModes }
    }

    /** True when the owner chose the subscription login: no key is needed and none is injected. */
    private fun subscription(harnessId: String) = mode(harnessId) == HarnessCredential.MODE_SUBSCRIPTION

    /** The declared credentials, one per harness id (the first declaration wins). */
    fun declared(): List<HarnessCredential> = declaredSource().distinctBy { it.harnessId }

    fun declaration(harnessId: String): HarnessCredential? = declared().firstOrNull { it.harnessId == harnessId }

    fun states(): List<HarnessCredentialState> = declared().sortedBy { it.harnessId }.map { c ->
        val value = keystore.field(c.secretName, HarnessCredential.FIELD)
        HarnessCredentialState(c, value != null, value?.let { Keystore.maskedTail(it) }.orEmpty())
    }

    /** Stores [value] as [harnessId]'s key. Refused for a harness that declares no credential. */
    fun set(harnessId: String, value: String) {
        val c = declaration(harnessId) ?: throw IllegalArgumentException("harness '$harnessId' declares no credential")
        keystore.setField(c.secretName, HarnessCredential.FIELD, value)
    }

    fun clear(harnessId: String): Boolean {
        val c = declaration(harnessId) ?: return false
        return keystore.clear(c.secretName)
    }

    /** `{ENV_NAME: key}` for [harnessId]; empty when it declares none or the key is unset. */
    fun env(harnessId: String): Map<String, String> {
        val c = declaration(harnessId) ?: return emptyMap()
        // CLAUDE-AUTH-CHOICE-2: a subscription session carries NO key: the variable is blanked, so a key the daemon itself inherited from its own
        // environment cannot reach the harness and bill an API account (a blank variable reads as unset).
        if (subscription(harnessId)) return mapOf(c.env to "")
        val v = keystore.field(c.secretName, HarnessCredential.FIELD) ?: return emptyMap()
        return mapOf(c.env to v)
    }

    /** CLAUDE-AUTH-CHOICE-2: the last probe result per harness id; kept in memory, rebuilt by the next check. */
    private val authStatus = java.util.concurrent.ConcurrentHashMap<String, AuthStatus>()

    /** The last known sign-in state of [harnessId] (signedIn null = never probed or the probe was unreadable). */
    fun authStatus(harnessId: String): AuthStatus = authStatus[harnessId] ?: AuthStatus(null, 0L)

    /**
     * Runs the harness's declared probe (e.g. `claude auth status`), records and returns the result. The probe costs no tokens and
     * never sets or reads an API key. A harness with no probe, or a probe that fails to run or parse, is signedIn = null (unknown).
     */
    fun checkAuth(harnessId: String): AuthStatus {
        val c = declaration(harnessId)
        val signedIn = if (c == null || c.probeArgv.isEmpty()) null else probe(c.probeArgv, c.probeField, c.env)
        return AuthStatus(signedIn, System.currentTimeMillis()).also { authStatus[harnessId] = it }
    }

    private fun probe(argv: List<String>, field: String, scrubEnv: String): Boolean? = try {
        val out = probeRunner(argv, scrubEnv) ?: return null
        (kotlinx.serialization.json.Json.parseToJsonElement(out) as? kotlinx.serialization.json.JsonObject)
            ?.get(field)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() }
    } catch (_: Exception) { null }

    /** The declared credentials with no key set: what the first-run step asks for. */
    fun missing(): List<HarnessCredential> = states().filter { !it.set && !subscription(it.credential.harnessId) }.map { it.credential }
}

/** CLAUDE-AUTH-CHOICE-2: one probe result. [signedIn] null = unknown; [lastProbe] is epoch millis, 0 = never probed. */
data class AuthStatus(val signedIn: Boolean?, val lastProbe: Long) {
    /** The wire word: `true`, `false` or `unknown`. */
    val word: String get() = when (signedIn) { true -> "true"; false -> "false"; null -> "unknown" }
}

/** How long a sign-in probe may run before it is killed (it is a local status read, so seconds). */
private const val PROBE_TIMEOUT_SECONDS = 30L

/**
 * Runs [argv] (never a shell), stdin closed, stderr discarded, and returns stdout whatever the exit code; null when it cannot
 * start or outlives [PROBE_TIMEOUT_SECONDS]. The child never sees the harness's API key variable [scrubEnv]: a subscription probe must not bill one.
 */
internal fun runProbe(argv: List<String>, scrubEnv: String): String? = try {
    val b = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD)
    b.environment().remove(scrubEnv)
    val p = b.start()
    p.outputStream.close()
    val out = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.readBytes().toString(Charsets.UTF_8) }
    if (!p.waitFor(PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out.get(PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
} catch (_: Exception) { null }
