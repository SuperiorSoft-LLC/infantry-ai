// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.updates.SourceOffer
import com.superiorsoftllc.infantry.daemon.api.updates.SourceRequest
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateApplier
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateComponent
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateSourceType
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateState
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.api.UpdateCredentialRef
import com.superiorsoftllc.infantry.plugin.api.UpdateDeclaration
import com.superiorsoftllc.infantry.plugin.api.UpdateSourceRef
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** UPDATE-ENGINE-1: the host's rules over the engine: components from module.json, declared source types, own-source only, credential names. */
class HostUpdatesTest {
    private val temp = Files.createTempDirectory("host-updates-test-").toFile()

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private val env = mapOf("ACME_TOKEN" to "acme-env", "INFANTRY_UPDATE_TOKEN" to "app-token", "SHARED_TOKEN" to "shared")
    private val secrets = mapOf("acme.license" to "acme-key", "update-token" to "app-secret")

    private fun manifest(name: String, update: UpdateSourceRef? = null, envs: List<String> = emptyList(), secrets: List<String> = emptyList()) =
        ModuleManifest(name = name, version = "1.0.0", update = update?.let(::UpdateDeclaration), daemon = DaemonCapabilities(env = envs, secrets = secrets))

    private fun host(vararg modules: HostUpdates.Module) =
        HostUpdates({ modules.toList() }, { "1.0.3" }, { env[it] }, { secrets[it] }, File(temp, "staging"), {})

    /** Records the credential each component's request carried. */
    private class Recorder(override val type: String = "rec") : UpdateSourceType {
        val creds = mutableMapOf<String, String?>()
        override fun latest(request: SourceRequest): SourceOffer? { creds[request.component.id] = request.credential?.reveal(); return null }
        override fun download(request: SourceRequest, offer: SourceOffer, into: File): File = error("unused")
    }

    @Test
    fun `the app and every module are components, with the module json source`() {
        val h = host(
            HostUpdates.Module("Acme_Notes", Tier.COMMUNITY, manifest("Acme_Notes", UpdateSourceRef("rec", "acme/notes"))),
            HostUpdates.Module("Infantry_Board", Tier.OFFICIAL, manifest("Infantry_Board")),
        )
        val list = h.engine.components()
        assertEquals(listOf(UpdateComponent.CORE_ID, "Acme_Notes", "Infantry_Board"), list.map { it.id })
        assertEquals("1.0.3", list.first().currentVersion)
        assertEquals("acme/notes", list[1].source?.location)
        assertNull(list[2].source)
    }

