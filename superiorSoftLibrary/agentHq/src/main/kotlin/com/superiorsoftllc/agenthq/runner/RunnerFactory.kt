package com.superiorsoftllc.agenthq.runner

/** Selects the runner from `AGENTHQ_RUNNER` (default `piped`). */
object RunnerFactory {
    const val ENV = "AGENTHQ_RUNNER"

    fun fromFlag(flag: String? = System.getenv(ENV)): ProcessRunner =
        when (flag?.trim()?.lowercase()) {
            null, "", "piped", "pipe", "default" -> PipedProcessRunner()
            "pty", "pty4j" -> PtyProcessRunner()
            else -> error("Unknown $ENV='$flag' (expected 'piped' or 'pty').")
        }
}
