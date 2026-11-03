package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CLI's test environment is hermetic.
 *
 * `tickets` without `--project` shows the project `INFANTRY_PROJECT_ID` names (CLI-T1), so a CLI
 * that inherited the caller's environment read the CALLER's board: inside an Infantry session that
 * is the session's own project, and the acceptance tests' own projects were filtered out of the
 * answer. Eleven acceptance tests failed, and only for the caller whose environment named a
 * project. The environment the CLI under test sees must therefore carry no `INFANTRY_*` setting.
 */
class CliTestEnvTest {

    @Test
    fun theEnvironmentCarriesNoInfantrySetting() {
        assertNull(
            CliTestEnv.getenv(AccessPolicy.PROJECT_ID_ENV),
            "the CLI must not see the caller's project scope",
        )
        assertTrue(
            CliTestEnv.vars.keys.none { it.startsWith("INFANTRY_", ignoreCase = true) },
            "the CLI environment must carry no INFANTRY_* setting: ${CliTestEnv.vars.keys}",
        )
    }

    @Test
    fun theRestOfTheEnvironmentSurvives() {
        val survivor = System.getenv().keys.firstOrNull { !it.startsWith("INFANTRY_", ignoreCase = true) }
        assertNotNull(survivor, "the operator's environment holds something that is not INFANTRY_*")
        assertEquals(
            System.getenv()[survivor],
            CliTestEnv.getenv(survivor),
            "the CLI still needs the rest of the environment",
        )
    }
}
