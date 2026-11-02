package com.superiorsoftllc.agenthq.session

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleProgressInitTest {
    @Test fun scriptIsGatedOnEnvAndPrintsPlanAndCount() {
        val s = GradleProgressInit.script
        assertTrue(s.contains("System.getenv('INFANTRY_PROGRESS') == '1'"))
        assertTrue(s.contains("##infantry plan ") && s.contains("##infantry count "))
    }

    @Test fun installIsIdempotent() {
        val home = Files.createTempDirectory("gh").toFile()
        assertTrue(GradleProgressInit.install(home))
        assertFalse(GradleProgressInit.install(home))
        assertEquals(GradleProgressInit.script, home.resolve("init.d/${GradleProgressInit.FILE_NAME}").readText())
        home.deleteRecursively()
    }
}
