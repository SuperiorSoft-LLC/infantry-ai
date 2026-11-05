// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater.daemon

import com.superiorsoftllc.infantry.daemon.api.updates.Credential
import com.superiorsoftllc.infantry.daemon.api.updates.SourceOffer
import com.superiorsoftllc.infantry.daemon.api.updates.SourceRequest
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateApplier
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateComponent
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateSourceType
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateState
import com.superiorsoftllc.infantry.daemon.api.updates.Updates
import com.superiorsoftllc.infantry.plugin.api.UpdateSourceRef
import com.superiorsoftllc.infantry.plugins.official.updater.ApplyResult
import com.superiorsoftllc.infantry.plugins.official.updater.AssetKind
import com.superiorsoftllc.infantry.plugins.official.updater.AvailableUpdate
import com.superiorsoftllc.infantry.plugins.official.updater.Distribution
import com.superiorsoftllc.infantry.plugins.official.updater.EngineAnswer
import com.superiorsoftllc.infantry.plugins.official.updater.EnginePath
import com.superiorsoftllc.infantry.plugins.official.updater.Fetched
import com.superiorsoftllc.infantry.plugins.official.updater.GitHubRelease
import com.superiorsoftllc.infantry.plugins.official.updater.ReleaseAsset
import com.superiorsoftllc.infantry.plugins.official.updater.SemVer
import com.superiorsoftllc.infantry.plugins.official.updater.ServerInstall
import com.superiorsoftllc.infantry.plugins.official.updater.ServerUpdateService
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateCheck
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateConfig
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateFailure
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSettings
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSource
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateVisibility
import com.superiorsoftllc.infantry.plugins.official.updater.VerifiedInstaller
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UPDATER-1: Infantry_Updater on core's update engine: the `infantry-release` source type, the core applier, the engine path. */
class EngineBridgeTest {
    private val sha = "a".repeat(64)
    private val v103 = SemVer.parse("1.0.3")!!

    /** A release source that answers [check] and records the config (and token) it was built with. */
    private class FakeSource(val config: UpdateConfig, val answer: (UpdateConfig) -> UpdateCheck, val sum: String?) : UpdateSource {
        override fun check() = answer(config)
        override fun expectedSha256(update: AvailableUpdate): Fetched<String> =
            sum?.let { Fetched.Ok(it) } ?: Fetched.Failed(UpdateFailure.BadAsset("no sums"))
        override fun download(update: AvailableUpdate, into: File): Fetched<VerifiedInstaller> {
            into.mkdirs()
            val f = File(into, update.installer.name).apply { writeText("archive") }
            return Fetched.Ok(VerifiedInstaller(update, f, sum ?: "", checksumVerified = true))
        }
    }

    private fun available(cfg: UpdateConfig) = UpdateCheck.Available(
        AvailableUpdate(
            cfg.currentVersion, v103,
            GitHubRelease(tagName = "v1.0.3", body = "notes 1.0.3"),
            ReleaseAsset("InfantryAI-server-1.0.3-windows-x64.zip", 7),
            ReleaseAsset("SHA256SUMS", 100),
        ),
    )

    private fun base(visibility: UpdateVisibility = UpdateVisibility.PRIVATE) =
        UpdateConfig("acme/app", SemVer.parse("1.0.2")!!, visibility = visibility, tokenSource = { "plugin-token" })

    private fun request(location: String = "acme/app", credential: Credential? = null, version: String = "1.0.2") = SourceRequest(
        UpdateComponent(UpdateComponent.CORE_ID, UpdateComponent.KIND_CORE, "Infantry AI", version, UpdateSourceRef(ReleaseSourceType.TYPE, location)),
        location, null, "windows", credential,
    )

    private fun type(base: UpdateConfig?, answer: (UpdateConfig) -> UpdateCheck, sum: String? = sha, built: MutableList<FakeSource> = mutableListOf()) =
        ReleaseSourceType(base = { base }, kind = { AssetKind.ServerArchiveFor() }, sources = { cfg, _ -> FakeSource(cfg, answer, sum).also { built += it } })

    @Test
    fun `an available release becomes an offer carrying its published SHA-256`() {
        val offer = type(base(), ::available).latest(request())!!
        assertEquals("1.0.3", offer.version)
        assertEquals("InfantryAI-server-1.0.3-windows-x64.zip", offer.artifact)
        assertEquals(sha, offer.sha256)
        assertEquals("notes 1.0.3", offer.notes)
    }

    @Test
    fun `the engine's credential wins over the plugin's own lookup, and the component's version is the current one`() {
        val built = mutableListOf<FakeSource>()
        type(base(), ::available, built = built).latest(request(credential = Credential("engine-token"), version = "1.0.1"))
        assertEquals("engine-token", built.single().config.token)
        assertEquals("1.0.1", built.single().config.currentVersion.toString())
        built.clear()
        type(base(), ::available, built = built).latest(request())
        assertEquals("plugin-token", built.single().config.token)
    }