    @Test
    fun `the module json update block parses`() {
        val text = """{"name":"Acme_Notes","version":"2.0.0","update":{"source":{"type":"github","location":"acme/notes","credential":{"env":"ACME_TOKEN","secret":"acme.license"}}}}"""
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), text)
        assertEquals(UpdateSourceRef("github", "acme/notes", credential = UpdateCredentialRef("ACME_TOKEN", "acme.license")), m.update?.source)
    }

    @Test
    fun `a source type must be declared, and a community one granted`() {
        val h = host()
        val official = h.scoped("Infantry_Updater", Tier.OFFICIAL, listOf("rec"), emptySet())
        assertFailsWith<IllegalStateException> { official.registerSource(Recorder("github")) }
        official.registerSource(Recorder("rec"))
        val community = h.scoped("Acme_Notes", Tier.COMMUNITY, listOf("acme-store"), emptySet())
        assertFailsWith<IllegalStateException> { community.registerSource(Recorder("acme-store")) }
        h.scoped("Acme_Notes", Tier.COMMUNITY, listOf("acme-store"), setOf("daemon.updateSource:acme-store")).registerSource(Recorder("acme-store"))
        assertEquals(listOf("acme-store", "rec"), official.sourceTypes())
    }

    @Test
    fun `a module sets its own source only, the app's only when official, and installs only when official`() {
        val h = host(HostUpdates.Module("Acme_Notes", Tier.COMMUNITY, manifest("Acme_Notes")), HostUpdates.Module("Other_Tool", Tier.COMMUNITY, manifest("Other_Tool")))
        val acme = h.scoped("Acme_Notes", Tier.COMMUNITY, emptyList(), emptySet())
        acme.setSource("Acme_Notes", UpdateSourceRef("rec"))
        assertFailsWith<IllegalStateException> { acme.setSource("Other_Tool", UpdateSourceRef("rec")) }
        assertFailsWith<IllegalStateException> { acme.setSource(UpdateComponent.CORE_ID, UpdateSourceRef("rec")) }
        assertFailsWith<IllegalStateException> { acme.apply("Acme_Notes") }
        assertFailsWith<IllegalStateException> {
            acme.registerApplier(object : UpdateApplier {
                override val kind = UpdateComponent.KIND_PLUGIN
                override fun apply(component: UpdateComponent, staged: UpdateState.Staged) = UpdateState.Applied(component.id, "x")
            })
        }
        h.scoped("Infantry_Updater", Tier.OFFICIAL, emptyList(), emptySet()).setSource(UpdateComponent.CORE_ID, UpdateSourceRef("rec", "owner/app"))
        assertEquals("owner/app", h.engine.component(UpdateComponent.CORE_ID)?.source?.location)
    }

    @Test
    fun `credentials resolve only under names the component may use`() {
        fun ref(env: String? = null, secret: String? = null) = UpdateSourceRef("rec", credential = UpdateCredentialRef(env, secret))
        val h = host(
            // vendor prefix: ACME_ env and acme secret names are its own
            HostUpdates.Module("Acme_Notes", Tier.COMMUNITY, manifest("Acme_Notes", ref(env = "ACME_TOKEN"))),
            HostUpdates.Module("Acme_Pro", Tier.COMMUNITY, manifest("Acme_Pro", ref(secret = "acme.license"))),
            // a community module may not take the app's token, by name or by a fake vendor prefix
            HostUpdates.Module("Evil_Grab", Tier.COMMUNITY, manifest("Evil_Grab", ref(env = "INFANTRY_UPDATE_TOKEN", secret = "update-token"))),
            HostUpdates.Module("Infantry_Fake", Tier.COMMUNITY, manifest("Infantry_Fake", ref(env = "INFANTRY_UPDATE_TOKEN"))),
            // declared but not granted (community), then declared and granted
            HostUpdates.Module("Some_Plugin", Tier.COMMUNITY, manifest("Some_Plugin", ref(env = "SHARED_TOKEN"), envs = listOf("SHARED_TOKEN"))),
            HostUpdates.Module("Some_Granted", Tier.COMMUNITY, manifest("Some_Granted", ref(env = "SHARED_TOKEN"), envs = listOf("SHARED_TOKEN")), setOf("daemon.env:SHARED_TOKEN")),
            // an official module's declared secret
            HostUpdates.Module("Infantry_Updater", Tier.OFFICIAL, manifest("Infantry_Updater", ref(secret = "update-token"), secrets = listOf("update-token"))),
        )
        val rec = Recorder()
        h.engine.registerSource(rec)
        h.engine.checkAll()
        assertEquals("acme-env", rec.creds["Acme_Notes"])
        assertEquals("acme-key", rec.creds["Acme_Pro"])
        assertNull(rec.creds["Evil_Grab"])
        assertNull(rec.creds["Infantry_Fake"])
        assertNull(rec.creds["Some_Plugin"])
        assertEquals("shared", rec.creds["Some_Granted"])
        assertEquals("app-secret", rec.creds["Infantry_Updater"])
    }

    @Test
    fun `the app component takes any credential name its official source setter gives`() {
        val h = host()
        val rec = Recorder()
        h.engine.registerSource(rec)
        h.scoped("Infantry_Updater", Tier.OFFICIAL, emptyList(), emptySet())
            .setSource(UpdateComponent.CORE_ID, UpdateSourceRef("rec", "owner/app", credential = UpdateCredentialRef(env = "INFANTRY_UPDATE_TOKEN", secret = "update-token")))
        h.engine.check(UpdateComponent.CORE_ID)
        assertNotNull(rec.creds[UpdateComponent.CORE_ID])
        assertEquals("app-token", rec.creds[UpdateComponent.CORE_ID], "env first, then the keystore")
    }
}
