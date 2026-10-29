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
import kotlin.test.Ignore
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

        // ticket-e063ce77 (Header needs ui.net.Controller) has its own @Ignore test below; everything else must link.
        val other = linkAll(sources).filter { !it.startsWith("Infantry_Header") }
        assertTrue(other.isEmpty(), "classes that do not link under the OFFICIAL loader (${other.size}):\n" + other.joinToString("\n"))
    }

    /** Links every class of each jar through its own OFFICIAL loader; returns "module: class -> cause" per failure. */
    private fun linkAll(sources: List<File>): List<String> {
        val linkFailures = mutableListOf<String>()
        for (src in sources) {
            val dir = File(bundled, "official/${src.name}")
            val jar = File(dir, "build/libs/${src.name}.jar")
            PluginClassLoader(dir, jar, DesktopPlugins::class.java.classLoader, Tier.OFFICIAL).use { loader ->
                val names = JarFile(jar).use { j -> j.entries().toList().map { it.name }.filter { it.endsWith(".class") && !it.startsWith("META-INF/") } }
                assertTrue(names.isNotEmpty())
                for (n in names) {
                    runCatching {
                        val c = Class.forName(n.removeSuffix(".class").replace('/', '.'), false, loader)
                        c.declaredMethods; c.declaredFields; c.declaredConstructors
                    }.onFailure { linkFailures += "${src.name}: $n -> ${it.cause?.message ?: it.message}" }
                }
            }
        }
        return linkFailures
    }

    @Test
    @Ignore("ticket-e063ce77: Infantry_Header needs ui.net.Controller, hidden from the OFFICIAL loader")
    fun `official jars - Infantry_Header classes link under the OFFICIAL loader`() {
        installAll()
        val failures = linkAll(allSources.filter { it.name == "Infantry_Header" })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- QA-E2E-1: all official jars, no-jar-no-tab, startup budget, leaks ----

    private val allSources get() = (System.getProperty("official.module.dirs") ?: error("official.module.dirs not set: run through Gradle jvmTest"))
        .split(File.pathSeparator).map(::File)

    private var pinnedKeys: Set<String> = emptySet()

    /** Signs every official jar once under [bundled] with a per-run key and pins its fingerprint. */
    private fun installAll() {
        val ks = File(tmp, "infantry.p12")
        tool("keytool", "-genkeypair", "-alias", "infantry", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Infantry", "-validity", "30",
            "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        val keys = File(bundled, "trusted-keys").also { it.mkdirs() }
        tool("keytool", "-exportcert", "-alias", "infantry", "-keystore", ks.path, "-storepass", "changeit", "-file", File(keys, "infantry.cer").path)
        allSources.forEach { install(it, ks) }
        pinnedKeys = setOf(OfficialTrust.fingerprint(OfficialTrust.infantryKeys(File(state, "plugins"), bundled).single()))
    }

    /** A bundled root holding the signed keys and only the jars [keep] accepts. */
    private fun subset(label: String, keep: (String) -> Boolean): File {
        val root = File(tmp, "subset-$label/modules")
        File(bundled, "trusted-keys").copyRecursively(File(root, "trusted-keys"))
        File(bundled, "official").listFiles()!!.filter { keep(it.name) }.forEach { it.copyRecursively(File(root, "official/${it.name}")) }
        return root
    }

    private fun loadFrom(root: File, stateName: String = "s-" + root.parentFile.name) =
        DesktopPlugins.load(File(tmp, stateName), env = { null }, property = { if (it == PluginFolders.BUNDLED_PROPERTY) root.path else null }, compiled = emptyList(), print = {}, pinned = pinnedKeys)

    private fun listIn(src: File, key: String, item: Regex): List<String> =
        Regex("\"$key\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(File(src, "module.json").readText())
            ?.groupValues?.get(1)?.let { body -> item.findAll(body).map { it.groupValues[1] }.toList() }.orEmpty()

    private fun tabsOf(src: File) = listIn(src, "tabs", Regex("\"id\"\\s*:\\s*\"([^\"]+)\""))
    private fun fillsOf(src: File) = listIn(src, "fills", Regex("\"([^\"]+)\""))

    @Test
    fun `all official jars load and register their tab and slot fills`() {
        installAll()
        loadFrom(bundled).use { loaded ->
            assertEquals(allSources.size, loaded.factory.statuses.count { it.tier == Tier.OFFICIAL }, loaded.log.joinToString("\n"))
            for (src in allSources) {
                val st = loaded.factory.statuses.single { it.name == src.name }
                assertEquals(ModuleState.LOADED, st.state, "${src.name}: ${st.reason}")
                tabsOf(src).forEach { assertTrue(loaded.factory.find(it) != null, "${src.name}: tab $it registered") }
                fillsOf(src).forEach { assertTrue(loaded.factory.fills(it), "${src.name}: slot $it filled") }
            }
        }
    }

    @Test
    fun `no jar means no tab, and an empty shell boots`() {
        installAll()
        for (src in allSources) {
            loadFrom(subset("minus-${src.name}") { it != src.name }).use { loaded ->
                assertTrue(loaded.factory.statuses.none { it.name == src.name && it.state == ModuleState.LOADED }, "${src.name} absent")
                tabsOf(src).forEach { assertTrue(loaded.factory.find(it) == null, "${src.name}: tab $it must be absent") }
                fillsOf(src).forEach { assertTrue(!loaded.factory.fills(it), "${src.name}: slot $it must be unfilled") }
            }
        }
        loadFrom(subset("empty") { false }).use { loaded ->
            assertTrue(loaded.factory.modules.isEmpty(), "zero jars, zero modules")
            assertTrue(loaded.factory.tabs("center").isEmpty())
        }
    }

    @Test
    fun `startup budget - each jar loads under 500 ms and the total is recorded`() {
        installAll()
        // Warm the JVM (host class loading) so the budget measures the plugin, not first-use cost.
        loadFrom(subset("warm") { it == allSources.first().name }).use { }
        val report = StringBuilder()
        for (src in allSources) {
            val root = subset("only-${src.name}") { it == src.name }
            val t0 = System.nanoTime()
            loadFrom(root).use { assertEquals(ModuleState.LOADED, it.factory.statuses.single { s -> s.name == src.name }.state) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            report.appendLine("${src.name}: $ms ms")
            assertTrue(ms < 500, "${src.name} took $ms ms (budget 500)")
        }
        val t0 = System.nanoTime()
        loadFrom(bundled, "s-all").use { }
        val total = (System.nanoTime() - t0) / 1_000_000
        report.appendLine("ALL ${allSources.size}: $total ms")
        File(System.getProperty("java.io.tmpdir"), "qa-e2e-1-startup.txt").writeText(report.toString())
        println(report)
        assertTrue(total < 500L * allSources.size, "total $total ms")
    }

    private fun collected(ref: java.lang.ref.WeakReference<*>): Boolean {
        repeat(40) { System.gc(); if (ref.get() == null) return true; Thread.sleep(50) }
        return ref.get() == null
    }

    @Test
    @Ignore("ticket-b8c0b854: all 25 loaders stay reachable after Loaded.close")
    fun `an unloaded plugin class loader is garbage collected`() {
        installAll()
        val refs = mutableListOf<Pair<String, java.lang.ref.WeakReference<ClassLoader>>>()
        loadFrom(bundled).use { loaded ->
            loaded.factory.modules.forEach { refs += it.manifest.name to java.lang.ref.WeakReference(it::class.java.classLoader) }
        }
        assertEquals(allSources.size, refs.size)
        val leaked = refs.filter { !collected(it.second) }.map { it.first }
        assertTrue(leaked.isEmpty(), "class loaders still reachable after unload: $leaked")
    }

    /** Control for the test above: a bare PluginClassLoader that linked a jar's classes goes away when closed and dropped. */
    @Test
    fun `a bare plugin class loader with linked classes is garbage collected`() {
        installAll()
        val src = allSources.first { it.name == "Infantry_Git" }
        val dir = File(bundled, "official/${src.name}")
        val jar = File(dir, "build/libs/${src.name}.jar")
        val ref = linkAndDrop(dir, jar)
        assertTrue(collected(ref), "bare loader still reachable")
    }

    private fun linkAndDrop(dir: File, jar: File): java.lang.ref.WeakReference<ClassLoader> {
        val loader = PluginClassLoader(dir, jar, DesktopPlugins::class.java.classLoader, Tier.OFFICIAL)
        JarFile(jar).use { j -> j.entries().toList().map { it.name }.filter { it.endsWith(".class") }.forEach { runCatching { Class.forName(it.removeSuffix(".class").replace('/', '.'), false, loader).declaredMethods } } }
        loader.close()
        return java.lang.ref.WeakReference(loader)
    }

    private fun usedHeap(): Long { repeat(5) { System.gc(); Thread.sleep(50) }; val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }

    @Test
    fun `load and unload repeatedly does not grow threads or heap`() {
        installAll()
        fun cycle() { loadFrom(bundled, "s-leak").close() }
        repeat(2) { cycle() }
        val threads0 = Thread.activeCount()
        val heap0 = usedHeap()
        repeat(6) { cycle() }
        val heap1 = usedHeap()
        val threads1 = Thread.activeCount()
        assertTrue(threads1 <= threads0 + 3, "threads $threads0 -> $threads1")
        assertTrue(heap1 - heap0 < 64L * 1024 * 1024, "heap grew ${(heap1 - heap0) / 1024 / 1024} MB over 6 cycles")
    }
}
