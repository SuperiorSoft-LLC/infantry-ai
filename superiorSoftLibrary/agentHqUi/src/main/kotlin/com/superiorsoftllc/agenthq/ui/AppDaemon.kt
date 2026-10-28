package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.AgentHqBin
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.mvp.SqlProjectPersistence
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import com.superiorsoftllc.agenthq.ui.shell.LocalServer
import java.io.File
import java.util.ServiceLoader

/**
 * NS5 (docs/NORTH-STAR-DESIGN.md 2a, 4a): the isolated settings of a shell app's BUNDLED daemon.
 *
 * Everything lives under the app's own state dir (`~/.infantry/apps/<appId>`), never the Infantry install's
 * ([InstallProfile]) nor the dev one (`~/.agenthq`). It binds loopback on an ephemeral port, so two apps (and the
 * Infantry daemon on 8787/8797) never collide and nothing on the LAN can reach it. Like [InstallProfile] these are
 * pinned as system properties, which the daemon reads ahead of the environment (`agentHqSetting`), so a variable
 * exported for the owner's daemon cannot pull the app onto the owner's data.
 */
object AppDaemonProfile {
    /** Loopback only: LAN exposure for a bundled app is an explicit owner toggle, later (design 5). */
    const val HOST = "127.0.0.1"

    /** 0 = an OS-assigned port, reserved before anything renders it (BUG-PORT-0). */
    const val PORT = "0"

    fun properties(app: ShellAppLaunch): Map<String, String> {
        val dir = app.stateDir.absoluteFile
        return linkedMapOf(
            "AGENTHQ_APP_ID" to app.manifest.appId,
            "AGENTHQ_STATE_DIR" to dir.path,
            "AGENTHQ_HOME" to dir.path,
            "AGENTHQ_DB" to File(dir, "agenthq.db").path,
            "AGENTHQ_UI_DATA_DIR" to File(dir, "ui").path,
            "AGENTHQ_HOST" to HOST,
            "AGENTHQ_PORT" to PORT,
            // Sessions are told this daemon's own address, never an AGENTHQ_URL naming the owner's daemon.
            AgentHqBin.OWN_URL_ONLY_SETTING to "true",
        )
    }

    /** Pin [properties] for this JVM; call before anything reads `stateDir()` or builds the daemon. */
    fun apply(app: ShellAppLaunch): Map<String, String> {
        app.stateDir.mkdirs()
        return properties(app).onEach { (k, v) -> System.setProperty(k, v) }
    }
}

/**
 * NS5: `seedProject` (app.json 2c) creates one project on the FIRST start of a bundled daemon, so a board has somewhere
 * to put tickets. "First start" is "the database file does not exist yet": a later start, or a user who deleted the
 * project, gets nothing re-created. Runs before the daemon opens the database, so the daemon's project cache loads it.
 */
object SeedProject {
    /** True when it created the project. */
    fun onFirstStart(db: File, name: String?): Boolean {
        if (name.isNullOrBlank() || db.exists()) return false
        db.parentFile?.mkdirs()
        val store = SqlAgentHqStore.open(db.path)
        try {
            ProjectStore(persistence = SqlProjectPersistence(store.database)).save(ProjectSave(name = name))
        } finally {
            store.close()
        }
        println("[agentHq-app] seeded project '$name' in $db")
        return true
    }
}

/**
 * NS5: the bundled daemon's boot, found through [ServiceLoader] so a build without the daemon (the thin flavour,
 * NS-L1) simply has none: its absence is a classpath fact, not an `if`. [create] owns the per-app state-dir lock.
 */
interface DaemonLauncher {
    /** The local server of the app whose (already pinned) state dir is [stateDir]; started on demand. */
    fun create(stateDir: File, seedProject: String?): LocalServer
}

object DaemonLaunchers {
    fun find(loader: ClassLoader = DaemonLauncher::class.java.classLoader): DaemonLauncher? =
        ServiceLoader.load(DaemonLauncher::class.java, loader).firstOrNull()
}

/** The in-process daemon (the full build): one daemon per app state dir ([InstalledLock]), seeded on first start. */
class InProcessDaemonLauncher : DaemonLauncher {
    override fun create(stateDir: File, seedProject: String?): LocalServer =
        InProcessDaemon(InstalledLock.acquireOrExit(stateDir), seedProject)
}
