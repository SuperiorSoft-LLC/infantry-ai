// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import com.superiorsoftllc.infantry.daemon.api.updates.Credential
import com.superiorsoftllc.infantry.daemon.api.updates.SourceOffer
import com.superiorsoftllc.infantry.daemon.api.updates.SourceRequest
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateApplier
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateComponent
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateSourceType
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateState
import com.superiorsoftllc.infantry.plugin.api.UpdateCredentialRef
import com.superiorsoftllc.infantry.plugin.api.UpdateSourceRef
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UPDATE-ENGINE-1: the generic engine with a fake source type: per-component sources, credentials by name, verify, stage, apply. */
class UpdateEngineTest {
    private val temp = Files.createTempDirectory("update-engine-test-").toFile()

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private val payload = "new build".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    /** A source type serving [version] with [sha]; it records the requests it saw. */
    private class FakeSource(
        override val type: String = "fake",
        var version: String? = "1.2.0",
        var sha: String = "",
        var bytes: ByteArray = ByteArray(0),
    ) : UpdateSourceType {
        val seen = mutableListOf<SourceRequest>()
        override fun latest(request: SourceRequest): SourceOffer? {
            seen += request
            return version?.let { SourceOffer(it, "plugin-$it.jar", sha, handle = "asset-1") }
        }
        override fun download(request: SourceRequest, offer: SourceOffer, into: File): File =
            File(into, offer.artifact).apply { writeBytes(bytes) }
    }

    private fun component(id: String = "Acme_Notes", version: String = "1.1.0", source: UpdateSourceRef? = UpdateSourceRef("fake", "acme/notes")) =
        UpdateComponent(id, UpdateComponent.KIND_PLUGIN, id, version, source)

    private fun engine(
        components: List<UpdateComponent>,
        credential: (UpdateComponent, UpdateSourceRef) -> Credential? = { _, _ -> null },
    ) = UpdateEngine({ components }, credential, File(temp, "staging"), platform = "windows")

    @Test
    fun `each component is checked against its own source and location`() {
        val source = FakeSource(sha = payloadSha, bytes = payload)
        val e = engine(listOf(component("Acme_Notes"), component("Other_Tool", source = UpdateSourceRef("fake", "other/tool", channel = "beta"))))
        e.registerSource(source)

        assertIs<UpdateState.Available>(e.check("Acme_Notes"))
        assertIs<UpdateState.Available>(e.check("Other_Tool"))
        assertEquals(listOf("acme/notes" to null, "other/tool" to "beta"), source.seen.map { it.location to it.channel })
        assertEquals("windows", source.seen.first().platform)
    }

    @Test
    fun `no source, an unknown type, up to date and an older offer`() {
        val source = FakeSource(version = "1.1.0", sha = payloadSha)
        val e = engine(listOf(component("A_None", source = null), component("A_Missing", source = UpdateSourceRef("nowhere")), component()))
        e.registerSource(source)

        assertIs<UpdateState.NoSource>(e.check("A_None"))
        assertTrue((e.check("A_Missing") as UpdateState.Failed).reason.contains("nowhere"))
        assertIs<UpdateState.UpToDate>(e.check("Acme_Notes"))
        source.version = "1.0.9"
        assertIs<UpdateState.UpToDate>(e.check("Acme_Notes"))
        source.version = null
        assertIs<UpdateState.UpToDate>(e.check("Acme_Notes"))
        assertIs<UpdateState.Failed>(e.check("No_Such"))
    }

    @Test
    fun `fetch stages a verified file and apply hands it to the applier of the kind`() {
        val e = engine(listOf(component()))
        e.registerSource(FakeSource(sha = payloadSha, bytes = payload))
        val applied = mutableListOf<File>()
        e.registerApplier(object : UpdateApplier {
            override val kind = UpdateComponent.KIND_PLUGIN
            override fun apply(component: UpdateComponent, staged: UpdateState.Staged): UpdateState {
                applied += staged.file
                return UpdateState.Applied(component.id, staged.offer.version)
            }
        })

        val staged = assertIs<UpdateState.Staged>(e.fetch("Acme_Notes"))
        assertTrue(staged.file.isFile)
        assertTrue(staged.file.canonicalPath.startsWith(File(temp, "staging").canonicalPath))
        assertIs<UpdateState.Applied>(e.apply("Acme_Notes"))
        assertEquals(listOf(staged.file), applied)
    }

