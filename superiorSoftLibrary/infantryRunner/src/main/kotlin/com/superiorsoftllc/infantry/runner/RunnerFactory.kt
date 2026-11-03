package com.superiorsoftllc.infantry.runner

/** Selects the runner from `INFANTRY_RUNNER` (default `piped`). */
object RunnerFactory {
    const val ENV = "INFANTRY_RUNNER"

    /** `INFANTRY_RUNNER` wins over the legacy `AGENTHQ_RUNNER`; the same rule as the daemon's InfantryEnv, kept here so the runner imports nothing of the daemon. */
    fun envFlag(): String? = System.getenv(ENV) ?: System.getenv("AGENTHQ_RUNNER")

    fun fromFlag(flag: String? = envFlag()): ProcessRunner =
        when (flag?.trim()?.lowercase()) {
            null, "", "piped", "pipe", "default" -> PipedProcessRunner()
            "pty", "pty4j" -> PtyProcessRunner()
            else -> error("Unknown $ENV='$flag' (expected 'piped' or 'pty').")
        }
}
