package com.superiorsoftllc.agenthq.cli

/**
 * The environment a CLI test runs with: the operator's environment with every `AGENTHQ_*` setting
 * removed.
 *
 * The CLI reads `AGENTHQ_*` to find the daemon and to scope the board it prints: `tickets` without
 * `--project` shows the project `AGENTHQ_PROJECT_ID` names, so a spawned agent sees only its own
 * project's board (CLI-T1). A test that inherited the caller's environment therefore read the
 * CALLER's board -- inside an AgentHQ session, the session's own project -- and every assertion
 * about the fixture's board failed. An inherited `AGENTHQ_URL` or `AGENTHQ_TOKEN` points the code
 * under test at the operator's own daemon instead of the fixture's, the same way.
 *
 * This is the same rule the live-daemon harness already applies to the daemon it spawns
 * ([LiveDaemonProcess]): the suite is hermetic, so a test's result does not depend on who ran it.
 */
internal object CliTestEnv {
    /** The operator's environment, minus every `AGENTHQ_*` setting. */
    private val clean: Map<String, String> =
        System.getenv().filterKeys { !(it.startsWith("AGENTHQ_", ignoreCase = true) || it.startsWith("INFANTRY_", ignoreCase = true)) }

    /** The variables the CLI under test sees. */
    val vars: Map<String, String> get() = clean

    /** [vars] as the `getenv` [Cli.run] takes. */
    val getenv: (String) -> String? = { key -> clean[key] }
}
