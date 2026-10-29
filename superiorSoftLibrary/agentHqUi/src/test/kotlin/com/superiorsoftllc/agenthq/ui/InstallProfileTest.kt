package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.AgentHqBin
import com.superiorsoftllc.agenthq.session.agentHqSetting
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PKG-1: the installed app's production defaults never land on the dev daemon's state dir or ports. */
class InstallProfileTest {
    private val home = File("C:/Users/someone").path
    private val localAppData = File(home, "AppData/Local").path
    /** INSTALL-1: the production state dir. NOT `%LOCALAPPDATA%\AgentHQ`, which is where the per-user MSI installs the app. */
    private val prodState = File(localAppData, "SuperiorSoft/AgentHQ").absoluteFile
    /** The per-user MSI's INSTALLDIR (jpackage: LocalAppDataFolder\<packageName>), wiped by its RemoveFolderEx on uninstall/upgrade. */
    private val perUserInstallDir = File(localAppData, "AgentHQ").absoluteFile
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf("LOCALAPPDATA" to localAppData, *pairs)::get

    @Test fun productionDefaultsDifferFromDev() {
        val props = InstallProfile.properties(env(), home)
        val port = props.getValue("AGENTHQ_PORT").toInt()
        assertEquals(8797, port)
        assertFalse(port in setOf(8787, 8788), "installed port must not be a dev port")
        // The 10-port fallback scan (DaemonPort.FALLBACK_SCAN) must not walk onto a dev port either.
        assertTrue((port..port + 10).none { it in InstallProfile.DEV_PORTS })
        val dir = File(props.getValue("AGENTHQ_STATE_DIR"))
        assertEquals(prodState, dir)
        assertNotEquals(File(home, ".agenthq").absoluteFile, dir)
        // INSTALL-CWD-1: the installed app's sessions run in their project's directory, never the launcher's cwd.
        assertEquals("true", props["AGENTHQ_PROJECT_DIRS"])
    }

    @Test fun everyPinnedPathLivesUnderTheInstalledStateDir() {
        val props = InstallProfile.properties(env(), home)
        val dir = props.getValue("AGENTHQ_STATE_DIR")
        listOf("AGENTHQ_HOME", "AGENTHQ_DB", "AGENTHQ_UI_DATA_DIR").forEach { key ->
            assertTrue(props.getValue(key).startsWith(dir), "$key=${props[key]} is outside $dir")
        }
    }

    @Test fun devEnvironmentVariablesDoNotLeakIn() {
        val props = InstallProfile.properties(env("AGENTHQ_STATE_DIR" to File(home, ".agenthq").path, "AGENTHQ_PORT" to "9999"), home)
        assertEquals(prodState.path, props["AGENTHQ_STATE_DIR"])
        assertEquals("8797", props["AGENTHQ_PORT"])
    }

    @Test fun overridesAreHonouredButNeverOntoDev() {
        assertEquals(9123, InstallProfile.port(env(InstallProfile.PORT_ENV to "9123")))
        InstallProfile.DEV_PORTS.forEach { dev -> assertEquals(8797, InstallProfile.port(env(InstallProfile.PORT_ENV to dev.toString()))) }
        assertEquals(8797, InstallProfile.port(env(InstallProfile.PORT_ENV to "not-a-port")))
        val custom = File(home, "agenthq-test").absoluteFile
        assertEquals(custom, InstallProfile.stateDir(env(InstallProfile.STATE_DIR_ENV to custom.path), home))
        assertEquals(
            prodState,
            InstallProfile.stateDir(env(InstallProfile.STATE_DIR_ENV to File(home, ".agenthq").path), home),
        )
    }

    @Test fun aPinnedPropertyWinsOverTheEnvironment() {
        val props = mapOf("AGENTHQ_STATE_DIR" to "installed")
        val envs = mapOf("AGENTHQ_STATE_DIR" to "dev", "AGENTHQ_DB" to "dev.db")
        assertEquals("installed", agentHqSetting("AGENTHQ_STATE_DIR", props::get, envs::get))
        assertEquals("dev.db", agentHqSetting("AGENTHQ_DB", props::get, envs::get))
        assertEquals("dev", agentHqSetting("AGENTHQ_STATE_DIR", { "" }, envs::get))
    }

    // INSTALL-1: the MSI uninstalls the old version on every upgrade, and jpackage's RemoveFolderEx deletes the whole
    // INSTALLDIR when it does. A state dir inside it lost the DB, logs and mailbox on the 1.0.0 -> 1.0.1 upgrade.
    @Test fun defaultStateDirIsOutsideThePerUserInstallDir() {
        val dir = InstallProfile.stateDir(env(), home)
        assertFalse(dir.isUnder(perUserInstallDir), "state dir $dir is inside the MSI install dir $perUserInstallDir")
    }

