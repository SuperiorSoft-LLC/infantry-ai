// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RUN-CMD-REPO-1: the root of a saved command is the project's registered directory, then the daemon's repo; else a refusal that says how to register. */
class CommandRootResolutionTest {
    private val home: File = Files.createTempDirectory("infantry-cmdroot-test").toFile()
    private val projectDir = File(home, "site").also { File(it, "vue").mkdirs() }
    private val daemonRepo = File(home, "repo").also { it.mkdirs() }
    private val file = File(home, "commands.json")
    private fun json(id: String) = """{"id":"$id","argv":["x"],"cwd":"vue","graceMillis":100}"""

    @AfterTest fun cleanUp() { home.deleteRecursively() }

    @Test fun `a project with a registered path saves with no daemon repo`() {
        val store = CommandStore(file, rootOf = { p -> if (p == "p1") projectDir else null })
        assertNull(store.save(json("site-dev"), project = "p1"))
        val saved = store.registry.find("site-dev")
        assertNotNull(saved); assertEquals("p1", saved.projectId)
        assertEquals(File(projectDir, "vue").canonicalFile, saved.workingDir.canonicalFile)
        // It survives a reload (the file keeps the project).
        assertEquals("p1", CommandRegistry.load(file) { p -> if (p == "p1") projectDir else null }.find("site-dev")?.projectId)
    }

    @Test fun `the project directory wins over the daemon repo`() {
        val store = CommandStore(file, rootOf = { p -> if (p == "p1") projectDir else daemonRepo })
        assertNull(store.save(json("a"), project = "p1"))
        assertTrue(store.registry.find("a")!!.workingDir.canonicalPath.startsWith(projectDir.canonicalPath))
    }

    @Test fun `no project call falls back to the daemon repo`() {
        File(daemonRepo, "vue").mkdirs()
        val store = CommandStore(file, rootOf = { daemonRepo })
        assertNull(store.save(json("a")))
        assertNull(store.registry.find("a")!!.projectId)
    }

    @Test fun `nothing resolves - the refusal says how to register the path`() {
        val refusal = CommandStore(file, rootOf = { null }).save(json("a"), project = "p1")
        assertNotNull(refusal); assertTrue("infantry_project_config_set" in refusal && "INFANTRY_REPO" in refusal, refusal)
    }

    @Test fun `a cwd escaping the project root is still refused`() {
        val store = CommandStore(file, rootOf = { projectDir })
        assertNotNull(store.save("""{"id":"a","argv":["x"],"cwd":"../repo"}""", project = "p1"))
    }
}
