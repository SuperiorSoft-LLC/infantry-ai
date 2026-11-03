package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.CliLocation

/**
 * CLIPATH-1 (contract 1.73.0): the "CLI locations" section of the Configuration modal. One row per CLI the daemon launches
 * (claude, opencode, pi, descriptor harnesses, git). The path shown is the one the DAEMON resolved and where it came from;
 * a change is a `cli_location_set` the daemon validates (a bad path comes back as an `error` notice, never as applied).
 */
object CliLocationsSetting {
    /** The first contract whose daemon serves `cli_locations` / `cli_location_set`. */
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 73

    const val TITLE = "CLI locations"
    const val APPLIES_TO = "Where the daemon runs each CLI from. Order: your path here, then the usual install folder, then PATH"

    /** What the row says about where [location] resolved: the path and its source, or that it was not found. */
    fun status(location: CliLocation): String {
        val path = location.path ?: return when {
            location.override != null -> "Not usable: ${location.reason ?: "the path set here is not an executable file"}"
            else -> "Not found on PATH or in the usual install folders"
        }
        return "Using $path (${sourceLabel(location.source)})"
    }

    /** [CliLocation.source] in words. */
    fun sourceLabel(source: String?): String = when (source) {
        "override" -> "set here"
        "env" -> "environment variable"
        "default" -> "default install location"
        "path" -> "found on PATH"
        null -> "not found"
        else -> source
    }

    /** The paths the daemon looked at for a CLI it could not find, one per line (empty when it was found). */
    fun triedLines(location: CliLocation): List<String> = if (location.path == null) location.tried else emptyList()

    /** The text field's starting value: the user's own override, else empty (the resolved path is shown beside it). */
    fun initialText(location: CliLocation): String = location.override.orEmpty()

    /** What Save sends for [text]: a trimmed path, or null (= clear) when the field is blank. */
    fun pathToSave(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
}
