// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File

/**
 * CMD-1: the saved commands and their runs, moved out of core. The frames keep their wire shapes and refusal texts; every
 * process goes through `api.runs` (cwd-bound to the project directory), and the owner gate stays in core.
 */
class RunDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val runs = api.runs
        maxRunSecretsSource = { api.settings.limit("limits.secrets.maxPerRun")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 16 }
        val store = CommandStore(File(runs.configDir, "commands.json"), runs::rootOf)
        // RUN-SCOPE-1: commands saved before they belonged to a project go to the project whose folder they run in.
        runCatching {
            val folders = api.projects.list().mapNotNull { p -> p.directory?.takeIf { it.isNotBlank() }?.let { p.id to File(it) } }
            store.assignUnassigned(folders).takeIf { it.isNotEmpty() }?.let { println("[infantry] run commands assigned to their project: ${it.joinToString()}") }
        }.onFailure { System.err.println("[infantry] run command migration skipped (${it::class.simpleName})") }
        val manager = RunManager(
            registry = { store.registry },
            runs = runs,
            emit = { api.events.broadcast(it.type, it.data) },
            limits = RunManager.RunLimits.from(api.settings),
            restricted = { api.policy.active().let { it.restricted == true || it.seats != null } }, // a seated preset is always restricted
            secrets = runs::secretFields,
        )

        RunCommandTools(store, manager, { api.events.broadcast(Envelope.COMMANDS, store.registry.specsJson()) }, { api.events.broadcast(it.type, it.data) })
            .all().forEach(api.tools::register)

        fun reply(e: Envelope?): Frame? = e?.let { Frame(it.type, it.data) }
        // RUN-SCOPE-1: a client gets every command with its project and filters by the project it shows.
        fun commandsFrame() = Frame(Envelope.COMMANDS, store.refreshUnrooted().specsJson())

        api.frames.handle(Envelope.COMMAND_LIST) { _, _ -> commandsFrame() }
        api.frames.handle(Envelope.COMMAND_SAVE) { f, _ -> change(api, f.type, store.save(f.data, manager::unlessRunning), store) }
        api.frames.handle(Envelope.COMMAND_DELETE) { f, _ -> change(api, f.type, store.delete(f.data, manager::unlessRunning), store) }
        api.frames.handle(Envelope.RUN_START) { f, _ ->
            store.refreshUnrooted()
            val started = manager.start(f.data)
            if (started.reply.type == Envelope.ERROR) return@handle reply(started.reply)
            // The `run` answer goes out first on the same broadcast order, then the child starts and streams.
            api.events.broadcast(started.reply.type, started.reply.data)
            started.launch()
            null
        }
        api.frames.handle(Envelope.RUN_STOP) { f, _ -> reply(manager.stop(f.data)) }
        api.frames.handle(Envelope.RUN_LIST) { _, _ -> reply(manager.list()) }
        api.frames.handle(Envelope.RUN_ATTACH) { f, _ -> reply(manager.attach(f.data)) }
        api.frames.handle(Envelope.RUN_RESIZE) { f, _ -> reply(manager.resize(f.data)) }
        api.frames.handle(Envelope.RUN_INPUT) { f, _ -> reply(manager.input(f.data)) }
    }

    /** A save or delete: a refusal answers the caller; success pushes the new list to every client. */
    private fun change(api: DaemonApi, type: String, refusal: String?, store: CommandStore): Frame? {
        if (refusal != null) return Frame(Envelope.ERROR, "$type refused: $refusal")
        api.events.broadcast(Envelope.COMMANDS, store.registry.specsJson())
        return null
    }
}