    @Test
    fun `a download whose SHA-256 differs is refused and removed`() {
        val e = engine(listOf(component()))
        e.registerSource(FakeSource(sha = payloadSha, bytes = "tampered".toByteArray()))

        val failed = assertIs<UpdateState.Failed>(e.fetch("Acme_Notes"))
        assertTrue("SHA-256 mismatch" in failed.reason, failed.reason)
        assertFalse(File(temp, "staging/Acme_Notes/1.2.0/plugin-1.2.0.jar").exists())
        assertIs<UpdateState.Failed>(e.apply("Acme_Notes"), "nothing staged: nothing to apply")
    }

    @Test
    fun `an offer without a SHA-256, a bad version or an unsafe name is refused at check`() {
        val source = FakeSource(sha = "")
        val e = engine(listOf(component()))
        e.registerSource(source)
        assertTrue("SHA-256" in (e.check("Acme_Notes") as UpdateState.Failed).reason)
        source.sha = payloadSha
        source.version = "latest"
        assertIs<UpdateState.Failed>(e.check("Acme_Notes"))
        val climbing = object : UpdateSourceType by source {
            override val type = "climb"
            override fun latest(request: SourceRequest) = SourceOffer("2.0.0", "../evil.jar", payloadSha)
        }
        val e2 = engine(listOf(component(source = UpdateSourceRef("climb"))))
        e2.registerSource(climbing)
        assertTrue("unsafe" in (e2.check("Acme_Notes") as UpdateState.Failed).reason)
    }

    @Test
    fun `the credential is resolved per component, only when declared, and never printed`() {
        val source = FakeSource(sha = payloadSha)
        val withCred = UpdateSourceRef("fake", "acme/notes", credential = UpdateCredentialRef(env = "ACME_TOKEN"))
        val asked = mutableListOf<String>()
        val e = engine(listOf(component(source = withCred), component("Free_One"))) { c, _ -> asked += c.id; Credential("s3cret-value") }
        e.registerSource(source)

        e.check("Acme_Notes")
        e.check("Free_One")
        assertEquals(listOf("Acme_Notes"), asked, "a component without a credential reference never asks")
        val cred = source.seen.first().credential!!
        assertEquals("s3cret-value", cred.reveal())
        assertFalse("s3cret" in cred.toString())
        assertFalse("s3cret" in source.seen.first().toString())
        assertNull(source.seen[1].credential)
    }

    @Test
    fun `setSource overrides the declared source, and clears it with null`() {
        val e = engine(listOf(component(source = null)))
        e.registerSource(FakeSource(sha = payloadSha))
        assertIs<UpdateState.NoSource>(e.check("Acme_Notes"))
        e.setSource("Acme_Notes", UpdateSourceRef("fake", "acme/notes"))
        assertIs<UpdateState.Available>(e.check("Acme_Notes"))
        e.setSource("Acme_Notes", null)
        assertIs<UpdateState.NoSource>(e.check("Acme_Notes"))
        assertFailsWith<IllegalArgumentException> { e.setSource("No_Such", null) }
    }

    @Test
    fun `a source type is registered once, and freed by its handle`() {
        val e = engine(emptyList())
        val handle = e.registerSource(FakeSource())
        assertFailsWith<IllegalStateException> { e.registerSource(FakeSource()) }
        assertFailsWith<IllegalArgumentException> { e.registerSource(FakeSource(type = "Bad Type")) }
        handle.close()
        assertEquals(emptyList(), e.sourceTypes())
        e.registerSource(FakeSource())
        assertEquals(listOf("fake"), e.sourceTypes())
    }
}
