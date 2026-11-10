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
    /**
     * HARNESS-MULTI-CREDENTIAL-1: which of the harness's credentials this is. Empty = the primary (legacy) one, stored as
     * `harness.<id>.key`; else `harness.<id>.<credId>.key` with its pointer in `harness.<id>.<credId>.keyRef`.
     */
    val credId: String = "",
) {
    /** The setting that stores the owner's choice of [authModes], per harness. */
    val modeSetting: String get() = modeSettingOf(harnessId)
    val secretName: String get() = secretNameOf(harnessId, credId)
    /** The setting naming the shared secret this credential reads its value from. */
    val refSetting: String get() = refSettingOf(harnessId, credId)

    companion object {
        const val FIELD = "value"
        fun secretNameOf(harnessId: String, credId: String = ""): String = if (credId.isEmpty()) "harness.$harnessId.key" else "harness.$harnessId.$credId.key"
        const val MODE_API_KEY = "api-key"
        const val MODE_SUBSCRIPTION = "subscription-login"
        val KNOWN_MODES = listOf(MODE_API_KEY, MODE_SUBSCRIPTION)
        fun modeSettingOf(harnessId: String): String = "harness.$harnessId.authMode"
        /** SECRET-REF-1: the setting naming a shared secret this harness's key is read from (a name, never a value). */
        fun refSettingOf(harnessId: String, credId: String = ""): String = if (credId.isEmpty()) "harness.$harnessId.keyRef" else "harness.$harnessId.$credId.keyRef"
        private val CRED_ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        /** Null when [credId] is empty (the primary credential) or a usable id; else why not. */
        fun credIdRefusal(credId: String): String? = if (credId.isEmpty() || CRED_ID.matches(credId)) null else "credential id '${credId.take(40)}' must be lowercase letters, digits, '_' and '-'"
        /** Null when [name] may be referenced as a harness key; else why not (a harness key or another built-in is never lent out). */
        fun refRefusal(name: String): String? = when {
            !Keystore.isName(name) -> "secret names are lowercase letters, digits, '.', '_' and '-'"
            name.startsWith("harness.") || name.startsWith("plugin.") -> "secret '$name' is built in and cannot be referenced"
            else -> null
        }
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
    /** SECRET-REF-1: the stored value of a `harness.<id>[.<credId>].keyRef` setting key (a shared secret's name), or null when none. */
    private val refSource: (String) -> String? = { null },
    private val declaredSource: () -> List<HarnessCredential>,
) {
    /** CLAUDE-AUTH-CHOICE-1: the chosen auth mode of [harnessId]: the stored one when the harness offers it, else null (no choice yet). */
    fun mode(harnessId: String): String? {
        val c = declaration(harnessId) ?: return null
        return modeSource(harnessId)?.takeIf { it in c.authModes }
    }

    /** True when the owner chose the subscription login: no key is needed and none is injected. */
    private fun subscription(harnessId: String) = mode(harnessId) == HarnessCredential.MODE_SUBSCRIPTION

    /** The declared credentials, one per (harness id, credential id); the first declaration wins. */
    fun declared(): List<HarnessCredential> = declaredSource().filter { HarnessCredential.credIdRefusal(it.credId) == null }.distinctBy { it.harnessId to it.credId }

    /** The credential [credId] of [harnessId] (default: the primary one), or null. */
    fun declaration(harnessId: String, credId: String = ""): HarnessCredential? = declared().firstOrNull { it.harnessId == harnessId && it.credId == credId }

    /** Every credential [harnessId] declares, in declaration order. */
    fun declarations(harnessId: String): List<HarnessCredential> = declared().filter { it.harnessId == harnessId }

    /** SECRET-REF-1: the shared secret credential [credId] of [harnessId] reads its key from, or null. A refused name counts as none. */
    fun usesSecret(harnessId: String, credId: String = ""): String? =
        refSource(HarnessCredential.refSettingOf(harnessId, credId))?.trim()?.takeIf { it.isNotEmpty() && HarnessCredential.refRefusal(it) == null }

    /**
     * SECRET-REF-1: the key value of [c]: the referenced shared secret (project scope first, then app) when a reference is
     * set, else the harness's own stored key. A reference that finds nothing yields null (never a fall back to the stored key).
     * The secret's `value` field, else its only field. Daemon side only.
     */
    private fun keyValue(c: HarnessCredential, project: String?): String? {
        val ref = usesSecret(c.harnessId, c.credId) ?: return keystore.field(c.secretName, HarnessCredential.FIELD)
        val fields = keystore.resolve(ref, project) ?: return null
        return fields[HarnessCredential.FIELD] ?: fields.values.singleOrNull()
    }

    fun states(): List<HarnessCredentialState> = declared().sortedBy { it.harnessId }.map { c ->
        val value = keyValue(c, null)
        HarnessCredentialState(c, value != null, value?.let { Keystore.maskedTail(it) }.orEmpty())
    }

    /** Stores [value] as [harnessId]'s key [credId]. Refused for a credential the harness does not declare. */
    fun set(harnessId: String, value: String, credId: String = "") {
        val c = declaration(harnessId, credId) ?: throw IllegalArgumentException("harness '$harnessId' declares no credential '$credId'")
        keystore.setField(c.secretName, HarnessCredential.FIELD, value)
    }

    fun clear(harnessId: String, credId: String = ""): Boolean {
        val c = declaration(harnessId, credId) ?: return false
        return keystore.clear(c.secretName)
    }

    /** `{ENV_NAME: key}` for every credential of [harnessId] that has a key; empty when it declares none or none is set. */
    fun env(harnessId: String, project: String? = null): Map<String, String> {
        val cs = declarations(harnessId)
        if (cs.isEmpty()) return emptyMap()
        // CLAUDE-AUTH-CHOICE-2: a subscription session carries NO key: the variable is blanked, so a key the daemon itself inherited from its own
        // environment cannot reach the harness and bill an API account (a blank variable reads as unset).
        if (subscription(harnessId)) return cs.associate { it.env to "" }
        return buildMap { for (c in cs) keyValue(c, project)?.let { put(c.env, it) } }
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
    // On Windows an npm-installed CLI is a `.cmd` shim, which CreateProcess cannot start by bare name: go through cmd /c (argv comes from a manifest, never from a client).
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val b = ProcessBuilder(if (windows) listOf("cmd", "/c") + argv else argv).redirectError(ProcessBuilder.Redirect.DISCARD)
    b.environment().remove(scrubEnv)
    val p = b.start()
    p.outputStream.close()
    val out = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.readBytes().toString(Charsets.UTF_8) }
    if (!p.waitFor(PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out.get(PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
} catch (_: Exception) { null }
