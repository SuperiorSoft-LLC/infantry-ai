// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.protocol.ProjectSave
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ONBOARD-2: a starter project gets a folder the daemon makes under its projects root; a reused folder is fine. */
class StarterProjectTest {
    private val root: File = Files.createTempDirectory("projects-root").toFile()

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    @Test
    fun `a starter project is created in a new folder under the root`() {
        val store = ProjectStore(projectsRoot = { File(root, "Infantry AI") })
        val p = store.save(ProjectSave(name = "My first project", directory = "ignored", starter = true))
        val dir = File(root, "Infantry AI/My first project")
        assertTrue(dir.isDirectory)
        assertEquals(dir.absolutePath, p.directory)
        // Again (a second setup run): the folder is reused, never refused.
        assertEquals(dir.absolutePath, store.save(ProjectSave(name = "My first project", starter = true)).directory)
    }

    @Test
    fun `a name is made safe as a folder name`() {
        val store = ProjectStore(projectsRoot = { root })
        val p = store.save(ProjectSave(name = "a/b:c*", starter = true))
        assertEquals(File(root, "a_b_c_").absolutePath, p.directory)
    }

    @Test
    fun `the flag is optional on the wire`() {
        assertEquals(false, ProjectSave.decode("""{"name":"x"}""").starter)
        assertEquals(true, ProjectSave.decode("""{"name":"x","starter":true}""").starter)
    }
}
