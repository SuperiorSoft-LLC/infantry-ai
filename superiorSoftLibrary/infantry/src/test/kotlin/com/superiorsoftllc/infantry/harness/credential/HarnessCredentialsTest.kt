// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.credential

import com.superiorsoftllc.infantry.harness.descriptor.HarnessDescriptor
import com.superiorsoftllc.infantry.harness.descriptor.InvalidDescriptor
import com.superiorsoftllc.infantry.keystore.KeystoreHandler
import com.superiorsoftllc.infantry.keystore.KnownSecret
import com.superiorsoftllc.infantry.keystore.Keystore
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.protocol.Envelope
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class HarnessCredentialsTest {
    private val key = "cc-SuperSecretKey1234567890WXYZ"
    private val decl = HarnessCredential("commandcode", "COMMANDCODE_API_KEY", "Command Code", "Get one at the vendor's site")

    private fun store(declared: List<HarnessCredential> = listOf(decl)): Pair<Keystore, HarnessCredentials> {
        val d = Files.createTempDirectory("hc-test").toFile()
        val ks = Keystore(d, env = { null }, vault = null)
        return ks to HarnessCredentials(ks) { declared }
    }

    @Test
    fun `unset then set, env carries the key, state shows only a masked tail`() {
        val (ks, c) = store()
        assertEquals(emptyMap(), c.env("commandcode"))
        assertEquals(listOf(decl), c.missing())
        c.set("commandcode", " $key ")
        assertEquals(mapOf("COMMANDCODE_API_KEY" to key), c.env("commandcode"))
        val s = c.states().single()
        assertTrue(s.set)
        assertFalse(s.tail.contains("SuperSecret"))
        assertEquals(emptyList(), c.missing())
        assertEquals(key, ks.field("harness.commandcode.key", "value"))
        assertTrue(c.clear("commandcode"))
        assertEquals(emptyMap(), c.env("commandcode"))
    }

    @Test
    fun `a harness that declares nothing cannot be given a key`() {
        val (_, c) = store()
        assertFailsWith<IllegalArgumentException> { c.set("claude", key) }
        assertEquals(emptyMap(), c.env("claude"))
    }

    @Test
    fun `env names that would hijack the daemon or system are refused`() {
        assertNull(HarnessCredential.envRefusal("OPENCODE_API_KEY"))
        for (bad in listOf("PATH", "INFANTRY_TOKEN", "AGENTHQ_TOKEN", "lower", "NODE_OPTIONS", "A B")) assertNotNull(HarnessCredential.envRefusal(bad), bad)
    }

    @Test
    fun `the secret frames set and report it as a known secret, no value comes back, the key never goes to a run`() {
        val (ks, c) = store()
        val pushed = mutableListOf<Envelope>()
        val h = KeystoreHandler(ks, extraKnown = { c.declared().map { KnownSecret(it.secretName, "${it.label} API key") } }, push = { pushed += it })
        val before = h.state().secrets.single { it.name == "harness.commandcode.key" }
        assertFalse(before.set)
        val reply = h.answer(Envelope(Envelope.SECRET_SET, """{"name":"harness.commandcode.key","value":"$key"}"""))!!
        assertEquals(Envelope.SECRET_STATE, reply.type)
        assertFalse(reply.data.contains(key), "the value never comes back")
        assertTrue(h.state().secrets.single { it.name == "harness.commandcode.key" }.set)
        assertEquals(mapOf("COMMANDCODE_API_KEY" to key), c.env("commandcode"))
        assertFailsWith<IllegalArgumentException> { KeystoreHandler.runSecrets({ ks.fields(it) })("harness.commandcode.key") }
    }

    @Test
    fun `a descriptor declares its credential`() {
        val json = Json.parseToJsonElement("""{"id":"commandcode","command":"cmd","credential":{"env":"COMMANDCODE_API_KEY","label":"Command Code","help":"h"}}""")
        val d = HarnessDescriptor.parse(json)
        assertEquals(HarnessCredential("commandcode", "COMMANDCODE_API_KEY", "Command Code", "h"), d.credential)
        assertNull(HarnessDescriptor.parse(Json.parseToJsonElement("""{"id":"x","command":"c"}""")).credential)
        assertFailsWith<InvalidDescriptor> { HarnessDescriptor.parse(Json.parseToJsonElement("""{"id":"x","command":"c","credential":{"env":"PATH"}}""")) }
    }

    @Test
    fun `module json declares its credential`() {
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(),
            """{"name":"Infantry_X","version":"1.0.0","apiVersion":2,"harness":{"ids":["x"],"credential":{"env":"X_API_KEY","label":"X","help":"h"}}}""")
        assertEquals("X_API_KEY", m.harness?.credential?.env)
    }
}

