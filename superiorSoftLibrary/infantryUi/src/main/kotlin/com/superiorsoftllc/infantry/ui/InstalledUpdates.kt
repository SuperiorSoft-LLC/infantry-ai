// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.session.infantrySetting
import com.superiorsoftllc.infantry.plugins.official.updater.MsiHandoff
import com.superiorsoftllc.infantry.plugins.official.updater.ResumeWorkers
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateService
import com.superiorsoftllc.infantry.keystore.Keystore
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * UPDATE-1: builds the installed app's [UpdateService] from the installed profile. Wiring only: the rules
 * (dev builds never update, where the repo and token come from) are [UpdateSettings.resolve]'s, in the Infantry_Updater plugin.
 */
object InstalledUpdates {
    /** The running version, from the launcher (`-Dinfantry.version`, set by infantryUi/build.gradle.kts). */
    fun version(getProperty: (String) -> String? = System::getProperty): String? = getProperty(UpdateSettings.VERSION_PROPERTY)

    fun service(installed: Boolean, stateDir: File?, exit: () -> Unit, runningAgents: () -> Int = { 0 }, busySessions: () -> List<String> = { emptyList() }): UpdateService {
        // UPDATER-DEFAULT-1: resolved again before each check, so a changed setting applies without a restart.
        val resolve = {
            UpdateSettings.resolve(installed, version(), stateDir, setting = { infantrySetting(it) },
                keystore = { stateDir?.let { Keystore(it).get(Keystore.UPDATE_TOKEN) } })
        }
        val settings = resolve()
        val installDir = InstallProfile.installDir()
        val launcher = System.getProperty(LaunchProfile.APP_PATH_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
        return UpdateService(
            settings,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            handoff = { MsiHandoff.start(it, installDir, launcher) },
            exit = exit,
            runningAgents = runningAgents,
            // UPDATER-KILLS-WORKERS-1: both on unless the setting says false (update.waitForWorkers / update.resumeWorkers).
            waitForWorkers = { infantrySetting("update.waitForWorkers") != "false" },
            beforeExit = { if (stateDir != null && infantrySetting("update.resumeWorkers") != "false") ResumeWorkers.save(stateDir, busySessions()) },
            reload = resolve,
        )
    }
}
