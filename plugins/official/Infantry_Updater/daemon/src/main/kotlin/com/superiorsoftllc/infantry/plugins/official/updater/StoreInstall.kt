// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

/**
 * STORE-1: whether this desktop launch is a Microsoft Store (MSIX) install. The Store owns its updates: such an install
 * must never download or run our MSI, so the window's updater is turned off for it ([disabledReason]) and no update pill
 * or toast appears (a manual "Check for updates" says why).
 *
 * Two signals, either is enough:
 *  - [PACKAGE_PROPERTY] `-Dinfantry.package=msix`: written into the launcher's `.cfg` by `scripts/msix/package-msix.ps1`
 *    when it builds the Store package, so it is baked into every MSIX we ship.
 *  - the launcher path (`jpackage.app-path`) lies under `\WindowsApps\`, where Windows installs every MSIX package; this
 *    also covers an MSIX someone packed by hand from our app image.
 */
object StoreInstall {
    const val PACKAGE_PROPERTY = "infantry.package"
    const val MSIX = "msix"

    /** jpackage's launcher path property (the same one `LaunchProfile.APP_PATH_PROPERTY` reads). */
    const val APP_PATH_PROPERTY = "jpackage.app-path"

    const val REASON = "this copy was installed from the Microsoft Store, which installs its updates"

    fun detected(getProperty: (String) -> String? = System::getProperty): Boolean {
        if (getProperty(PACKAGE_PROPERTY)?.trim()?.equals(MSIX, ignoreCase = true) == true) return true
        val appPath = getProperty(APP_PATH_PROPERTY)?.replace('/', '\\')?.lowercase() ?: return false
        return "\\windowsapps\\" in appPath
    }

    /** The updater settings for a Store install, or null when this is not one (then the normal rules apply). */
    fun disabledReason(getProperty: (String) -> String? = System::getProperty): UpdateSettings.Disabled? =
        if (detected(getProperty)) UpdateSettings.Disabled(REASON) else null
}
