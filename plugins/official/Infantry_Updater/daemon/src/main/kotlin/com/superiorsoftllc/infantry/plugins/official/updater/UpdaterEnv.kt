// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.io.File

/**
 * UPDATER-1: the settings lookups the updater used to borrow from the core (`infantrySetting`, `stateDir()`), which a
 * plugin cannot link. Same rules: a system property wins over the environment; a blank value is unset. The daemon half
 * passes its own [com.superiorsoftllc.infantry.daemon.api.DaemonApi.env] instead; these are for the processes that run
 * the updater outside the daemon (the headless `infantry update` launcher, the desktop window).
 */
object UpdaterEnv {
    /** A system property, else the environment variable of the same name; blank = unset. */
    fun setting(name: String, getProperty: (String) -> String? = System::getProperty, getenv: (String) -> String? = System::getenv): String? =
        getProperty(name)?.takeIf { it.isNotBlank() } ?: getenv(name)?.takeIf { it.isNotBlank() }

    /** The environment only (a token never comes from a system property: it would show on a command line). */
    fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /** The daemon's state dir as the core resolves it: `INFANTRY_STATE_DIR`, else `~/.infantry`. Read-only: never created here. */
    fun stateDir(lookup: (String) -> String? = { setting(it) }): File =
        lookup("INFANTRY_STATE_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".infantry")

    /** The daemon's port file in [stateDir] (the core's `DaemonPort.FILE_NAME`) and the port override (`DaemonPort.PORT_ENV`). */
    const val PORT_FILE = "daemon.port"
    const val PORT_ENV = "INFANTRY_PORT"

    /** The bearer token a client presents to a daemon that asks for one (the core's `AccessPolicy` token variables). */
    const val CLIENT_TOKEN_ENV = "INFANTRY_TOKEN"
    const val CLIENT_TOKEN_FILE_ENV = "INFANTRY_TOKEN_FILE"

    /** The remote-access token the daemon stores in its state dir (the core's `RemoteAccess.TOKEN_FILE`). */
    const val REMOTE_TOKEN_FILE = "remote.token"

    /**
     * The daemon's bearer token for `infantry update` on the same machine: `INFANTRY_TOKEN`, else the file
     * `INFANTRY_TOKEN_FILE` names, else the remote-access token stored in [state] (a daemon in remote mode asks for it even
     * on loopback). Only ever sent to `127.0.0.1`; never printed.
     */
    fun clientToken(state: File): String? =
        env(CLIENT_TOKEN_ENV)
            ?: env(CLIENT_TOKEN_FILE_ENV)?.let { runCatching { File(it).readText().trim() }.getOrNull() }?.takeIf { it.isNotEmpty() }
            ?: File(state, REMOTE_TOKEN_FILE).takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }?.takeIf { it.isNotEmpty() }
}
