// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NIGHTLY-1: update channels (Stable / Nightly) are manifest data, the user's `updater.releaseChannel` picks one, nightly
 * versions order between two stables, and switching Nightly -> Stable offers the newest stable even when it is older.
 */
class ReleaseChannelTest {
    private val servers = mutableListOf<FakeGitHub>()
    private val temp: File = Files.createTempDirectory("release-channel").toFile()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop() }
        temp.deleteRecursively()
    }

    private val nightly = ReleaseChannel("nightly", "Nightly", ReleaseChannel.Mode.PRERELEASE, "nightly")

    private fun v(text: String) = assertNotNull(SemVer.parse(text), "'$text' must parse")

    @Test
    fun `a nightly orders above the current stable, below the next one, and by date then run`() {
        val order = listOf("1.0.7", "1.0.8-nightly.20261107.12", "1.0.8-nightly.20261107.13", "1.0.8-nightly.20261108.14", "1.0.8", "1.0.9-nightly.20261109.15")
        assertEquals(order, order.shuffled(java.util.Random(7)).map(::v).sorted().map { it.toString() })
        // A run number of a later day can be lower (a re-created workflow): the date still decides first.
        assertTrue(v("1.0.8-nightly.20261108.2") > v("1.0.8-nightly.20261107.99"))
    }

    @Test
    fun `release file names carry a nightly version, and the debug APK is not mistaken for one`() {
        assertEquals(v("1.0.8-nightly.20261107.12"), AssetKind.WindowsInstaller.versionOf("InfantryAI-1.0.8-nightly.20261107.12.msi"))
        assertEquals(v("1.0.8-nightly.20261107.12"), AssetKind.AndroidApk.versionOf("InfantryAI-1.0.8-nightly.20261107.12.apk"))
        assertEquals(v("1.0.8-nightly.20261107.12"), AssetKind.AndroidApk.versionOf("InfantryAI-1.0.8-nightly.20261107.12-debug.apk"))
        assertEquals(v("1.0.8"), AssetKind.AndroidApk.versionOf("InfantryAI-1.0.8-debug.apk"), "-debug is the debug APK of 1.0.8, not a pre-release")
        val server = assertNotNull(ServerArchive.parse("InfantryAI-server-1.0.8-nightly.20261107.12-linux-x64.tar.gz"))
        assertEquals(v("1.0.8-nightly.20261107.12"), server.version)
        assertEquals(ServerArchive.Platform("linux", "x64"), server.platform)
        assertEquals(ServerArchive.Platform("windows", "x64"), ServerArchive.parse("InfantryAI-server-1.0.8-windows-x64.zip")?.platform)
        assertNull(AssetKind.WindowsInstaller.versionOf("InfantryAI-1.0.8-windows.zip"))
        assertEquals("InfantryAI-server-1.0.8-nightly.20261107.12-linux-x64.tar.gz", ServerArchive.fileName(v("1.0.8-nightly.20261107.12"), ServerArchive.Platform("linux", "x64")))
    }

    @Test
    fun `the baked manifest declares stable and nightly, and the setting's options are exactly those ids`() {
        val baked = ReleaseChannel.fromManifest(UpdateManifest::get)
        assertEquals(listOf("stable", "nightly"), baked.map { it.id })
        assertEquals(ReleaseChannel.Mode.LATEST, baked[0].mode)
        assertEquals(nightly, baked[1])
        assertEquals("stable", UpdateManifest.get(UpdateManifest.KEY_DEFAULT_CHANNEL))
        // The UI select (module.json config) must offer exactly the manifest's channels, default stable.
        val module = Json.parseToJsonElement(File("../module.json").readText()).jsonObject
        val field = module["daemon"]!!.jsonObject["config"]!!.jsonArray.flatMap { it.jsonObject["fields"]!!.jsonArray }
            .map { it.jsonObject }.single { it["key"]!!.jsonPrimitive.content == UpdateSettings.KEY_RELEASE_CHANNEL }
        assertEquals(baked.map { it.id }, (field["options"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("stable", field["default"]!!.jsonPrimitive.content)
        assertEquals(baked.associate { it.id to it.label }, (field["optionLabels"] as JsonObject).mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun `channels from manifest data skip bad entries and always keep stable`() {
        assertEquals(listOf(ReleaseChannel.STABLE), ReleaseChannel.fromManifest({ null }), "no channels in the manifest = Stable only")
        val warnings = mutableListOf<String>()
        val m = mapOf("channels" to "beta,Bad Id,odd,nightly", "channels.beta.release" to "sideways", "channels.odd.release" to "prerelease",
            "channels.nightly.release" to "prerelease", "channels.nightly.preRelease" to "nightly")
        val got = ReleaseChannel.fromManifest({ m[it] }, warnings)
        assertEquals(listOf("stable", "nightly"), got.map { it.id })
        assertEquals(3, warnings.size, "beta (bad mode), 'bad id' and odd (no preRelease) are each a warning: $warnings")
    }

    @Test
    fun `the user setting picks the channel, default stable, and an unknown value is ignored with a warning`() {
        val manifest = mapOf(UpdateManifest.KEY_REPO to "o/r", "channels" to "stable,nightly", "channels.stable.release" to "latest",
            "channels.nightly.release" to "prerelease", "channels.nightly.preRelease" to "nightly", "defaultChannel" to "stable")
        fun resolve(pick: String?) = assertIs<UpdateSettings.Enabled>(UpdateSettings.resolve(true, "1.0.7", temp, setting = { null }, env = { null },
            credentialStore = { null }, config = { if (it == UpdateSettings.KEY_RELEASE_CHANNEL) pick else null }, manifest = { manifest[it] }))
        assertEquals("stable", resolve(null).config.channel.id)
        assertEquals(nightly, resolve("nightly").config.channel)
        assertEquals(nightly, resolve("Nightly").config.channel, "the value is case-insensitive")
        val bad = resolve("weekly")
        assertEquals("stable", bad.config.channel.id)
        assertTrue(bad.warnings.any { "weekly" in it }, "a bad channel is a warning, never updates off: ${bad.warnings}")
        assertTrue(UpdateSettings.KEY_RELEASE_CHANNEL in UpdateSettings.SETTING_KEYS, "the window reads it through the daemon's mirror")
        // The engine-side copy for another install keeps the channel.
        assertEquals(nightly, resolve("nightly").config.forVersion(v("1.0.6")).channel)
    }

    @Test
    fun `the newest of a prerelease channel is the highest published stable-or-own version`() {
        fun r(tag: String, pre: Boolean, draft: Boolean = false) = GitHubRelease(tagName = tag, prerelease = pre, draft = draft)
        val list = listOf(
            r("v1.0.9-nightly.20261110.20", pre = true, draft = true), // a draft never counts
            r("v1.0.8-nightly.20261108.14", pre = true),
            r("v1.0.8-rc.1", pre = true), // another prerelease line
            r("v1.0.8-nightly.20261107.12", pre = true),
            r("v1.0.7", pre = false),
            r("v1.0.9", pre = true), // a stable tag published as a prerelease is neither
        )
        assertEquals("v1.0.8-nightly.20261108.14", GitHubReleases.pickNewest(list, nightly)?.tagName)
        assertEquals("v1.0.8", GitHubReleases.pickNewest(list + r("v1.0.8", pre = false), nightly)?.tagName, "a newer stable beats every nightly")
        assertEquals("v1.0.7", GitHubReleases.pickNewest(list, ReleaseChannel.STABLE)?.tagName)
        assertNull(GitHubReleases.pickNewest(emptyList(), nightly))
    }

    /** A fake GitHub with a stable latest (`v1.0.7`) and a release list holding it and one nightly. */
    private fun releases(): FakeGitHub {
        val gh = FakeGitHub().also { servers += it }
        fun asset(id: Int, name: String, size: Int) =
            """{"name":"$name","size":$size,"url":"${gh.base}/repos/superiorsoft/agenthq/releases/assets/$id","browser_download_url":"${gh.base}/dl/$name"}"""
        val stable = """{"tag_name":"v1.0.7","body":"stable notes","draft":false,"prerelease":false,"assets":[${asset(1, "InfantryAI-1.0.7.msi", 3)},${asset(2, "InfantryAI-1.0.7.apk", 3)},${asset(3, "SHA256SUMS", 10)}]}"""
        val night = """{"tag_name":"v1.0.8-nightly.20261107.12","body":"nightly notes","draft":false,"prerelease":true,"assets":[${asset(4, "InfantryAI-1.0.8-nightly.20261107.12.msi", 3)},${asset(5, "SHA256SUMS", 10)}]}"""
        gh.raw("/repos/superiorsoft/agenthq/releases/latest", stable.toByteArray())
        gh.raw("/repos/superiorsoft/agenthq/releases", "[$night,$stable]".toByteArray())
        return gh
    }

    private fun config(gh: FakeGitHub, current: String, channel: ReleaseChannel) = UpdateConfig("superiorsoft/agenthq", v(current), gh.base, channel = channel)

    @Test
    fun `nightly channel offers the newest nightly over the current stable`() {
        val gh = releases()
        val up = assertIs<UpdateCheck.Available>(Updater(config(gh, "1.0.7", nightly)).check()).update
        assertEquals(v("1.0.8-nightly.20261107.12"), up.latest)
        assertEquals("InfantryAI-1.0.8-nightly.20261107.12.msi", up.installer.name)
        assertEquals("SHA256SUMS", up.checksum?.name)
        assertNull(up.channelNote, "an ordinary upgrade carries no channel note")
        assertTrue(gh.requests.any { it.path == "/repos/superiorsoft/agenthq/releases" }, "a prerelease channel reads the release list")
        assertIs<UpdateCheck.UpToDate>(Updater(config(gh, "1.0.8-nightly.20261107.12", nightly)).check(), "the newest nightly is up to date")
    }

    @Test
    fun `switching nightly to stable offers the newest stable even though it is older, and says so`() {
        val gh = releases()
        val up = assertIs<UpdateCheck.Available>(Updater(config(gh, "1.0.8-nightly.20261107.12", ReleaseChannel.STABLE)).check()).update
        assertEquals(v("1.0.7"), up.latest)
        assertTrue(up.older)
        val note = assertNotNull(up.channelNote)
        assertTrue("OLDER" in note && "v1.0.7" in note && "Stable" in note, note)
        assertTrue(up.notes.startsWith(note) && up.notes.endsWith("stable notes"), "the note comes before the release notes: ${up.notes}")
        assertTrue(gh.requests.none { it.path == "/repos/superiorsoft/agenthq/releases" }, "Stable asks only releases/latest")
        // The phone is told Android cannot downgrade in place.
        val apk = assertIs<UpdateCheck.Available>(Updater(config(gh, "1.0.8-nightly.20261107.12", ReleaseChannel.STABLE), kind = AssetKind.AndroidApk).check()).update
        assertTrue("uninstall" in assertNotNull(apk.channelNote), apk.channelNote)
    }

    @Test
    fun `stable never downgrades a stable install and never offers a prerelease`() {
        val gh = releases()
        assertIs<UpdateCheck.UpToDate>(Updater(config(gh, "1.0.7", ReleaseChannel.STABLE)).check())
        assertIs<UpdateCheck.UpToDate>(Updater(config(gh, "1.0.8", ReleaseChannel.STABLE)).check(), "an older stable is not offered to a stable 1.0.8")
        assertIs<UpdateCheck.UpToDate>(Updater(config(gh, "1.0.9", nightly)).check(), "nor on the nightly channel")
        val pre = FakeGitHub().also { servers += it }
            .raw("/repos/superiorsoft/agenthq/releases/latest", """{"tag_name":"v2.0.0-nightly.20261107.1","prerelease":true,"assets":[]}""".toByteArray())
        assertIs<UpdateCheck.UpToDate>(Updater(UpdateConfig("superiorsoft/agenthq", v("1.0.7"), pre.base)).check())
    }

    @Test
    fun `a local releases folder on stable skips a nightly file`() {
        val dir = File(temp, "releases").apply { mkdirs() }
        File(dir, "InfantryAI-1.0.8-nightly.20261107.12.msi").writeText("n")
        File(dir, "InfantryAI-1.0.7.msi").writeText("s")
        assertIs<UpdateCheck.UpToDate>(LocalDirSource(dir, v("1.0.7")).check(), "Stable sees only 1.0.7")
        val got = LocalDirSource(dir, v("1.0.7"), channel = nightly).check()
        // The nightly is chosen; it has no SHA256SUMS next to it, so it is refused, which proves it was the pick.
        val failed = assertIs<UpdateCheck.Failed>(got)
        assertTrue("1.0.8-nightly.20261107.12" in failed.failure.message, failed.failure.message)
    }
}
