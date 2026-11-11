// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCredentials
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec

/**
 * RUNNER-CRED-1 (orchestrator side): what this orchestrator lends a session it starts on a remote runner, resolved at the start
 * from its own keystore, so a fresh runner needs no `-e` key, no keystore and no git login of its own.
 *
 * * env: the harness credential of the session's harness ([harnessEnv], the same map a local spawn gets; blank values, as in
 *   subscription mode, are not lent).
 * * secrets: the user secrets a render in the session's project can name ([secretNames] / [secret]), at most [maxSecrets]; a
 *   built-in (`harness.*`, `plugin.*`, the update token) never goes.
 * * git: the secret named by `runners.gitSecret` ([gitSecret]): field `token` (or its only field) and optional `username`.
 *
 * [lendsTo] is the per-runner allow-list (`runners.lendCredentials`); a runner off the list gets nothing.
 */
class CredentialLender(
    private val lendsTo: (runner: String) -> Boolean,
    private val harnessEnv: (harnessId: String, projectId: String?) -> Map<String, String>,
    private val harnessIds: () -> Collection<String>,
    private val secretNames: (projectId: String?) -> Collection<String>,
    private val secret: (name: String, projectId: String?) -> Map<String, String>?,
    private val gitSecret: () -> String,
    private val maxSecrets: () -> Int,
) {
    /** The credentials for [spec] on [runner], or null when nothing is lent. */
    fun lend(runner: String, spec: StartSpec): SessionCredentials? {
        if (!lendsTo(runner)) return null
        val project = spec.projectId?.takeIf { it.isNotBlank() }
        val env = spec.provider?.takeIf { it.isNotBlank() }
            ?.let { runCatching { harnessEnv(it, project) }.getOrDefault(emptyMap()) }.orEmpty()
            .filterValues { it.isNotEmpty() }
        val gitName = gitSecret().trim()
        val git = gitName.takeIf { it.isNotEmpty() }?.let { runCatching { secret(it, project) }.getOrNull() }?.let(::gitOf)
        val max = maxSecrets().coerceAtLeast(0)
        val secrets = LinkedHashMap<String, Map<String, String>>()
        for (name in runCatching { secretNames(project) }.getOrDefault(emptyList()).sorted()) {
            if (secrets.size >= max) break
            if (name == gitName || BUILT_IN.any { name.startsWith(it) }) continue
            runCatching { secret(name, project) }.getOrNull()?.filterValues { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { secrets[name] = it }
        }
        return SessionCredentials(env, secrets, git).takeUnless { it.isEmpty }
    }

    /** The harness ids whose key [runner] is lent at each start: a runner reports them signed in (placement, health). */
    fun lentKeys(runner: String): Set<String> {
        if (!lendsTo(runner)) return emptySet()
        return runCatching { harnessIds() }.getOrDefault(emptyList()).filter { id ->
            runCatching { harnessEnv(id, null) }.getOrDefault(emptyMap()).values.any { it.isNotEmpty() }
        }.toSet()
    }

    companion object {
        private val BUILT_IN = listOf("harness.", "plugin.")

        /** A git credential from a secret's [fields]: `token` (else the only field) and optional `username`; null when none. */
        fun gitOf(fields: Map<String, String>): GitCredential? {
            val token = (fields["token"] ?: fields.values.singleOrNull())?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val user = fields["username"]?.trim()?.takeIf { it.isNotEmpty() }
            return if (user == null) GitCredential(token) else GitCredential(token, user)
        }
    }
}
