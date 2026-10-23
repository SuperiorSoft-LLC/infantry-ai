package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.session.agentHqSetting
import com.superiorsoftllc.agenthq.update.MsiHandoff
import com.superiorsoftllc.agenthq.update.UpdateService
import com.superiorsoftllc.agenthq.keystore.Keystore
import com.superiorsoftllc.agenthq.update.UpdateSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * UPDATE-1: builds the installed app's [UpdateService] from the installed profile. Wiring only: the rules
 * (dev builds never update, where the repo and token come from) are [UpdateSettings.resolve]'s, in the core.
 */
object InstalledUpdates {
    /** The running version, from the launcher (`-Dagenthq.version`, set by agentHqUi/build.gradle.kts). */
    fun version(getProperty: (String) -> String? = System::getProperty): String? = getProperty(UpdateSettings.VERSION_PROPERTY)

    fun service(installed: Boolean, stateDir: File?, exit: () -> Unit, runningAgents: () -> Int = { 0 }): UpdateService {
        val settings = UpdateSettings.resolve(installed, version(), stateDir, setting = { agentHqSetting(it) },
            keystore = { stateDir?.let { Keystore(it).get(Keystore.UPDATE_TOKEN) } })
        val installDir = InstallProfile.installDir()
        val launcher = System.getProperty(LaunchProfile.APP_PATH_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
        return UpdateService(
            settings,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            handoff = { MsiHandoff.start(it, installDir, launcher) },
            exit = exit,
            runningAgents = runningAgents,
        )
    }
}
