package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.OfficialTrust
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LOADER-2: the REAL official jars (Infantry_Git, Infantry_Chat, built by their own Gradle projects), signed here with a
 * per-run key that stands in for the pinned Infantry one, load through [DesktopPlugins] with the tier-aware
 * [PluginClassLoader] (nothing compiled in), and every class of the jar resolves through that loader. The same classes sit
 * on this test's classpath, but the filtered parent hides them, so only the jar can provide them.
 */
class DesktopPluginsOfficialJarTest {
    private val tmp = Files.createTempDirectory("official-jar-test").toFile()
    private val state = File(tmp, "state")
    private val bundled = File(tmp, "install/modules")

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    /** Copies the built jar with its module.json added (the signed declaration OfficialTrust compares), then signs it. */
    private fun install(src: File, ks: File) {
        val name = src.name
        val dir = File(bundled, "official/$name").also { it.mkdirs() }
        val json = File(src, "module.json")
        json.copyTo(File(dir, "module.json"))
        val jar = File(dir, "build/libs/$name.jar").also { it.parentFile.mkdirs() }
        JarFile(File(src, "build/libs/$name.jar")).use { j ->
            ZipOutputStream(jar.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("module.json")); z.write(json.readBytes()); z.closeEntry()
                for (e in j.entries()) {
                    if (e.isDirectory || e.name == "module.json" || e.name.startsWith("META-INF/")) continue
                    z.putNextEntry(ZipEntry(e.name)); j.getInputStream(e).use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
        tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", jar.path, "infantry")
    }

    @Test
    fun `real signed official jars load through DesktopPlugins and every class of them resolves`() {
        val sources = (System.getProperty("official.module.dirs") ?: error("official.module.dirs not set: run through Gradle jvmTest"))
            .split(File.pathSeparator).map(::File)
        val ks = File(tmp, "infantry.p12")
        tool("keytool", "-genkeypair", "-alias", "infantry", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Infantry", "-validity", "30",
            "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        val keys = File(bundled, "trusted-keys").also { it.mkdirs() }
        tool("keytool", "-exportcert", "-alias", "infantry", "-keystore", ks.path, "-storepass", "changeit", "-file", File(keys, "infantry.cer").path)
        sources.forEach { install(it, ks) }
        val pinned = setOf(OfficialTrust.fingerprint(OfficialTrust.infantryKeys(File(state, "plugins"), bundled).single()))

        val loaded = DesktopPlugins.load(state, env = { null }, property = { if (it == PluginFolders.BUNDLED_PROPERTY) bundled.path else null }, compiled = emptyList(), print = {}, pinned = pinned)
        loaded.use {
            for (src in sources) {
                val st = loaded.factory.statuses.single { it.name == src.name }
                assertEquals(ModuleState.LOADED, st.state, "${src.name}: ${st.reason}\n${loaded.log.joinToString("\n")}")
                assertEquals(Tier.OFFICIAL, st.tier)
            }
            assertTrue(loaded.factory.find("infantry.git") != null, "the Git tab is registered from the jar")
        }

        // Link every class of each jar through its own OFFICIAL loader: getDeclaredMethods/Fields resolve the host and Compose
        // types a class touches, so a package missing from the OFFICIAL allow-list is a NoClassDefFoundError here.
        for (src in sources) {
            val dir = File(bundled, "official/${src.name}")
            val jar = File(dir, "build/libs/${src.name}.jar")
            PluginClassLoader(dir, jar, DesktopPlugins::class.java.classLoader, Tier.OFFICIAL).use { loader ->
                val names = JarFile(jar).use { j -> j.entries().toList().map { it.name }.filter { it.endsWith(".class") && !it.startsWith("META-INF/") } }
                assertTrue(names.isNotEmpty())
                for (n in names) {
                    val c = Class.forName(n.removeSuffix(".class").replace('/', '.'), false, loader)
                    c.declaredMethods; c.declaredFields; c.declaredConstructors
                }
            }
        }
    }
}
