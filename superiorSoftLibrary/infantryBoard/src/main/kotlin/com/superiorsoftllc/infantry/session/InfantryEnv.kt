// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import java.util.Locale

/**
 * Environment lookup with the legacy-name fallback (RENAME-2): `INFANTRY_*` is the name; the old `AGENTHQ_*` name is
 * still read when only it is set, and `-Dagenthq.*` system properties are read as `-Dinfantry.*`.
 *
 * `INFANTRY_*` wins when both are set. Nothing here removes or renames the legacy variables: [all] and
 * [installProcessMirror] make both spellings visible so old scripts, hooks and role notes keep working.
 */
object InfantryEnv {
    /** The pre-rename prefix. */
    private const val LEGACY_PREFIX = "AGENTHQ_"
    private const val PREFIX = "INFANTRY_"
    private const val LEGACY_PROP = "agenthq."
    private const val PROP = "infantry."

    /** [name] read from [source]; an `INFANTRY_*` name falls back to its legacy `AGENTHQ_*` spelling. */
    fun get(name: String, source: (String) -> String? = System::getenv): String? =
        source(name) ?: aliasOf(name)?.let(source)

    /** The other spelling of [name] (`INFANTRY_PORT` <-> `AGENTHQ_PORT`); null for a name with neither prefix. */
    fun aliasOf(name: String): String? = when {
        name.startsWith(PREFIX) -> LEGACY_PREFIX + name.removePrefix(PREFIX)
        name.startsWith(LEGACY_PREFIX) -> PREFIX + name.removePrefix(LEGACY_PREFIX)
        else -> null
    }

    /** The whole environment with each name present under both spellings (the `INFANTRY_*` value wins). */
    fun all(source: Map<String, String> = System.getenv()): Map<String, String> {
        val out = HashMap(source)
        for ((k, v) in source) if (k.startsWith(LEGACY_PREFIX)) out.putIfAbsent(PREFIX + k.removePrefix(LEGACY_PREFIX), v)
        for ((k, v) in source) if (k.startsWith(PREFIX)) out[LEGACY_PREFIX + k.removePrefix(PREFIX)] = v
        return out
    }

    /** `-Dagenthq.x` read as `-Dinfantry.x`: copies every legacy property to its new name unless that is already set. */
    fun mirrorProperties(props: java.util.Properties = System.getProperties()) {
        for (k in props.stringPropertyNames().toList()) {
            if (k.lowercase(Locale.ROOT).startsWith(LEGACY_PROP)) {
                val n = PROP + k.substring(LEGACY_PROP.length)
                if (props.getProperty(n) == null) props.setProperty(n, props.getProperty(k))
            }
        }
    }

    /**
     * Makes every `AGENTHQ_*` variable of this process also readable (and inherited by children) as `INFANTRY_*`, and
     * the other way round, so the many direct `System.getenv("INFANTRY_X")` reads see a legacy-only setup. Mutating the
     * process environment needs `--add-opens java.base/java.util=ALL-UNNAMED` (the launchers pass it); without it
     * this only mirrors system properties and returns false. Call it first thing in a `main`.
     */
    fun installProcessMirror(): Boolean {
        mirrorProperties()
        return try {
            val env = System.getenv()
            val field = env.javaClass.getDeclaredField("m").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val writable = field.get(env) as MutableMap<String, String>
            for ((k, v) in all(env)) if (!env.containsKey(k)) writable[k] = v
            true
        } catch (_: Throwable) {
            false
        }
    }
}