/** CLAUDE-AUTH-CHOICE-1: the per-harness auth mode choice over the credential. */
class HarnessAuthModeTest {
    private val decl = HarnessCredential("claude", "ANTHROPIC_API_KEY", "Claude Code", "", listOf("api-key", "subscription-login"))

    private fun creds(mode: String?): HarnessCredentials {
        val d = Files.createTempDirectory("hc-mode").toFile()
        return HarnessCredentials(Keystore(d, env = { null }, vault = null), { mode }) { listOf(decl) }
    }

    @Test
    fun `no choice yet keeps the key behaviour and still asks`() {
        val c = creds(null)
        assertNull(c.mode("claude"))
        assertEquals(listOf(decl), c.missing())
    }

    @Test
    fun `subscription login injects no key and asks for none even when one is stored`() {
        val c = creds("subscription-login")
        c.set("claude", "sk-ant-abcdefghijklmnop")
        assertEquals("subscription-login", c.mode("claude"))
        assertEquals(mapOf("ANTHROPIC_API_KEY" to ""), c.env("claude"))
        assertEquals(emptyList(), creds("subscription-login").missing())
    }

    /** RUNNER-CRED-1: a credential declared for the subscription mode (a setup-token) signs that mode in; never the API-key mode. */
    @Test
    fun `a subscription token goes only in subscription mode and is never missing`() {
        val token = HarnessCredential("claude", "CLAUDE_CODE_OAUTH_TOKEN", "Claude Code subscription token", credId = "oauth", forMode = "subscription-login")
        fun two(mode: String?): HarnessCredentials {
            val d = Files.createTempDirectory("hc-token").toFile()
            return HarnessCredentials(Keystore(d, env = { null }, vault = null), { mode }) { listOf(decl, token) }
        }
        val sub = two("subscription-login")
        assertEquals(mapOf("ANTHROPIC_API_KEY" to ""), sub.env("claude"), "no token stored: the key stays blanked, nothing else")
        sub.set("claude", "sk-ant-abcdefghijklmnop"); sub.set("claude", "oat-token-1234567890", "oauth")
        assertEquals(mapOf("ANTHROPIC_API_KEY" to "", "CLAUDE_CODE_OAUTH_TOKEN" to "oat-token-1234567890"), sub.env("claude"))
        val api = two("api-key")
        api.set("claude", "sk-ant-abcdefghijklmnop"); api.set("claude", "oat-token-1234567890", "oauth")
        assertEquals(mapOf("ANTHROPIC_API_KEY" to "sk-ant-abcdefghijklmnop"), api.env("claude"))
        assertEquals(listOf(decl), two(null).missing(), "the optional token is never asked for")
    }

    @Test
    fun `module json declares a credential for one auth mode`() {
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(),
            """{"name":"Infantry_X","version":"1.0.0","apiVersion":2,"harness":{"ids":["x"],"credentials":[{"id":"oauth","env":"X_TOKEN","forMode":"subscription-login"}]}}""")
        assertEquals("subscription-login", m.harness?.credentials?.single()?.forMode)
    }

    @Test
    fun `api key mode injects the key and a mode the harness does not offer is ignored`() {
        val c = creds("api-key")
        c.set("claude", "sk-ant-abcdefghijklmnop")
        assertEquals(mapOf("ANTHROPIC_API_KEY" to "sk-ant-abcdefghijklmnop"), c.env("claude"))
        assertNull(creds("telepathy").mode("claude"))
    }

    @Test
    fun `manifest auth modes drop unknown names and the setting name is per harness`() {
        assertEquals(listOf("api-key"), HarnessCredential.cleanModes(listOf("api-key", "x", "api-key")))
        assertEquals("harness.claude.authMode", decl.modeSetting)
    }
}

/** CLAUDE-AUTH-CHOICE-2: the subscription sign-in probe and the no-key session env. */
class HarnessAuthProbeTest {
    private val decl = HarnessCredential(
        "claude", "ANTHROPIC_API_KEY", "Claude Code", "", listOf("api-key", "subscription-login"),
        probeArgv = listOf("claude", "auth", "status"), probeField = "loggedIn", loginCommand = "claude auth login",
    )

    private fun creds(mode: String?, out: String?, ran: MutableList<List<String>> = mutableListOf()): HarnessCredentials {
        val d = Files.createTempDirectory("hc-probe").toFile()
        return HarnessCredentials(Keystore(d, env = { null }, vault = null), { mode }, { argv, _ -> ran += argv; out }) { listOf(decl) }
    }

