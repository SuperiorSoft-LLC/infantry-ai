package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.73.0 (CLIPATH-1): one CLI the daemon launches, as Settings > "CLI locations" shows it.
 *
 *  - [id]: the harness / provider id, or `git`; what `cli_location_set` names.
 *  - [path]: the executable a launch uses right now; null = not found (then [reason] says why, [tried] lists every path).
 *  - [source]: where [path] came from: `override` (Settings), `env` (its `INFANTRY_*_EXE` variable), `default` (a known
 *    install location) or `path` (PATH); null when not found.
 *  - [override]: the path the user set in Settings, null when none (it is listed even when it is broken).
 */
@Serializable
data class CliLocation(
    val id: String,
    val label: String,
    val path: String? = null,
    val source: String? = null,
    val override: String? = null,
    val tried: List<String> = emptyList(),
    val reason: String? = null,
)

/** The `cli_locations` frame: every CLI, built-ins first (claude, opencode, pi, git), then descriptor harnesses. */
@Serializable
data class CliLocations(val tools: List<CliLocation>)

/**
 * The `cli_location_set` payload. [path] null or blank CLEARS the override (back to default location / PATH). A path
 * that is not an existing executable file is refused (`error` "cli_location_set refused: <reason>"), never stored.
 */
@Serializable
data class CliLocationSet(val id: String, val path: String? = null) {
    companion object {
        fun decode(text: String): CliLocationSet = StrictJson.decode(serializer(), text, "cli_location_set payload")
    }
}
