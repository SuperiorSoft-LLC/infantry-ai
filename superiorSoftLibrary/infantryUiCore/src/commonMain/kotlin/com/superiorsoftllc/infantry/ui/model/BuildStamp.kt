package com.superiorsoftllc.infantry.ui.model

/**
 * BUILD-STAMP-1: a build string, `<version>+<UTC yyyyMMdd>.<UTC HHmm>.<short sha>[.dirty]` (e.g. `1.0.1+20261023.1432.e85b223`),
 * as Gradle stamps it (root `gradle/build-stamp.gradle.kts`). The app's own comes from its host ([PlatformHost.appBuild]), the
 * daemon's from its `ready` frame (`build=`). Anything that is not in that shape (a dev "1.0.1", "dev") is kept as [raw] only.
 */
data class BuildStamp(
    val raw: String,
    val version: String? = null,
    /** `yyyyMMdd`, UTC. */
    val date: String? = null,
    /** `HHmm`, UTC. */
    val time: String? = null,
    val sha: String? = null,
    /** Built from a working tree with uncommitted changes. */
    val dirty: Boolean = false,
) {
    /** `2026-10-23 14:32 UTC`, or null when [raw] carries no time. */
    val builtAt: String?
        get() = if (date == null || time == null) null
        else "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)} ${time.substring(0, 2)}:${time.substring(2, 4)} UTC"

    /** One line for Configuration > About: `version 1.0.1, built 2026-10-23 14:32 UTC, commit e85b223, uncommitted changes`. */
    val detail: String?
        get() = if (sha == null) null
        else listOfNotNull("version $version", builtAt?.let { "built $it" }, "commit $sha", "uncommitted changes".takeIf { dirty }).joinToString(", ")

    /**
     * Whether [other] is built from the same code: same version, commit and dirty flag. The time is left out, so the same
     * commit packaged twice is not a mismatch. Unparsed strings compare as text.
     */
    fun sameCode(other: BuildStamp): Boolean =
        if (sha != null && other.sha != null) version == other.version && sha == other.sha && dirty == other.dirty else raw == other.raw

    companion object {
        val PATTERN = Regex("""^(\d+\.\d+\.\d+)\+(\d{8})\.(\d{4})\.([0-9a-f]{4,40}|unknown)(\.dirty)?$""")

        /** Null for null/blank; a [BuildStamp] with only [raw] for a string that is not a full stamp. */
        fun parse(raw: String?): BuildStamp? {
            val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val m = PATTERN.matchEntire(s) ?: return BuildStamp(s)
            val (version, date, time, sha, dirty) = m.destructured
            return BuildStamp(s, version, date, time, sha, dirty.isNotEmpty())
        }
    }
}

/** BUILD-STAMP-1: what the footer and Configuration > About say about the app's and the daemon's builds. */
object BuildCheck {
    /** The plain warning when the app and the daemon are different builds; null when they match or either is unknown. */
    fun mismatch(appBuild: String?, daemonBuild: String?): String? {
        val app = BuildStamp.parse(appBuild) ?: return null
        val daemon = BuildStamp.parse(daemonBuild) ?: return null
        if (app.sameCode(daemon)) return null
        return "Different builds: this app is ${app.raw} but the daemon is ${daemon.raw}. Restart or update the older one."
    }

    /** The footer's short text, and whether it is a warning (the builds differ). */
    fun footer(appBuild: String?, daemonBuild: String?): Pair<String, Boolean> {
        val app = BuildStamp.parse(appBuild)
        val daemon = BuildStamp.parse(daemonBuild)
        return when {
            mismatch(appBuild, daemonBuild) != null -> "app ${app!!.raw} \u2260 daemon ${daemon!!.raw}" to true
            app != null -> "build ${app.raw}" to false
            daemon != null -> "daemon build ${daemon.raw}" to false
            else -> "build unknown" to false
        }
    }
}
