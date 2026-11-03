package com.superiorsoftllc.infantry.executables

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLIPATH-1: what `cli_location_set` does, without a socket: validate, store or clear, answer with the resolution. */
class CliLocationServiceTest {
    private val env = object : ExecutableEnvironment {
        val files = mutableMapOf("/opt/claude" to true, "/usr/bin/git" to true, "/noexec" to false)
        override val os = HostOs.LINUX
        override fun env(name: String): String? = if (name == "PATH") "/usr/bin" else null
        override fun home(): String? = "/home/u"
        override fun exists(path: String) = path in files
        override fun isDirectory(path: String) = false
        override fun isExecutable(path: String) = files[path] == true
        override fun isAbsolute(path: String) = path.startsWith("/")
    }
    private val overrides = ExecutableOverrides.InMemory()
    private val service = CliLocationService(ExecutableResolver(env, overrides), overrides) { CliTools.KNOWN }

    @Test
    fun settingThenClearingAPathChangesTheSource() {
        val set = service.set("claude", " /opt/claude ").single { it.id == "claude" }
        assertEquals("/opt/claude", set.path)
        assertEquals("override", set.source)
        assertEquals("/opt/claude", overrides.get("claude"), "stored trimmed")

        val cleared = service.set("claude", "").single { it.id == "claude" }
        assertNull(overrides.get("claude"))
        assertNull(cleared.override)
        assertNull(cleared.path, "nothing else provides claude on this fake machine")
        assertTrue(cleared.tried.isNotEmpty() && cleared.reason != null)

        assertEquals("default", service.list().single { it.id == "git" }.source)
    }

    @Test
    fun aBadPathOrUnknownToolIsRefusedAndNothingIsStored() {
        val notExec = assertFailsWith<IllegalArgumentException> { service.set("claude", "/noexec") }
        assertTrue("is not executable" in notExec.message.orEmpty(), notExec.message)
        assertFailsWith<IllegalArgumentException> { service.set("claude", "/missing") }
        assertNull(overrides.get("claude"))

        val unknown = assertFailsWith<IllegalArgumentException> { service.set("vim", "/opt/claude") }
        assertTrue("unknown CLI 'vim'" in unknown.message.orEmpty())
    }
}
