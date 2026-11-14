// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.docs.daemon

import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocsDaemonPluginTest {
    private fun loader(): ClassLoader {
        val dir = File.createTempFile("docs", "").also { it.delete(); File(it, "docs").mkdirs() }
        File(dir, "docs/index.txt").writeText("Usage\nHome\n")
        File(dir, "docs/Usage.md").writeText("# Usage and worker limits\n\nbody")
        File(dir, "docs/Home.md").writeText("no heading")
        return URLClassLoader(arrayOf(dir.toURI().toURL()), null)
    }

    @Test fun listNamesEveryPageWithTitle() {
        val pages = DocsDaemonPlugin.list(loader()).toString()
        assertTrue("Usage and worker limits" in pages && "\"Home\"" in pages)
    }

    @Test fun showIsCaseInsensitiveAndReturnsText() {
        val r = DocsDaemonPlugin.show(loader(), "usage.md")
        assertEquals("Usage", r["page"].toString().trim('"'))
        assertTrue("body" in r["text"].toString())
    }

    @Test fun unknownPageNamesTheAvailableOnes() {
        val e = assertFailsWith<IllegalArgumentException> { DocsDaemonPlugin.show(loader(), "nope") }
        assertTrue("Usage, Home" in e.message!!)
    }
}
