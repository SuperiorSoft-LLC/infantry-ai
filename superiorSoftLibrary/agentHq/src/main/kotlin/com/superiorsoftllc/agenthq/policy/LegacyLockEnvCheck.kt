package com.superiorsoftllc.agenthq.policy

/**
 * Startup trap for a retired lockdown flag. The flags in `policy/retired-lock-env.txt` used to lock the daemon by
 * themselves; now they lock nothing, so a deployment that still sets only one would run OPEN while its owner believes it is
 * locked. [verdict] names that case so the daemon can warn loudly and refuse to start.
 *
 * Opt-out: `AGENTHQ_ALLOW_RETIRED_LOCK_ENV=1` (the daemon then only warns).
 */
object LegacyLockEnvCheck {
    const val OPT_OUT_ENV = "AGENTHQ_ALLOW_RETIRED_LOCK_ENV"

    val retiredNames: List<String> by lazy {
        LegacyLockEnvCheck::class.java.getResourceAsStream("/policy/retired-lock-env.txt")?.bufferedReader()?.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        } ?: emptyList()
    }

    class Verdict(val message: String, val refuse: Boolean)

    /** Null = nothing to say; else the loud message and whether the daemon must refuse to start. */
    fun verdict(env: (String) -> String?, names: List<String> = retiredNames): Verdict? {
        if (!env(PolicyRuntime.POLICY_ENV).isNullOrBlank()) return null
        val set = names.filter { PolicyRuntime.truthy(env(it)) }
        if (set.isEmpty()) return null
        val msg = "${set.joinToString(", ")} is set but no policy is pinned, so NOTHING is locked: the flag no longer locks " +
            "anything. Set ${PolicyRuntime.POLICY_ENV}=<preset id> (and install the plugin that supplies the preset), or remove the flag."
        return if (PolicyRuntime.truthy(env(OPT_OUT_ENV))) Verdict("$msg ($OPT_OUT_ENV is set: starting anyway)", false) else Verdict("$msg Set $OPT_OUT_ENV=1 to start anyway.", true)
    }
}
