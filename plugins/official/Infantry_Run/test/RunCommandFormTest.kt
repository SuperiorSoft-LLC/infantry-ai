package com.superiorsoftllc.agenthq.plugins.official.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** S4b: moved from :agenthqUiCore's RunModelTest with [RunCommandForm] (Infantry_Run); RUNTIME-7: into the official module's own tests. */
class RunCommandFormTest {
    @Test fun theFormRulesDeriveTheIdAndSplitArgvByLine() {
        assertEquals("build-web-2", RunCommandForm.idFor("  Build Web (2)! "))
        assertEquals(listOf("gradlew.bat", "build", "--info"), RunCommandForm.argvOf("gradlew.bat\r\n  build \n\n--info\n"))
        assertNull(RunCommandForm.cwdOf("   "))
        assertEquals("web", RunCommandForm.cwdOf(" web "))
    }
}
