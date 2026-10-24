package com.superiorsoftllc.agenthq.daemon

import io.ktor.http.ContentType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WEB-1: the bundle is a fixed list of files, taken once; nothing outside its folder is ever listed. */
class WebBundleTest {

    private fun tempDir(): File = Files.createTempDirectory("agenthq-web-bundle").toFile()

    @Test
    fun theBundleListsItsFilesAsExactPathsAndTheRootNamesTheIndex() {
        val dir = tempDir()
        try {
            File(dir, "web/composeResources/drawable").mkdirs()
            File(dir, "web/index.html").writeText("i")
            File(dir, "web/composeResources/drawable/role.png").writeText("p")
            File(dir, "web/odd{name}.js").writeText("x")
            File(dir, "outside.txt").writeText("o")
            val bundle = WebBundle.load(File(dir, "web"))
            assertTrue(bundle.installed)
            assertEquals(listOf("/composeResources/drawable/role.png", "/index.html"), bundle.paths, "route-pattern characters are never listed")
            assertEquals("index.html", bundle.file("/")?.name)
            assertTrue(bundle.isAsset("/") && bundle.isAsset("/index.html"))
            for (p in listOf("/../outside.txt", "/outside.txt", "/composeResources", "/composeResources/", "/web/index.html", "index.html")) {
                assertFalse(bundle.isAsset(p), p)
                assertNull(bundle.file(p), p)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aMissingFolderIsAnEmptyBundleWhoseRootIsStillAnAsset() {
        val bundle = WebBundle.load(File(tempDir(), "nope"))
        assertFalse(bundle.installed)
        assertTrue(bundle.paths.isEmpty())
        assertTrue(bundle.isAsset("/"), "/ answers the not-installed note")
        assertNull(bundle.file("/"))
        assertFalse(WebBundle.EMPTY.installed)
    }

    @Test
    fun locateTakesTheFirstFolderThatHoldsAnIndex() {
        val dir = tempDir()
        try {
            val app = File(dir, "app/web").apply { mkdirs() }
            File(app, "index.html").writeText("i")
            val env = mapOf("AGENTHQ_WEB_DIR" to File(dir, "empty").path, "AGENTHQ_APP_HOME" to File(dir, "app").path)
            assertEquals(app, WebBundle.locate(getenv = { env[it] }, property = { null }))
            assertNull(WebBundle.locate(getenv = { null }, property = { null }))
            val res = File(dir, "res/web").apply { mkdirs() }
            File(res, "index.html").writeText("i")
            assertEquals(res, WebBundle.locate(getenv = { null }, property = { if (it == "compose.application.resources.dir") File(dir, "res").path else null }))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wasmIsServedAsApplicationWasmForStreamingCompilation() {
        assertEquals(ContentType("application", "wasm"), WebBundle.contentType("agentHqWeb.wasm"))
        assertTrue(WebBundle.contentType("a.mjs").match(ContentType("text", "javascript")))
        assertTrue(WebBundle.contentType("index.html").match(ContentType.Text.Html))
    }
}
