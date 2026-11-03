package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGIN-MANAGER-1: `infantry plugin ...` against a temp plugins root; no daemon, no network. */
class PluginCliTest {
    private val tmp = Files.createTempDirectory("plugin-cli").toFile()
    private val m = PluginManager(File(tmp, "plugins"), File(tmp, "state"))

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder(); val err = StringBuilder()
        return Triple(PluginCli.run(args.toList(), out, err, m), out.toString(), err.toString())
    }

    private fun pack(): File {
        val f = File(tmp, "Acme_Cli-1.0.0.ihq")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write("""{"name":"Acme_Cli","version":"1.0.0","entry":"x.Y","jar":"lib/a.jar","services":["net.fetch"]}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/a.jar")); z.write(ByteArray(64)); z.closeEntry()
        }
        return f
    }

    @Test fun `install without a grant prints what is asked and stops, then --grant-all-requested installs`() {
        val f = pack()
        val (code, _, err) = run("install", f.path)
        assertEquals(1, code)
        assertTrue(err.contains("CONSENT_REQUIRED") && err.contains("net.fetch"), err)
        assertTrue(run("list").second.contains("no plugins"))

        val (code2, out2, _) = run("install", f.path, "--grant-all-requested")
        assertEquals(0, code2, out2)
        val list = run("list").second
        assertTrue(list.contains("Acme_Cli") && list.contains("custom") && list.contains("enabled"), list)
        assertTrue(run("status", "Acme_Cli").second.contains("granted: net.fetch"))

        assertEquals(0, run("disable", "Acme_Cli").first)
        assertTrue(run("list").second.contains("disabled"))
        assertEquals(0, run("remove", "Acme_Cli").first)
        assertEquals(1, run("status", "Acme_Cli").first)
    }

    @Test fun `show warns that an unlisted package is custom, bad usage is exit 2`() {
        val (code, out, _) = run("show", pack().path)
        assertEquals(0, code)
        assertTrue(out.contains("custom plugin") && out.contains("WARNING") && out.contains("net.fetch"), out)
        assertEquals(2, run().first)
        assertEquals(2, run("frobnicate").first)
    }
}
