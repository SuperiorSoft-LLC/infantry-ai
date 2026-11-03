package com.superiorsoftllc.infantry.ui.shell

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmPlatformHostTest {
    /** A home with no `.infantry/daemon.port`, so no test reads the operator's real state dir. */
    private val emptyHome = Files.createTempDirectory("infantry-home").toFile().apply { deleteOnExit() }.absolutePath

    @Test fun desktopDefaultIsLoopbackDaemon() {
        assertEquals("ws://127.0.0.1:8787/ws", JvmPlatformHost(env = { null }, home = emptyHome).defaultDaemonUrl)
    }

    /** PORT-1: a daemon that fell back to another port records it; the desktop default follows that file. */
    @Test fun desktopDefaultFollowsThePortTheDaemonRecorded() {
        val home = Files.createTempDirectory("infantry-home").toFile().apply { deleteOnExit() }
        File(home, ".infantry").apply { mkdirs() }.resolve("daemon.port").writeText("8791\n")
        assertEquals("ws://127.0.0.1:8791/ws", JvmPlatformHost(env = { null }, home = home.absolutePath).defaultDaemonUrl)
    }

    /** PORT-1: INFANTRY_STATE_DIR is where the daemon writes the file, so it is where the desktop reads it. */
    @Test fun stateDirOverrideIsWhereTheRecordedPortIsRead() {
        val stateDir = Files.createTempDirectory("infantry-state").toFile().apply { deleteOnExit() }
        File(stateDir, "daemon.port").writeText("8790")
        val host = JvmPlatformHost(env = { if (it == "INFANTRY_STATE_DIR") stateDir.absolutePath else null }, home = emptyHome)
        assertEquals("ws://127.0.0.1:8790/ws", host.defaultDaemonUrl)
    }

    /**
     * BUG 464d7f97: the installed app pins INFANTRY_STATE_DIR as a system property (InstallProfile.apply), not in the
     * environment. The daemon reads the property first, so Settings > Plugins must read the same `<state>/plugins`,
     * not `~/.infantry/plugins`: a community module the daemon found is listed, off or on.
     */
    @Test fun thePinnedStateDirPropertyIsWherePluginsAreRead() {
        val stateDir = Files.createTempDirectory("infantry-state").toFile().apply { deleteOnExit() }
        File(stateDir, "plugins/community/Acme_Probe").apply { mkdirs() }.resolve("module.json")
            .writeText("""{"name":"Acme_Probe","version":"1.0.0","apiVersion":2,"author":"Acme","license":"MIT"}""")
        File(stateDir, "daemon.port").writeText("8799")
        val host = JvmPlatformHost(
            env = { null }, home = emptyHome,
            property = { if (it == "INFANTRY_STATE_DIR") stateDir.absolutePath else null },
        )
        val probe = host.tabFactory.statuses.single { it.name == "Acme_Probe" }
        assertEquals(com.superiorsoftllc.infantry.plugin.api.Tier.COMMUNITY, probe.tier)
        assertEquals("ws://127.0.0.1:8799/ws", host.defaultDaemonUrl)
    }

    @Test fun anUnreadablePortFileFallsBackToTheFixedPort() {
        val home = Files.createTempDirectory("infantry-home").toFile().apply { deleteOnExit() }
        File(home, ".infantry").apply { mkdirs() }.resolve("daemon.port").writeText("not a port")
        assertEquals("ws://127.0.0.1:8787/ws", JvmPlatformHost(env = { null }, home = home.absolutePath).defaultDaemonUrl)
    }

    @Test fun readsSettingsFromEnvironmentLookup() {
        val host = JvmPlatformHost(env = { if (it == ShellKeys.TOKEN) "tok" else null }, home = emptyHome)
        assertEquals("tok", host.setting(ShellKeys.TOKEN))
        assertNull(host.setting(ShellKeys.WS_URL))
    }

    @Test fun readsTextFilesAndReturnsNullWhenUnreadable() {
        val f = Files.createTempFile("infantry-token", ".txt").toFile().apply { writeText("abc\n"); deleteOnExit() }
        val host = JvmPlatformHost(env = { null }, home = emptyHome)
        assertEquals("abc\n", host.readTextFile(f.absolutePath))
        assertNull(host.readTextFile(f.absolutePath + ".missing"))
    }

    /** TEST-SPEED-4: blur is on unless -Dinfantry.ui.blur=off (the UI tests' CPU renderer pays ~150 ms a frame for it). */
    @Test fun blurIsSupportedUnlessTheBlurPropertyIsOff() {
        val before = System.getProperty("infantry.ui.blur")
        try {
            System.clearProperty("infantry.ui.blur")
            assertTrue(JvmPlatformHost(env = { null }, home = emptyHome).supportsBlur)
            System.setProperty("infantry.ui.blur", "OFF")
            assertFalse(JvmPlatformHost(env = { null }, home = emptyHome).supportsBlur)
            System.setProperty("infantry.ui.blur", "on")
            assertTrue(JvmPlatformHost(env = { null }, home = emptyHome).supportsBlur)
        } finally {
            if (before == null) System.clearProperty("infantry.ui.blur") else System.setProperty("infantry.ui.blur", before)
        }
    }

    @Test fun defaultPlatformHostIsTheJvmHost() {
        assertIs<JvmPlatformHost>(defaultPlatformHost())
    }
}
