// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.uidriver

import java.io.File

/**
 * UI-DRIVER-1: the opt-in gate of the UI driver. The driver refuses to start unless every check passes:
 *
 * - the caller opted in explicitly ([OPT_IN_ENV] = `1`);
 * - AWT is headless, so no real window can ever open on the owner's desktop (one would throw HeadlessException);
 * - the API port is in [PORTS] (the test range) and never one of [OWNER_PORTS];
 * - the state dir is a temp dir (under `java.io.tmpdir`) and nowhere under the owner's `~/.infantry` or `~/.agenthq`.
 *
 * The driver lives only in the infantryUi TEST source set, so a release build does not contain it at all
 * (UiDriverReleaseGuardTest proves it). This gate is the second fence, for a dev who runs the test classpath by hand.
 */
object UiDriverGuard {
    const val OPT_IN_ENV = "INFANTRY_UI_DRIVER"
    /** Minutes without a request before the driver stops itself (default 30), so a forgotten instance never lingers. */
    const val IDLE_ENV = "INFANTRY_UI_DRIVER_IDLE_MIN"
    val PORTS = 18900..18999
    /** The owner's daemon, preview, MCP and second-instance ports. */
    val OWNER_PORTS = setOf(8787, 8788, 8797, 8798)

    /** Every reason the driver must not start; empty = it may. [ownerHome] is the REAL user home (read before any override). */
    fun problems(optIn: String?, headless: Boolean, port: Int, stateDir: File, tmpRoot: File, ownerHome: File?): List<String> = buildList {
        if (optIn != "1") add("$OPT_IN_ENV is not 1: the UI driver runs only on an explicit opt-in")
        if (!headless) add("java.awt.headless is not true: the UI driver never opens a real window")
        if (port in OWNER_PORTS) add("port $port is one of the owner's ports $OWNER_PORTS")
        else if (port !in PORTS) add("port $port is outside the test range ${PORTS.first}-${PORTS.last}")
        val state = stateDir.canonicalFile
        if (!state.startsWith(tmpRoot.canonicalFile)) add("state dir $state is not under the temp dir ${tmpRoot.canonicalFile}")
        ownerHome?.let { home ->
            listOf(".infantry", ".agenthq").map { File(home, it).canonicalFile }.filter { state.startsWith(it) }
                .forEach { add("state dir $state is inside the owner's state dir $it") }
        }
    }
}
