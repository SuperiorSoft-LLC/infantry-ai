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
        if (subscription(harnessId)) return emptyMap()
        val v = keystore.field(c.secretName, HarnessCredential.FIELD) ?: return emptyMap()
        return mapOf(c.env to v)
    }

    /** The declared credentials with no key set: what the first-run step asks for. */
    fun missing(): List<HarnessCredential> = states().filter { !it.set && !subscription(it.credential.harnessId) }.map { it.credential }
}