    @Test fun aStateDirInsideTheInstallDirFallsBackToTheDefault() {
        val install = File(home, "Apps/AgentHQ").absoluteFile
        val inside = File(install, "data").path
        assertEquals(prodState, InstallProfile.stateDir(env(InstallProfile.STATE_DIR_ENV to inside), home, installDir = install))
        assertEquals(prodState, InstallProfile.stateDir(env(InstallProfile.STATE_DIR_ENV to install.path), home, installDir = install))
        // A sibling whose name merely starts with the install dir's is not inside it.
        val sibling = File(home, "Apps/AgentHQ-data").absoluteFile
        assertEquals(sibling, InstallProfile.stateDir(env(InstallProfile.STATE_DIR_ENV to sibling.path), home, installDir = install))
    }

    @Test fun whenTheInstallDirHoldsTheDefaultTheRoamingDirIsUsed() {
        // The owner picked %LOCALAPPDATA%\SuperiorSoft as the install dir in the MSI's directory chooser.
        val install = File(localAppData, "SuperiorSoft").absoluteFile
        val roaming = File(home, "AppData/Roaming").path
        val dir = InstallProfile.stateDir(env("APPDATA" to roaming), home, installDir = install)
        assertEquals(File(roaming, "SuperiorSoft/AgentHQ").absoluteFile, dir)
    }

    @Test fun theInstallDirIsTheLaunchersFolder() {
        val exe = File(home, "AppData/Local/AgentHQ/AgentHQ.exe")
        assertEquals(exe.parentFile.absoluteFile, InstallProfile.installDir(mapOf(LaunchProfile.APP_PATH_PROPERTY to exe.path)::get))
        assertEquals(null, InstallProfile.installDir { null })
    }

    /**
     * INSTALL-URL-1: a dev-exported AGENTHQ_URL (the dev daemon on 8787) must not reach the installed app's sessions.
     * The daemon reads settings property-first (agentHqSetting), so the lookup below is exactly what it sees once
     * InstallProfile.apply has pinned its properties over an environment that still carries the dev URL.
     */
    @Test fun anInheritedDevUrlNeverReachesTheInstalledSessions() {
        val inherited = env(AgentHqBin.URL_ENV to "ws://127.0.0.1:8787")
        val props = InstallProfile.properties(inherited, home, cliBin = null, installDir = null)
        val port = props.getValue("AGENTHQ_PORT").toInt()
        val daemonSees: (String) -> String? = { agentHqSetting(it, getProperty = props::get, getenv = inherited) }
        val warnings = mutableListOf<String>()
        val sessionEnv = AgentHqBin.urlEnv("127.0.0.1", port, env = daemonSees, warn = { warnings += it })
        assertEquals("ws://127.0.0.1:8797/ws", sessionEnv[AgentHqBin.URL_ENV], "the installed sessions must name the installed daemon's own port")
        assertEquals(1, warnings.size, "ignoring the inherited URL is logged")
        assertTrue("ws://127.0.0.1:8787" in warnings.single(), warnings.single())
    }

    // XPLAT-STATE-1: AppData/Local is a Windows folder. macOS and Linux get their platform's own data dir.
    private val unixHome = File("/home/someone").path

    @Test fun linuxUsesXdgDataHomeElseLocalShare() {
        val noXdg: (String) -> String? = { null }
        assertEquals(File(unixHome, ".local/share/agenthq").absoluteFile, InstallProfile.stateDir(noXdg, unixHome, os = "Linux"))
        val xdg = File("/data/xdg").path
        assertEquals(File(xdg, "agenthq").absoluteFile, InstallProfile.stateDir(mapOf("XDG_DATA_HOME" to xdg)::get, unixHome, os = "Linux"))
        // A blank XDG_DATA_HOME is unset, per the XDG spec.
        assertEquals(File(unixHome, ".local/share/agenthq").absoluteFile, InstallProfile.stateDir(mapOf("XDG_DATA_HOME" to " ")::get, unixHome, os = "Linux"))
        // A LOCALAPPDATA leaking into a Linux env (wine, WSL interop) is not read.
        assertEquals(File(unixHome, ".local/share/agenthq").absoluteFile, InstallProfile.stateDir(env(), unixHome, os = "Linux"))
    }

    @Test fun macOsUsesApplicationSupport() {
        val home = File("/Users/someone").path
        assertEquals(
            File(home, "Library/Application Support/AgentHQ").absoluteFile,
            InstallProfile.stateDir({ null }, home, os = "Mac OS X"),
        )
    }

    @Test fun windowsKeepsItsExistingPathWhateverTheHostOs() {
        assertEquals(prodState, InstallProfile.stateDir(env(), home, os = "Windows 11"))
        val props = InstallProfile.properties(env(), home, cliBin = null, installDir = null, os = "Windows 11")
        assertEquals(prodState.path, props["AGENTHQ_STATE_DIR"])
    }

    @Test fun theDevDirOverrideIsRefusedOnEveryOs() {
        val dev = File(unixHome, ".agenthq").path
        assertEquals(
            File(unixHome, ".local/share/agenthq").absoluteFile,
            InstallProfile.stateDir(mapOf(InstallProfile.STATE_DIR_ENV to dev)::get, unixHome, os = "Linux"),
        )
    }

    private fun File.isUnder(parent: File): Boolean = absoluteFile.normalize().toPath().startsWith(parent.absoluteFile.normalize().toPath())
}
