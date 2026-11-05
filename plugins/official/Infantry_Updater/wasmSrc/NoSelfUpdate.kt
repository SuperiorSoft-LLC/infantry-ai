// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater.ui

import com.superiorsoftllc.infantry.ui.shell.SelfUpdateProvider

/** UPDATER-1: no APK to install here (the desktop window has its own MSI updater; a browser reloads). */
internal actual fun platformSelfUpdate(): SelfUpdateProvider = SelfUpdateProvider { _, _, _ -> null }
