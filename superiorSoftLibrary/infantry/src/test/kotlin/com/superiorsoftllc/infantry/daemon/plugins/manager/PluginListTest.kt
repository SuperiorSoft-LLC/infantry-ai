// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins.manager

import com.superiorsoftllc.infantry.update.ReleaseAssets
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PLUGIN-REPO-1: a hand-written plugin list (the minimal form and a full catalog), import / remove as a source, repo ->
 * release asset resolution and the SHA256SUMS check, on a temp plugins root. The "GitHub" API is an in-memory fake.
 */
class PluginListTest {
    private val tmp = Files.createTempDirectory("plist-test").toFile()
    private val root = File(tmp, "plugins").also { it.mkdirs() }
    private val state = File(tmp, "state").also { it.mkdirs() }
    private val repo = File(tmp, "repo").also { it.mkdirs() }
    private val web = mutableMapOf<String, ByteArray>()
    private val fake = Fetcher { url, dest, _ -> dest.parentFile?.mkdirs(); dest.writeBytes(web[url] ?: throw IOException("HTTP 404 for $url")) }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun manager() = PluginManager(root, state, fetcher = fake, releasesApi = { API })

    private fun pack(id: String = "Acme_Echo", version: String = "1.0.0"): File {
        val f = File(repo, "$id-$version.ihq")
        val manifest = """{"name":"$id","version":"$version","apiVersion":2,"license":"Apache-2.0","entry":"x.Y","jar":"lib/$id.jar"}"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/$id.jar")); z.write(ByteArray(300) { (it + version.hashCode()).toByte() }); z.closeEntry()
        }
        return f
    }

    /** Publishes [f] as the latest release `v<version>` of [ownerRepo] on the fake API; [sums] = the SHA256SUMS text (null = none). */
    private fun release(ownerRepo: String, version: String, f: File, sums: String? = ReleaseAssets.sumsLine(ReleaseAssets.sha256(f), f.name)) {
        val dl = "https://dl.test/$ownerRepo/v$version"
        web["$dl/${f.name}"] = f.readBytes()
        val assets = mutableListOf("""{"name":"${f.name}","size":${f.length()},"browser_download_url":"$dl/${f.name}"}""")
        if (sums != null) { web["$dl/SHA256SUMS"] = sums.toByteArray(); assets += """{"name":"SHA256SUMS","browser_download_url":"$dl/SHA256SUMS"}""" }
        web["$API/repos/$ownerRepo/releases/latest"] =
            """{"tag_name":"v$version","html_url":"https://github.com/$ownerRepo/releases/tag/v$version","assets":[${assets.joinToString(",")}]}""".toByteArray()
    }

    private fun listFile(name: String = "list.json", text: String = MINIMAL) = File(tmp, name).apply { writeText(text) }

    @Test fun `the minimal hand-written list parses as a schema-1 catalog`() {
        val (c, problems) = PluginLists.parse(MINIMAL)
        assertNotNull(c, problems.joinToString())
        assertEquals("SuperiorSoft plugins", c.name)
        val e = c.plugins.single { it.id == "Acme_Echo" }
        assertEquals("Echo", e.name)
        assertEquals("acme/echo", e.repo)
        assertTrue(e.versions.isEmpty())
        assertEquals(listOf("test"), e.tags)
        assertEquals("superiorsoft-plugins", PluginLists.sourceId(c, "x/list.json"))
    }

    @Test fun `a full catalog with versions, sha256 and grants is still a valid list`() {
        val full = """{"schema":1,"id":"idx","class":"community","plugins":[{"id":"Acme_Full","description":"d","license":"MIT",
            "versions":[{"version":"1.2.0","grants":["daemon.code"],"url":"Acme_Full-1.2.0.ihq","sha256":"${"a".repeat(64)}"}]}]}"""
        val (c, problems) = PluginLists.parse(full)
        assertNotNull(c, problems.joinToString())
        assertEquals("idx", PluginLists.sourceId(c, "whatever.json"))
        assertEquals("1.2.0", c.plugins.single().versions.single().version)
    }

    @Test fun `a broken list is refused with every problem`() {
        val (c, problems) = PluginLists.parse("""{"schema":2,"plugins":[{"id":"A"},{"id":"B","repo":"not a repo"},{"id":"B","repo":"o/b"},{"id":"bad id!","repo":"o/c"}]}""")
        assertNull(c)
        assertTrue(problems.any { "schema 2" in it }, problems.toString())
        assertTrue(problems.any { it.startsWith("A: needs a repo") }, problems.toString())
        assertTrue(problems.any { "repo 'not a repo'" in it }, problems.toString())
        assertTrue(problems.any { "B: id is not unique" in it }, problems.toString())
        assertTrue(problems.any { "not a plain name" in it }, problems.toString())
        assertNull(PluginLists.parse("not json").first)
    }

    @Test fun `repo forms`() {
        assertEquals(RepoRef.Releases("SuperiorSoft-LLC/infantry-voice"), RepoRef.parse("SuperiorSoft-LLC/infantry-voice"))
        assertEquals(RepoRef.Releases("o/r"), RepoRef.parse("https://github.com/o/r.git"))
        assertEquals(RepoRef.Direct("https://x.test/a.ihq"), RepoRef.parse("https://x.test/a.ihq"))
        assertEquals(RepoRef.Direct("file:/c/a.ihq"), RepoRef.parse("file:/c/a.ihq"))
        assertNull(RepoRef.parse("ftp://x/y"))
    }

    @Test fun `import saves a community source, browse shows its plugins, remove takes it away`() {
        val m = manager()
        val f = listFile()
        val r = m.importList(f.path)
        assertTrue(r.ok, "${r.code}: ${r.message}")
        val s = m.sources().single()
        assertEquals("superiorsoft-plugins", s.id)
        assertEquals("community", s.cls)
        assertEquals(f.absoluteFile.toURI().toString(), s.url)
        val hits = m.search()
        assertEquals(setOf("Acme_Echo", "Acme_Other"), hits.map { it.entry.id }.toSet())
        assertTrue(hits.all { it.fromRepo && it.latest == null && it.listName == "SuperiorSoft plugins" && it.cls == PluginClass.COMMUNITY })
        assertEquals("Acme_Other", m.search("other").single().entry.id)
        // The same file again refreshes; another list under the same id is refused; --id imports it side by side.
        assertTrue(m.importList(f.path).ok)
        val other = listFile("other.json")
        assertEquals("SOURCE_EXISTS", m.importList(other.path).code)
        assertTrue(m.importList(other.path, id = "second").ok)
        assertEquals(2, m.sources().size)
        assertTrue(m.removeSource("second").ok)
        assertTrue(m.removeSource("superiorsoft-plugins").ok)
        assertTrue(m.sources().isEmpty())
        assertTrue(m.search().isEmpty())
    }

    @Test fun `an official class in the list file never makes the imported source official`() {
        val m = manager()
        assertTrue(m.importList(listFile(text = MINIMAL.replace("\"schema\": 1,", "\"schema\": 1, \"class\": \"official\",")).path).ok)
        assertEquals("community", m.sources().single().cls)
    }

    @Test fun `an unreadable or invalid list is refused and nothing is saved`() {
        val m = manager()
        assertEquals("FETCH_FAILED", m.importList(File(tmp, "missing.json").path).code)
        assertEquals("FETCH_FAILED", m.importList("https://nowhere.test/list.json").code)
        assertEquals("BAD_LIST", m.importList(listFile(text = """{"schema":1,"plugins":[{"id":"A"}]}""").path).code)
        assertTrue(m.sources().isEmpty())
    }

    @Test fun `a repo resolves to the asset named after the plugin and its SHA256SUMS line`() {
        val f = pack()
        release("acme/echo", "1.0.0", f)
        val rr = RepoReleases(fake, { API }, File(tmp, "scratch").also { it.mkdirs() }, 1L shl 20)
        val pkg = rr.resolve("Acme_Echo", RepoRef.Releases("acme/echo"))
        assertEquals("1.0.0", pkg.version)
        assertEquals(f.name, pkg.assetName)
        assertEquals(ReleaseAssets.sha256(f), pkg.sha256)
        assertEquals("1.0.0", rr.latestVersion(RepoRef.Releases("acme/echo")))
        val assets = listOf(RepoAsset("README.zip"), RepoAsset("Acme_Echo-1.0.0.ihq"), RepoAsset("SHA256SUMS"))
        assertEquals("Acme_Echo-1.0.0.ihq", RepoReleases.pickAsset("Acme_Echo", assets)?.name)
        assertEquals("only.jar", RepoReleases.pickAsset("X", listOf(RepoAsset("only.jar"), RepoAsset("SHA256SUMS")))?.name)
        assertNull(RepoReleases.pickAsset("X", listOf(RepoAsset("a.zip"), RepoAsset("b.zip"))))
        assertEquals("ab".repeat(32), RepoReleases.sumFor("${"AB".repeat(32)} *x.ihq\n${"cd".repeat(32)}  y.ihq", "x.ihq"))
        assertNull(RepoReleases.sumFor("${"ab".repeat(32)}  x.ihq", "y.ihq"))
    }

    @Test fun `install from a repo entry, update to a newer release, then remove`() {
        val m = manager()
        release("acme/echo", "1.0.0", pack())
        assertTrue(m.importList(listFile().path).ok)
        val ref = "superiorsoft-plugins:Acme_Echo"
        val insp = assertNotNull(m.inspect(ref).first)
        assertEquals("community", insp.cls.label)
        assertEquals("Apache-2.0", insp.license)
        insp.file.delete()
        val r = m.install(ref, emptySet())
        assertTrue(r.ok, "${r.code}: ${r.message}")
        assertEquals("1.0.0", m.status("Acme_Echo")?.version)
        assertTrue(m.checkUpdates().isEmpty())

        release("acme/echo", "1.1.0", pack(version = "1.1.0"))
        assertEquals("1.1.0", m.checkUpdates().single().to)
        val u = m.update("Acme_Echo")
        assertTrue(u.ok, "${u.code}: ${u.message}")
        assertEquals("1.1.0", m.status("Acme_Echo")?.version)

        assertTrue(m.remove("Acme_Echo").ok)
        assertNull(m.status("Acme_Echo"))
    }

    @Test fun `a package whose sha256 is not the release's SHA256SUMS is refused and nothing is installed`() {
        val m = manager()
        release("acme/echo", "1.0.0", pack(), sums = "${"0".repeat(64)}  Acme_Echo-1.0.0.ihq")
        assertTrue(m.importList(listFile().path).ok)
        val r = m.install("superiorsoft-plugins:Acme_Echo", emptySet())
        assertEquals("HASH_MISMATCH", r.code, r.message)
        assertNull(m.status("Acme_Echo"))
    }

    @Test fun `a release without SHA256SUMS installs with a warning, a wrong package name is refused`() {
        val m = manager()
        release("acme/echo", "1.0.0", pack(), sums = null)
        assertTrue(m.importList(listFile().path).ok)
        val insp = assertNotNull(m.inspect("superiorsoft-plugins:Acme_Echo").first)
        assertTrue(insp.warnings.any { "SHA256SUMS" in it }, insp.warnings.toString())
        insp.file.delete()
        // Acme_Other's repo ships a package that is really Acme_Echo.
        release("acme/other", "1.0.0", pack())
        assertEquals("BAD_MANIFEST", m.install("superiorsoft-plugins:Acme_Other", emptySet()).code)
        assertFalse(m.list().any { it.id == "Acme_Other" })
    }

    private companion object {
        const val API = "https://api.test"
        val MINIMAL = """{ "schema": 1, "name": "SuperiorSoft plugins", "plugins": [
            { "id": "Acme_Echo", "name": "Echo", "description": "Says it back.", "repo": "acme/echo", "tags": ["test"] },
            { "id": "Acme_Other", "name": "Other", "description": "Another one.", "repo": "https://github.com/acme/other", "tags": ["test"] } ] }"""
    }
}
