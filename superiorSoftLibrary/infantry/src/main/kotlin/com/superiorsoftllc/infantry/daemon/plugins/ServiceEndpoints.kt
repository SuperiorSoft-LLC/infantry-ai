// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

/**
 * ENDPOINT-ENV-1: resolves a module's `daemon.endpoints` (service name -> environment -> address) for the running build
 * environment. Generic: core names no service and no address; the table is the module's data.
 *
 * Order: the developer override variable ([overrideVariable]), the exact environment, then (outside prod) `dev`, then `prod`.
 * M1-OWNER-1: a test build (`staging`, `qa`, ...) whose service has a `dev` entry never falls through to the real service;
 * `prod` is the last resort only for a service that declares no `dev` address. Addresses are checked at load ([refusal]):
 * every one must be http(s), a `prod` one must be https.
 */
object ServiceEndpoints {
    const val PROD = "prod"
    const val DEV = "dev"
    const val OVERRIDE_PREFIX = "INFANTRY_ENDPOINT_"

    /** `INFANTRY_ENDPOINT_<MODULE>_<NAME>`: upper case, every character outside A-Z and 0-9 becomes `_`. */
    fun overrideVariable(moduleId: String, name: String): String =
        OVERRIDE_PREFIX + "${moduleId}_$name".uppercase().replace(Regex("[^A-Z0-9]"), "_")

    /** The address of [name] for [environment]; null when the module does not declare it (or the override is not http(s)). */
    fun resolve(table: Map<String, Map<String, String>>, moduleId: String, name: String, environment: String, env: (String) -> String?): String? {
        env(overrideVariable(moduleId, name))?.trim()?.takeIf { it.isNotEmpty() && isHttp(it) }?.let { return it.trimEnd('/') }
        val byEnv = table[name] ?: return null
        val fallback = if (environment == PROD) null else byEnv[DEV]
        return (byEnv[environment] ?: fallback ?: byEnv[PROD])?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')
    }

    /** Why the table cannot load, or null when it is fine. */
    fun refusal(table: Map<String, Map<String, String>>): String? {
        for ((name, byEnv) in table) {
            if (name.isBlank()) return "daemon.endpoints has a blank service name"
            if (byEnv.isEmpty()) return "daemon.endpoints.$name declares no environment"
            for ((environment, url) in byEnv) {
                val u = url.trim()
                if (environment.isBlank()) return "daemon.endpoints.$name has a blank environment name"
                if (environment == PROD && !u.startsWith("https://")) return "daemon.endpoints.$name.prod must be https (got '$u')"
                if (!isHttp(u)) return "daemon.endpoints.$name.$environment must be an http(s) address (got '$u')"
            }
        }
        return null
    }

    private fun isHttp(u: String): Boolean =
        runCatching { java.net.URI(u) }.getOrNull()?.let { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrBlank() } == true
}
