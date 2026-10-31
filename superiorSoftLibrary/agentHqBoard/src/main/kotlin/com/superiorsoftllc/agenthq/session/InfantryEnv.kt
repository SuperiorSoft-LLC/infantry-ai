package com.superiorsoftllc.agenthq.session

/**
 * Environment lookup with the product-rename aliases: every `AGENTHQ_*` variable is also read as `INFANTRY_*`.
 *
 * `INFANTRY_*` wins when both are set; otherwise the `AGENTHQ_*` name is used, so existing setups keep working.
 * Nothing here removes or renames the `AGENTHQ_*` variables.
 */
object InfantryEnv {
    private const val LEGACY_PREFIX = "AGENTHQ_"
    private const val PREFIX = "INFANTRY_"

    /** The `INFANTRY_*` alias of [name] if set, else [name] itself, read from [source]. */
    fun get(name: String, source: (String) -> String? = System::getenv): String? =
        aliasOf(name)?.let(source) ?: source(name)

    /** `INFANTRY_PORT` for `AGENTHQ_PORT`; null for a name that is not an `AGENTHQ_*` variable. */
    fun aliasOf(name: String): String? =
        if (name.startsWith(LEGACY_PREFIX)) PREFIX + name.removePrefix(LEGACY_PREFIX) else null

    /** The whole environment with each `INFANTRY_*` also present under its `AGENTHQ_*` name (INFANTRY wins). */
    fun all(source: Map<String, String> = System.getenv()): Map<String, String> {
        val out = HashMap(source)
        for ((k, v) in source) if (k.startsWith(PREFIX)) out[LEGACY_PREFIX + k.removePrefix(PREFIX)] = v
        return out
    }
}
