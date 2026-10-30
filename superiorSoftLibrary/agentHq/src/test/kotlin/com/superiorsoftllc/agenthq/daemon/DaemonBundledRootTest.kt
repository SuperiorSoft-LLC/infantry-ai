package com.superiorsoftllc.agenthq.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PACKAGE-1: [installModules] finds the bundled modules root of each install layout (docs/DAEMON-PLUGINS.md section 9). */
class DaemonBundledRootTest {
    private val dir = Files.createTempDirectory("daemon-bundled-root").toFile()

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun file(path: String) = File(dir, path).apply { parentFile.mkdirs(); writeText("x") }
    private fun folder(path: String) = File(dir, path).apply { mkdirs() }

    @Test
    fun `installDist, headless and docker - modules next to lib`() {
        val modules = folder("install/modules")
        assertEquals(modules, installModules(file("install/lib/agentHq.jar"), property = { null }))
    }

    @Test
    fun `the desktop app image - modules under the compose resources dir, jars in app with no lib`() {
        val modules = folder("InfantryAI/app/resources/modules")
        val resources = File(dir, "InfantryAI/app/resources").path
        assertEquals(modules, installModules(file("InfantryAI/app/agentHq-abc.jar"), property = { if (it == "compose.application.resources.dir") resources else null }))
    }

    @Test
    fun `a dev run from classes, or a layout without modules, has none`() {
        assertNull(installModules(folder("build/classes/kotlin/main"), property = { null }))
        assertNull(installModules(file("install/lib/agentHq.jar"), property = { null }), "lib/ without a modules folder")
        val resources = folder("dev/resources").path
        assertNull(installModules(folder("build/classes"), property = { if (it == "compose.application.resources.dir") resources else null }), "a `run` resources dir has no modules/")
    }
}
