package com.superiorsoftllc.agenthq.runner

/** Selects the runner from `AGENTHQ_RUNNER` (default `piped`). */
object RunnerFactory {
    const val ENV = "AGENTHQ_RUNNER"

    /** `INFANTRY_RUNNER` (the product-name alias) wins over `AGENTHQ_RUNNER`; the same rule as the daemon's InfantryEnv, kept here so the runner imports nothing of the daemon. */
    fun envFlag(): String? = System.getenv("INFANTRY_RUNNER") ?: System.getenv(ENV)

    fun fromFlag(flag: String? = envFlag()): ProcessRunner =
        when (flag?.trim()?.lowercase()) {
            null, "", "piped", "pipe", "default" -> PipedProcessRunner()
            "pty", "pty4j" -> PtyProcessRunner()
            else -> error("Unknown $ENV='$flag' (expected 'piped' or 'pty').")
        }
}
