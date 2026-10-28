package com.superiorsoftllc.agenthq.ui

/**
 * NS6a (docs/NORTH-STAR-DESIGN.md 4a): which daemon a shell app uses at start. Pure, so the order is unit-tested.
 *
 * 1. `app.json` `daemon.url` with a stored grant for it -> connect (grants are NS6b: none exist yet).
 * 2. A saved server the user picked in Servers -> connect.
 * 3. `mode != connect` and the build has a daemon -> start the bundled one.
 * 4. Otherwise -> the Connect dialog.
 *
 * There is no step that falls back: a failed connect is never turned into [Bundle] here or anywhere (the
 * switchError "Use own data instead" choice is the user's, NS6b). The Infantry install's daemon is never a candidate.
 */
sealed interface DaemonChoice {
    data class Connect(val url: String, val source: Source) : DaemonChoice
    data object Bundle : DaemonChoice
    data object ConnectDialog : DaemonChoice

    enum class Source { APP_GRANT, SAVED_SERVER }

    companion object {
        fun resolve(
            app: AppManifest.Daemon,
            saved: String?,
            buildHasDaemon: Boolean,
            hasGrant: (url: String) -> Boolean = { false },
        ): DaemonChoice {
            app.url?.takeIf { hasGrant(it) }?.let { return Connect(it, Source.APP_GRANT) }
            saved?.takeIf { it.isNotBlank() }?.let { return Connect(it, Source.SAVED_SERVER) }
            if (app.mode != MODE_CONNECT && buildHasDaemon) return Bundle
            return ConnectDialog
        }

        const val MODE_CONNECT = "connect"
    }
}
