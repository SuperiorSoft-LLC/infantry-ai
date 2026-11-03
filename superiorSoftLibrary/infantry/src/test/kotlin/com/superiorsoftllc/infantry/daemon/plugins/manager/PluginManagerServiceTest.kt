// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins.manager

import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.update.ReleaseAssets
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PLUGIN-MANAGER-1: the manager end to end on a temp plugins root with a fake (folder) catalog and synthetic Acme packages.
 * Signatures are real (keytool/jarsigner, keys generated per run); nothing touches the network or the owner's state.
 */
class PluginManagerServiceTest {
    private val tmp = Files.createTempDirectory("pm-test").toFile()
    private val root = File(tmp, "plugins").also { it.mkdirs() }
    private val state = File(tmp, "state").also { it.mkdirs() }
    private val repo = File(tmp, "repo").also { it.mkdirs() }
    private val noNetwork = Fetcher { url, _, _ -> throw IOException("offline: $url") }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun manager(fetcher: Fetcher = DefaultFetcher) = PluginManager(root, state, fetcher = fetcher)

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    private fun key(alias: String): File {
        val ks = File(tmp, "$alias.p12")
        tool("keytool", "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=$alias", "-validity", "30", "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        return ks
    }

    private fun trust(alias: String, ks: File, sub: String = "") {
        val dir = File(File(root, "trusted-keys"), sub).also { it.mkdirs() }
        tool("keytool", "-exportcert", "-alias", alias, "-keystore", ks.path, "-storepass", "changeit", "-file", File(dir, "$alias.cer").path)
    }

    private fun sign(f: File, ks: File, alias: String) = tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", f.path, alias)

    /** An Acme package: module.json + one jar. [extra] is spliced into the manifest (a daemon block, services). */
    private fun pack(id: String = "Acme_Echo", version: String = "1.0.0", extra: String = "", api: Int = 2, dir: File = repo): File {
        val f = File(dir, "$id-$version.ihq")
        val manifest = """{"name":"$id","version":"$version","apiVersion":$api,"license":"Apache-2.0","entry":"x.Y","jar":"lib/$id.jar"${if (extra.isEmpty()) "" else ",$extra"}}"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/$id.jar")); z.write(ByteArray(300) { (it + version.hashCode()).toByte() }); z.closeEntry()
        }
        return f
    }

    private val daemonBlock = """"daemon":{"entry":"x.D","jar":"lib/Acme_Echo.jar","tools":[{"name":"echo","action":"echo.read"}]}"""

    private fun entry(f: File, id: String, version: String, grants: List<String>, api: ApiRanges = ApiRanges(), sha: String = ReleaseAssets.sha256(f)) =
        CatalogEntry(id, "test plugin", listOf("test"), "Apache-2.0", null, listOf(CatalogVersion(version, api, grants, f.name, f.length(), sha)))

    /** Writes `repo/catalog.json` and returns it; entries for the same id are merged into one with all versions. */
    private fun catalog(vararg entries: CatalogEntry, cls: String = "community"): Catalog {
        val merged = entries.groupBy { it.id }.map { (id, es) -> es.first().copy(id = id, versions = es.flatMap { it.versions }) }
        val c = Catalog(id = "test", cls = cls, plugins = merged)
        File(repo, "catalog.json").writeText(ManagerJson.encodeToString(Catalog.serializer(), c))
        return c
    }

    private fun source(m: PluginManager, cls: String = "community", id: String = "idx") {
        assertTrue(m.addSource(CatalogSource(id, cls, dir = repo.path)).ok)
    }

    private fun installOk(m: PluginManager, ref: String, grants: Set<String>? = emptySet()) =
        m.install(ref, grants).also { assertTrue(it.ok, "${it.code}: ${it.message}") }

    @Test fun `community install needs consent and writes exactly the consented grants`() {
        val f = pack(extra = daemonBlock)
        catalog(entry(f, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")))
        val m = manager(); source(m)

        val none = m.install("Acme_Echo", null)
        assertEquals("CONSENT_REQUIRED", none.code)
        assertEquals(listOf("daemon.code", "daemon.tool"), none.requested)
        assertNull(m.status("Acme_Echo"), "nothing is installed without consent")

        installOk(m, "Acme_Echo", setOf("daemon.code", "daemon.tool"))
        val row = assertNotNull(m.status("Acme_Echo"))
        assertEquals(PluginClass.COMMUNITY, row.cls)
        assertEquals(Tier.COMMUNITY, row.tier)
        assertTrue(row.enabled)
        assertEquals(listOf("daemon.code", "daemon.tool"), row.granted)
        val setting = PluginFolders.config(root).modules.getValue("Acme_Echo")
        assertEquals(listOf("daemon.code", "daemon.tool"), setting.allow.sorted())
        assertTrue(File(root, "community/Acme_Echo/module.json").isFile)
        assertEquals("ALREADY_INSTALLED", m.install("Acme_Echo", emptySet()).code)
    }

    @Test fun `a grant the plugin does not ask for is refused`() {
        val f = pack(); catalog(entry(f, "Acme_Echo", "1.0.0", emptyList()))
        val m = manager(); source(m)
        assertEquals("UNKNOWN_GRANT", m.install("Acme_Echo", setOf("daemon.processes")).code)
    }

    @Test fun `a wrong or tampered sha256 is refused and nothing is installed`() {
        val f = pack(); catalog(entry(f, "Acme_Echo", "1.0.0", emptyList(), sha = "0".repeat(64)))
        val m = manager(); source(m)
        assertEquals("HASH_MISMATCH", m.install("Acme_Echo", emptySet()).code)
        assertNull(m.status("Acme_Echo"))
        // The asset changes after the catalog pinned it.
        catalog(entry(f, "Acme_Echo", "1.0.0", emptyList()))
        f.appendBytes(byteArrayOf(1, 2, 3))
        assertEquals("HASH_MISMATCH", m.install("Acme_Echo", emptySet()).code)
        assertNull(m.status("Acme_Echo"))
    }

    @Test fun `the catalog cannot under-report the grants`() {
        val f = pack(extra = daemonBlock); catalog(entry(f, "Acme_Echo", "1.0.0", emptyList()))
        val m = manager(); source(m)
        assertEquals("GRANTS_MISMATCH", m.install("Acme_Echo", emptySet()).code)
        assertNull(m.status("Acme_Echo"))
    }

    @Test fun `official class needs the Infantry signature, a tampered signature is refused`() {
        val ks = key("infantry"); trust("infantry", ks)
        val signed = pack("Acme_Official"); sign(signed, ks, "infantry")
        val unsigned = pack("Acme_Unsigned")
        catalog(entry(signed, "Acme_Official", "1.0.0", emptyList()), entry(unsigned, "Acme_Unsigned", "1.0.0", emptyList()), cls = "official")
        val m = manager(); source(m, "official")

        assertEquals("NOT_SIGNED_OFFICIAL", m.install("Acme_Unsigned", emptySet()).code)
        installOk(m, "Acme_Official")
        val row = assertNotNull(m.status("Acme_Official"))
        assertEquals(Tier.OFFICIAL, row.tier)
        assertEquals(PluginClass.OFFICIAL, row.cls)
        assertTrue(File(root, "official/Acme_Official/module.json").isFile)

        // One jar byte changed under the same signature files.
        val bad = File(repo, "Acme_Tampered-1.0.0.ihq")
        val src = pack("Acme_Tampered", dir = File(tmp, "src").also { it.mkdirs() }); sign(src, ks, "infantry")
        ZipFile(src).use { zf -> ZipOutputStream(bad.outputStream()).use { z -> zf.entries().asSequence().forEach { e ->
            var b = zf.getInputStream(e).readBytes()
            if (e.name == "lib/Acme_Tampered.jar") b = b.also { it[5] = (it[5] + 1).toByte() }
            z.putNextEntry(ZipEntry(e.name)); z.write(b); z.closeEntry()
        } } }
        src.delete()
        catalog(entry(bad, "Acme_Tampered", "1.0.0", emptyList()), cls = "official")
        val r = m.install("Acme_Tampered", emptySet())
        assertFalse(r.ok)
        assertEquals("BAD_SIGNATURE", r.code)
        assertNull(m.status("Acme_Tampered"))
    }

    @Test fun `update keeps the old version, rollback restores it`() {
        val v1 = pack(version = "1.0.0"); val v2 = pack(version = "1.1.0")
        catalog(entry(v1, "Acme_Echo", "1.0.0", emptyList()))
        val m = manager(); source(m)
        installOk(m, "Acme_Echo")
        assertTrue(m.checkUpdates().isEmpty())

        catalog(entry(v1, "Acme_Echo", "1.0.0", emptyList()), entry(v2, "Acme_Echo", "1.1.0", emptyList()))
        val offer = m.checkUpdates().single()
        assertEquals("1.1.0", offer.to)
        assertFalse(offer.grantsRaised)
        val u = m.update("Acme_Echo")
        assertTrue(u.ok, u.message)
        assertEquals("1.1.0", m.status("Acme_Echo")!!.version)
        assertTrue(File(root, "_versions/Acme_Echo/1.0.0/module.json").isFile)
        assertEquals("NOT_NEWER", m.update("Acme_Echo").code)

        val rb = m.rollback("Acme_Echo")
        assertTrue(rb.ok, rb.message)
        assertEquals("1.0.0", m.status("Acme_Echo")!!.version)
        assertTrue(File(root, "_versions/Acme_Echo/1.1.0/module.json").isFile, "the newer one is kept so the rollback can be undone")
        assertTrue(m.rollback("Acme_Echo").ok)
        assertEquals("1.1.0", m.status("Acme_Echo")!!.version, "a rollback can be rolled forward again")
    }

    @Test fun `a grant-raising update is never applied without consent`() {
        val v1 = pack(version = "1.0.0"); val v2 = pack(version = "2.0.0", extra = daemonBlock)
        catalog(entry(v1, "Acme_Echo", "1.0.0", emptyList()))
        val m = manager(); source(m)
        installOk(m, "Acme_Echo")
        catalog(entry(v1, "Acme_Echo", "1.0.0", emptyList()), entry(v2, "Acme_Echo", "2.0.0", listOf("daemon.code", "daemon.tool")))

        assertTrue(m.checkUpdates().single().grantsRaised)
        val r = m.update("Acme_Echo")
        assertEquals("GRANTS_RAISED", r.code)
        assertEquals(listOf("daemon.code", "daemon.tool"), r.requested)
        assertEquals("1.0.0", m.status("Acme_Echo")!!.version, "the old version is still the active one")
        assertTrue(PluginFolders.config(root).modules["Acme_Echo"]?.allow.orEmpty().isEmpty())

        assertTrue(m.update("Acme_Echo", setOf("daemon.code", "daemon.tool")).ok)
        assertEquals("2.0.0", m.status("Acme_Echo")!!.version)
        assertEquals(listOf("daemon.code", "daemon.tool"), PluginFolders.config(root).modules.getValue("Acme_Echo").allow.sorted())
        // Back to 1.0.0: the extra grants are dropped, never kept.
        assertTrue(m.rollback("Acme_Echo").ok)
        assertTrue(PluginFolders.config(root).modules["Acme_Echo"]?.allow.orEmpty().isEmpty())
    }

    @Test fun `an incompatible api is refused and not offered`() {
        val f = pack(); catalog(entry(f, "Acme_Echo", "1.0.0", emptyList(), api = ApiRanges(plugin = ">=3 <4")))
        val m = manager(); source(m)
        assertEquals("API_INCOMPATIBLE", m.install("Acme_Echo", emptySet()).code)
        assertNull(m.search("Acme").single().latest, "no compatible version is offered")
        val old = pack("Acme_Old", api = 1); catalog(entry(old, "Acme_Old", "1.0.0", emptyList()))
        assertEquals("API_INCOMPATIBLE", m.install("Acme_Old", emptySet()).code)
        assertNull(m.status("Acme_Old"))
    }

    @Test fun `disabled plugin loads nothing and enable restores it`() {
        val f = pack(); catalog(entry(f, "Acme_Echo", "1.0.0", emptyList()))
        val m = manager(); source(m)
        installOk(m, "Acme_Echo")
        assertTrue(m.setEnabled("Acme_Echo", false).ok)
        assertEquals(false, PluginFolders.config(root).modules.getValue("Acme_Echo").enabled)
        assertFalse(m.status("Acme_Echo")!!.enabled)
        assertTrue(m.setEnabled("Acme_Echo", true).ok)
        assertTrue(m.status("Acme_Echo")!!.enabled)
        assertEquals("NOT_INSTALLED", m.setEnabled("Nope", true).code)
        // --disabled installs leave it off.
        val g = pack("Acme_Off"); catalog(entry(f, "Acme_Echo", "1.0.0", emptyList()), entry(g, "Acme_Off", "1.0.0", emptyList()))
        assertTrue(m.install("Acme_Off", emptySet(), enable = false).ok)
        assertFalse(m.status("Acme_Off")!!.enabled)
    }

    @Test fun `custom sources work with no network and are labelled custom`() {
        val m = manager(noNetwork)
        val f = pack("Acme_Mine", extra = daemonBlock.replace("Acme_Echo", "Acme_Mine"))
        val (insp, refused) = m.inspect(f.path)
        assertNull(refused)
        assertEquals(PluginClass.CUSTOM, insp!!.cls)
        assertTrue(insp.warnings.any { it.contains("CUSTOM") })
        insp.file.delete()

        assertEquals("CONSENT_REQUIRED", m.install(f.path, null).code)
        installOk(m, f.path, setOf("daemon.code", "daemon.tool"))
        assertEquals(PluginClass.CUSTOM, m.status("Acme_Mine")!!.cls)

        // A plugin folder (a work in progress) is a custom source too.
        val dir = File(tmp, "wip/Acme_Dir").also { File(it, "lib").mkdirs() }
        File(dir, "module.json").writeText("""{"name":"Acme_Dir","version":"0.1.0","entry":"x.Y","jar":"lib/a.jar"}""")
        File(dir, "lib/a.jar").writeBytes(ByteArray(50))
        installOk(m, dir.path)
        assertEquals(PluginClass.CUSTOM, m.status("Acme_Dir")!!.cls)

        // A dir source of bare .ihq files is indexed on the fly, offline.
        val bare = File(tmp, "bare").also { it.mkdirs() }
        pack("Acme_Bare", dir = bare)
        assertTrue(m.addSource(CatalogSource("mine", "custom", dir = bare.path)).ok)
        assertEquals("Acme_Bare", m.search("Bare").single().entry.id)
        installOk(m, "mine:Acme_Bare")
        assertEquals("mine", m.status("Acme_Bare")!!.source)
    }

    @Test fun `a broken signature on a custom package is refused, a user-trusted key is only labelled`() {
        val ks = key("mykey"); trust("mykey", ks, "custom")
        val f = pack("Acme_Signed"); sign(f, ks, "mykey")
        val m = manager(noNetwork)
        val (insp, refused) = m.inspect(f.path)
        assertNull(refused)
        assertTrue(insp!!.signedByUserKey)
        assertEquals(Tier.COMMUNITY, insp.tier, "a user's own key never makes a plugin official")
        insp.file.delete()
        installOk(m, f.path)
        assertEquals(PluginClass.CUSTOM, m.status("Acme_Signed")!!.cls)
    }

    @Test fun `remove keeps the files for restore, purge deletes them, bundled is refused`() {
        val f = pack(); catalog(entry(f, "Acme_Echo", "1.0.0", emptyList()))
        val m = manager(); source(m)
        installOk(m, "Acme_Echo")
        assertTrue(m.remove("Acme_Echo").ok)
        assertNull(m.status("Acme_Echo"))
        assertFalse(File(root, "community/Acme_Echo").exists())
        assertTrue(File(root, "_versions/Acme_Echo/1.0.0").isDirectory)
        assertFalse(PluginFolders.config(root).modules.containsKey("Acme_Echo"))
        installOk(m, "Acme_Echo")
        assertTrue(m.remove("Acme_Echo", purge = true).ok)
        assertFalse(File(root, "_versions/Acme_Echo").exists())

        val bundled = File(tmp, "bundled").also { File(it, "official/Acme_Core").mkdirs() }
        File(bundled, "official/Acme_Core/module.json").writeText("""{"name":"Acme_Core","version":"1.0.0","entry":"x.Y","jar":"lib/a.jar"}""")
        val mb = PluginManager(root, state, bundledRoot = bundled)
        assertTrue(mb.status("Acme_Core")!!.bundled)
        assertEquals("BUNDLED", mb.remove("Acme_Core").code)
    }

    @Test fun `an offline url source keeps using its cached catalog`() {
        val f = pack(); val c = catalog(entry(f, "Acme_Echo", "1.0.0", emptyList()))
        var online = true
        val fetcher = Fetcher { url, dest, max ->
            if (!online) throw IOException("offline")
            DefaultFetcher.fetch(if (url.endsWith("catalog.json")) File(repo, "catalog.json").path else File(repo, url.substringAfterLast('/')).path, dest, max)
        }
        val m = manager(fetcher)
        assertTrue(m.addSource(CatalogSource("web", "community", url = "https://example.invalid/idx/catalog.json")).ok)
        assertTrue(m.refresh().single().ok)
        online = false
        val r = m.refresh().single()
        assertTrue(r.stale, r.message)
        assertEquals(1, m.search("Acme").size)
        assertEquals(c.plugins.size, r.plugins)
    }

    @Test fun `catalog validate checks hash grants and manifest`() {
        val f = pack(extra = daemonBlock)
        val good = catalog(entry(f, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")))
        val m = manager()
        assertEquals(emptyList(), m.validate(good, repo))
        val bad = catalog(entry(f, "Acme_Echo", "1.0.0", emptyList(), sha = "1".repeat(64)))
        val problems = m.validate(bad, repo)
        assertTrue(problems.any { it.contains("sha256") }, problems.toString())
        assertTrue(problems.any { it.contains("grants") }, problems.toString())
    }

    @Test fun `a yanked version is never chosen or offered, an installed one shows its reason`() {
        val v1 = pack(version = "1.0.0"); val v2 = pack(version = "1.1.0")
        fun yankedEntry(y: Boolean) = entry(v2, "Acme_Echo", "1.1.0", emptyList()).let { e -> e.copy(repo = "https://example.org/r", versions = e.versions.map { it.copy(yanked = y, yankedReason = if (y) "broken" else null) }) }
        catalog(entry(v1, "Acme_Echo", "1.0.0", emptyList()), yankedEntry(true))
        val m = manager(); source(m)
        installOk(m, "Acme_Echo")
        assertEquals("1.0.0", m.status("Acme_Echo")!!.version, "install skips the yanked 1.1.0")
        assertTrue(m.checkUpdates().isEmpty())
        assertTrue(m.install("idx:Acme_Echo@1.1.0", emptySet()).message.isNotEmpty())
        catalog(entry(v1, "Acme_Echo", "1.0.0", emptyList()).let { e -> e.copy(versions = e.versions.map { it.copy(yanked = true, yankedReason = "bad build") }) })
        assertEquals("bad build", m.status("Acme_Echo")!!.yanked)
    }

    @Test fun `catalog builder sorts, refuses a name that differs from the id and duplicate ids`() {
        val d = File(repo, "entries").also { it.mkdirs() }
        File(d, "B_Two.json").writeText("""{"id":"B_Two"}""")
        File(d, "A_One.json").writeText("""{"id":"A_One","repo":"https://x/y","unknown":1}""")
        val (c, bad) = CatalogBuilder.build(d, "idx", "community")
        assertTrue(bad.isEmpty(), bad.toString())
        assertEquals(listOf("A_One", "B_Two"), c!!.plugins.map { it.id })
        assertEquals("https://x/y", c.plugins[0].repo)
        File(d, "Wrong.json").writeText("""{"id":"Other"}""")
        assertTrue(CatalogBuilder.build(d, "idx", "community").second.single().contains("file name must equal"))
    }

    // ---- PLUGIN-HOTLOAD-1: the manager applies a change to the running host ---------------------------------------------

    /** A host that only records what it was asked, and what the module's folder held at that moment (the jar is open until unload). */
    private inner class RecordingHost(private val dependents: List<String> = emptyList()) : PluginHotApply {
        val calls = mutableListOf<String>()
        private fun seen(id: String) = File(root, "community/$id/module.json").takeIf { it.isFile }?.readText()?.substringAfter("\"version\":\"")?.substringBefore('"') ?: "-"
        override fun load(id: String): HotOutcome { calls += "load:$id@${seen(id)}"; return HotOutcome(true, "loaded $id", loaded = listOf(id)) }
        override fun unload(id: String): HotOutcome { calls += "unload:$id@${seen(id)}"; return HotOutcome(true, "unloaded $id", unloaded = dependents + id) }
        override fun reload(id: String): HotOutcome { calls += "reload:$id@${seen(id)}"; return HotOutcome(true, "reloaded $id", unloaded = listOf(id), loaded = listOf(id)) }
    }

    private fun hotManager(host: PluginHotApply?) = PluginManager(root, state, hot = host)

    @Test fun `install loads live and update unloads before the files move, then loads the new version`() {
        val v1 = pack(version = "1.0.0", extra = daemonBlock); val v2 = pack(version = "1.1.0", extra = daemonBlock)
        catalog(entry(v1, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")))
        val host = RecordingHost()
        val m = hotManager(host); source(m)
        val i = m.install("Acme_Echo", setOf("daemon.code", "daemon.tool"))
        assertTrue(i.ok, i.message)
        assertFalse(i.restartNeeded, "a daemon half applied live needs no restart")
        assertTrue("live" in i.message && "restart" !in i.message.lowercase(), i.message)
        assertEquals(listOf("load:Acme_Echo@1.0.0"), host.calls)

        host.calls.clear()
        catalog(entry(v1, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")), entry(v2, "Acme_Echo", "1.1.0", listOf("daemon.code", "daemon.tool")))
        val u = m.update("Acme_Echo")
        assertTrue(u.ok, u.message)
        assertFalse(u.restartNeeded)
        assertEquals(listOf("unload:Acme_Echo@1.0.0", "load:Acme_Echo@1.1.0"), host.calls, "unload sees the old files, load the new ones")

        host.calls.clear()
        val rb = m.rollback("Acme_Echo")
        assertTrue(rb.ok && !rb.restartNeeded, rb.message)
        assertEquals(listOf("unload:Acme_Echo@1.1.0", "load:Acme_Echo@1.0.0"), host.calls)
    }

    @Test fun `remove unloads first, a dependent that left with it is reported and not started again`() {
        val f = pack(extra = daemonBlock); catalog(entry(f, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")))
        val host = RecordingHost(dependents = listOf("Acme_Dependent"))
        val m = hotManager(host); source(m)
        installOk(m, "Acme_Echo", setOf("daemon.code", "daemon.tool"))
        host.calls.clear()
        val r = m.remove("Acme_Echo")
        assertTrue(r.ok && !r.restartNeeded, r.message)
        assertEquals(listOf("unload:Acme_Echo@1.0.0"), host.calls, "the jar is still on disk when the unload runs")
        assertFalse(File(root, "community/Acme_Echo").exists())
        assertTrue("Acme_Echo" in r.message)
    }

    @Test fun `an update brings back the dependents that left with the plugin`() {
        val v1 = pack(version = "1.0.0", extra = daemonBlock); val v2 = pack(version = "1.1.0", extra = daemonBlock)
        catalog(entry(v1, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")))
        val host = RecordingHost(dependents = listOf("Acme_Late", "Acme_Dependent"))
        val m = hotManager(host); source(m)
        installOk(m, "Acme_Echo", setOf("daemon.code", "daemon.tool"))
        catalog(entry(v1, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")), entry(v2, "Acme_Echo", "1.1.0", listOf("daemon.code", "daemon.tool")))
        host.calls.clear()
        assertTrue(m.update("Acme_Echo").ok)
        assertEquals(listOf("unload:Acme_Echo@1.0.0", "load:Acme_Echo@1.1.0", "load:Acme_Dependent@-", "load:Acme_Late@-"), host.calls)
    }

    @Test fun `disable unloads and enable loads, grants reload`() {
        val f = pack(extra = daemonBlock); catalog(entry(f, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")))
        val host = RecordingHost()
        val m = hotManager(host); source(m)
        installOk(m, "Acme_Echo", setOf("daemon.code", "daemon.tool"))
        host.calls.clear()
        val d = m.setEnabled("Acme_Echo", false)
        assertTrue(d.ok && !d.restartNeeded, d.message)
        val e = m.setEnabled("Acme_Echo", true)
        assertTrue(e.ok && !e.restartNeeded, e.message)
        val g = m.setGrants("Acme_Echo", setOf("daemon.code"))
        assertTrue(g.ok && !g.restartNeeded, g.message)
        assertEquals(listOf("unload:Acme_Echo@1.0.0", "load:Acme_Echo@1.0.0", "reload:Acme_Echo@1.0.0"), host.calls)
    }

    @Test fun `a harness half stays start-only and the answer says so`() {
        val f = pack(extra = """"daemon":{"entry":"x.D","jar":"lib/Acme_Echo.jar","tools":[{"name":"echo","action":"echo.read"}]},"harness":{"jar":"lib/Acme_Echo.jar","ids":["acme-echo"]}""")
        catalog(entry(f, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool", "harness.code")))
        val host = RecordingHost()
        val m = hotManager(host); source(m)
        val r = m.install("Acme_Echo", setOf("daemon.code", "daemon.tool", "harness.code"))
        assertTrue(r.ok, r.message)
        assertTrue(r.restartNeeded, "the harness half loads at the next start")
        assertTrue("harness" in r.message && "start" in r.message, r.message)
        assertEquals(listOf("load:Acme_Echo@1.0.0"), host.calls, "the daemon half still loads live")
    }

    @Test fun `with no running daemon a change waits for the next start`() {
        val f = pack(extra = daemonBlock); catalog(entry(f, "Acme_Echo", "1.0.0", listOf("daemon.code", "daemon.tool")))
        val m = hotManager(null); source(m)
        val r = m.install("Acme_Echo", setOf("daemon.code", "daemon.tool"))
        assertTrue(r.ok && r.restartNeeded, r.message)
        assertTrue(m.setEnabled("Acme_Echo", false).restartNeeded)
    }
}