    @Test
    fun `the probe reads the manifest field and records the time`() {
        val ran = mutableListOf<List<String>>()
        val c = creds("subscription-login", """{"loggedIn": false, "authMethod": "none"}""", ran)
        assertNull(c.authStatus("claude").signedIn)
        assertEquals(0L, c.authStatus("claude").lastProbe)
        assertEquals(false, c.checkAuth("claude").signedIn)
        assertEquals(listOf(listOf("claude", "auth", "status")), ran)
        assertEquals("false", c.authStatus("claude").word)
        assert(c.authStatus("claude").lastProbe > 0L)
        assertEquals(true, creds("subscription-login", """{"loggedIn": true}""").checkAuth("claude").signedIn)
    }

    @Test
    fun `RUNNER-LOGIN-1 no API key warning for a signed-in CLI, probed once`() {
        val ran = mutableListOf<List<String>>()
        val signedIn = creds(null, """{"loggedIn": true}""", ran)
        assertEquals(emptyList(), signedIn.missingAtLaunch("claude"), "auth mode not chosen, but the CLI is logged in")
        assertEquals(emptyList(), signedIn.missingAtLaunch("claude"))
        assertEquals(1, ran.size, "the probe runs once, then its answer is reused")
        assertEquals(listOf(decl), creds(null, """{"loggedIn": false}""").missingAtLaunch("claude"), "signed out: the warning stays")
        assertEquals(listOf(decl), creds(null, null).missingAtLaunch("claude"), "unknown: the warning stays")
        val apiKey = mutableListOf<List<String>>()
        assertEquals(listOf(decl), creds("api-key", """{"loggedIn": true}""", apiKey).missingAtLaunch("claude"), "API-key mode chosen: a key is needed")
        assertEquals(emptyList(), apiKey, "a chosen mode runs no probe")
        assertEquals(emptyList(), creds("subscription-login", null).missingAtLaunch("claude"), "subscription chosen: never warns")
    }

    @Test
    fun `an unreadable or missing probe is unknown, never signed in`() {
        assertNull(creds("subscription-login", "not json").checkAuth("claude").signedIn)
        assertNull(creds("subscription-login", null).checkAuth("claude").signedIn)
        assertEquals("unknown", creds("subscription-login", null).checkAuth("claude").word)
        assertNull(creds("subscription-login", """{"loggedIn": true}""").checkAuth("nope").signedIn)
    }

    @Test
    fun `a subscription session env never carries the stored key and blanks the variable`() {
        val c = creds("subscription-login", null)
        c.set("claude", "sk-ant-abcdefghijklmnop")
        val env = c.env("claude")
        assertEquals("", env["ANTHROPIC_API_KEY"])
        assert(env.values.none { it.contains("sk-ant") })
    }

    @Test
    fun `SECRET-REF-1 a key can reference a shared secret, resolved per launch, the value never leaves the keystore`() {
        val key = "cc-SuperSecretKey1234567890WXYZ"
        val decl = HarnessCredential("commandcode", "COMMANDCODE_API_KEY", "Command Code")
        val d = Files.createTempDirectory("hc-ref").toFile()
        val ks = Keystore(d, env = { null }, vault = null)
        var ref: String? = "command_key"
        val c = HarnessCredentials(ks, refSource = { ref }) { listOf(decl) }
        assertEquals(emptyMap(), c.env("commandcode"))
        assertEquals(listOf(decl), c.missing(), "unresolved reference = missing, naming the secret")
        assertEquals("command_key", c.usesSecret("commandcode"))
        ks.setField("command_key", "COMMAND_KEY", key)
        assertEquals(mapOf("COMMANDCODE_API_KEY" to key), c.env("commandcode"))
        ks.setField("command_key", "COMMAND_KEY", key + "2")
        assertEquals(mapOf("COMMANDCODE_API_KEY" to key + "2"), c.env("commandcode"), "resolved per launch")
        ks.setField("command_key", "OTHER", "x")
        ks.setField("proj", "value", "p", "p1")
        assertEquals(emptyMap(), c.env("commandcode"), "two fields and no value field is ambiguous")
        ks.setField("command_key", "value", key, "p1")
        assertEquals(key, c.env("commandcode", "p1")["COMMANDCODE_API_KEY"], "project scope wins")
        val s = c.states().single()
        assertFalse(s.tail.contains("SuperSecret"))
        val h = KeystoreHandler(ks, extraKnown = { c.declared().map { k -> KnownSecret(k.secretName, k.label, usesSecret = { c.usesSecret(k.harnessId).orEmpty() }) } })
        val wire = Json.encodeToString(com.superiorsoftllc.infantry.keystore.SecretState.serializer(), h.state())
        assertTrue(wire.contains("command_key"))
        assertFalse(wire.contains("SuperSecret"), "no value on the wire")
        ks.clear("command_key"); ks.clear("command_key", "p1")
        assertEquals(emptyMap(), c.env("commandcode"), "deleted secret = unresolved")
        ref = "harness.other.key"
        assertNull(c.usesSecret("commandcode"), "a built-in name is never referenced")
    }
}

