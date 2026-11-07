// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TestKit: a fake plugin repo with a UI half and a daemon half (plain java projects), packed by the real plugin. */
class InfantryPluginFunctionalTest {
    private val dir = Files.createTempDirectory("infantry-plugin-test").toFile()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun write(path: String, text: String) = File(dir, path).also { it.parentFile.mkdirs() }.writeText(text)

    private fun project(manifest: String, daemon: Boolean) {
        write("settings.gradle.kts", """
            rootProject.name = "fake"
            include(":plug")
            // stand-ins for the monorepo's SDK projects: the plugin must pick them (not Maven coordinates) because they exist
            include(":superiorSoftLibrary:infantryUiCore", ":superiorSoftLibrary:infantryDaemonApi", ":superiorSoftLibrary:infantryHarnessApi")
            ${if (daemon) """include(":daemon-plugin-official-Acme_Mod"); project(":daemon-plugin-official-Acme_Mod").projectDir = file("official/Acme_Mod/daemon")""" else ""}
            project(":plug").projectDir = file("official/Acme_Mod")
        """.trimIndent())
        listOf("infantryUiCore", "infantryDaemonApi", "infantryHarnessApi").forEach { write("superiorSoftLibrary/$it/build.gradle.kts", "plugins { java }") }
        write("superiorSoftLibrary/build.gradle.kts", "")
        write("official/Acme_Mod/module.json", manifest)
        write("official/Acme_Mod/build.gradle.kts", """
            plugins { java; id("tech.superiorsoftllc.infantry.plugin") }
            tasks.jar { archiveFileName.set("Acme_Mod.jar") }
        """.trimIndent())
        write("official/Acme_Mod/src/main/java/a/A.java", "package a; public class A {}")
        if (daemon) {
            write("official/Acme_Mod/daemon/build.gradle.kts", """
                plugins { java }
                tasks.jar { archiveFileName.set("Acme_Mod-daemon.jar") }
            """.trimIndent())
            write("official/Acme_Mod/daemon/src/main/java/b/B.java", "package b; public class B {}")
        }
    }

    private fun run(vararg args: String, fail: Boolean = false) =
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace").let { if (fail) it.buildAndFail() else it.build() }

    @Test fun packsBothHalvesAndWritesCatalogEntry() {
        project("""{"name":"Acme_Mod","version":"1.2.3","apiVersion":2,"entry":"a.A","jar":"build/libs/Acme_Mod.jar","daemon":{"entry":"b.B","jar":"daemon/build/libs/x.jar","apiAtLeast":"1.8.0"}}""", daemon = true)
        run(":plug:catalogEntry", "-Pihq.url=https://example.test/Acme_Mod.ihq")
        val ihq = File(dir, "official/Acme_Mod/build/ihq/Acme_Mod.ihq")
        ZipFile(ihq).use { z ->
            assertEquals(setOf("module.json", "lib/Acme_Mod.jar", "lib/Acme_Mod-daemon.jar"), z.entries().toList().filter { !it.isDirectory }.map { it.name }.toSet())
            val m = z.getInputStream(z.getEntry("module.json")).bufferedReader().readText()
            assertTrue("\"jar\": \"lib/Acme_Mod.jar\"" in m, m)
            assertTrue("\"jar\": \"lib/Acme_Mod-daemon.jar\"" in m, m)
        }
        val entry = File(dir, "official/Acme_Mod/build/ihq/catalog-entry.json").readText()
        val sha = MessageDigest.getInstance("SHA-256").digest(ihq.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue("\"sha256\": \"$sha\"" in entry, entry)
        assertTrue("\"size\": ${ihq.length()}" in entry, entry)
        assertTrue("\"url\": \"https://example.test/Acme_Mod.ihq\"" in entry, entry)
        assertTrue("\"version\": \"1.2.3\"" in entry && "\"daemon\": \">=1.8.0\"" in entry && "\"plugin\": \">=2\"" in entry, entry)
    }

    @Test fun uiOnlyModulePacksOneJar() {
        project("""{"name":"Acme_Mod","version":"1.0.0","entry":"a.A","jar":"x"}""", daemon = false)
        run(":plug:ihq")
        ZipFile(File(dir, "official/Acme_Mod/build/ihq/Acme_Mod.ihq")).use { z ->
            assertEquals(setOf("module.json", "lib/Acme_Mod.jar"), z.entries().toList().filter { !it.isDirectory }.map { it.name }.toSet())
        }
    }

    @Test fun refusesDaemonDeclaredWithoutFolder() {
        project("""{"name":"Acme_Mod","version":"1.0.0","entry":"a.A","daemon":{"entry":"b.B"}}""", daemon = false)
        assertTrue("declares \"daemon\" but daemon/build.gradle.kts does not exist" in run(":plug:ihq", fail = true).output)
    }

    @Test fun refusesNameThatIsNotAFolderName() {
        project("""{"name":"../evil","version":"1.0.0","entry":"a.A"}""", daemon = false)
        assertTrue("must be a plain folder name" in run(":plug:ihq", fail = true).output)
    }
}