    @Test
    fun `a public source never sends a token, whatever the engine resolved`() {
        val built = mutableListOf<FakeSource>()
        type(base(UpdateVisibility.PUBLIC), ::available, built = built).latest(request(credential = Credential("engine-token")))
        assertNull(built.single().config.token)
    }

    @Test
    fun `an absolute location is a releases folder`() {
        val dir = Files.createTempDirectory("rel").toFile()
        val built = mutableListOf<FakeSource>()
        type(base(), ::available, built = built).latest(request(location = dir.absolutePath))
        assertEquals(dir.absolutePath, built.single().config.localDir?.absolutePath)
        dir.deleteRecursively()
    }

    @Test
    fun `up to date is no offer, a failure and a missing SHA-256 throw, no settings throws`() {
        assertNull(type(base(), { UpdateCheck.UpToDate(it.currentVersion, it.currentVersion) }).latest(request()))
        assertFailsWith<IOException> { type(base(), { UpdateCheck.Failed(UpdateFailure.Offline("x")) }).latest(request()) }
        assertFailsWith<IOException> { type(base(), ::available, sum = null).latest(request()) }
        assertFailsWith<IOException> { type(null, ::available).latest(request()) }
    }

    @Test
    fun `download writes the offered file into the staging folder`() {
        val t = type(base(), ::available)
        val offer = t.latest(request())!!
        val into = Files.createTempDirectory("stage").toFile()
        val file = t.download(request(), offer, into)
        assertEquals(File(into, offer.artifact).canonicalPath, file.canonicalPath)
        into.deleteRecursively()
    }

    @Test
    fun `the app's source names the token only for a private repo`() {
        assertNull(ReleaseSourceType.coreSource(null))
        val public = ReleaseSourceType.coreSource(base(UpdateVisibility.PUBLIC))!!
        assertEquals("acme/app", public.location)
        assertNull(public.credential)
        val private = ReleaseSourceType.coreSource(base())!!
        assertEquals(UpdateSettings.TOKEN_ENV, private.credential?.env)
        assertEquals(UpdateSettings.TOKEN_SECRET, private.credential?.secret)
    }

    @Test
    fun `the core applier refuses without an archive install`() {
        val staged = UpdateState.Staged(UpdateComponent.CORE_ID, SourceOffer("1.0.3", "x.zip", sha), File("x.zip"))
        val c = UpdateComponent(UpdateComponent.CORE_ID, UpdateComponent.KIND_CORE, "Infantry AI", "1.0.2", null)
        assertIs<UpdateState.Failed>(CoreArchiveApplier { null }.apply(c, staged))
    }

    /** An engine that answers fixed states, as core's would. */
    private class FakeUpdates(val checked: UpdateState, val fetched: UpdateState, val applied: UpdateState) : Updates {
        override fun registerSource(source: UpdateSourceType) = AutoCloseable {}
        override fun registerApplier(applier: UpdateApplier) = AutoCloseable {}
        override fun setSource(componentId: String, source: UpdateSourceRef?) = Unit
        override fun sourceTypes() = listOf(ReleaseSourceType.TYPE)
        override fun components() = emptyList<UpdateComponent>()
        override fun check(componentId: String) = checked
        override fun fetch(componentId: String) = fetched
        override fun apply(componentId: String) = applied
    }

    @Test
    fun `the engine path maps the engine's states`() {
        val offer = SourceOffer("1.0.3", "x.zip", sha, notes = "n")
        val ok = CoreEnginePath(FakeUpdates(
            UpdateState.Available("core", "1.0.2", offer),
            UpdateState.Staged("core", offer, File("x.zip")),
            UpdateState.Applied("core", "1.0.3"),
        ))
        assertEquals(EngineAnswer.Available("1.0.3", "n"), ok.check())
        assertEquals(EngineAnswer.Installed("1.0.3"), ok.install())
        val bad = CoreEnginePath(FakeUpdates(UpdateState.NoSource("core"), UpdateState.Failed("core", "sha mismatch"), UpdateState.Failed("core", "x")))
        assertIs<EngineAnswer.Failed>(bad.check())
        assertEquals(EngineAnswer.Failed("sha mismatch"), bad.install())
    }

    @Test
    fun `the service checks and installs through the engine when it has one`() {
        var hooked: String? = null
        val engine = object : EnginePath {
            override fun check() = EngineAnswer.Available("1.0.3", "n")
            override fun install() = EngineAnswer.Installed("1.0.3")
        }
        val root = Files.createTempDirectory("install").toFile()
        val service = ServerUpdateService(
            settings = UpdateSettings.Enabled(base()),
            distribution = Distribution.ARCHIVE,
            version = "1.0.2",
            install = ServerInstall(root),
            sourceFor = { error("the plugin's own source must not be used when the engine is there") },
            onStaged = { hooked = it },
            engine = engine,
        )
        assertEquals("1.0.3", service.check().available?.version)
        assertEquals(ApplyResult.Staged("1.0.3"), service.apply(confirm = false))
        assertEquals("1.0.3", hooked)
        assertTrue(service.status().staged == "1.0.3")
        root.deleteRecursively()
    }
}