/** HARNESS-MULTI-CREDENTIAL-1: several keys for one harness, each with its own env variable, secret and reference. */
class HarnessMultiCredentialTest {
    private val goKey = "go-FakeOpenCodeGoKey1234567890AAAA"
    private val cmdKey = "cmd-FakeCommandCodeKey1234567890BBBB"
    private val primary = HarnessCredential("opencode", "OPENCODE_API_KEY", "OpenCode Go")
    private val cmd = HarnessCredential("opencode", "CMD_API_KEY", "Command Code", credId = "cmd")

    private fun creds(refs: Map<String, String> = emptyMap()): Pair<Keystore, HarnessCredentials> {
        val ks = Keystore(Files.createTempDirectory("hc-multi").toFile(), env = { null }, vault = null)
        return ks to HarnessCredentials(ks, refSource = { refs[it] }) { listOf(primary, cmd) }
    }

    @Test
    fun `each credential has its own secret, reference setting and env variable`() {
        assertEquals("harness.opencode.key", primary.secretName)
        assertEquals("harness.opencode.cmd.key", cmd.secretName)
        assertEquals("harness.opencode.keyRef", primary.refSetting)
        assertEquals("harness.opencode.cmd.keyRef", cmd.refSetting)
        val (_, c) = creds()
        assertEquals(2, c.declarations("opencode").size)
        assertEquals(2, c.missing().size)
        c.set("opencode", cmdKey, "cmd")
        assertEquals(mapOf("CMD_API_KEY" to cmdKey), c.env("opencode"))
        assertEquals(listOf(primary), c.missing(), "only the unset credential is missing")
        c.set("opencode", goKey)
        assertEquals(mapOf("OPENCODE_API_KEY" to goKey, "CMD_API_KEY" to cmdKey), c.env("opencode"))
        assertFailsWith<IllegalArgumentException> { c.set("opencode", "x", "nope") }
        assertTrue(c.clear("opencode", "cmd"))
        assertEquals(mapOf("OPENCODE_API_KEY" to goKey), c.env("opencode"))
    }

    @Test
    fun `a reference on one credential never feeds the other`() {
        val (ks, c) = creds(mapOf("harness.opencode.cmd.keyRef" to "command_key"))
        ks.setField("command_key", "value", cmdKey)
        assertEquals(mapOf("CMD_API_KEY" to cmdKey), c.env("opencode"))
        assertNull(c.usesSecret("opencode"))
        assertEquals("command_key", c.usesSecret("opencode", "cmd"))
        assertFalse(c.env("opencode").containsKey("OPENCODE_API_KEY"), "the Command Code key is never sent as OPENCODE_API_KEY")
    }

    @Test
    fun `the single credential form still works and the wire carries no value`() {
        val ks = Keystore(Files.createTempDirectory("hc-single").toFile(), env = { null }, vault = null)
        val c = HarnessCredentials(ks) { listOf(primary) }
        c.set("opencode", goKey)
        assertEquals(mapOf("OPENCODE_API_KEY" to goKey), c.env("opencode"))
        val (ks2, c2) = creds()
        c2.set("opencode", goKey); c2.set("opencode", cmdKey, "cmd")
        val h = KeystoreHandler(ks2, extraKnown = { c2.declared().map { k -> KnownSecret(k.secretName, "${k.label} API key") } })
        val wire = Json.encodeToString(com.superiorsoftllc.infantry.keystore.SecretState.serializer(), h.state())
        assertTrue(wire.contains("harness.opencode.cmd.key"))
        assertFalse(wire.contains("FakeCommandCode") || wire.contains("FakeOpenCode"), "no value on the wire")
        assertNotNull(HarnessCredential.credIdRefusal("Bad Id"))
        assertNull(HarnessCredential.credIdRefusal(""))
    }

    @Test
    fun `module json parses a credentials list and the opencode manifest declares both keys`() {
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(),
            """{"name":"Infantry_X","version":"1.0.0","apiVersion":2,"harness":{"ids":["x"],"credential":{"env":"A_KEY"},"credentials":[{"id":"b","env":"B_KEY","provider":"B"}]}}""")
        assertEquals("A_KEY", m.harness?.credential?.env)
        assertEquals("b", m.harness?.credentials?.single()?.id)
        val f = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "plugins/official/Infantry_OpenCode/module.json") }.first { it.exists() }
        val oc = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), f.readText()).harness!!
        assertEquals(setOf("OPENCODE_API_KEY", "CMD_API_KEY"), (listOfNotNull(oc.credential) + oc.credentials).map { it.env }.toSet())
    }
}
