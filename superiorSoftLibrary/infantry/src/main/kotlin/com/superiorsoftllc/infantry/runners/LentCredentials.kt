// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCredentials
import com.superiorsoftllc.infantry.harness.credential.HarnessCredential
import com.superiorsoftllc.infantry.keystore.Keystore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * RUNNER-CRED-1 (runner side): the credentials an orchestrator lent the sessions it started here, in MEMORY only. Nothing here is
 * written to disk, logged or answered back over any frame; a daemon restart forgets every loan (the orchestrator lends again at
 * its next start).
 *
 * * The harness key ([SessionCredentials.env]) goes to the session's launches through `SessionManager.lendEnv`, not here.
 * * The secrets answer a render (`{{secret:NAME}}`) in the session's project ([secretFields]) after this runner's own keystore
 *   found nothing: the runner's own say wins, as it does for the harness key and the git identity.
 * * The git credential is the one [RemoteStarts] clones and pushes the session's ticket branch with.
 *
 * A loan lives while its session's record exists on this runner ([alive]); [release] drops it at once (a remove).
 */
class LentCredentials(private val alive: (sessionId: String) -> Boolean = { true }) {
    private class Loan(val seq: Long, val projectId: String?, val secrets: Map<String, Map<String, String>>)

    private val loans = ConcurrentHashMap<String, Loan>()
    private val seq = AtomicLong()

    /** Keep [credentials]' secrets for [sessionId] of [projectId] (the env and git parts are held by their own users). */
    fun lend(sessionId: String, projectId: String?, credentials: SessionCredentials) {
        if (credentials.secrets.isEmpty()) loans.remove(sessionId)
        else loans[sessionId] = Loan(seq.incrementAndGet(), projectId?.takeIf { it.isNotBlank() }, credentials.secrets)
    }

    fun release(sessionId: String) { loans.remove(sessionId) }

    /** The fields of lent secret [name] for a render in [projectId]: the newest live loan of that project that has it; else null. */
    fun secretFields(name: String, projectId: String?): Map<String, String>? = live(projectId).firstOrNull { name in it.secrets }?.secrets?.get(name)

    /** The lent secret names a render in [projectId] can use (names only). */
    fun secretNames(projectId: String?): Set<String> = live(projectId).flatMap { it.secrets.keys }.toSet()

    /** How many sessions hold a loan (tests, health). */
    fun size(): Int = loans.size

    private fun live(projectId: String?): List<Loan> {
        loans.keys.filterNot { runCatching { alive(it) }.getOrDefault(false) }.forEach(loans::remove)
        val p = projectId?.takeIf { it.isNotBlank() }
        return loans.values.filter { it.projectId == p }.sortedByDescending { it.seq }
    }

    /** What [screen] kept, and the NAMES of what it dropped with why (for the audit line; never a value). */
    data class Screened(val credentials: SessionCredentials, val dropped: List<String>) {
        /** One audit fragment: names only. */
        /** The same loan without its env: the harness is signed in on this runner, and the runner's own login wins. */
        fun ownLogin(): Screened = Screened(credentials.copy(env = emptyMap()), dropped + credentials.env.keys.sorted().map { "env:$it(runner signed in)" })

        fun summary(): String = buildList {
            if (credentials.env.isNotEmpty()) add("env ${credentials.env.keys.sorted().joinToString(",")}")
            if (credentials.secrets.isNotEmpty()) add("secrets ${credentials.secrets.size}")
            if (credentials.git != null) add("git")
            if (dropped.isNotEmpty()) add("dropped ${dropped.joinToString(",")}")
        }.joinToString("; ")
    }

    companion object {
        /** The scope label a lent secret shows in a run's secret list (names only). */
        const val SCOPE = "lent"

        /**
         * The part of [lent] this runner accepts: env names a harness credential may use ([HarnessCredential.envRefusal]: no
         * `INFANTRY_*`, `PATH`, `LD_PRELOAD`...), blank values dropped; at most [maxSecrets] secrets with user names (no
         * `harness.` / `plugin.` built-in, which a render never reads); a git token of one line.
         */
        fun screen(lent: SessionCredentials, maxSecrets: Int): Screened {
            val dropped = mutableListOf<String>()
            val env = lent.env.filter { (k, v) ->
                val ok = HarnessCredential.envRefusal(k) == null && v.isNotEmpty() && '\u0000' !in v
                if (!ok) dropped += "env:${k.take(40)}"
                ok
            }
            val secrets = LinkedHashMap<String, Map<String, String>>()
            for ((name, fields) in lent.secrets) {
                val ok = Keystore.isName(name) && !name.startsWith("harness.") && !name.startsWith("plugin.") && fields.isNotEmpty()
                when {
                    !ok -> dropped += "secret:${name.take(64)}"
                    secrets.size >= maxSecrets.coerceAtLeast(0) -> dropped += "secret:${name.take(64)}(over limits.runners.lendSecretsMax)"
                    else -> secrets[name] = fields.toMap()
                }
            }
            val git = lent.git?.takeIf { g -> g.token.isNotBlank() && g.token.none { it == '\n' || it == '\r' } && g.username.isNotBlank() && g.username.none { it == ':' || it == '\n' || it == '\r' } }
            if (lent.git != null && git == null) dropped += "git"
            return Screened(SessionCredentials(env, secrets, git), dropped)
        }

        /**
         * The environment that hands [credential] to ONE git call as an HTTP header, through git's own `GIT_CONFIG_*` variables: never
         * in argv (a process list), never in the clone's config or the remote URL (the volume), never in a credential store.
         */
        fun gitEnv(credential: GitCredential?): Map<String, String> {
            if (credential == null) return emptyMap()
            val basic = java.util.Base64.getEncoder().encodeToString("${credential.username}:${credential.token}".toByteArray(Charsets.UTF_8))
            return mapOf(
                "GIT_CONFIG_COUNT" to "1",
                "GIT_CONFIG_KEY_0" to "http.extraHeader",
                "GIT_CONFIG_VALUE_0" to "Authorization: Basic $basic",
            )
        }
    }
}
